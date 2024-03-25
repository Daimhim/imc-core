package org.daimhim.imc_core

import okio.ByteString.Companion.toByteString
import org.java_websocket.WebSocket
import org.java_websocket.client.WebSocketClient
import org.java_websocket.framing.Framedata
import org.java_websocket.framing.PingFrame
import org.java_websocket.handshake.ServerHandshake
import timber.multiplatform.log.Timber
import java.lang.Exception
import java.net.URI
import java.nio.ByteBuffer
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.Lock

class JavaWebEngine : IEngine {
    private var webSocketClient : WebSocketClientImpl? = null
    private val rapidResponseForce = RapidResponseForce<String>()
    private val scheduledThread = Executors.newScheduledThreadPool(1)
    private val heartbeatThread = HeartbeatThread(this)

    override fun engineOn(key: String) {
        if (webSocketClient == null){
            webSocketClient = WebSocketClientImpl(URI(key),this)
        }
        webSocketClient?.connectBlocking()
        if (!isConnect()){
            return
        }
        scheduledThread.scheduleWithFixedDelay(heartbeatThread,
            5000L,5000L,TimeUnit.MILLISECONDS)
    }
    override fun engineOff() {
        webSocketClient?.close()
        webSocketClient?.javaWebEngine = null
        webSocketClient = null
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
    }

    override fun onNetworkChange(networkState: Int) {
    }

    override fun makeConnection() {
    }

    class HeartbeatThread(
        private var javaWebEngine: JavaWebEngine
    ) : Runnable{
        override fun run() {
            Timber.i("心跳HeartbeatThread111")
            javaWebEngine
                .webSocketClient
                ?.sendPing()
            Timber.i("心跳HeartbeatThread222")
        }

    }
    /***
     * 监听相关
     */
    internal val imcListenerManager = IMCListenerManager()
    var imcStatusListener: IMCStatusListener? = null

    class WebSocketClientImpl(
        serverUri:URI,
        var javaWebEngine: JavaWebEngine?,
    ) : WebSocketClient(serverUri) {


        override fun onOpen(handshakedata: ServerHandshake?) {
            Timber.i("onOpen ${handshakedata?.httpStatus} ${handshakedata?.httpStatusMessage}")
            javaWebEngine?.imcStatusListener?.connectionSucceeded()
        }

        override fun onMessage(message: String) {
            Timber.i("onMessage message:${message}")
            (javaWebEngine?:return)
                .imcListenerManager
                .onMessage(javaWebEngine?:return,message)
        }

        override fun onMessage(bytes: ByteBuffer) {
            Timber.i("onMessage bytes ${bytes.limit()}")
            (javaWebEngine?:return)
                .imcListenerManager
                .onMessage(javaWebEngine?:return,bytes.toByteString())
        }
        override fun onClose(code: Int, reason: String?, remote: Boolean) {
            Timber.i("onClose code:${code} reason:${reason} remote:${remote}")
            javaWebEngine
                ?.imcStatusListener
                ?.connectionClosed(code, reason)
        }

        override fun onError(ex: Exception) {
            Timber.i(ex,"onError")
            javaWebEngine
                ?.imcStatusListener
                ?.connectionLost(ex)
        }

        override fun onPreparePing(conn: WebSocket?): PingFrame {
            Timber.i("onPreparePing")
            return super.onPreparePing(conn)
        }

        override fun onWebsocketPong(conn: WebSocket?, f: Framedata?) {
            super.onWebsocketPong(conn, f)
            Timber.i("onWebsocketPong")
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
        fun resuming(){}
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
}