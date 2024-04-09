package org.daimhim.imc_core

import okio.ByteString.Companion.toByteString
import org.java_websocket.WebSocket
import org.java_websocket.client.WebSocketClient
import org.java_websocket.drafts.Draft_6455
import org.java_websocket.framing.CloseFrame
import org.java_websocket.framing.Framedata
import org.java_websocket.framing.PingFrame
import org.java_websocket.handshake.ServerHandshake
import timber.multiplatform.log.Timber
import java.net.URI
import java.nio.ByteBuffer
import java.util.concurrent.Callable
import java.util.concurrent.TimeUnit

class JavaWebEngine : IEngine {
    companion object{
        val CONNECTION_UNEXPECTEDLY_CLOSED = -1 //： 连接意外关闭
        val CONNECTION_RESET  = -2 //： 连接被重置
        val CONNECTION_TERMINATED = -3 //： 连接被终止
    }
    private var webSocketClient : WebSocketClientImpl? = null

    private val connectTimeout:Int = 5 * 1000
    // 心跳间隔 单位 秒
    private var heartbeatInterval = 5
    // 是否正在连接中
    private var isConnecting = false
    // 当前链接Key
    private var currentKey = ""
    // 重置中
    private var isResetting = false
    // socket回调
    private val javaWebsocketListener = object : JavaWebSocketListener{
        override fun onOpen(handshakedata: ServerHandshake?) {
            Timber.i("onOpen ${handshakedata?.httpStatus} ${handshakedata?.httpStatusMessage}")
            imcStatusListener?.connectionSucceeded()
        }

        override fun onMessage(message: String) {
            Timber.i("onMessage message:${message}")
            imcListenerManager
                .onMessage(this@JavaWebEngine,message)
        }

        override fun onMessage(bytes: ByteBuffer) {
            Timber.i("onMessage bytes ${bytes.limit()}")
            imcListenerManager
                .onMessage(this@JavaWebEngine,bytes.toByteString())
        }

        override fun onClose(code: Int, reason: String?, remote: Boolean) {
            Timber.i("onClose code:${code} reason:${reason} remote:${remote}")
            imcStatusListener
                ?.connectionClosed(code, reason)
            cleanPreviousConnection()
            // 重置中
            if (isResetting){
                engineOn(currentKey)
            }
        }

        override fun onError(ex: Exception) {
            Timber.i(ex,"onError")
            imcStatusListener
                ?.connectionLost(ex)
            forceClose(CONNECTION_UNEXPECTEDLY_CLOSED,"${ex.message}")
            cleanPreviousConnection()
            // 重置中
            if (isResetting){
                engineOn(currentKey)
            }
        }

        override fun onPreparePing() {
            Timber.i("onPreparePing")
        }

        override fun onWebsocketPong() {
            Timber.i("onWebsocketPong")
        }

    }

