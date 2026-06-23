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
 *      ↑          ↑               │                       │
 *      │          │               │ onClose/onError       │ onClose/onError
 *      │          │               ↓                       ↓
 *      │          └─────── Reconnecting(k') ←─engineOn(k')─┘
 *      │                          │
 *      │                          │ (auto-reconnect 后 onOpen,
 *      │                          │  或 onClose 路径里 engineOn(k') 重启)
 *      │ engineOff                │
 *      └────── Closing ←──────────┘
 * ```
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
    private val messageCache: IMessageCache = builder.messageCache
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
     * 在锁外 fork 线程,因为 engineOn 内部 synchronized,autoConnect 的 alarm 回调线程不该被它阻塞。
     */
    private val autoConnectReconnector = IReconnector {
        val nextKey = resolveReconnectKey()
        if (nextKey == null) {
            return@IReconnector
        }
        Thread({ engineOn(nextKey) }, "V2JWE-restart").start()
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
            imcListenerManager.onMessage(this@V2JavaWebEngine, bytes.array())
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
         * onClose / onError 后的统一状态推进:
         *  - Reconnecting(k') 路径(URL 切换 / 调度的重连):置空 client + 重新 engineOn(k')
         *  - Connected/Connecting:转 Reconnecting(curKey),交给 ProgressiveAutoConnect 接管
         *  - Closing:转 Idle 并释放
         *  - Idle:已经被 engineOff 清理过,无事可做
         */
        private fun handleAfterClose() {
            handshakeWatchdog.unregister(handshakeWatchdogId)
            // 锁内只搬状态机 + 抓 transition 描述,事件 emit 移到锁外
            data class Transition(val from: String, val to: String, val reason: String)
            var transition: Transition? = null
            val nextKey: String? = synchronized(syncJWE) {
                when (val s = state) {
                    is EngineState.Reconnecting -> {
                        webSocketClient = null
                        if (autoConnect.isActive()) null else resolveReconnectKey()
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
                        null
                    }
                    EngineState.Closing -> {
                        webSocketClient = null
                        state = EngineState.Idle
                        transition = Transition("Closing", "Idle", "engineOff complete")
                        null
                    }
                    EngineState.Idle -> null
                }
            }
            transition?.let {
                ImcEvents.emit(
                    ImcEvent.EngineStateTransition(it.from, it.to, it.reason)
                )
            }
            // 锁外 engineOn 避免重入
            if (nextKey != null) {
                Thread({ engineOn(nextKey) }, "V2JWE-restart").start()
            }
        }
    }

    // ── IEngine API ────────────────────────────────────────────────────

    override fun engineOn(key: String) {
        // 锁内只动状态机;事件 emit 放锁外,避免 sink 卡死引擎入口
        val keyChangeEvent: ImcEvent.EngineKeyChanged?
        synchronized(syncJWE) {
            val oldKey = state.targetKey()
            keyChangeEvent = if (oldKey != key) ImcEvent.EngineKeyChanged(oldKey, key) else null
            dispatchEngineOn(key)
        }
        keyChangeEvent?.let { ImcEvents.emit(it) }
    }

    /** 必须在 syncJWE 锁内调用 */
    private fun dispatchEngineOn(key: String) {
        val s = state
        when {
            s == EngineState.Idle || s == EngineState.Closing -> {
                spawnNewClient(key)
            }
            s is EngineState.Connecting && key != s.key -> {
                state = EngineState.Reconnecting(key)
                webSocketClient?.close()
            }
            s is EngineState.Connected && key != s.key -> {
                state = EngineState.Reconnecting(key)
                webSocketClient?.close()
            }
            s is EngineState.Reconnecting && key == s.key -> {
                // 同 key 调度的重连进来(典型:autoConnect alarm fire 走 reconnector.restart →
                // engineOn(同 key))。理论上 webSocketClient 已被 handleAfterClose 置 null;
                // 兜底:若没置(历史 bug 残留 / 其他路径),这里先把旧 client 关掉再 spawn,
                // 避免错过 spawnNewClient 导致 engine 静默挂死。close 幂等,旧 client 已 closed
                // 不会出错。
                webSocketClient?.let {
                    try { it.close() } catch (_: Exception) {}
                }
                webSocketClient = null
                spawnNewClient(key)
            }
            s is EngineState.Reconnecting && key != s.key -> {
                // 切到另一个 key, 旧 client 可能还在关
                state = EngineState.Reconnecting(key)
                webSocketClient?.close()
            }
            // 其他情况(同 key 已在连接 / 等关)no-op
        }
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
            messageCache = messageCache,
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
        // 真正的会话终止才清缓存(持久化实现的 clear() 会抹掉文件 / SP)。
        // 切 URL 那条路径不走 engineOff,因此不会误伤缓存。
        try { messageCache.clear() } catch (e: Exception) { e.printStackTrace() }
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

    override fun send(byteArray: ByteArray): Boolean {
        if (state !is EngineState.Connected) makeConnection()
        webSocketClient?.send(byteArray)
        return state is EngineState.Connected
    }

    override fun send(text: String): Boolean {
        if (state !is EngineState.Connected) makeConnection()
        webSocketClient?.send(text)
        return state is EngineState.Connected
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
     */
    private fun kickReconnect() {
        val client = webSocketClient
        if (client != null) {
            client.resetStartAutoConnect()
        } else {
            autoConnect.resetStartAutoConnect()
        }
    }

    // ── 内部 WebSocket 客户端 ─────────────────────────────────────────────

    class WebSocketClientImpl(
        serverUri: URI,
        httpHeaders: Map<String, String> = mutableMapOf(),
        timeout: Int = 0,
        private val messageCache: IMessageCache = InMemoryMessageCache(),
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

        var javaWebSocketListener: JavaWebSocketListener? = null
        private var isClosed = false
        private var linkNative: ILinkNative? = null
        private var autoConnect: IAutoConnect? = null
        private val syncConnectionLost = Any()

        // 发送缓存:用 cacheSync 串行化 send() 路径里"判定 isOpen → 立即发 or 入缓存"两步,
        // 防止 isOpen 真切换瞬间出现旁路。缓存内部容量 / TTL / 持久化等策略全交给 [IMessageCache].
        private val cacheSync = Any()

        // ── 协议层(java-websocket 回调)──────────────────────────────

        override fun onOpen(handshakedata: ServerHandshake?) {
            // A5: cert pinning 校验。失败立即 close,不进入正常 onOpen 路径。
            //   pin 失败 → emit TlsFailure(PIN_FAILURE) + close() → 走 onClose → 标准抢救链。
            if (certificatePinner != null && !verifyCertificatePin()) {
                close()
                return
            }
            retryingSendingCache()
            startConnectionLostTimerEx()
            linkNative?.updateLastPong()
            stopAutoConnect()
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
            // 同样**不**调 messageCache.clear() —— URL 切换路径也会走 close(),清了等于丢消息;
            // 清缓存只在 engineOff 的快路径里做。
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

        override fun connect() {
            super.connect()
            synchronized(syncConnectionLost) {
                isClosed = false
            }
        }

        // ── 心跳层 ───────────────────────────────────────────────────

        override fun onWebsocketPong(conn: WebSocket?, f: Framedata?) {
            retryingSendingCache()
            linkNative?.updateLastPong()
            super.onWebsocketPong(conn, f)
        }

        fun onChangeMode(linkNative: ILinkNative) {
            this.linkNative = linkNative
            linkNative.initLinkNative(this)
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
            linkNative?.stopConnectionLostTimer(isError)
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

        // ── 发送缓存层 ────────────────────────────────────────────────
        //
        // 未连上时按 FIFO 把应用层 payload(text / 字节)塞进 [messageCache];
        // isOpen 切换 / onMessage / onPong 触发 retryingSendingCache 排空。
        // 帧不缓存, flush 时按当前 [draft] 重新成帧 — 这样持久化实现只需要序列化 payload。
        // 客户端必须 mask, draft.createFrames 第二参数固定 true。

        override fun send(text: String?) {
            if (text.isNullOrEmpty()) return
            // 锁内只动缓存,锁外 emit:synchronized 是 inline 的,non-local return 可以直接跳出 send
            val cachedEvent: ImcEvent.MessageCached = synchronized(cacheSync) {
                if (isOpen) {
                    super.send(text)
                    return  // non-local return → 不走 emit
                }
                val msg = CachedMessage.Text(RapidResponseForceV4.makeOnlyId(), text)
                messageCache.put(msg)
                ImcEvent.MessageCached(msg.id, isText = true, sizeBytes = msg.sizeBytes)
            }
            ImcEvents.emit(cachedEvent)
        }

        override fun send(data: ByteArray?) {
            if (data == null) return
            val cachedEvent: ImcEvent.MessageCached = synchronized(cacheSync) {
                if (isOpen) {
                    super.send(data)
                    return
                }
                val msg = CachedMessage.Binary(RapidResponseForceV4.makeOnlyId(), data)
                messageCache.put(msg)
                ImcEvent.MessageCached(msg.id, isText = false, sizeBytes = msg.sizeBytes)
            }
            ImcEvents.emit(cachedEvent)
        }

        override fun send(bytes: ByteBuffer?) {
            if (bytes == null) return
            val cachedEvent: ImcEvent.MessageCached = synchronized(cacheSync) {
                if (isOpen) {
                    super.send(bytes)
                    return
                }
                // ByteBuffer 可能直接共享底层数组,持久化场景必须复制成独立 byte[]
                val arr = ByteArray(bytes.remaining())
                bytes.duplicate().get(arr)
                val msg = CachedMessage.Binary(RapidResponseForceV4.makeOnlyId(), arr)
                messageCache.put(msg)
                ImcEvent.MessageCached(msg.id, isText = false, sizeBytes = msg.sizeBytes)
            }
            ImcEvents.emit(cachedEvent)
        }

        private fun retryingSendingCache() {
            // 收集本次 flush 的事件,锁内只填,锁外统一 emit
            val flushedEvents = ArrayList<ImcEvent>()
            var remaining = 0
            synchronized(cacheSync) {
                if (!isOpen || messageCache.isEmpty()) return@synchronized
                var flushed = 0
                var item = messageCache.pollFirst()
                while (item != null) {
                    if (!isOpen) {
                        // 中途断了,放回队首等下次
                        messageCache.pushFirst(item)
                        remaining = messageCache.size()
                        break
                    }
                    val frames = when (val m: CachedMessage = item) {
                        is CachedMessage.Text -> draft.createFrames(m.text, true)
                        is CachedMessage.Binary -> draft.createFrames(ByteBuffer.wrap(m.data), true)
                    }
                    sendFrame(frames)
                    flushedEvents.add(ImcEvent.MessageFlushed(item.id, item.sizeBytes))
                    flushed++
                    item = messageCache.pollFirst()
                }
                if (remaining == 0) remaining = messageCache.size()
                if (flushed > 0) {
                    flushedEvents.add(ImcEvent.CacheFlushBatch(flushed = flushed, remaining = remaining))
                }
            }
            // 锁外 emit
            for (e in flushedEvents) ImcEvents.emit(e)
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
        internal var messageCache: IMessageCache = InMemoryMessageCache()
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
         * 替换默认的发送缓存实现。
         *
         * 默认 [InMemoryMessageCache](64KB + 5s TTL),纯内存。
         * 想要进程重建后继续重放就用 [FileMessageCache];Android 想存到 SP
         * 可参考 demo 模块的 `SharedPrefsMessageCache`。
         */
        fun setMessageCache(cache: IMessageCache) = apply { this.messageCache = cache }

        fun build(): V2JavaWebEngine = V2JavaWebEngine(this)
    }
}
