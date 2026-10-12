package org.daimhim.imc_core

import org.java_websocket.WebSocket
import org.java_websocket.client.WebSocketClient
import org.java_websocket.drafts.Draft_6455
import org.java_websocket.framing.CloseFrame
import org.java_websocket.framing.Framedata
import org.java_websocket.handshake.ServerHandshake
import java.net.URI
import java.nio.ByteBuffer

/**
 * 引擎生命周期状态机
 *
 * 状态转换图:
 *
 * ```
 *     Idle ──engineOn(k)──→ Connecting(k) ──onOpen──→ Connected(k)
 *                             ↑   │                       │
 *          replaceClient(k')  │   │ onClose/onError       │ onClose/onError
 *     (换 key / 重试 / 换网)   │   ↓                       ↓
 *                             └─ Reconnecting(k) ←─────────┘
 *                                 (autoConnect 定时 → replaceClient)
 *
 *     任意状态 ──engineOff──→ Closing ──→ Idle
 * ```
 *
 * Connecting / Connected 也可直接 replaceClient 回到 Connecting(k'):旧 client 被标记换下,
 * 它迟到的收场回调不推进状态机。
 */
internal sealed class EngineState {
    object Idle : EngineState()
    object Closing : EngineState()
    data class Connecting(val key: String) : EngineState()
    data class Connected(val key: String) : EngineState()
    data class Reconnecting(val key: String) : EngineState()
}

private fun EngineState.targetKey(): String? = when (this) {
    is EngineState.Connecting -> key
    is EngineState.Connected -> key
    is EngineState.Reconnecting -> key
    EngineState.Idle, EngineState.Closing -> null
}

class V2JavaWebEngine private constructor(builder: Builder) : IEngine {

    // ── 配置 ────────────────────────────────────────────────────────────
    private val heartbeatMode: Map<Int, ILinkNative> = builder.heartbeatMode
    // autoConnect 在 V2 链路里是必备组件,Builder.build() 已强制非空:
    //  - handleAfterClose 的 Connected→Reconnecting 分支依赖 autoConnect 接管退避,
    //    否则 engine 会卡死在 Reconnecting;
    //  - Reconnecting 分支的"切 URL 立即重启 vs 让 autoConnect 退避"判断也需要 isActive()。
    private val autoConnect: IAutoConnect = builder.autoConnect
        ?: error("autoConnect 不能为空,请通过 Builder.setAutoConnect 注入")
    // 内核重发池(可选能力):只收带业务 ID 的消息;跨 client 世代共享,engineOff 时整池回收
    private val resendPool: ResendPool =
        ResendPool(builder.messageCache, builder.resendPoolConfig, builder.clock)
    private val keyProvider: IKeyProvider? = builder.keyProvider
    private val certificatePinner: CertificatePinner? = builder.certificatePinner
    // P1: 端侧 DNS 缓存(TTL + stale-fallback)。注入到每个 WebSocketClientImpl,让重连在 DNS 抖动时
    // 用历史 IP 续命。默认 new 一个;App 可通过 Builder.setDnsCache 传共享实例(与 NetProber 共用,
    // 探测成功也能预热缓存)。
    private val dnsCache: DnsCache = builder.dnsCache
    private val connectTimeout: Int = 5 * 1000

    /**
     * 给 autoConnect 用的「重连入口」。autoConnect.startConnect 通过它走 engineOn 路径,
     * 而不是直接 `webSocketClient.reconnect()` —— 这样可以:
     *  - 每次重连前现拿 KeyProvider 的最新 url(首连/重连标识、token 续期、备用 host)
     *  - 修了 webSocketClient=null 时 reconnect() no-op 的卡死 bug(走 spawnNewClient
     *    不依赖现有 client 存活)
     *
     * 在锁外 fork 线程,因为 engineOn 内部 synchronized,autoConnect 的 alarm 回调线程不该被它阻塞;
     * KeyProvider 也放进线程里调 —— 它可能卡(token 续期走网络),不能卡住调度线程。
     */
    private val autoConnectReconnector = IReconnector { reason ->
        // #4:IReconnector.restart 的语义是「触发一次重连尝试」,不是「确保连着」——
        // 同 key 也必须真重建,见 [DispatchMode]
        val mode = if (reason == RestartReason.SCHEDULED) DispatchMode.RETRY else DispatchMode.REPLACE
        Thread({
            val nextKey = resolveReconnectKey() ?: return@Thread
            engineOnInternal(nextKey, mode, "autoConnect:$reason")
        }, "V2JWE-restart").start()
    }

    /**
     * 决定本次重连用的 url:优先 KeyProvider(实时算),没设 Provider 就用上次 engineOn 缓存的 key。
     * 两者都为空说明 engine 还没启动过 / 已 engineOff 过,跳过。
     */
    private fun resolveReconnectKey(): String? =
        keyProvider?.runCatching { provide() }?.getOrNull() ?: state.targetKey()

    init {
        autoConnect.setReconnector(autoConnectReconnector)
    }

    /**
     * 当前应使用的心跳模式 key
     *
     * 在 [onChangeMode] 被调用时记录(即便此刻 webSocketClient 还没建好),
     * [spawnNewClient] 创建新 client 时按此值初始化心跳,
     * 保证"先调 onChangeMode、后调 engineOn"这种顺序也能拿到正确的心跳模式
     */
    @Volatile
    private var pendingMode: Int = builder.defHeartbeatMode

    // ── 状态(全部围绕 syncJWE 同步)─────────────────────────────────────
    private val syncJWE = Any()

    @Volatile
    private var state: EngineState = EngineState.Idle
    // 写都在 syncJWE 内;kickReconnect / onChangeMode 等锁外快照读,需要可见性
    @Volatile
    private var webSocketClient: WebSocketClientImpl? = null

    // ── handshake watchdog ──────────────────────────────────────────────
    // 兜底:client.connect() 调完后 state=Connecting,但 Java-WebSocket 在 WS upgrade
    // 阶段没单独超时,server 不回 101 时会一直挂着。这里给每次 spawnNewClient 配一个
    // 15s 看门狗 —— 到点了如果 state 还是 Connecting(同 key) 且 client 还是当时那个,
    // 强 close() 走 onClose 链回到 Reconnecting,让 autoConnect 接管。
    // onOpen / handleAfterClose / engineOff 都要 unregister 它。
    private val handshakeWatchdog = RapidResponseForceV4()
    private val handshakeWatchdogId = "handshake-watchdog"
    private val handshakeTimeoutMs: Long = 15_000L

