package org.daimhim.imc_core

import okhttp3.internal.checkDuration
import okio.ByteString.Companion.toByteString
import org.java_websocket.WebSocket
import org.java_websocket.client.WebSocketClient
import org.java_websocket.drafts.Draft
import org.java_websocket.drafts.Draft_6455
import org.java_websocket.enums.Opcode
import org.java_websocket.enums.ReadyState
import org.java_websocket.enums.Role
import org.java_websocket.framing.CloseFrame
import org.java_websocket.framing.Framedata
import org.java_websocket.framing.PingFrame
import org.java_websocket.handshake.ServerHandshake
import java.net.URI
import java.nio.ByteBuffer
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.collections.ArrayList

class JavaWebEngine(private val builder: Builder) : IEngine {
    companion object {
        val CONNECTION_UNEXPECTEDLY_CLOSED = -1 //： 连接意外关闭
        val CONNECTION_RESET = -2 //： 连接被重置
        val CONNECTION_TERMINATED = -3 //： 连接被终止
    }

    private var webSocketClient: WebSocketClientImpl? = null

    private val connectTimeout: Int = 5 * 1000

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
            IMCLog.i("onOpen ${handshakedata?.httpStatus} ${handshakedata?.httpStatusMessage}")
            imcStatusListener?.connectionSucceeded()
        }

        override fun onMessage(message: String) {
            IMCLog.i("onMessage message:${message}")
            imcListenerManager
                .onMessage(this@JavaWebEngine, message)
        }

        override fun onMessage(bytes: ByteBuffer) {
            IMCLog.i("onMessage bytes ${bytes.limit()}")
            imcListenerManager
                .onMessage(this@JavaWebEngine, bytes.toByteString())
        }

        override fun onClose(code: Int, reason: String?, remote: Boolean) {
            IMCLog.i("onClose code:${code} reason:${reason} remote:${remote}")
            imcStatusListener
                ?.connectionClosed(code, reason)
            // 重置中
            if (isResetting) {
                engineOn(currentKey)
            }
        }

        override fun onError(ex: Exception) {
            IMCLog.i(ex, "onError")
            imcStatusListener?.connectionLost(ex)
//            forceClose(CONNECTION_UNEXPECTEDLY_CLOSED, "${ex.message}")
            // 重置中
            if (isResetting) {
                engineOn(currentKey)
            }
        }


    }

    override fun engineOn(key: String) {
        // 更换URL
        synchronized(syncJWE) {
            IMCLog.i("engineOn ${webSocketClient == null}")
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
                    timeout = connectTimeout,
                    rescueEnable = builder.rescueEnable(),
                    heartbeatEnable = builder.heartbeatEnable(),
                    nst = builder.nst(),
                    customHeartbeat = builder.customHeartbeat(),
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
            webSocketClient?.connectionLostTimeout = builder.minHeartbeatInterval
            webSocketClient?.maxReconnectDelay = builder.maxReconnectDelay.toLong()
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
        webSocketClient?.close()
        webSocketClient?.javaWebSocketListener = null
        webSocketClient = null
        synchronized(syncJWE) {
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

    internal fun isConnect(): Boolean {
        return engineState() == IEngineState.ENGINE_OPEN
    }

    override fun engineState(): Int {
        val readyState = webSocketClient?.readyState ?: ReadyState.NOT_YET_CONNECTED
        return if (readyState == ReadyState.OPEN) {
            IEngineState.ENGINE_OPEN
        } else {
            IEngineState.ENGINE_CLOSED
        }
    }

    override fun send(byteArray: ByteArray): Boolean {
        if (!isConnect()) {
            makeConnection()
        }
        webSocketClient?.send(byteArray)
        return engineState() == IEngineState.ENGINE_OPEN
    }

    override fun send(text: String): Boolean {
        if (!isConnect()) {
            makeConnection()
        }
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
        IMCLog.i("onChangeMode $mode")
        // 心跳间隔 单位 秒
        if (mode == 0) {
            webSocketClient?.connectionLostTimeout = builder.minHeartbeatInterval
        } else {
            webSocketClient?.connectionLostTimeout = builder.maxHeartbeatInterval
        }
    }

    override fun onNetworkChange(networkState: Int) {
        webSocketClient?.resetStartAutoConnect()
    }

    override fun makeConnection() {
        webSocketClient?.resetStartAutoConnect()
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
        private val rescueEnable: Boolean,
        private val heartbeatEnable: Boolean,
        private val nst: NST? = null,
        private val customHeartbeat: CustomHeartbeat? = null,
    ) : WebSocketClient(serverUri, Draft_6455(), httpHeaders, timeout) {

        var javaWebSocketListener: JavaWebSocketListener? = null
        val rapidResponseForce = RapidResponseForceV2()
        private var isClosed = false


        // 心跳超时
        private val timedTasks = object : Comparable<Pair<String, Any?>> {
            override fun compareTo(other: Pair<String, Any?>): Int {
                IMCLog.i("timeoutCallback ${other.first}")
                if (other.first == HEARTBEAT_INTERVAL) {
                    // 心跳
                    heartbeatInterval(other)
                } else if (other.first == AUTO_RECONNECT) {
                    // 自动连接
                    autoReconnect(other)
                }
                return 0;
            }

        }

        init {
            rapidResponseForce.timeoutCallback(this.timedTasks)
        }

        override fun onOpen(handshakedata: ServerHandshake?) {
            retryingSendingCache()
            updateLastPong()
            stopAutoConnect()
            javaWebSocketListener?.onOpen(handshakedata)
        }

        override fun onMessage(message: String) {
            retryingSendingCache()
            updateLastPong()
            restartConnectionLostTimer()
            javaWebSocketListener?.onMessage(message)
        }

        override fun onMessage(bytes: ByteBuffer) {
            retryingSendingCache()
            updateLastPong()
            restartConnectionLostTimer()
            javaWebSocketListener?.onMessage(bytes)
        }

        override fun onClose(code: Int, reason: String?, remote: Boolean) {
            if (isClosed) {
                javaWebSocketListener?.onClose(code, reason, remote)
                return
            }
            javaWebSocketListener?.onError(IllegalStateException("code:${code} reason${reason} remote${remote}"))
            // 停止心跳
            stopConnectionLostTimer()
            // z自动重连
            abnormalDisconnectionAndAutomaticReconnection()
        }

        override fun onError(ex: Exception) {
            javaWebSocketListener?.onError(ex)
            if (isClosed) {
                return
            }
            // 停止心跳
            stopConnectionLostTimer()
            // z自动重连
            abnormalDisconnectionAndAutomaticReconnection()
        }

        override fun close() {
            super.close()
            synchronized(syncConnectionLost) {
                isClosed = true
                clearCache()
            }
        }

        override fun connect() {
            super.connect()
            synchronized(syncConnectionLost) {
                isClosed = false
            }
        }

        /*
         * ------------------------心跳---------------------------------------
         */

        // 最后一次心跳响应
        private var lastPong = System.nanoTime()

        // 心跳间隔
        val HEARTBEAT_INTERVAL = "心跳间隔_${hashCode()}"

        override fun onWebsocketPong(conn: WebSocket?, f: Framedata?) {
            retryingSendingCache()
//            IMCLog.i("onWebsocketPong updateLastPong")
            updateLastPong()
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
//            IMCLog.i("setConnectionLostTimeout ${connectionLostTimeout}")
            synchronized(syncConnectionLost) {
                this.connectionLostTimeout = TimeUnit.MILLISECONDS.toNanos(connectionLostTimeout.toLong())
                // 是否启动了自动心跳
                if (!heartbeatEnable){
                    return
                }
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
            IMCLog.i("startConnectionLostTimer")
            synchronized(syncConnectionLost) {
                // 是否启动了自动心跳
                if (!heartbeatEnable){
                    return
                }
                if (connectionLostTimeout < 0) {
                    return
                }
                websocketRunning = true
                restartConnectionLostTimer()
            }
        }

        public override fun stopConnectionLostTimer() {
//            IMCLog.i("stopConnectionLostTimer")
            synchronized(syncConnectionLost) {
                // 是否启动了自动心跳
                if (!heartbeatEnable){
                    return
                }
                websocketRunning = false
                cancelConnectionLostTimer()
            }
        }

        private fun cancelConnectionLostTimer() {
//            IMCLog.i("cancelConnectionLostTimer")
            rapidResponseForce.unRegister(HEARTBEAT_INTERVAL)
        }

        private fun restartConnectionLostTimer() {
            // 是否启动了自动心跳
            if (!heartbeatEnable){
                return
            }
            val timeOut = TimeUnit.NANOSECONDS.toMillis(connectionLostTimeout)
            IMCLog.i("restartConnectionLostTimer ${timeOut}")
            cancelConnectionLostTimer()
            rapidResponseForce.register(HEARTBEAT_INTERVAL, timeOut = timeOut)
        }

        private fun executeConnectionLostDetection(webSocket: WebSocket, minimumPongTime: Long): Boolean {
            IMCLog.i("executeConnectionLostDetection sendPing111 ${customHeartbeat == null}")
//            IMCLog.i("executeConnectionLostDetection")
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
            when {
                customHeartbeat == null -> {
                    sendPing()
                }

                customHeartbeat.byteOrString() -> {
                    send(customHeartbeat.byteHeartbeat())
                }

                else -> {
                    send(customHeartbeat.stringHeartbeat())
                }
            }
            IMCLog.i("executeConnectionLostDetection sendPing222")
            return true
        }

        fun getLastPong(): Long {
            return lastPong;
        }

        fun updateLastPong() {
            this.lastPong = System.nanoTime();
        }

        fun heartbeatInterval(other: Pair<String, Any?>){
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
                    return
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            synchronized(syncConnectionLost) {
                restartConnectionLostTimer()
            }
        }
        /*
         * ------------------------心跳---------------------------------------
         */


        /**
        ------------------------自动连接---------------------------------------
         */

        //定时器
        private val AUTO_RECONNECT = "自动连接_${hashCode()}"

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

        /**
         * 异常断开 并启动自动重连
         */
        private fun abnormalDisconnectionAndAutomaticReconnection() {
            // 是否启动了 自动抢救
            if (!rescueEnable) {
                return
            }
            // 连接结果初始化
            synchronized(syncConnectionLost) {
                isConnecting_Automatically = false
            }
            // 初次还是多次
            if (isAutomaticallyConnecting) {
                // 连接已经过了
                waitingNextAutomaticConnection(reconnectDelay)
                return
            }
            // 初次
            startAutoConnect()
        }

        /**
         * 对外 启动自动连接
         */
        fun startAutoConnect() {
            IMCLog.i("开始抢救 isConnecting_Automatically:${isConnecting_Automatically} ${isAutomaticallyConnecting} isOpen:${isOpen}")
            synchronized(syncConnectionLost) {
                // 是否启动了 自动抢救
                if (!rescueEnable) {
                    return
                }
                if (isOpen) {
                    // 不需要抢救
                    return
                }
                if (isAutomaticallyConnecting) {
                    //多次启动 直接返回
                    return
                }
                if (isConnecting_Automatically) {
                    // 正在连接 直接返回
                    return
                }
                // 初次 记录一下初次
                isAutomaticallyConnecting = true
                waitingNextAutomaticConnection(reconnectDelay)
            }
        }

        /**
         * 等待下一次 自动连接
         */
        private fun waitingNextAutomaticConnection(delay: Long) {
            IMCLog.i("等待下一次 ${delay} isWaitingNextAutomaticConnection:${isWaitingNextAutomaticConnection} reconnectDelay:$reconnectDelay")
            synchronized(syncConnectionLost) {
                if (isWaitingNextAutomaticConnection) {
                    return
                }
                if (reconnectDelay < maxReconnectDelay) {
                    reconnectDelay = delay * 2
                }
                isWaitingNextAutomaticConnection = true
                rapidResponseForce.register(AUTO_RECONNECT, null, reconnectDelay)
                IMCLog.i("等待下一次 111 ${reconnectDelay}")
            }
        }

        /**
         * 重置自动连接，用于更新配置
         */
        internal fun resetStartAutoConnect() {
            IMCLog.i("重置抢救，之前记录${reconnectDelay}")
            stopAutoConnect()
            startAutoConnect()
        }

        fun stopAutoConnect() {
            IMCLog.i("停止抢救")
            synchronized(syncConnectionLost) {
                // 是否启动了 自动抢救
                if (!rescueEnable) {
                    return
                }
                isAutomaticallyConnecting = false
                isConnecting_Automatically = false
                isWaitingNextAutomaticConnection = false
                reconnectDelay = initReconnectDelay
                rapidResponseForce.unRegister(AUTO_RECONNECT)
            }
        }

        private fun startConnect(any: Any?) {
            IMCLog.i("startConnect 111 ${isConnecting_Automatically}")
            synchronized(syncConnectionLost) {
                if (isConnecting_Automatically) {
                    return
                }
                isConnecting_Automatically = true
            }
//            reconnectBlocking()
            reconnect()
            IMCLog.i("startConnect 222")
        }

        private fun autoReconnect(other: Pair<String, Any?>){
            synchronized(syncConnectionLost) {
                isWaitingNextAutomaticConnection = false
            }
            startConnect(other.second)
        }
        /**
         * ------------------------自动连接--------------------------------------- */


        /**
          ------------------------消息发送缓存--------------------------------------- */
        private val cacheSync = Any()
        private val role = Role.CLIENT
        private val cacheList = mutableListOf<Pair<String,List<Framedata>>>()
        private val CACHE_SIZE = 8 * 1024
        private var currentOccupiedSize = 0

        override fun send(text: String?) {
            if (text.isNullOrEmpty()){
                return
            }
            synchronized(cacheSync){
                if (isOpen){
                    super.send(text)
                    return
                }
                addCache(element = draft.createFrames(text, role == Role.CLIENT))
            }
        }

        override fun send(data: ByteArray?) {
            if (isOpen){
                super.send(data)
                return
            }
            synchronized(cacheSync){
                addCache(element = draft.createFrames(ByteBuffer.wrap(data), role == Role.CLIENT))
            }
        }

        override fun send(bytes: ByteBuffer?) {
            if (isOpen){
                super.send(bytes)
                return
            }
            synchronized(cacheSync){
                addCache(element = draft.createFrames(bytes, role == Role.CLIENT))
            }
        }

        @Synchronized
        private fun addCache(id : String = RapidResponseForceV2.makeOnlyId(),element : List<Framedata>){
            var lCurrentOccupiedSize = currentOccupiedSize
            element.forEach {
                lCurrentOccupiedSize += it.payloadData.capacity()
            }
            if (lCurrentOccupiedSize > CACHE_SIZE){
                cacheList
                    .removeFirstOrNull()
                    ?.second
                    ?.forEach {
                        lCurrentOccupiedSize -= it.payloadData.capacity()
                    }
            }
            currentOccupiedSize = lCurrentOccupiedSize
            IMCLog.i("addCache currentOccupiedSize:${currentOccupiedSize}")
            rapidResponseForce.register(id,null)
            cacheList.add(id to element)
        }

        private fun retryingSendingCache(){
            synchronized(cacheSync){
                if (cacheList.isEmpty()){
                    return@synchronized
                }
                if (!isOpen){
                    return@synchronized
                }
                var item = cacheList.removeFirstOrNull()
                while (item != null){
                    sendFrame(item.second)
                    item
                        .second
                        .forEach {
                            currentOccupiedSize -= it.payloadData.capacity()
                        }
                    IMCLog.i("retryingSendingCache1 currentOccupiedSize:${currentOccupiedSize}")
                    if (!isOpen){
                        cacheList.add(0,item)
                        return@synchronized
                    }
                    item = cacheList.removeFirstOrNull()
                }
                currentOccupiedSize = 0
                IMCLog.i("retryingSendingCache2 currentOccupiedSize:${currentOccupiedSize}")
            }
        }

        private fun clearCache(){
            synchronized(cacheSync){
                cacheList.clear()
                currentOccupiedSize = 0
            }
        }
        /**
          ------------------------消息发送缓存--------------------------------------- */
    }

    interface JavaWebSocketListener {
        fun onOpen(handshakedata: ServerHandshake?)
        fun onMessage(message: String)
        fun onMessage(bytes: ByteBuffer)
        fun onClose(code: Int, reason: String?, remote: Boolean)
        fun onError(ex: Exception)
    }

    class Builder {
        internal var javaWebEngine: JavaWebEngine? = null
        internal var maxHeartbeatInterval = 45 * 1000
        internal var minHeartbeatInterval = 5 * 1000
        internal var maxReconnectDelay = 128 * 1000
        internal var debug = false
        internal var rescueEnable = true
        internal var heartbeatEnable = true
        internal var nst: NST? = null
        internal var imcLogFactory: IIMCLogFactory? = null
        internal var customHeartbeat: CustomHeartbeat? = null

        /**
         *
         * @return 为IMC提供的JavaWebEngine客户端
         */
        fun javaWebEngine(): JavaWebEngine =
            javaWebEngine ?: JavaWebEngine
                .Builder()
                .build()

        fun javaWebEngine(javaWebEngine: JavaWebEngine) = apply {
            this.javaWebEngine = javaWebEngine
        }

        fun setIMCLog(imcLogFactory: IIMCLogFactory) = apply {
            this.imcLogFactory = imcLogFactory
        }

        fun heartbeatInterval(min: Long, max: Long, unit: TimeUnit) = apply {
            minHeartbeatInterval = checkDuration("minHeartbeatInterval", min, unit)
            maxHeartbeatInterval = checkDuration("maxHeartbeatInterval", max, unit)
        }

        fun maxReconnectDelay(delay: Long, unit: TimeUnit) = apply {
            maxReconnectDelay = checkDuration("maxReconnectDelay", delay, unit)
        }

        fun debug(debug: Boolean) = apply {
            this.debug = debug
        }

        fun debug(): Boolean = debug

        fun nst(nst: NST) = apply { this.nst = nst }
        fun nst() = nst

        fun rescueEnable(rescueEnable: Boolean) = apply { this.rescueEnable = rescueEnable }
        fun rescueEnable() = rescueEnable

        fun heartbeatEnable(heartbeatEnable: Boolean) = apply { this.heartbeatEnable = heartbeatEnable }
        fun heartbeatEnable() = heartbeatEnable

        fun customHeartbeat(): CustomHeartbeat? = customHeartbeat

        fun customHeartbeat(customHeartbeat: CustomHeartbeat) = apply {
            this.customHeartbeat = customHeartbeat
        }

        fun build(): JavaWebEngine {
            IMCLog.setIIMCLogFactory(imcLogFactory)
            return JavaWebEngine(this)
        }
    }
}