    override fun engineOn(key: String) {
        // 更换URL
        Timber.i("engineOn ${webSocketClient == null}")
        if (webSocketClient == null){
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
        if (isConnect()){
            resetKey(key)
            return
        }
        // 正在连接中，是否需要重置URL
        if (isConnecting){
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

    private fun resetKey(key:String){
        if (key == currentKey){
            return
        }
        currentKey = key
        isResetting = true
        webSocketClient?.close()
    }

    override fun engineOff() {
        webSocketClient?.close()
    }

    private fun cleanPreviousConnection(){
        webSocketClient?.javaWebSocketListener = null
        webSocketClient = null
        isConnecting = false
    }

    private fun forceClose(code: Int,reason: String?){
//        webSocketClient?.close(code,reason)
        webSocketClient?.javaWebSocketListener?.onClose(code, reason, false)
    }

    fun isConnect():Boolean{
        return engineState() == IEngineState.ENGINE_OPEN
    }
    override fun engineState(): Int {
        if (webSocketClient == null){
            return IEngineState.ENGINE_CLOSED
        }
        if (webSocketClient?.isClosed == true){
            return IEngineState.ENGINE_CLOSED
        }
        if (webSocketClient?.isClosing == true){
            return IEngineState.ENGINE_CLOSED
        }
        if (webSocketClient?.isOpen == true){
            return IEngineState.ENGINE_OPEN
        }
        return IEngineState.ENGINE_CLOSED_FAILED
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
        if (heartbeatInterval == mode){
            return
        }
        heartbeatInterval = mode
        webSocketClient?.connectionLostTimeout = heartbeatInterval
    }

    override fun onNetworkChange(networkState: Int) {
    }

    override fun makeConnection() {
    }

    /***
     * 监听相关
     */
    internal val imcListenerManager = IMCListenerManager()
    var imcStatusListener: IMCStatusListener? = null

    class WebSocketClientImpl(
        serverUri:URI,
        httpHeaders: Map<String, String> = mutableMapOf(),
        timeout:Int = 0,
    ) : WebSocketClient(serverUri, Draft_6455(), httpHeaders,timeout) {
        var javaWebSocketListener : JavaWebSocketListener? = null
        val rapidResponseForce = RapidResponseForceV2()
        // 心跳间隔
        val HEARTBEAT_INTERVAL = "HEARTBEAT_INTERVAL"
        // 心跳超时
//        val HEARTBEAT_TIMEOUT = "HEARTBEAT_TIMEOUT"
        private val timedTasks = object : Comparable<Pair<String,Any?>>{
            override fun compareTo(other: Pair<String, Any?>): Int {
                Timber.i("timeoutCallback ${other.first}")
                if (other.first == HEARTBEAT_INTERVAL){
                    try {
                        var minimumPongTime:Long
                        synchronized(syncConnectionLost){
                            minimumPongTime = (System.nanoTime() - (connectionLostTimeout * 1.5)).toLong()
                        }

                        val executeConnectionLostDetection =
                            executeConnectionLostDetection(connection, minimumPongTime)
                        if (!executeConnectionLostDetection){
                            // 心跳结束
                            cancelConnectionLostTimer()
                            return 0
                        }
                    }catch (e:Exception){
                        e.printStackTrace()
                    }
                    synchronized(syncConnectionLost){
                        restartConnectionLostTimer()
                    }
                }
                return 0;
            }

        }
        init {
            rapidResponseForce.timeoutCallback(this.timedTasks)
        }
        override fun onOpen(handshakedata: ServerHandshake?) {
            updateLastPong()
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
        }

        override fun onError(ex: Exception) {
            javaWebSocketListener?.onError(ex)
        }

        override fun onPreparePing(conn: WebSocket?): PingFrame {
            javaWebSocketListener?.onPreparePing()
            return super.onPreparePing(conn)
        }

        override fun onWebsocketPong(conn: WebSocket?, f: Framedata?) {
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
            Timber.i("setConnectionLostTimeout ${connectionLostTimeout}")
            synchronized(syncConnectionLost){
                this.connectionLostTimeout = TimeUnit.SECONDS.toNanos(connectionLostTimeout.toLong())
                if (this.connectionLostTimeout <= 0) {
                    cancelConnectionLostTimer()
                    return
                }
                if (websocketRunning){
                    updateLastPong()
                    restartConnectionLostTimer()
                }
            }
        }

        override fun startConnectionLostTimer() {
            Timber.i("startConnectionLostTimer")
            synchronized(syncConnectionLost){
                if (connectionLostTimeout < 0){
                    return
                }
                websocketRunning = true
                restartConnectionLostTimer()
            }
        }

        override fun stopConnectionLostTimer() {
            Timber.i("stopConnectionLostTimer")
            synchronized(syncConnectionLost){
                websocketRunning = false
                cancelConnectionLostTimer()
            }
        }

        private fun cancelConnectionLostTimer(){
            Timber.i("cancelConnectionLostTimer")
            rapidResponseForce.unRegister(HEARTBEAT_INTERVAL)
        }
        private fun restartConnectionLostTimer(){
            Timber.i("restartConnectionLostTimer")
            cancelConnectionLostTimer()
            rapidResponseForce.register(HEARTBEAT_INTERVAL)
        }
        private fun executeConnectionLostDetection(webSocket: WebSocket, minimumPongTime: Long):Boolean {
            Timber.i("executeConnectionLostDetection")
            if (getLastPong() < minimumPongTime){
                webSocket.closeConnection(
                    CloseFrame.ABNORMAL_CLOSE,
                    "The connection was closed because the other endpoint did not respond with a pong in time. For more information check: https://github.com/TooTallNate/Java-WebSocket/wiki/Lost-connection-detection"
                )
                return false
            }
            if (!webSocket.isOpen){
                return false
            }
            if (!websocketRunning){
                return false
            }
            webSocket.sendPing()
            Timber.i("executeConnectionLostDetection sendPing")
            return true
        }
        private var lastPong  = System.nanoTime()
        fun getLastPong():Long {
            return lastPong;
        }

        /**
         * Update the timestamp when the last pong was received
         */
        fun updateLastPong() {
            this.lastPong = System.nanoTime();
        }


    }

    /**
     * 智能心跳
     *  智能模式
     *  固定模式
     *
     */
    class IntelligentHeartbeat : Callable<Any?> {
        private val synchronizeCore = Any()
        private val heartbeat = RapidResponseForce<String>()
        private lateinit var hsc:HeartbeatSendingController
        val WAITING_NEXT_HEARTBEAT = "WAITING_NEXT_HEARTBEAT"
        val WAITING_HEARTBEAT_RECEIPT = "WAITING_HEARTBEAT_RECEIPT"
        init {
            hsc.callPong(this)
            heartbeat.timeoutCallback {
                when(it?.firstOrNull()){
                    WAITING_HEARTBEAT_RECEIPT->{
                        // 回执超时
                        val callPongFailed = hsc.callPongFailed()
                        if (!callPongFailed){
                            return@timeoutCallback
                        }
                        nextHeartbeat()
                    }
                    WAITING_NEXT_HEARTBEAT -> {
                        // 延时下一次
                        startHeartbeat()
                    }
                }
            }
        }
        fun startHeartbeat(){
            synchronized(synchronizeCore){
                hsc.sendPing()
                heartbeat.register(WAITING_HEARTBEAT_RECEIPT,WAITING_HEARTBEAT_RECEIPT)
            }
        }
        fun nextHeartbeat(){
            heartbeat.register(WAITING_NEXT_HEARTBEAT,WAITING_NEXT_HEARTBEAT)
        }
        fun stopHeartbeat(){}
        fun skipHeartbeat(count:Int = 1){}
        fun pausing(){}
        fun resuming(){

        }
        override fun call(): Any? {
            // pong成功
            // 解除等待
            heartbeat.unRegister(WAITING_HEARTBEAT_RECEIPT)
            // 注册下次心跳
            heartbeat.register(WAITING_NEXT_HEARTBEAT,WAITING_NEXT_HEARTBEAT)
            return hsc.callPongSuccessful()
        }
    }

    interface HeartbeatSendingController {
        fun sendPing(ping:Any? = null)
        fun callPong(callable: Callable<Any?>)
        fun callPongSuccessful():Boolean

        fun callPongFailed():Boolean
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