    // ── 监听器 ──────────────────────────────────────────────────────────
    internal val imcListenerManager = IMCListenerManager()

    @Volatile
    var imcStatusListener: IMCStatusListener? = null

    // ── 内部 WebSocket 事件桥 ────────────────────────────────────────────
    private val javaWebsocketListener = object : JavaWebSocketListener {
        override fun onOpen(handshakedata: ServerHandshake?) {
            handshakeWatchdog.unregister(handshakeWatchdogId)
            ImcEvents.emit(
                ImcEvent.ConnectionOpened(
                    httpStatus = handshakedata?.httpStatus?.toInt(),
                    httpStatusMessage = handshakedata?.httpStatusMessage,
                )
            )
            val transitionTo: String? = synchronized(syncJWE) {
                val key = state.targetKey()
                val prev = state
                if (key != null) state = EngineState.Connected(key)
                if (state !== prev) state.toString() else null
            }
            if (transitionTo != null) {
                // emit 在锁外:transitionTo 已经捕获了新状态字符串,这里不再触碰 state
                ImcEvents.emit(
                    ImcEvent.EngineStateTransition(
                        from = "Connecting/Reconnecting",
                        to = transitionTo,
                        reason = "onOpen",
                    )
                )
            }
            imcStatusListener?.connectionSucceeded()
        }

        override fun onMessage(message: String) {
            imcListenerManager.onMessage(this@V2JavaWebEngine, message)
        }

        override fun onMessage(bytes: ByteBuffer) {
            // #6:不能用 bytes.array() —— 那是底层数组,长度是 capacity 而非本次消息长度,
            // 分片 / 大消息时会把尾部垃圾字节一起交给业务;直接缓冲区(allocateDirect)还会抛
            // UnsupportedOperationException。按 position..limit 精确拷贝,
            // duplicate() 以免动到调用方的 position。
            val payload = ByteArray(bytes.remaining())
            bytes.duplicate().get(payload)
            imcListenerManager.onMessage(this@V2JavaWebEngine, payload)
        }

        override fun onClose(code: Int, reason: String?, remote: Boolean) {
            ImcEvents.emit(ImcEvent.ConnectionClosed(code, reason, remote))
            imcStatusListener?.connectionClosed(code, reason)
            handleAfterClose()
        }

        override fun onError(ex: Exception) {
            ImcEvents.emit(
                ImcEvent.ConnectionLost(
                    errorClass = ex.javaClass.simpleName,
                    message = ex.message,
                )
            )
            // A4: SSL/TLS 错误细分类,给业务侧分诊 UX 用
            emitTlsFailureIfApplicable(ex)
            imcStatusListener?.connectionLost(ex)
            handleAfterClose()
        }

        /**
         * A4: 把 onError 里的 SSL/TLS 异常拆成 [ImcEvent.TlsFailure] 分类事件。
         *
         * 分类规则(JVM/OkHttp 的常见异常映射):
         *  - SSLHandshakeException → HANDSHAKE(握手期失败,常见对端拒绝/拦截)
         *  - SSLPeerUnverifiedException → PIN_FAILURE(证书校验失败)
         *  - SSLProtocolException 含 "close_notify" → CLOSE_NOTIFY
         *  - SSLProtocolException 其余 → READ/WRITE(此处无方向区分,先并入 READ)
         *  - SSLException 兜底 → UNKNOWN
         *
         * 不是 SSL 类异常直接 return,不 emit。
         */
        private fun emitTlsFailureIfApplicable(ex: Throwable) {
            val name = ex.javaClass.simpleName
            val msg = ex.message.orEmpty()
            val stage = when {
                name == "SSLHandshakeException" -> ImcEvent.TlsFailure.Stage.HANDSHAKE
                name == "SSLPeerUnverifiedException" -> ImcEvent.TlsFailure.Stage.PIN_FAILURE
                name == "SSLProtocolException" && msg.contains("close_notify", ignoreCase = true) ->
                    ImcEvent.TlsFailure.Stage.CLOSE_NOTIFY
                name == "SSLProtocolException" -> ImcEvent.TlsFailure.Stage.READ
                name.startsWith("SSL") -> ImcEvent.TlsFailure.Stage.UNKNOWN
                else -> return
            }
            // 简单启发:"Connection closed by peer" / "closed the connection" / "reset by peer" 视作对端关
            val peerInitiated = msg.contains("closed by peer", ignoreCase = true) ||
                msg.contains("closed the connection", ignoreCase = true) ||
                msg.contains("reset by peer", ignoreCase = true)
            ImcEvents.emit(
                ImcEvent.TlsFailure(
                    stage = stage,
                    errorClass = name,
                    message = ex.message,
                    isPeerInitiated = peerInitiated,
                )
            )
        }

        /**
         * onClose / onError 后的统一状态推进(仅当前 client;被换下的 client 在
         * WebSocketClientImpl 层已早退,到不了这里):
         *  - Connected/Connecting:转 Reconnecting(curKey),由 autoConnect 接管
         *    (WebSocketClientImpl 随后调 abnormalDisconnectionAndAutomaticReconnection)
         *  - Reconnecting:client 已不在,只做兜底置空
         *  - Closing:转 Idle 并释放
         *  - Idle:已经被 engineOff 清理过,无事可做
         *
         * #9:历史上 Reconnecting 分支在 autoConnect 不活跃时会另起 V2JWE-restart 线程 engineOn,
         * 与紧随其后的 abnormalDisconnection → autoConnect 构成两条并行重连路径。换 client
         * 现在统一走 [replaceClient](旧 client 被标记换下,收场不作数),这条路径已无来源,删除。
         */
        private fun handleAfterClose() {
            handshakeWatchdog.unregister(handshakeWatchdogId)
            // 锁内只搬状态机 + 抓 transition 描述,事件 emit 移到锁外
            data class Transition(val from: String, val to: String, val reason: String)
            var transition: Transition? = null
            synchronized(syncJWE) {
                when (val s = state) {
                    is EngineState.Reconnecting -> {
                        webSocketClient = null
                    }
                    is EngineState.Connected, is EngineState.Connecting -> {
                        val k = s.targetKey()!!
                        val prev = s.toString()
                        state = EngineState.Reconnecting(k)
                        // 旧 client 必须置 null:后续 autoConnect alarm fire 调 engineOn(same key)
                        // 时,dispatchEngineOn 的 Reconnecting-same-key 分支依赖 webSocketClient == null
                        // 才会走 spawnNewClient。漏置会导致 alarm fire 后静默 no-op,引擎 hang
                        // 在 Reconnecting 状态,业务侧表现为"点了 Connect 很久不连上"。
                        webSocketClient = null
                        transition = Transition(prev, state.toString(), "waiting auto-reconnect")
                    }
                    EngineState.Closing -> {
                        webSocketClient = null
                        state = EngineState.Idle
                        transition = Transition("Closing", "Idle", "engineOff complete")
                    }
                    EngineState.Idle -> Unit
                }
            }
            transition?.let {
                ImcEvents.emit(
                    ImcEvent.EngineStateTransition(it.from, it.to, it.reason)
                )
            }
        }
    }

