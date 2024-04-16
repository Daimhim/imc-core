package org.daimhim.imc_core

import okio.ByteString.Companion.toByteString
import org.java_websocket.WebSocket
import org.java_websocket.client.WebSocketClient
import org.java_websocket.drafts.Draft_6455
import org.java_websocket.enums.ReadyState
import org.java_websocket.framing.CloseFrame
import org.java_websocket.framing.Framedata
import org.java_websocket.framing.PingFrame
import org.java_websocket.handshake.ServerHandshake
import timber.multiplatform.log.Timber
import java.net.URI
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

class JavaWebEngine : IEngine {
    companion object {
        val CONNECTION_UNEXPECTEDLY_CLOSED = -1 //： 连接意外关闭
        val CONNECTION_RESET = -2 //： 连接被重置
        val CONNECTION_TERMINATED = -3 //： 连接被终止
    }

    private var webSocketClient: WebSocketClientImpl? = null

    private val connectTimeout: Int = 5 * 1000

    // 心跳间隔 单位 秒
    private var heartbeatInterval = 5

    // 是否正在连接中
    private var isConnecting = false

    // 当前链接Key
    private var currentKey = ""

    // 重置中
    private var isResetting = false
    private val syncJWE = Any()

    // socket回调
    private val javaWebsocketListener = object : JavaWebSocketListener {
        override fun onOpen(handshakedata: ServerHandshake?) {
            Timber.i("onOpen ${handshakedata?.httpStatus} ${handshakedata?.httpStatusMessage}")
            imcStatusListener?.connectionSucceeded()
        }

        override fun onMessage(message: String) {
            Timber.i("onMessage message:${message}")
            imcListenerManager
                .onMessage(this@JavaWebEngine, message)
        }

        override fun onMessage(bytes: ByteBuffer) {
            Timber.i("onMessage bytes ${bytes.limit()}")
            imcListenerManager
                .onMessage(this@JavaWebEngine, bytes.toByteString())
        }

        override fun onClose(code: Int, reason: String?, remote: Boolean) {
            Timber.i("onClose code:${code} reason:${reason} remote:${remote}")
            imcStatusListener
                ?.connectionClosed(code, reason)
            // 重置中
            if (isResetting) {
                engineOn(currentKey)
            }
        }

        override fun onError(ex: Exception) {
            Timber.i(ex, "onError")
            imcStatusListener
                ?.connectionLost(ex)
            forceClose(CONNECTION_UNEXPECTEDLY_CLOSED, "${ex.message}")
            // 重置中
            if (isResetting) {
                engineOn(currentKey)
            }
        }

        override fun onPreparePing() {
//            Timber.i("onPreparePing")
        }

        override fun onWebsocketPong() {
//            Timber.i("onWebsocketPong")
        }

    }

    override fun engineOn(key: String) {
        val sockConnST = SockConnST()
        sockConnST.isNetworkAvailable(""){

        }
        // 更换URL
        synchronized(syncJWE){
            Timber.i("engineOn ${webSocketClient == null}")
            if (webSocketClient == null) {
                val finalUrl: String = when {
                    key.startsWith("http:", ignoreCase = true) -> {
                        "ws:${key.substring(5)}"
                    }
                    key.startsWith("https:", ignoreCase = true) -> {
                        "wss:${key.substring(6)}"
                    }
                    else -> key
                }
                webSocketClient = WebSocketClientImpl(
                    URI(finalUrl),
                    timeout = connectTimeout
                )
            }
            // 已连接，是否需要重置URL
            if (isConnect()) {
                resetKey(key)
                return
            }
            // 正在连接中，是否需要重置URL
            if (isConnecting) {
                resetKey(key)
                return
            }
            // 初始化状态
            webSocketClient?.javaWebSocketListener = javaWebsocketListener
            webSocketClient?.connectionLostTimeout = heartbeatInterval
            webSocketClient?.connect()
            isConnecting = true
            isResetting = false
        }
    }

    private fun resetKey(key: String) {
        if (key == currentKey) {
            return
        }
        currentKey = key
        isResetting = true
        webSocketClient?.close()
    }

