package org.daimhim.imc_core

import okhttp3.*
import okhttp3.internal.checkDuration
import okio.ByteString
import okio.ByteString.Companion.toByteString
import timber.multiplatform.log.Timber
import java.net.SocketTimeoutException
import java.util.*
import java.util.concurrent.*

class OkhttpIEngine(private val builder: Builder) : IEngine {

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
            Timber.i("onClosed code$code reason$reason")
            engineState = IEngineState.ENGINE_CLOSED
            heartbeat.stopHeartbeat()
            imcStatusListener?.connectionClosed()
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Timber.i("onClosing code$code reason$reason")
        }

        override fun onOpen(webSocket: WebSocket, response: Response) {
            Timber.i("onOpen response${response.message}")
            iEngineActionListener?.onSuccess(getIEngine())
            engineState = IEngineState.ENGINE_OPEN
            heartbeat.startHeartbeat()
            imcStatusListener?.connectionSucceeded()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Timber.i(t, "onFailure response ${connected} ${response?.message}")
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
            Timber.i("onMessage text:${text}")
            imcListenerManager.onMessage(getIEngine(), text)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            Timber.i("onMessage bytes:${bytes.size}")
            imcListenerManager.onMessage(getIEngine(), bytes)
        }
    }
    var engineState = IEngineState.ENGINE_CLOSED // 当前连接状态
    override fun engineOn(key: String) {
        engineOn(Request.Builder().url(key).build(),null)
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
    fun engineOn(request: Request, engineActionListener: IEngineActionListener?) {
        Timber.i("engineOn 111")
        check(!connecting) {
            throw IllegalStateException("正在连接，请稍后重试")
        }
        check(engineState() != IEngineState.ENGINE_OPEN) {
            throw IllegalStateException("已经连接了，请勿重复连接")
        }
        check(!connected) {
            throw IllegalStateException("已经连接了，请勿重复连接")
        }
        Timber.i("engineOn 222")
        // 初始化心跳
        val name = "WebSocketEngine ${request.url.redact()}"
        heartbeat.initHeartbeat(getIEngine(),builder.customHeartbeat(), name)
        autoReconnect.initAutoReconnect(getIEngine(), name)
        connect(request, engineActionListener)
    }

    private fun connect(request: Request, engineActionListener: IEngineActionListener?) {
        Timber.i("engineOn 333")
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
            // 直接连接
            webSocket = okHttpClient.newWebSocket(
                request,
                webSocketListenerImpl
            )
        }
    }

    private fun getIEngine(): OkhttpIEngine {
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

    override fun send(byteArray: ByteArray):Boolean {
        return webSocket?.send(byteArray.toByteString())?:false
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
        if (!connected) {
            return
        }
        okHttpClient
            .dispatcher
            .executorService
            .submit {
                Timber.i("Heartbeat setForeground foreground ${mode}")
                // 非强制实现
                heartbeat.heartbeatInterval = if (mode == 0) {
                    builder.minHeartbeatInterval
                } else {
                    builder.maxHeartbeatInterval
                }.toLong()
                heartbeat.startHeartbeat()
            }
    }

    /**
     * 网络状态切换
     * On network change
     *
     * @param networkState
     */
    override fun onNetworkChange(networkState: Int) {
        Timber.i("autoReconnect onNetworkChange ${networkState}")
        // 非强制实现
        if (!connected) {
            return
        }
        okHttpClient
            .dispatcher
            .executorService
            .submit {
                autoReconnect.resetStartReconnectCycle()
            }
    }

    override fun makeConnection() {
        Timber.i("autoReconnect makeConnection")
        // 非强制实现
        if (!connected) {
            return
        }
        okHttpClient
            .dispatcher
            .executorService
            .submit {
                autoReconnect.resetStartReconnectCycle()
            }
    }

    internal fun failWebSocket(e: Exception, response: Response?){
        okHttpClient
            .dispatcher
            .executorService
            .submit {
                webSocketListenerImpl.onFailure(webSocket!!,e,response)
            }
    }

    /**
     * 自动连接
     * Auto reconnect
     */
    private val autoReconnect = AutoReconnect()

    private class AutoReconnect() {
        private lateinit var rapidResponseForce : RapidResponseForce<String>
        internal lateinit var okhttpIEngine: OkhttpIEngine
        private val initReconnectDelay = 1000L
        internal var reconnectDelay = initReconnectDelay // Reconnect delay, starts at 1
        private val syncAutoReconnect = Object()
        //内部记录 是否正在连接
        private var isConnecting = false
        // 组ID
        private val makeGroupId = "AutoReconnect:Group:${hashCode()}"
        //定时器
        private val makeTimekeepingId  = "AutoReconnect:Timekeeping:${hashCode()}"
        // 超时
        private val makeTimeoutId  = "AutoReconnect:Timeout:${hashCode()}"

        fun initAutoReconnect(engine: OkhttpIEngine, name: String) {
            okhttpIEngine = engine
            rapidResponseForce = RapidResponseForce<String>(
                groupId = makeGroupId,
            )
            rapidResponseForce.timeoutCallback {list->
                Timber.i("initAutoReconnect.timeoutCallback ${list?.size}")
                synchronized(syncAutoReconnect) {
                    list?.forEach {
                        when (it) {
                            makeTimeoutId -> {
                                // 连接超时
                                Timber.i("initAutoReconnect 连接超时")
                                failure(okhttpIEngine, TimeoutException("连接超时"))
                            }

                            makeTimekeepingId -> {
                                Timber.i("initAutoReconnect 积蓄完毕开始抢救")
                                reconnect()
                            }
                        }
                    }
                }
            }
        }

        fun startReconnectCycle() {
            Timber.i("startReconnectCycle 开始抢救 ${okhttpIEngine.engineState}")
            // 连接正常
            if (okhttpIEngine.engineState == IEngineState.ENGINE_OPEN){
                return
            }
            if (isConnecting){
                return
            }
            synchronized(syncAutoReconnect){
                isConnecting = true
                rapidResponseForce.unRegister(makeTimeoutId)
                rapidResponseForce.unRegister(makeTimekeepingId)
                // 直接连接
                rapidResponseForce.register(makeTimeoutId,makeTimeoutId)
                connect()
            }
        }
        fun resetStartReconnectCycle(){
            Timber.i("resetStartReconnectCycle 重置开始抢救 ${okhttpIEngine.engineState} ${isConnecting}")
            synchronized(syncAutoReconnect){
                Timber.i("resetStartReconnectCycle 重置开始抢救 111")
                reconnectDelay = initReconnectDelay // Reset Delay Timer
                reconnect()
            }
        }

        private fun reconnect(){
            if (okhttpIEngine.engineState == IEngineState.ENGINE_OPEN){
                stopReconnectCycle()
                return
            }
            synchronized(syncAutoReconnect){
                rapidResponseForce.unRegister(makeTimeoutId)
                rapidResponseForce.unRegister(makeTimekeepingId)
                // 直接连接
                rapidResponseForce.register(makeTimeoutId,makeTimeoutId)
                connect()
            }
        }

        fun stopReconnectCycle() {
            Timber.i("stopReconnectCycle 停止抢救")
            synchronized(syncAutoReconnect){
                rapidResponseForce.unRegister(makeTimekeepingId)
                rapidResponseForce.unRegister(makeTimeoutId)
                reconnectDelay = initReconnectDelay // Reset Delay Timer
                isConnecting = false
            }
        }

        private fun failure(iEngine: IEngine, t: Throwable){
            synchronized(syncAutoReconnect) {
                if (reconnectDelay < okhttpIEngine.maxReconnectDelay) {
                    reconnectDelay *= 2
                }
                Timber.i("failure 抢救失败 积蓄能量准备下次抢救， 在${reconnectDelay}ms之后")
                rapidResponseForce.unRegister(makeTimekeepingId)
                rapidResponseForce.unRegister(makeTimeoutId)
                rapidResponseForce.register(makeTimekeepingId, makeTimekeepingId, reconnectDelay)
            }
        }
        private fun connect() {
            if (okhttpIEngine.connecting){
                // 正在连接中
                return
            }
            if (okhttpIEngine.webSocket == null){
                Timber.i("connect 抢救失败")
                failure(okhttpIEngine, IllegalStateException("webSocket == null"))
                return
            }
            okhttpIEngine
                .connect(okhttpIEngine.webSocket?.request()!!,
                    object : IEngineActionListener {
                        override fun onSuccess(iEngine: IEngine) {
                            Timber.i("connect 抢救成功")
                            stopReconnectCycle()
                        }

                        override fun onFailure(iEngine: IEngine, t: Throwable) {
                            Timber.i("connect 抢救失败")
                            failure(iEngine, t)
                        }
                    })
        }
    }


    /**
     * Heartbeat 心跳实现
     *
     */
    private val heartbeat = Heartbeat()

    private class Heartbeat : V2IMCSocketListener {
        private lateinit var rapidResponseForce : RapidResponseForce<String>
        var heartbeatInterval = 5 * 1000L
        private lateinit var okhttpIEngine: OkhttpIEngine
        private lateinit var customHeartbeat : CustomHeartbeat
        // 组ID
        private val makeGroupId = "Heartbeat:Group:${hashCode()}"
        //定时器
        private val makeTimekeepingId  = "Heartbeat:Timekeeping:${hashCode()}"
        // 超时
        private val makeTimeoutId  = "Heartbeat:Timeout:${hashCode()}"
        fun initHeartbeat(engine: OkhttpIEngine, heartbeat : CustomHeartbeat, name: String) {
            Timber.i("Heartbeat 初始化心跳机制")
            customHeartbeat = heartbeat
            okhttpIEngine = engine
            heartbeatInterval = engine.builder.minHeartbeatInterval.toLong()
            okhttpIEngine.removeIMCSocketListener(this)
            okhttpIEngine.addIMCSocketListener(9, this)
            rapidResponseForce = RapidResponseForce<String>(
                groupId = makeGroupId,
            )
            rapidResponseForce.timeoutCallback {list->
                list?.forEach {
                    when (it) {
                        makeTimeoutId -> {
                            failure(okhttpIEngine, TimeoutException("连接超时"))
                        }

                        makeTimekeepingId -> {
                            startHeartbeat()
                        }
                    }
                }
            }
        }

        fun startHeartbeat() {
            Timber.i("Heartbeat 启动心跳")
            rapidResponseForce.unRegister(makeTimeoutId)
            rapidResponseForce.unRegister(makeTimekeepingId)
            // 准备接收，超时监听
            rapidResponseForce.register(makeTimeoutId,makeTimeoutId)
            sendHeartbeat()
        }

        fun stopHeartbeat() {
            Timber.i("Heartbeat 停止心跳")
            rapidResponseForce.unRegister(makeTimekeepingId)
            rapidResponseForce.unRegister(makeTimeoutId)
        }

        private fun rescheduleHeartbeat(){
            rapidResponseForce.unRegister(makeTimekeepingId)
            rapidResponseForce.unRegister(makeTimeoutId)
            // 准备接收，超时监听
            rapidResponseForce.register(makeTimekeepingId,makeTimekeepingId,heartbeatInterval)
            Timber.i("Heartbeat 延时等待下一次：${heartbeatInterval}")
        }

        private fun sendHeartbeat(){
            Timber.i("Heartbeat 发送心跳 ")
            val b = if (customHeartbeat.byteOrString()) {
                Timber.i("sendHeartbeat byte")
                okhttpIEngine.send(customHeartbeat.byteHeartbeat())
            } else {
                okhttpIEngine.send(customHeartbeat.stringHeartbeat().also {
                    Timber.i("sendHeartbeat ${it}")
                })
            }
            Timber.i("Heartbeat 发送心跳结果：$b ")
        }
        private fun failure(iEngine: IEngine, t: Throwable){
            Timber.i("Heartbeat 心跳无回执")
            okhttpIEngine.failWebSocket(
                SocketTimeoutException(
                    "sent ping but didn't receive pong within " +
                            "${heartbeatInterval}ms"
                ), null
            )
        }
        override fun onMessage(iEngine: IEngine, text: String): Boolean {
            return updateAuxiliaryHeartbeat(customHeartbeat.isHeartbeat(iEngine,text))
        }

        override fun onMessage(iEngine: IEngine, bytes: ByteArray): Boolean {
            return updateAuxiliaryHeartbeat(customHeartbeat.isHeartbeat(iEngine,bytes))
        }

        private fun updateAuxiliaryHeartbeat(isHeartbeat:Boolean):Boolean {
            Timber.i("Heartbeat 心跳回执 ${isHeartbeat}")
            //心跳
            if (isHeartbeat){
                rescheduleHeartbeat()
                return true
            }
            rescheduleHeartbeat()
            return false
        }
    }




    /***
     * 监听相关
     */
    private val imcListenerManager = IMCListenerManager()
    private var imcStatusListener: IMCStatusListener? = null
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

    class Builder {
        internal var okHttpClient: OkHttpClient? = null
        internal var maxHeartbeatInterval = 45 * 1000
        internal var minHeartbeatInterval = 5 * 1000
        internal var maxReconnectDelay = 128 * 1000
        internal var customHeartbeat : CustomHeartbeat? = null

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

        fun customHeartbeat(): CustomHeartbeat =
            customHeartbeat ?: DefCustomHeartbeat()

        fun customHeartbeat(customHeartbeat: CustomHeartbeat) = apply {
            this.customHeartbeat = customHeartbeat
        }

        fun heartbeatInterval(min: Long, max: Long, unit: TimeUnit) = apply {
            minHeartbeatInterval = checkDuration("minHeartbeatInterval", min, unit)
            maxHeartbeatInterval = checkDuration("maxHeartbeatInterval", max, unit)
        }

        fun maxReconnectDelay(delay: Long, unit: TimeUnit) = apply {
            maxReconnectDelay = checkDuration("maxReconnectDelay", delay, unit)
        }

        fun build(): OkhttpIEngine {
            return OkhttpIEngine(this)
        }
    }
}