    // ── IEngine API ────────────────────────────────────────────────────

    override fun engineOn(key: String) = engineOnInternal(key, DispatchMode.ENSURE, "engineOn")

    /**
     * [dispatchEngineOn] 对「当前 client」的处置方式。
     *
     * #4:[engineOn] 是幂等语义("确保用这个 key 连着"),而 autoConnect 的 [IReconnector]
     * 是"触发一次重连尝试",同 key 也要真重建 —— 两者必须分开。
     */
    private enum class DispatchMode {
        /** [engineOn]:同 key 已连 / 在连则不动;换 key 才换 client */
        ENSURE,

        /** autoConnect 定时重试:已连上的照样重建;同 key 的在途尝试不掐,让它自然回归 */
        RETRY,

        /** 换网 / 回前台等:当前 client(含在途尝试)一律换掉 */
        REPLACE,
    }

    private fun engineOnInternal(key: String, mode: DispatchMode, reason: String) {
        // 锁内只动状态机;事件 emit 放锁外,避免 sink 卡死引擎入口
        val keyChangeEvent: ImcEvent.EngineKeyChanged?
        val transition: ImcEvent.EngineStateTransition?
        synchronized(syncJWE) {
            val prev = state
            // 内部重启(autoConnect / 换网 / 回前台)不得把已 engineOff 的引擎拉起来:
            // V2JWE-restart 线程与 engineOff 存在竞态,只有业务显式 engineOn 才能从 Idle 起步
            if (mode != DispatchMode.ENSURE &&
                (prev == EngineState.Idle || prev == EngineState.Closing)
            ) return
            val oldKey = prev.targetKey()
            keyChangeEvent = if (oldKey != key) ImcEvent.EngineKeyChanged(oldKey, key) else null
            val replaced = dispatchEngineOn(key, mode)
            transition = if (replaced && prev !is EngineState.Reconnecting && prev != EngineState.Idle) {
                ImcEvent.EngineStateTransition(prev.toString(), state.toString(), "replace:$reason")
            } else null
        }
        keyChangeEvent?.let { ImcEvents.emit(it) }
        transition?.let { ImcEvents.emit(it) }
    }

    /**
     * 必须在 syncJWE 锁内调用。
     *
     * @return true = 起了新 client
     */
    private fun dispatchEngineOn(key: String, mode: DispatchMode): Boolean {
        val s = state
        val replace = when (s) {
            EngineState.Idle, EngineState.Closing -> true
            // Reconnecting:旧 client 已在 handleAfterClose 置空,任何 key 都直接起新的
            is EngineState.Reconnecting -> true
            is EngineState.Connecting -> key != s.key || mode == DispatchMode.REPLACE
            is EngineState.Connected -> key != s.key || mode != DispatchMode.ENSURE
        }
        if (replace) replaceClient(key)
        return replace
    }

    /**
     * 必须在 syncJWE 锁内调用。换掉当前 client(若有)并立即起新的。
     *
     * 所有换 client 的路径(换 URL、autoConnect 重试、换网、回前台)统一走这里。旧 client 先
     * [WebSocketClientImpl.markSuperseded] 再 close:它迟到的 onClose/onError 一律不作数 ——
     * 不推进状态机、不唤醒 autoConnect、池子也不会冲进它。
     *
     * N4:旧 client 可能卡在不可中断的 DNS 解析里,close() 迟迟不回 onClose;不等它收场,
     * 立刻 spawn 新的。#9:历史上 Connected 分支走「close → onClose → handleAfterClose 另起
     * 线程 engineOn」+「abnormal → autoConnect 排定时」两条并行重连路径,定时若在新连接
     * onOpen 之后到点,会把刚建好的连接再拆一次。
     */
    private fun replaceClient(key: String) {
        val old = webSocketClient
        webSocketClient = null
        if (old != null) {
            old.markSuperseded()
            try { old.close() } catch (_: Exception) {}
        }
        spawnNewClient(key)
    }

    /**
     * 必须在 syncJWE 锁内调用。
     * **不在这里 emit ImcEvent**:state transition 事件由 onOpen / handleAfterClose 等更准确的位置触发,
     * 这里 Connecting 状态只是中间态,emit 反而噪声大。
     */
    private fun spawnNewClient(key: String) {
        val finalUrl = normalizeUrl(key)
        val uri = try {
            URI(finalUrl)
        } catch (e: java.net.URISyntaxException) {
            // 用户拷贝 URL / token 时常夹换行、NBSP、zero-width space 之类
            // 严格 URI 解析会爆 — fallback 到容错路径:
            //  1. 剥控制字符 + Unicode 空白
            //  2. 仍失败就把 query 段做 percent-encode
            val cleaned = finalUrl.filter { c -> c.code > 0x20 && c.code != 0x7F && !c.isWhitespace() }
            try { URI(cleaned) } catch (_: java.net.URISyntaxException) {
                URI(percentEncodeQuery(cleaned))
            }
        }
        val client = WebSocketClientImpl(
            uri,
            timeout = connectTimeout,
            resendPool = resendPool,
            certificatePinner = certificatePinner,
            dnsCache = dnsCache,
        ).apply {
            javaWebSocketListener = this@V2JavaWebEngine.javaWebsocketListener
            setAutoConnect(autoConnect)
            // 用 pendingMode (而非 defHeartbeatMode), 让连接前调过的 onChangeMode 生效
            heartbeatMode[pendingMode]?.let { onChangeMode(it) }
        }
        webSocketClient = client
        state = EngineState.Connecting(key)
        armHandshakeWatchdog(client, key)
        client.connect()
    }