    override fun engineOff() {
        cleanPreviousConnection()
        webSocketClient?.closeBlocking()
        webSocketClient?.javaWebSocketListener = null
        webSocketClient = null
        synchronized(syncJWE){
            isConnecting = false
            isResetting = false
        }
    }

    private fun cleanPreviousConnection() {
        webSocketClient?.stopConnectionLostTimer()
        webSocketClient?.stopAutoConnect()
    }

    private fun forceClose(code: Int, reason: String?) {
//        webSocketClient?.close(code,reason)
        webSocketClient?.javaWebSocketListener?.onClose(code, reason, false)
    }

    fun isConnect(): Boolean {
        return engineState() == IEngineState.ENGINE_OPEN
    }

    override fun engineState(): Int {
        val readyState = webSocketClient?.readyState ?: ReadyState.NOT_YET_CONNECTED
        return if (readyState == ReadyState.OPEN){
            IEngineState.ENGINE_OPEN
        }else{
            IEngineState.ENGINE_CLOSED
        }
    }

    override fun send(byteArray: ByteArray): Boolean {
        webSocketClient?.send(byteArray)
        return engineState() == IEngineState.ENGINE_OPEN
    }

    override fun send(text: String): Boolean {
        webSocketClient?.send(text)
        return engineState() == IEngineState.ENGINE_OPEN
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
        Timber.i("onChangeMode $mode")
        if (heartbeatInterval == mode) {
            return
        }
        heartbeatInterval = mode
        webSocketClient?.connectionLostTimeout = heartbeatInterval
    }

    override fun onNetworkChange(networkState: Int) {
        webSocketClient?.resetStartAutoConnect()
    }

    override fun makeConnection() {
    }

    /***
     * 监听相关
     */
    internal val imcListenerManager = IMCListenerManager()
    var imcStatusListener: IMCStatusListener? = null

