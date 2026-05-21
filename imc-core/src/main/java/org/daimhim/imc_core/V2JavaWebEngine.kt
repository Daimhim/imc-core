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
    private val autoConnect: IAutoConnect? = builder.autoConnect
    private val messageCache: IMessageCache = builder.messageCache
    private val keyProvider: IKeyProvider? = builder.keyProvider
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
            IMCLog.w("[reconnector] 跳过本次重连:KeyProvider 返回 null 且当前没有缓存 key")
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
        autoConnect?.setReconnector(autoConnectReconnector)
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
            IMCLog.i("onOpen ${handshakedata?.httpStatus} ${handshakedata?.httpStatusMessage}")
            synchronized(syncJWE) {
                val key = state.targetKey()
                if (key != null) state = EngineState.Connected(key)
                IMCLog.i("transition → $state")
            }
            imcStatusListener?.connectionSucceeded()
        }

        override fun onMessage(message: String) {
            IMCLog.i("onMessage str:$message")
            imcListenerManager.onMessage(this@V2JavaWebEngine, message)
        }

        override fun onMessage(bytes: ByteBuffer) {
            IMCLog.i("onMessage bytes:${bytes.limit()}")
            imcListenerManager.onMessage(this@V2JavaWebEngine, bytes.array())
        }

        override fun onClose(code: Int, reason: String?, remote: Boolean) {
            IMCLog.i("onClose code:$code reason:$reason remote:$remote")
            imcStatusListener?.connectionClosed(code, reason)
            handleAfterClose()
        }

        override fun onError(ex: Exception) {
            IMCLog.i(ex, "onError")
            imcStatusListener?.connectionLost(ex)
            handleAfterClose()
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
            val nextKey: String? = synchronized(syncJWE) {
                when (val s = state) {
                    is EngineState.Reconnecting -> {
                        // 两种情况都会落到这里:
                        //  (A) dispatchEngineOn 因 URL 切换提前置 Reconnecting,且关掉了旧 client → 应立刻重启新 client
                        //  (B) ProgressiveAutoConnect 调 webSocketClient.reconnect() 触发 onClose 时,
                        //      state 已经由前一次 handleAfterClose 第二分支搬到 Reconnecting
                        // (B) 必须让出控制权给 autoConnect 自己驱动退避,否则会无视退避立即重连 → 风暴
                        webSocketClient = null
                        // fallback 重启走 resolveReconnectKey():有 KeyProvider 就拿 Provider 的最新 url,
                        // 没有就退到 s.key(state 缓存)。跟 autoConnectReconnector 行为一致。
                        if (autoConnect?.isActive() == true) null else resolveReconnectKey()
                    }
                    is EngineState.Connected, is EngineState.Connecting -> {
                        val k = s.targetKey()!!
                        state = EngineState.Reconnecting(k)
                        IMCLog.i("transition → $state (waiting auto-reconnect)")
                        null
                    }
                    EngineState.Closing -> {
                        webSocketClient = null
                        state = EngineState.Idle
                        IMCLog.i("transition → Idle")
                        null
                    }
                    EngineState.Idle -> null
                }
            }
            // 锁外 engineOn 避免重入
            if (nextKey != null) {
                Thread({ engineOn(nextKey) }, "V2JWE-restart").start()
            }
        }
    }

    // ── IEngine API ────────────────────────────────────────────────────

    override fun engineOn(key: String) {
        synchronized(syncJWE) {
            IMCLog.i("engineOn key=$key state=$state")
            dispatchEngineOn(key)
        }
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
            s is EngineState.Reconnecting && key == s.key && webSocketClient == null -> {
                // 同 key 且旧 client 已被 handleAfterClose 释放 → 重启
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

    /** 必须在 syncJWE 锁内调用 */
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
        ).apply {
            javaWebSocketListener = this@V2JavaWebEngine.javaWebsocketListener
            setAutoConnect(autoConnect)
            // 用 pendingMode (而非 defHeartbeatMode), 让连接前调过的 onChangeMode 生效
            heartbeatMode[pendingMode]?.let { onChangeMode(it) }
        }
        webSocketClient = client
        state = EngineState.Connecting(key)
        IMCLog.i("transition → $state, connecting... heartbeatMode=$pendingMode")
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
            synchronized(syncJWE) {
                val s = state
                if (s is EngineState.Connecting && s.key == key && webSocketClient === client) {
                    IMCLog.w("handshake watchdog 超时(${handshakeTimeoutMs}ms), 强关 client → 走 Reconnecting")
                    client.close()
                }
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
        synchronized(syncJWE) {
            IMCLog.i("engineOff state=$state")
            if (state == EngineState.Idle) return
            handshakeWatchdog.unregister(handshakeWatchdogId)
            state = EngineState.Closing
            val client = webSocketClient
            // 顺序:解绑 listener → 停 timer → 停 auto-connect → close socket → 释放
            // 解绑 listener 在 close 之前,onClose 回来时 listener=null 不会再触发 handleAfterClose
            client?.javaWebSocketListener = null
            client?.stopConnectionLostTimerEx(false)
            client?.stopAutoConnect()
            client?.close()
            webSocketClient = null
            state = EngineState.Idle
            IMCLog.i("transition → Idle")
        }
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
        IMCLog.i("onChangeMode $mode (prev pendingMode=$pendingMode)")
        // 不管 webSocketClient 是否已建, 都记下这次调用 — 下次 spawnNewClient 会用它
        pendingMode = mode
        heartbeatMode[mode]?.let {
            webSocketClient?.onChangeMode(it)
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
            autoConnect?.resetStartAutoConnect()
        }
    }

    // ── 内部 WebSocket 客户端 ─────────────────────────────────────────────

    class WebSocketClientImpl(
        serverUri: URI,
        httpHeaders: Map<String, String> = mutableMapOf(),
        timeout: Int = 0,
        private val messageCache: IMessageCache = InMemoryMessageCache(),
    ) : WebSocketClient(serverUri, Draft_6455(), httpHeaders, timeout) {

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
            retryingSendingCache()
            startConnectionLostTimerEx()
            linkNative?.updateLastPong()
            stopAutoConnect()
            javaWebSocketListener?.onOpen(handshakedata)
        }

        override fun onMessage(message: String) {
            retryingSendingCache()
            linkNative?.updateLastPong()
            javaWebSocketListener?.onMessage(message)
        }

        override fun onMessage(bytes: ByteBuffer) {
            retryingSendingCache()
            linkNative?.updateLastPong()
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
            abnormalDisconnectionAndAutomaticReconnection()
        }

        override fun close() {
            super.close()
            synchronized(syncConnectionLost) {
                isClosed = true
            }
            // 显式 close 意味着用户希望终止本次会话, 缓存按现有语义一起丢弃。
            // 注意:持久化实现的 clear() 会同步抹掉文件 / SP, 这跟"关掉就别记账"的预期一致。
            messageCache.clear()
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
            IMCLog.i("resetStartAutoConnect")
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
            synchronized(cacheSync) {
                if (isOpen) {
                    super.send(text); return
                }
                messageCache.put(
                    CachedMessage.Text(RapidResponseForceV4.makeOnlyId(), text)
                )
            }
        }

        override fun send(data: ByteArray?) {
            if (data == null) return
            synchronized(cacheSync) {
                if (isOpen) {
                    super.send(data); return
                }
                messageCache.put(
                    CachedMessage.Binary(RapidResponseForceV4.makeOnlyId(), data)
                )
            }
        }

        override fun send(bytes: ByteBuffer?) {
            if (bytes == null) return
            synchronized(cacheSync) {
                if (isOpen) {
                    super.send(bytes); return
                }
                // ByteBuffer 可能直接共享底层数组,持久化场景必须复制成独立 byte[]
                val arr = ByteArray(bytes.remaining())
                bytes.duplicate().get(arr)
                messageCache.put(
                    CachedMessage.Binary(RapidResponseForceV4.makeOnlyId(), arr)
                )
            }
        }

        private fun retryingSendingCache() {
            synchronized(cacheSync) {
                if (!isOpen || messageCache.isEmpty()) return@synchronized
                var flushed = 0
                var item = messageCache.pollFirst()
                while (item != null) {
                    if (!isOpen) {
                        // 中途断了,放回队首等下次
                        messageCache.pushFirst(item)
                        IMCLog.i("retryingSendingCache 中途断, 已 flush $flushed 条, 剩 ${messageCache.size()} 条")
                        return@synchronized
                    }
                    val frames = when (val m: CachedMessage = item) {
                        is CachedMessage.Text -> draft.createFrames(m.text, true)
                        is CachedMessage.Binary -> draft.createFrames(ByteBuffer.wrap(m.data), true)
                    }
                    sendFrame(frames)
                    flushed++
                    item = messageCache.pollFirst()
                }
                if (flushed > 0) IMCLog.i("retryingSendingCache 全部 flush 完, 共 $flushed 条")
            }
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
        internal var imcLogFactory: IIMCLogFactory? = null
        internal var heartbeatMode = mutableMapOf<Int, ILinkNative>()
        internal var defHeartbeatMode = 0
        internal var autoConnect: IAutoConnect? = null
        internal var messageCache: IMessageCache = InMemoryMessageCache()
        internal var keyProvider: IKeyProvider? = null

        fun setIMCLog(imcLogFactory: IIMCLogFactory) = apply {
            this.imcLogFactory = imcLogFactory
        }

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

        fun build(): V2JavaWebEngine {
            IMCLog.setIIMCLogFactory(imcLogFactory)
            return V2JavaWebEngine(this)
        }
    }
}