    /**
     * 给当前 client 起 15s 握手看门狗。
     *
     * 触发条件:到点了 state 仍是 `Connecting(同 key)`,且 webSocketClient 引用没变(中间没被
     * dispatchEngineOn 切走)。满足才强 close —— 让 close 走标准 onClose 链,handleAfterClose
     * 把 Connecting → Reconnecting,autoConnect 自然接管下一轮。
     *
     * RRFv4 同 id 注册自动覆盖,所以 dispatchEngineOn 切 client 时不需要显式 unregister,
     * 新 spawnNewClient 注册时旧的就被覆盖了。
     */
    private fun armHandshakeWatchdog(client: WebSocketClientImpl, key: String) {
        handshakeWatchdog.register(handshakeWatchdogId, handshakeTimeoutMs) { _ ->
            // 先在锁内判定是否真要 fire,fire 决策与事件 emit 分离避免持锁
            val fired: Boolean = synchronized(syncJWE) {
                val s = state
                if (s is EngineState.Connecting && s.key == key && webSocketClient === client) {
                    client.close()
                    true
                } else false
            }
            if (fired) {
                ImcEvents.emit(ImcEvent.HandshakeWatchdogFired(key, handshakeTimeoutMs))
            }
        }
    }

    private fun normalizeUrl(key: String): String = when {
        key.startsWith("http:", ignoreCase = true) -> "ws:${key.substring(5)}"
        key.startsWith("https:", ignoreCase = true) -> "wss:${key.substring(6)}"
        else -> key
    }

    /**
     * 把 `?...` 之后每个非 RFC 3986 query 合法字符 percent-encode。
     * 保留 sub-delims / unreserved / `:@/?` + 已经 percent-encoded 的 `%`。
     */
    private fun percentEncodeQuery(url: String): String {
        val q = url.indexOf('?')
        if (q < 0) return url
        val hash = url.indexOf('#', q)
        val queryEnd = if (hash < 0) url.length else hash
        val sb = StringBuilder(url.substring(0, q + 1))
        var i = q + 1
        while (i < queryEnd) {
            val c = url[i]
            if (c.isLetterOrDigit() || c in "-._~!$&'()*+,;=:@/?%") {
                sb.append(c)
            } else {
                val bytes = c.toString().toByteArray(Charsets.UTF_8)
                for (b in bytes) {
                    sb.append('%').append(String.format("%02X", b.toInt() and 0xFF))
                }
            }
            i++
        }
        if (hash >= 0) sb.append(url, hash, url.length)
        return sb.toString()
    }

    override fun engineOff() {
        // 锁内推进状态机 + 关 client;锁外做 clear 缓存 / 通知监听 / dispose autoConnect,
        // 避免长操作 / 用户回调阻塞 syncJWE。
        val shouldNotify = synchronized(syncJWE) {
            if (state == EngineState.Idle) return@synchronized false
            handshakeWatchdog.unregister(handshakeWatchdogId)
            state = EngineState.Closing
            val client = webSocketClient
            // 顺序:解绑 listener → 停 timer → 停 auto-connect → close socket → 释放
            // 解绑 listener 在 close 之前,onClose 回来时 listener=null 不会再触发 handleAfterClose
            client?.javaWebSocketListener = null
            client?.stopConnectionLostTimerEx(false)
            client?.stopAutoConnect()
            // 必须用 closeAndTerminate 而非 close():engineOff 是终止意图,需要标记 isClosed=true
            // 阻止 onClose/onError 把 autoConnect 重新唤醒。普通 close() 现在故意不设 isClosed,
            // 是为了让握手 watchdog / 心跳判死 / URL 切换 都能走标准抢救链。
            client?.closeAndTerminate()
            webSocketClient = null
            state = EngineState.Idle
            true
        }
        if (!shouldNotify) return
        // 真正的会话终止才回收重发池:逐条 MessageEvicted(SESSION_END),业务据此改状态 / 留待下次登录补发。
        // 切 URL / 强制重建那些路径不走 engineOff,因此不会误伤池子。
        try { resendPool.endSession() } catch (e: Exception) { e.printStackTrace() }
        // 释放 autoConnect 持有的外部资源(典型:NetSurveillance 的 listener),避免泄漏。
        // 如果用户在 engineOff 后又调 engineOn 复用同一 engine,需要新建 builder 重新注入 autoConnect。
        try { autoConnect.dispose() } catch (e: Exception) { e.printStackTrace() }
        // 业务侧主动 engineOff 也应该收到一次明确的 connectionClosed 回调 ——
        // 由于 listener 在 close 之前已被解绑,onClose 不会回到 V2JWE 的 javaWebsocketListener,
        // 这里走快路径补一次,避免业务漏判终止信号。
        imcStatusListener?.connectionClosed(CloseFrame.NORMAL, "engineOff")
        ImcEvents.emit(ImcEvent.EngineStopped())
    }

    /** 内部辅助:当前是否处于 Connected 状态 */
    internal fun isConnect(): Boolean = state is EngineState.Connected

    /** 调试用:返回当前目标 URL,Idle 时为 null */
    internal fun currentKey(): String? = state.targetKey()

    override fun engineState(): Int = when (state) {
        EngineState.Idle, EngineState.Closing -> IEngineState.ENGINE_CLOSED
        is EngineState.Connecting -> IEngineState.ENGINE_CONNECTING
        is EngineState.Connected -> IEngineState.ENGINE_OPEN
        is EngineState.Reconnecting -> IEngineState.ENGINE_FAILED
    }