    class WebSocketClientImpl(
        serverUri: URI,
        httpHeaders: Map<String, String> = mutableMapOf(),
        timeout: Int = 0,
    ) : WebSocketClient(serverUri, Draft_6455(), httpHeaders, timeout) {
        var javaWebSocketListener: JavaWebSocketListener? = null
        val rapidResponseForce = RapidResponseForceV2()
        private var isClosed = false
        // 最后一次心跳响应
        private var lastPong = System.nanoTime()

        // 心跳间隔
        val HEARTBEAT_INTERVAL = "HEARTBEAT_INTERVAL"
        //定时器
        private val AUTO_RECONNECT  = "AUTO_RECONNECT"

        // 心跳超时
        private val timedTasks = object : Comparable<Pair<String, Any?>> {
            override fun compareTo(other: Pair<String, Any?>): Int {
                Timber.i("timeoutCallback ${other.first}")
                if (other.first == HEARTBEAT_INTERVAL) {
                    try {
                        var minimumPongTime: Long
                        synchronized(syncConnectionLost) {
                            minimumPongTime = (System.nanoTime() - (connectionLostTimeout * 1.5)).toLong()
                        }

                        val executeConnectionLostDetection =
                            executeConnectionLostDetection(connection, minimumPongTime)
                        if (!executeConnectionLostDetection) {
                            // 心跳结束
                            cancelConnectionLostTimer()
                            return 0
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                    synchronized(syncConnectionLost) {
                        restartConnectionLostTimer()
                    }
                } else if (other.first == AUTO_RECONNECT){
                    synchronized(syncConnectionLost) {
                        isWaitingNextAutomaticConnection = false
                    }
                    startConnect(other.second)
                }
                return 0;
            }

        }
        init {
            rapidResponseForce.timeoutCallback(this.timedTasks)
        }

        override fun onOpen(handshakedata: ServerHandshake?) {
            updateLastPong()
            stopAutoConnect()
            javaWebSocketListener?.onOpen(handshakedata)
        }

        override fun onMessage(message: String) {
            updateLastPong()
            restartConnectionLostTimer()
            javaWebSocketListener?.onMessage(message)
        }

        override fun onMessage(bytes: ByteBuffer) {
            updateLastPong()
            restartConnectionLostTimer()
            javaWebSocketListener?.onMessage(bytes)
        }

        override fun onClose(code: Int, reason: String?, remote: Boolean) {
            javaWebSocketListener?.onClose(code, reason, remote)
            if (isClosed){
                return
            }
            // 停止心跳
            stopConnectionLostTimer()
            // 是否启动了 自动抢救
            if (!rescueEnable){
                return
            }
            // 连接结果初始化
            synchronized(syncConnectionLost){
                isConnecting_Automatically = false
            }
            // 初次还是多次
            if (isAutomaticallyConnecting){
                // 连接已经过了
                waitingNextAutomaticConnection(reconnectDelay)
                return
            }
            // 初次
            startAutoConnect()
        }

        override fun onError(ex: Exception) {
            javaWebSocketListener?.onError(ex)
            if (isClosed){
                return
            }
            // 停止心跳
            stopConnectionLostTimer()
            // 是否启动了 自动抢救
            if (!rescueEnable){
                return
            }
            // 连接结果初始化
            synchronized(syncConnectionLost){
                isConnecting_Automatically = false
            }
            // 初次还是多次
            if (isAutomaticallyConnecting){
                // 连接已经过了
                waitingNextAutomaticConnection(reconnectDelay)
                return
            }
            // 初次
            startAutoConnect()
        }

        override fun onPreparePing(conn: WebSocket?): PingFrame {
            javaWebSocketListener?.onPreparePing()
            return super.onPreparePing(conn)
        }

        override fun onWebsocketPong(conn: WebSocket?, f: Framedata?) {
//            Timber.i("onWebsocketPong updateLastPong")
            updateLastPong()
            javaWebSocketListener?.onWebsocketPong()
            super.onWebsocketPong(conn, f)
        }

        private var websocketRunning = false
        private val syncConnectionLost = Any()

        private var connectionLostTimeout = TimeUnit.SECONDS.toNanos(60)

        override fun getConnectionLostTimeout(): Int {
            synchronized(syncConnectionLost) {
                return TimeUnit.NANOSECONDS.toSeconds(connectionLostTimeout).toInt()
            }
        }

        override fun setConnectionLostTimeout(connectionLostTimeout: Int) {
//            Timber.i("setConnectionLostTimeout ${connectionLostTimeout}")
            synchronized(syncConnectionLost) {
                this.connectionLostTimeout = TimeUnit.SECONDS.toNanos(connectionLostTimeout.toLong())
                if (this.connectionLostTimeout <= 0) {
                    cancelConnectionLostTimer()
                    return
                }
                if (websocketRunning) {
                    updateLastPong()
                    restartConnectionLostTimer()
                }
            }
        }

        override fun startConnectionLostTimer() {
            Timber.i("startConnectionLostTimer")
            synchronized(syncConnectionLost) {
                if (connectionLostTimeout < 0) {
                    return
                }
                websocketRunning = true
                restartConnectionLostTimer()
            }
        }

        public override fun stopConnectionLostTimer() {
//            Timber.i("stopConnectionLostTimer")
            synchronized(syncConnectionLost) {
                websocketRunning = false
                cancelConnectionLostTimer()
            }
        }

        private fun cancelConnectionLostTimer() {
//            Timber.i("cancelConnectionLostTimer")
            rapidResponseForce.unRegister(HEARTBEAT_INTERVAL)
        }

        private fun restartConnectionLostTimer() {
            val timeOut = TimeUnit.NANOSECONDS.toMillis(connectionLostTimeout)
//            Timber.i("restartConnectionLostTimer ${timeOut}")
            cancelConnectionLostTimer()
            rapidResponseForce.register(HEARTBEAT_INTERVAL, timeOut = timeOut)
        }

        private fun executeConnectionLostDetection(webSocket: WebSocket, minimumPongTime: Long): Boolean {
            Timber.i("executeConnectionLostDetection sendPing111")
//            Timber.i("executeConnectionLostDetection")
            if (getLastPong() < minimumPongTime) {
                webSocket.closeConnection(
                    CloseFrame.ABNORMAL_CLOSE,
                    "The connection was closed because the other endpoint did not respond with a pong in time. For more information check: https://github.com/TooTallNate/Java-WebSocket/wiki/Lost-connection-detection"
                )
                return false
            }
            if (!webSocket.isOpen) {
                return false
            }
            if (!websocketRunning) {
                return false
            }
            webSocket.sendPing()
            Timber.i("executeConnectionLostDetection sendPing222")
            return true
        }

        fun getLastPong(): Long {
            return lastPong;
        }

        /**
         * Update the timestamp when the last pong was received
         */
        fun updateLastPong() {
            this.lastPong = System.nanoTime();
        }

        override fun close() {
            super.close()
            synchronized(syncConnectionLost){
                isClosed = true
            }
        }

        override fun connect() {
            super.connect()
            synchronized(syncConnectionLost){
                isClosed = false
            }
        }
        /***
         * 抢救启用
         */
        var rescueEnable = true
        /**
         * 正在抢救中
         */
        private var isAutomaticallyConnecting = false

        /**
         * 连接中状态 连接结果，open/close
         */
        private var isConnecting_Automatically = false
        /**
         * 进入等待下一次连接
         */
        private var isWaitingNextAutomaticConnection = false
        // 初始值
        private val initReconnectDelay = 1000L
        internal var reconnectDelay = initReconnectDelay  // Reconnect delay, starts at 1
        var maxReconnectDelay = 128_1000L

        fun startAutoConnect(){
            Timber.i("开始抢救 isConnecting_Automatically:${isConnecting_Automatically} ${isAutomaticallyConnecting} isOpen:${isOpen}")
            synchronized(syncConnectionLost){
                if (isOpen){
                    // 不需要抢救
                    return
                }
                if (isAutomaticallyConnecting){
                    //多次启动 直接返回
                    return
                }
                if (isConnecting_Automatically){
                    // 正在连接 直接返回
                    return
                }
                // 初次 记录一下初次
                isAutomaticallyConnecting = true
                waitingNextAutomaticConnection(reconnectDelay)
            }
        }

        private fun waitingNextAutomaticConnection(delay:Long){
            Timber.i("等待下一次 ${delay} isWaitingNextAutomaticConnection:${isWaitingNextAutomaticConnection}")
            synchronized(syncConnectionLost){
                if (isWaitingNextAutomaticConnection){
                    return
                }
                if (reconnectDelay < maxReconnectDelay){
                    reconnectDelay  = delay * 2
                }
                isWaitingNextAutomaticConnection = true
                rapidResponseForce.register(AUTO_RECONNECT,null,reconnectDelay)
            }
        }
        fun resetStartAutoConnect(){
            Timber.i("重置抢救，之前记录${reconnectDelay}")
            stopAutoConnect()
            startAutoConnect()
        }
        fun stopAutoConnect(){
            Timber.i("停止抢救")
            synchronized(syncConnectionLost){
                isAutomaticallyConnecting = false
                isConnecting_Automatically = false
                isWaitingNextAutomaticConnection = false
                reconnectDelay = initReconnectDelay
                rapidResponseForce.unRegister(AUTO_RECONNECT)
            }
        }

        fun startConnect(any: Any?){
            Timber.i("startConnect 111 ${isConnecting_Automatically}")
            synchronized(syncConnectionLost){
                if (isConnecting_Automatically){
                    return
                }
                isConnecting_Automatically = true
            }
//            reconnectBlocking()
            reconnect()
            Timber.i("startConnect 222")
        }
    }

    interface JavaWebSocketListener {
        fun onOpen(handshakedata: ServerHandshake?)
        fun onMessage(message: String)
        fun onMessage(bytes: ByteBuffer)
        fun onClose(code: Int, reason: String?, remote: Boolean)
        fun onError(ex: Exception)
        fun onPreparePing()
        fun onWebsocketPong()
    }
}