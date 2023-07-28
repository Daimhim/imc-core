package org.daimhim.imc_core

import okhttp3.*
import okhttp3.internal.checkDuration
import okhttp3.internal.ws.RealWebSocket
import okio.ByteString
import timber.multiplatform.log.Timber
import java.net.SocketTimeoutException
import java.util.*
import java.util.concurrent.*
import java.util.concurrent.locks.ReentrantLock

class WebSocketEngine(private val builder: Builder) : IEngine {

    /**
     * Ok http client
     * 客户端
     */
    private val okHttpClient: OkHttpClient = builder.okHttpClient()
    private val maxReconnectDelay = builder.maxReconnectDelay

    /**
     * Web socket 长连接
     */
    internal var webSocket: WebSocket? = null
    private val webSocketListenerImpl = object : WebSocketListener() {
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Timber.d("onClosed code$code reason$reason")
            engineState = IEngineState.ENGINE_CLOSED
            heartbeat.stopHeartbeat()
            imcStatusListener?.connectionClosed()
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Timber.d("onClosing code$code reason$reason")
        }

        override fun onOpen(webSocket: WebSocket, response: Response) {
            Timber.d("onOpen response${response.message}")
            iEngineActionListener?.onSuccess(getIEngine())
            engineState = IEngineState.ENGINE_OPEN
            heartbeat.startHeartbeat()
            imcStatusListener?.connectionSucceeded()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Timber.d(t, "onFailure response ${connected} ${response?.message}")
            if (!connected) {
                onClosed(webSocket, IEngineState.ENGINE_CLOSED_FAILED, response?.message ?: "")
                return
            }
            val first = iEngineActionListener == null
            iEngineActionListener?.onFailure(getIEngine(), t)
            engineState = IEngineState.ENGINE_FAILED
            heartbeat.stopHeartbeat()
            autoReconnect.startReconnectCycle()
            imcStatusListener?.connectionLost(t)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            imcListenerManager.onMessage(getIEngine(), text)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            imcListenerManager.onMessage(getIEngine(), bytes)
        }
    }
    var engineState = IEngineState.ENGINE_CLOSED // 当前连接状态
    override fun engineOn(key: String) {
        engineOn(Request.Builder().url(key).build())
    }

    /**
     * Connecting 连接中
     */
    private var connecting = false

    /**
     * Connected 从开始连接到关闭  true->false
     */
    private var connected = false

    /**
     * 连接反馈监听
     * I engine action listener
     */
    private var iEngineActionListener: IEngineActionListener? = null

    // 连接操作同步锁
    private val connectLock = Any()
    override fun engineOn(request: Request, engineActionListener: IEngineActionListener?) {
        Timber.d("engineOn 111")
        check(!connecting) {
            throw IllegalStateException("正在连接，请稍后重试")
        }
        check(engineState() != IEngineState.ENGINE_OPEN) {
            throw IllegalStateException("已经连接了，请勿重复连接")
        }
        check(!connected) {
            throw IllegalStateException("已经连接了，请勿重复连接")
        }
        Timber.d("engineOn 222")
        // 初始化心跳
        val name = "WebSocketEngine ${request.url.redact()}"
        heartbeat.initHeartbeat(getIEngine(), name)
        autoReconnect.initAutoReconnect(getIEngine(), name)
        connect(request, engineActionListener)
    }

    private fun connect(request: Request, engineActionListener: IEngineActionListener?) {
        Timber.d("engineOn 333")
        synchronized(connectLock) {
            connecting = true
            connected = true
            engineState = IEngineState.ENGINE_CONNECTING
            webSocket?.close(IEngineState.ENGINE_CLOSED, "engineOn")
            // 连接回调
            iEngineActionListener = object : IEngineActionListener {
                override fun onSuccess(iEngine: IEngine) {
                    Timber.i("connect onSuccess")
                    engineActionListener?.onSuccess(iEngine)
                    iEngineActionListener = null
                    synchronized(connectLock) {
                        connecting = false
                    }
                }

                override fun onFailure(iEngine: IEngine, t: Throwable) {
                    Timber.i("connect onFailure")
                    engineActionListener?.onFailure(iEngine, t)
                    iEngineActionListener = null
                    synchronized(connectLock) {
                        connecting = false
                    }
                }
            }
            Timber.i("AutoReconnect ReconnectTask run 222 ")
            // 直接连接
            webSocket = okHttpClient.newWebSocket(
                request,
                webSocketListenerImpl
            )
        }
    }

    private fun getIEngine(): WebSocketEngine {
        return this
    }

    override fun engineOff() {
        synchronized(connectLock) {
            connected = false
        }

        val close = webSocket?.close(IEngineState.ENGINE_CLOSED, "engineOff") ?: false
        Timber.i("engineOff $close")
        if (webSocket != null && !close) {
            webSocket?.cancel()
        }
        if (engineState != IEngineState.ENGINE_CLOSED){
            webSocketListenerImpl.onClosed(
                webSocket!!, IEngineState.ENGINE_CLOSED_FAILED,
                "The response to sending the close command timed out or the socket has been closed due to failure"
            )
        }
        webSocket = null
    }

    override fun engineState(): Int {
        return engineState
    }

    override fun send(byteString: ByteString):Boolean {
        return webSocket?.send(byteString)?:false
    }

    override fun send(text: String):Boolean {
        return webSocket?.send(text)?:false
    }

    /**
     * 节能与性能模式
     * Set foreground
     *
     * @param foreground
     */
    override fun onChangeMode(mode: Int) {
        Timber.d("Heartbeat setForeground foreground ${mode}")
        if (!connected) {
            return
        }
        // 非强制实现
        heartbeat.heartbeatInterval = if (mode == 0) {
            builder.minHeartbeatInterval
        } else {
            builder.maxHeartbeatInterval
        }.toLong()
        heartbeat.startHeartbeat()
    }

    /**
     * 网络状态切换
     * On network change
     *
     * @param networkState
     */
    override fun onNetworkChange(networkState: Int) {
        // 非强制实现
        if (!connected) {
            return
        }
        autoReconnect.stopReconnectCycle()
        autoReconnect.startReconnectCycle()
    }

    internal fun failWebSocket(e: Exception, response: Response?){
        (webSocket as RealWebSocket?)?.failWebSocket(e,response)
    }

    /**
     * 自动连接
     * Auto reconnect
     */
    private val autoReconnect = AutoReconnect()

    private class AutoReconnect() {
        internal lateinit var webSocketEngine: WebSocketEngine
        private var reconnectTimer: ScheduledExecutorService? = null
        private var reconnectFuture: ScheduledFuture<*>? = null
        internal var reconnectDelay = 1000L // Reconnect delay, starts at 1
        private var reconnecting = false;
        fun initAutoReconnect(engine: WebSocketEngine, name: String) {
            Timber.d("initAutoReconnect.")
            webSocketEngine = engine
            reconnectTimer = ScheduledThreadPoolExecutor(
                1,
                okhttp3.internal.threadFactory(name, false)
            )
        }

        fun startReconnectCycle() {
            Timber.d("startReconnectCycle $reconnecting $reconnectDelay $webSocketEngine")
            if (webSocketEngine.connecting) {
                return
            }
            if (reconnecting) {
                return
            }
            reconnecting = true
            reconnectFuture = reconnectTimer?.schedule(
                ReconnectTask(this),
                reconnectDelay, TimeUnit.MILLISECONDS
            );
        }

        fun stopReconnectCycle() {
            Timber.d("stopReconnectCycle")
            reconnecting = false
            reconnectFuture?.cancel(true)
            reconnectFuture = null
            reconnectDelay = 1000L // Reset Delay Timer
        }

        fun rescheduleReconnectCycle(delay: Long) {
            Timber.d("rescheduleReconnectCycle $delay")
            if (reconnectTimer != null) {
                reconnectFuture = reconnectTimer?.schedule(
                    ReconnectTask(this),
                    delay, TimeUnit.MILLISECONDS
                );
            } else {
                reconnectDelay = delay
                startReconnectCycle()
            }
        }
    }

    private class ReconnectTask(
        private val autoReconnect: AutoReconnect
    ) : TimerTask() {
        override fun run() {
            Timber.i("AutoReconnect ReconnectTask run 000")
            autoReconnect.webSocketEngine
                .connect(autoReconnect.webSocketEngine.webSocket?.request()!!, object : IEngineActionListener {
                    override fun onSuccess(iEngine: IEngine) {
                        Timber.i("AutoReconnect ReconnectTask onSuccess")
                        autoReconnect.stopReconnectCycle()
                    }

                    override fun onFailure(iEngine: IEngine, t: Throwable) {
                        Timber.i("AutoReconnect ReconnectTask onFailure")
                        if (autoReconnect.reconnectDelay < autoReconnect.webSocketEngine.maxReconnectDelay) {
                            autoReconnect.reconnectDelay = autoReconnect.reconnectDelay * 2
                        }
                        autoReconnect.rescheduleReconnectCycle(autoReconnect.reconnectDelay)
                    }
                })
            Timber.i("AutoReconnect ReconnectTask run 111")
        }

    }


    /**
     * Heartbeat 心跳实现
     *
     */
    private val heartbeat = Heartbeat()

    private class Heartbeat : V2IMCSocketListener<IEngine> {
        var heartbeatInterval = 5 * 1000L
        private var awaitingPong = false
        private var scheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
        private var currentScheduledFuture: Future<*>? = null
        private lateinit var webSocketEngine: WebSocketEngine
        private val lockHeartbeat = ReentrantLock()
        private val available = lockHeartbeat.newCondition()
        private var isActive = false

        private var auxiliaryHeartbeat = false

        fun initHeartbeat(engine: WebSocketEngine, name: String) {
            Timber.d("Heartbeat PingRunnable initHeartbeat")
            webSocketEngine = engine
            webSocketEngine.removeIMCSocketListener(this)
            webSocketEngine.addIMCSocketListener(9, this)
        }

        fun startHeartbeat() {
            Timber.d("Heartbeat PingRunnable startHeartbeat")
            lockHeartbeat.lock()
            awaitingPong = false
            auxiliaryHeartbeat = false
            try {
                if (!isActive){
                    currentScheduledFuture = scheduledExecutorService
                        .submit(PingRunnable())
                }else{
                    available.signal()
                }
            }finally {
                lockHeartbeat.unlock()
            }
            Timber.d("Heartbeat PingRunnable minAuxiliaryHeartbeatCalculationTime")
        }

        fun stopHeartbeat() {
            Timber.d("Heartbeat PingRunnable stopHeartbeat")
            lockHeartbeat.lock()
            try {
                currentScheduledFuture?.cancel(true)
                currentScheduledFuture = null
            }finally {
                lockHeartbeat.unlock()
            }

        }


        override fun onMessage(iEngine: IEngine, text: String): Boolean {
            return updateAuxiliaryHeartbeat(false)
        }

        override fun onMessage(iEngine: IEngine, bytes: ByteString): Boolean {
            return updateAuxiliaryHeartbeat(bytes == ByteString.EMPTY)
        }

        private fun updateAuxiliaryHeartbeat(isHeartbeat:Boolean):Boolean {
            lockHeartbeat.lock()
            Timber.d("Heartbeat PingRunnable onMessage  bytes 111 $isHeartbeat")
            try {
                //心跳
                if (isHeartbeat) {
                    awaitingPong = false
                    return true
                }
                auxiliaryHeartbeat = true
                available.signal()
            }finally {
                lockHeartbeat.unlock()
            }
            return false
        }

        private inner class PingRunnable : Runnable {
            override fun run() {
                lockHeartbeat.lock()
                isActive = true
                try {
                    while (true){
                        Timber.d("Heartbeat PingRunnable run ${awaitingPong}")
                        if (awaitingPong) {
                            webSocketEngine.failWebSocket(
                                    SocketTimeoutException(
                                        "sent ping but didn't receive pong within " +
                                                "${heartbeatInterval}ms"
                                    ), null
                                )
                            return
                        }
                        Timber.d("Heartbeat PingRunnable run 111 auxiliaryHeartbeat ${auxiliaryHeartbeat}")
                        if (auxiliaryHeartbeat){
                            auxiliaryHeartbeat = false
                            val await = available.await(heartbeatInterval, TimeUnit.MILLISECONDS)
                            continue
                        }
                        Timber.d("Heartbeat PingRunnable run 222 awaitingPong$awaitingPong heartbeatInterval${heartbeatInterval} ")
                        awaitingPong = true
                        webSocketEngine.send(ByteString.EMPTY)
                        val await = available.await(heartbeatInterval, TimeUnit.MILLISECONDS)
                    }
                }finally {
                    isActive = false
                    lockHeartbeat.unlock()
                }
            }

        }
    }




    /***
     * 监听相关
     */
    private val imcListenerManager = IMCListenerManager<IEngine>()
    private var imcStatusListener: IMCStatusListener? = null
    override fun addIMCListener(imcListener: V2IMCListener) {
        imcListenerManager.addIMCListener(imcListener)
    }

    override fun removeIMCListener(imcListener: V2IMCListener) {
        imcListenerManager.removeIMCListener(imcListener)
    }

    override fun addIMCSocketListener(level: Int, imcSocketListener: V2IMCSocketListener<IEngine>) {
        imcListenerManager.addIMCSocketListener(level, imcSocketListener)
    }

    override fun removeIMCSocketListener(imcSocketListener: V2IMCSocketListener<IEngine>) {
        imcListenerManager.removeIMCSocketListener(imcSocketListener)
    }

    override fun setIMCStatusListener(listener: IMCStatusListener?) {
        imcStatusListener = listener
    }

    class Builder {
        internal var okHttpClient: OkHttpClient? = null
        internal var maxHeartbeatInterval = 45 * 1000
        internal var minHeartbeatInterval = 5 * 1000
        internal var maxReconnectDelay = 128 * 1000

        /**
         * Ok http client
         *
         * @return 为IMC提供的okhttp客户端
         */
        fun okHttpClient(): OkHttpClient =
            okHttpClient ?: OkHttpClient
                .Builder()
                .build()

        fun okHttpClient(okHttpClient: OkHttpClient) = apply {
            this.okHttpClient = okHttpClient
        }

        fun heartbeatInterval(min: Long, max: Long, unit: TimeUnit) = apply {
            minHeartbeatInterval = checkDuration("minHeartbeatInterval", min, unit)
            maxHeartbeatInterval = checkDuration("maxHeartbeatInterval", max, unit)
        }

        fun maxReconnectDelay(delay: Long, unit: TimeUnit) = apply {
            maxReconnectDelay = checkDuration("maxReconnectDelay", delay, unit)
        }

        fun build(): WebSocketEngine {
            return WebSocketEngine(this)
        }
    }
}