    // 返回值以「是否真的写进了已打开的 socket」为准,不再看状态机:心跳判死 / 换网 kick 的软 close
    // 之后 state 仍是 Connected、socket 已在 CLOSING,历史实现在这里返回 true 而帧实际进了缓存被丢弃。
    override fun send(byteArray: ByteArray): Boolean = send(byteArray, null) == SendResult.SENT

    override fun send(text: String): Boolean = send(text, null) == SendResult.SENT

    override fun send(text: String, bizId: String?): SendResult =
        dispatchSend(bizId, CachedMessage.Text(bizId ?: "", text, clock()))

    override fun send(byteArray: ByteArray, bizId: String?): SendResult =
        dispatchSend(bizId, CachedMessage.Binary(bizId ?: "", byteArray.copyOf(), clock()))

    override fun resendPool(): IResendPool = resendPool

    private val clock: () -> Long = builder.clock

    private fun dispatchSend(bizId: String?, message: CachedMessage): SendResult {
        val client: WebSocketClientImpl?
        val inSession: Boolean
        synchronized(syncJWE) {
            client = webSocketClient
            inSession = state != EngineState.Idle && state != EngineState.Closing
        }
        val result = resendPool.sendOrQueue(bizId, message, client?.poolWriter, allowQueue = inSession)
        if (result != SendResult.SENT && inSession) makeConnection()
        return result
    }

    override fun addIMCListener(imcListener: V2IMCListener) {
        imcListenerManager.addIMCListener(imcListener)
    }

    override fun removeIMCListener(imcListener: V2IMCListener) {
        imcListenerManager.removeIMCListener(imcListener)
    }

    override fun addIMCSocketListener(level: Int, imcSocketListener: V2IMCSocketListener) {
        imcListenerManager.addIMCSocketListener(level, imcSocketListener)
    }

    override fun removeIMCSocketListener(imcSocketListener: V2IMCSocketListener) {
        imcListenerManager.removeIMCSocketListener(imcSocketListener)
    }

    override fun setIMCStatusListener(listener: IMCStatusListener?) {
        imcStatusListener = listener
    }

    override fun onChangeMode(mode: Int) {
        val prev = pendingMode
        // 不管 webSocketClient 是否已建, 都记下这次调用 — 下次 spawnNewClient 会用它
        pendingMode = mode
        heartbeatMode[mode]?.let {
            webSocketClient?.onChangeMode(it)
        }
        if (prev != mode) {
            ImcEvents.emit(ImcEvent.HeartbeatModeChanged(prev, mode))
        }
    }

    override fun onNetworkChange(networkState: Int) {
        kickReconnect()
    }

    override fun makeConnection() {
        kickReconnect()
    }

    /**
     * 立即催一次重连(放弃当前退避)。
     *
     * webSocketClient 在 autoConnect 驱动重连期间会被 [handleAfterClose] 置空,
     * 那时不能只 `webSocketClient?.` 一句带过(会变 no-op),要直接戳 autoConnect。
     *
     * ⚠ 连接健康(isOpen)时**不能**走 client.resetStartAutoConnect()——它会
     * stopConnectionLostTimer 而 autoConnect 并不会真的重连(连接没断),心跳计时器
     * 被停后再无人重挂,连接静默到被服务端空闲踢。典型触发:App 冷启动网络监控的
     * 初始回调 onNetworkChange → kickReconnect。健康时什么都不做。
     *
     * 等待重连期间的 reset 语义见 ProgressiveAutoConnect.resetStartAutoConnect:压缩 pending
     * 定时器到 IMMEDIATE(带 500ms 去抖),不再重排满退避、也不会被连环 poke 推迟 fire。
     */
    private fun kickReconnect() {
        val client = webSocketClient
        when {
            client == null -> autoConnect.resetStartAutoConnect()
            !client.isOpen -> client.resetStartAutoConnect()   // 半开/未连:停心跳 + 催重连(历史行为)
            // 健康:什么都不做。没有退避可重置;历史上这里走 resetStartAutoConnect → stop+start,
            // 会刷新 flapping 会话起点(#6)
            else -> Unit
        }
    }

    // ── 内部 WebSocket 客户端 ─────────────────────────────────────────────

    class WebSocketClientImpl(
        serverUri: URI,
        httpHeaders: Map<String, String> = mutableMapOf(),
        timeout: Int = 0,
        /** 引擎共享的重发池;单独使用 client 时给一个独立的空池 */
        private val resendPool: ResendPool = ResendPool(),
        /** A5: 证书指纹校验钩子,null 表示不做 pinning */
        private val certificatePinner: CertificatePinner? = null,
        /** P1: 端侧 DNS 缓存,接进库的 DnsResolver,DNS 抖动时回退历史 IP */
        private val dnsCache: DnsCache = DnsCache(),
    ) : WebSocketClient(serverUri, Draft_6455(), httpHeaders, timeout) {

        init {
            // R6 修复:Java-WebSocket 自带 LCD(60s 默认)在 [WebSocket.startConnectionLostTimer]
            // 时直接读 `connectionLostTimeout` 私有字段决定是否起 timer —— 我们 override
            // [getConnectionLostTimeout] / [setConnectionLostTimeout] 只能拦截方法调用,改不了字段。
            // 唯一办法:在构造期把字段真置 0,LCD 整个机制就被绕开了。
            //
            // 原因:R4 22h 459 次 onOpen 里 303 次 1006(占 66%)全是 LCD 等不到 WS-level PONG 杀
            // 活连接 — V2Fixed 走 customHeartbeat(binary gzip "心跳内容")路径时不发 WS-level PING,
            // 服务端也不主动发 PONG 帧,LCD 永远等不到。本类 [linkNative] 自管心跳调度,LCD 冗余且有害。
            super.setConnectionLostTimeout(0)

            // P1: 把库的 DNS 解析换成 DnsCache。Java-WebSocket 1.5.4 起支持 setDnsResolver;
            // 默认实现每次连接走 new InetSocketAddress(host) 直查系统 DNS —— DNS 抖动(Server A
            // 06-15 实测连环 UnknownHostException)时直接把重连打死。换成 DnsCache 后:TTL 内命中
            // 缓存、过期 fresh 解析、fresh 失败回退上次成功 IP(stale window 内),把短时 DNS 故障
            // 对长连接的影响抹平。DnsResolver 只能返回单个 InetAddress,取首个。
            setDnsResolver { resolveUri ->
                val host = resolveUri.host ?: throw java.net.UnknownHostException("null host")
                dnsCache.resolve(host).firstOrNull()
                    ?: throw java.net.UnknownHostException(host)
            }
        }

        // 以下字段在引擎线程写、在 java-websocket 读线程 / 计时器线程读,需要可见性
        @Volatile
        var javaWebSocketListener: JavaWebSocketListener? = null
        @Volatile
        private var isClosed = false
        @Volatile
        private var linkNative: ILinkNative? = null
        @Volatile
        private var autoConnect: IAutoConnect? = null
        private val syncConnectionLost = Any()

        /**
         * 本 client 已被引擎换下(URL 切换 / 同 key 强制重建 / spawnNewClient 换代)。
         *
         * 置位后迟到的 onClose/onError 一进来就早退:不推进引擎状态机、不唤醒 autoConnect。
         * 修 N4:切换路径现在会立刻 spawn 新 client,旧 client 的收场可能迟到(卡在不可中断的
         * DNS 里时尤其明显);走老路会进 handleAfterClose 的 Connected/Connecting 分支,
         * 把 webSocketClient 置 null、把 Connecting(newKey) 打回 Reconnecting ——
         * 刚起来的新 client 就这么被打掉。
         *
         * 与 [closeAndTerminate] 的 isClosed 分工:isClosed = 「会话被彻底终止,别再抢救」;
         * superseded = 「我已经不是引擎的当前 client,我的收场不作数」。
         */
        @Volatile
        private var superseded = false

        // ── 协议层(java-websocket 回调)──────────────────────────────

        override fun onOpen(handshakedata: ServerHandshake?) {
            // A5: cert pinning 校验。失败立即 close,不进入正常 onOpen 路径。
            //   pin 失败 → emit TlsFailure(PIN_FAILURE) + close() → 走 onClose → 标准抢救链。
            if (certificatePinner != null && !verifyCertificatePin()) {
                close()
                return
            }
            retryingSendingCache()
            // RC1 修复:连接真正建立时强制重挂计时器(先停后启)。
            // spawn 时 onChangeMode 已 start 过一次,但起点是 spawn 时刻;握手若耗时
            // (弱网下 P50 319ms、max 23s),首 tick 会提前踩进来。此处重挂把计时
            // 起点对齐 onOpen,并顺带冲掉任何陈旧状态。守卫此时 owner===this,放行。
            stopConnectionLostTimerEx()
            startConnectionLostTimerEx()
            linkNative?.updateLastPong()
            autoConnect?.onConnectionEstablished()
            javaWebSocketListener?.onOpen(handshakedata)
        }

        /**
         * A5: 用 [certificatePinner] 比对 SSLSession.peerCertificates。
         * 仅在 wss:// 路径下检查;ws:// 跳过(无证书可比);非 SSL Socket 也跳过。
         */
        private fun verifyCertificatePin(): Boolean {
            val pinner = certificatePinner ?: return true
            val socket = try { socket } catch (_: Exception) { null } ?: return true
            if (socket !is javax.net.ssl.SSLSocket) return true
            val certs = try { socket.session.peerCertificates.toList() } catch (e: Exception) {
                ImcEvents.emit(
                    ImcEvent.TlsFailure(
                        stage = ImcEvent.TlsFailure.Stage.PIN_FAILURE,
                        errorClass = e.javaClass.simpleName,
                        message = "peerCertificates threw: ${e.message}",
                        isPeerInitiated = false,
                    )
                )
                return false
            }
            val host = try { uri.host } catch (_: Exception) { "" } ?: ""
            val ok = try { pinner.pin(host, certs) } catch (e: Exception) {
                ImcEvents.emit(
                    ImcEvent.InternalError(
                        site = "CertificatePinner.pin",
                        errorClass = e.javaClass.simpleName,
                        message = e.message,
                    )
                )
                false
            }
            if (!ok) {
                ImcEvents.emit(
                    ImcEvent.TlsFailure(
                        stage = ImcEvent.TlsFailure.Stage.PIN_FAILURE,
                        errorClass = "CertificatePinner",
                        message = "pin rejected for host=$host",
                        isPeerInitiated = false,
                    )
                )
            }
            return ok
        }

        override fun onMessage(message: String) {
            retryingSendingCache()
            linkNative?.updateLastPong()
            // A1: 心跳应答(如 dataPing 的 "HEART_BEAT")由 SDK 吞掉,不转发给业务 listener。
            // updateLastPong 上面已经触发过,所以这里只做"是否转发"的过滤。
            if (linkNative?.isIncomingHeartbeat(message) == true) return
            javaWebSocketListener?.onMessage(message)
        }

        override fun onMessage(bytes: ByteBuffer) {
            retryingSendingCache()
            linkNative?.updateLastPong()
            // A1: 同上,二进制心跳应答也由 SDK 吞掉。
            val byteArray = ByteArray(bytes.remaining()).also { bytes.duplicate().get(it) }
            if (linkNative?.isIncomingHeartbeat(byteArray) == true) return
            javaWebSocketListener?.onMessage(bytes)
        }

        override fun onClose(code: Int, reason: String?, remote: Boolean) {
            // 本地主动 close(): 不抢救
            if (isClosed) {
                javaWebSocketListener?.onClose(code, reason, remote)
                return
            }
            // 远端关闭: 区分 NORMAL/GOING_AWAY 与其他
            // N4:已被引擎换下 —— 迟到的收场不推进状态机、不唤醒 autoConnect。
            // 必须挡在 javaWebSocketListener 派发之前,否则 handleAfterClose 已经跑完。
            if (superseded) {
                ImcEvents.emit(ImcEvent.ConnectionClosed(code, reason, remote))
                return
            }
            val isRemoteNormalClose = remote &&
                (code == CloseFrame.NORMAL || code == CloseFrame.GOING_AWAY)
            if (isRemoteNormalClose) {
                javaWebSocketListener?.onClose(code, reason, remote)
            } else {
                javaWebSocketListener?.onError(
                    IllegalStateException("code:$code reason:$reason remote:$remote")
                )
            }
            stopConnectionLostTimerEx(true)
            abnormalDisconnectionAndAutomaticReconnection()
        }

        override fun onError(ex: Exception) {
            // N4:已被引擎换下,同 onClose
            if (superseded) return
            javaWebSocketListener?.onError(ex)
            if (isClosed) return
            stopConnectionLostTimerEx(true)
            // A6: 把最近一次 onError 的异常类告诉 autoConnect,让 SSL 连续错触发 forceProbe
            autoConnect?.onErrorSignal(ex.javaClass.simpleName)
            abnormalDisconnectionAndAutomaticReconnection()
        }

        override fun close() {
            super.close()
            // 不设 isClosed=true:历史上这里设过,导致握手 watchdog / 心跳判死 调 close() 后,
            // 紧接着的 onError / onClose 走 isClosed 早返回分支,**跳过**
            // [abnormalDisconnectionAndAutomaticReconnection],autoConnect 不会被调度,
            // 引擎卡死在 Reconnecting,业务侧表现为"点 Connect 很久不连上"(P0)。
            // 现在的语义是:close() = 软 close,让 onClose / onError 继续走标准抢救链;
            // engineOff 真正终止会话要走 [closeAndTerminate],显式标记 isClosed 阻断 autoConnect。
            // 同样**不**回收重发池 —— URL 切换路径也会走 close(),清了等于丢消息;
            // 池的回收只在 engineOff 里做。
        }

        /**
         * engineOff 专用:close + 标记 isClosed=true,阻断后续 onClose/onError 触发 autoConnect。
         *
         * 普通的 close() 调用方(握手 watchdog / 心跳判死 / URL 切换)绝对**不要**走这条 ——
         * 它们都依赖 onClose 继续往下走 [abnormalDisconnectionAndAutomaticReconnection]
         * 让 autoConnect 接管下一轮调度。
         */
        fun closeAndTerminate() {
            synchronized(syncConnectionLost) {
                isClosed = true
            }
            super.close()
        }

        /** 引擎换代时调用:声明本 client 已被替换,之后的收场回调一律不作数。幂等。 */
        fun markSuperseded() {
            synchronized(syncConnectionLost) { superseded = true }
        }

        override fun connect() {
            super.connect()
            synchronized(syncConnectionLost) {
                isClosed = false
            }
        }

        // ── 心跳层 ───────────────────────────────────────────────────

        override fun onWebsocketPong(conn: WebSocket?, f: Framedata?) {
            retryingSendingCache()
            // 走 onWsPong(而非 updateLastPong):让心跳实现能区分「pong 控制帧」
            // 与「普通入站消息」,WS_PONG_ONLY 判活与 cause=PONG 观测都依赖这个区分
            linkNative?.onWsPong()
            super.onWebsocketPong(conn, f)
        }

        fun onChangeMode(linkNative: ILinkNative) {
            val previous = this.linkNative
            this.linkNative = linkNative
            // 无条件 init:initLinkNative 幂等(同 client 早退);换代(spawn 重连后同一
            // ILinkNative 实例挂到新 client)时把 owner 换到本 client 并强杀陈旧计时器,
            // 这是 RC1/RC3 守卫生效的前提 —— 不能只在 previous !== linkNative 时才 init。
            linkNative.initLinkNative(this)
            // RC2 修复:必须先停「旧档位」的计时器再启动新的。
            // 历史代码先换 this.linkNative 再 stopConnectionLostTimerEx(),停的是
            // 新档位 → 旧档位计时器漏网成僵尸,双计时器并行(线上 30s/45s 交替发);
            // 僵尸 tick 在无入站流量时走容差→FAIL,误杀健康连接。
            // 旧档位此刻 owner 仍是本 client,守卫放行。
            if (previous != null && previous !== linkNative) {
                val prevOwner = previous.currentAttachedClient()
                if (prevOwner == null || prevOwner === this) {
                    previous.stopConnectionLostTimer()
                }
            }
            stopConnectionLostTimerEx()
            startConnectionLostTimerEx()
            if (!isOpen) return
            linkNative.sendHeartbeat()
        }

        override fun getConnectionLostTimeout(): Int = linkNative?.getConnectionLostTimeout() ?: 0

        override fun setConnectionLostTimeout(connectionLostTimeout: Int) {
            linkNative?.setConnectionLostTimeout(connectionLostTimeout)
        }

        fun startConnectionLostTimerEx() {
            linkNative?.startConnectionLostTimer()
        }

        fun stopConnectionLostTimerEx(isError: Boolean = false) {
            val ln = linkNative ?: return
            // RC3 代际守卫:同一个 ILinkNative 实例跨 client 世代共享时,
            // 旧 client 迟到的 onClose/onError 不能停掉新 client 刚挂的计时器 —
            // 否则新会话从此无心跳(线上 sent=0 会话的另一根因,EngineKeyChanged
            // 切换路径高发)。owner 为 null(实现未适配代际)时退化为历史行为。
            val owner = ln.currentAttachedClient()
            if (owner != null && owner !== this) return
            ln.stopConnectionLostTimer(isError)
        }

        // ── 抢救层 ───────────────────────────────────────────────────

        fun setAutoConnect(auto: IAutoConnect?) {
            autoConnect = auto
            autoConnect?.initAutoConnect(this)
        }

        private fun abnormalDisconnectionAndAutomaticReconnection() {
            autoConnect?.abnormalDisconnectionAndAutomaticReconnection()
        }

        internal fun resetStartAutoConnect() {
            linkNative?.stopConnectionLostTimer()
            autoConnect?.resetStartAutoConnect()
        }

        fun stopAutoConnect() {
            autoConnect?.stopAutoConnect()
        }

        fun startAutoConnect() {
            autoConnect?.startAutoConnect()
        }

        // ── 发送层 ────────────────────────────────────────────────────
        //
        // 直接对 client 调 send(心跳等)只在 socket 已打开时发,未打开**直接丢弃**,不缓存 ——
        // 迟到的心跳没有意义,业务消息要内核代发请走引擎的 send(payload, bizId) 进重发池。
        // Java-WebSocket 在未打开时 send 会抛 WebsocketNotConnectedException,这里吞掉:
        // 「判 isOpen → 写」之间 socket 可能刚好被关。

        override fun send(text: String?) {
            if (text.isNullOrEmpty() || !isOpen) return
            try { super.send(text) } catch (_: org.java_websocket.exceptions.WebsocketNotConnectedException) {}
        }

        override fun send(data: ByteArray?) {
            if (data == null || !isOpen) return
            try { super.send(data) } catch (_: org.java_websocket.exceptions.WebsocketNotConnectedException) {}
        }

        override fun send(bytes: ByteBuffer?) {
            if (bytes == null || !isOpen) return
            try { super.send(bytes) } catch (_: org.java_websocket.exceptions.WebsocketNotConnectedException) {}
        }

        /**
         * 给重发池的写出口。已被换下 / 已终止的 client 一律视为不可写,
         * 迟到的回调不会把池子冲进旧 socket。
         */
        internal val poolWriter: ResendPool.Writer = object : ResendPool.Writer {
            override val isOpenForWrite: Boolean
                get() = isOpen && !superseded && !isClosed

            /**
             * 按当前 [draft] 成帧写出(池里存的是 payload 不是帧)。客户端必须 mask,
             * createFrames 第二参数固定 true。未打开时 sendFrame 抛 WebsocketNotConnectedException,
             * 由池把这条留在池里。
             */
            override fun write(message: CachedMessage) {
                val frames = when (message) {
                    is CachedMessage.Text -> draft.createFrames(message.text, true)
                    is CachedMessage.Binary -> draft.createFrames(ByteBuffer.wrap(message.data), true)
                }
                sendFrame(frames)
            }
        }

        /** onOpen / 入站消息 / pong 时把重发池里的存量冲出去 */
        private fun retryingSendingCache() {
            if (!poolWriter.isOpenForWrite) return
            resendPool.flush(poolWriter)
        }

    }

    interface JavaWebSocketListener {
        fun onOpen(handshakedata: ServerHandshake?)
        fun onMessage(message: String)
        fun onMessage(bytes: ByteBuffer)
        fun onClose(code: Int, reason: String?, remote: Boolean)
        fun onError(ex: Exception)
    }

    class Builder {
        internal var heartbeatMode = mutableMapOf<Int, ILinkNative>()
        internal var defHeartbeatMode = 0
        internal var autoConnect: IAutoConnect? = null
        // 重发池的存储层:容量 / TTL 由 ResendPoolConfig 管,存储本身不淘汰
        internal var messageCache: IMessageCache = InMemoryMessageCache(maxBytes = Int.MAX_VALUE, maxAgeMs = 0)
        internal var resendPoolConfig: ResendPoolConfig = ResendPoolConfig()
        /** 池的时钟,单测注入;生产用墙钟(Doze 期间照常走,TTL 判定不会被冻结) */
        internal var clock: () -> Long = { System.currentTimeMillis() }
        internal var keyProvider: IKeyProvider? = null
        internal var certificatePinner: CertificatePinner? = null
        internal var dnsCache: DnsCache = DnsCache()

        /** A5: 证书指纹校验钩子;wss:// 握手后立即比对,不通过则触发 close + TlsFailure(PIN_FAILURE) */
        fun setCertificatePinner(pinner: CertificatePinner) = apply { certificatePinner = pinner }

        /** P1: 注入共享 DnsCache(与 NetProber 共用可让探测预热连接用的解析)。不设 = 引擎自带一个。 */
        fun setDnsCache(cache: DnsCache) = apply { this.dnsCache = cache }

        /**
         * 设置 URL Provider:每次 autoConnect 触发重连时调用,拿到最新 url。
         *
         * 不设 = 沿用历史行为(engineOn 传入的 url 固化在 EngineState 里,所有重试都用它)。
         *
         * 设了之后:
         *  - autoConnect 调度的每次重连都会现拿 Provider 给的 url(典型用法:首连/重连标识翻转、
         *    JWT token 续期、备用 host 切换)
         *  - App 仍可直接调 [engineOn] 立即触发一次连接,但下一次 autoConnect 重试会回到 Provider
         *
         * 注意 Provider 不要做阻塞 IO,会在 SDK 重连热路径上同步调用。
         */
        fun setKeyProvider(provider: IKeyProvider) = apply { this.keyProvider = provider }

        fun defHeartbeatMode(d: Int) = apply { this.defHeartbeatMode = d }

        fun addHeartbeatMode(key: Int, value: ILinkNative): Builder = apply {
            heartbeatMode[key] = value
        }

        fun removeHeartbeatMode(key: Int): Builder = apply {
            heartbeatMode.remove(key)
        }

        fun setAutoConnect(auto: IAutoConnect) = apply { autoConnect = auto }

        /**
         * 替换重发池的存储层。
         *
         * 默认纯内存(不自带淘汰,容量 / TTL 由 [setResendPoolConfig] 管)。
         * 想要进程重建后继续重放就用 [FileMessageCache](建议传 owner = 账号 ID,
         * maxBytes 不小于池的 maxBytes);Android 想存到 SP 可参考 demo 模块的 `SharedPrefsMessageCache`。
         *
         * 只有带业务 ID 发送的消息才会进池,详见 [IResendPool]。用持久化存储时,业务在进程重启后
         * 自行补发前同样要先 [IResendPool.take],否则会和内核从文件恢复的条目双发。
         */
        fun setMessageCache(cache: IMessageCache) = apply { this.messageCache = cache }

        /** 重发池容量 / TTL / 状态记录保留策略,见 [ResendPoolConfig] */
        fun setResendPoolConfig(config: ResendPoolConfig) = apply { this.resendPoolConfig = config }

        internal fun setClock(clock: () -> Long) = apply { this.clock = clock }

        fun build(): V2JavaWebEngine = V2JavaWebEngine(this)
    }
}
