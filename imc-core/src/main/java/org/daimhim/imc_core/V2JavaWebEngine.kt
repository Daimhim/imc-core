package org.daimhim.imc_core

import org.java_websocket.WebSocket
import org.java_websocket.client.WebSocketClient
import org.java_websocket.drafts.Draft_6455
import org.java_websocket.enums.ReadyState
import org.java_websocket.enums.Role
import org.java_websocket.framing.Framedata
import org.java_websocket.handshake.ServerHandshake
import java.net.URI
import java.nio.ByteBuffer

class V2JavaWebEngine private constructor(private val builder: Builder) : IEngine {
    companion object {
        val CONNECTION_UNEXPECTEDLY_CLOSED = -1 //： 连接意外关闭
        val CONNECTION_RESET = -2 //： 连接被重置
        val CONNECTION_TERMINATED = -3 //： 连接被终止
    }

    /**
     * 心跳模式
     * 1 智能心跳
     * 2 自定义心跳
     */
    private var heartbeatMode : MutableMap<Int,ILinkNative> = builder.heartbeatMode
    private var defHeartbeatMode : Int = builder.defHeartbeatMode
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
                .onMessage(this@V2JavaWebEngine, message)
        }

        override fun onMessage(bytes: ByteBuffer) {
            IMCLog.i("onMessage bytes ${bytes.limit()}")
            imcListenerManager
                .onMessage(this@V2JavaWebEngine, bytes)
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
                    nst = builder.nst(),
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
            // 初始化 监听
            webSocketClient?.javaWebSocketListener = javaWebsocketListener
            //初始化 自动连接
            webSocketClient?.setAutoConnect(builder.autoConnect)
            // 初始化状态
            heartbeatMode[this.defHeartbeatMode]?.let {
                webSocketClient?.onChangeMode(it)
            }
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
        webSocketClient?.stopConnectionLostTimerEx(false)
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
        IMCLog.i("sendHeartbeat onChangeMode $mode")
        // 心跳间隔 单位 秒
        heartbeatMode[mode]?.let {
            webSocketClient?.onChangeMode(it)
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
        private val nst: NST? = null,
    ) : WebSocketClient(serverUri, Draft_6455(), httpHeaders, timeout) {

        var javaWebSocketListener: JavaWebSocketListener? = null
        private val rapidResponseForce = RapidResponseForceV2()
        private var isClosed = false
        private var iLinkNative: ILinkNative? = null

        /**
         * 心跳器
         */
        private var linkNative : ILinkNative? = null

        fun onChangeMode(iLinkNative: ILinkNative){
            this.linkNative = iLinkNative
            this.linkNative?.initLinkNative(this)
            stopConnectionLostTimerEx()
            startConnectionLostTimerEx()
            if (!isOpen){
                return
            }
            this.linkNative?.sendHeartbeat()
        }
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
            if (isClosed) {
                javaWebSocketListener?.onClose(code, reason, remote)
                return
            }
            javaWebSocketListener?.onError(IllegalStateException("code:${code} reason${reason} remote${remote}"))
            // 停止心跳
            stopConnectionLostTimerEx(true)
            // z自动重连
            abnormalDisconnectionAndAutomaticReconnection()
        }

        override fun onError(ex: Exception) {
            javaWebSocketListener?.onError(ex)
            if (isClosed) {
                return
            }
            // 停止心跳
            stopConnectionLostTimerEx(true)
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
        private val syncConnectionLost = Any()
        override fun onWebsocketPong(conn: WebSocket?, f: Framedata?) {
            retryingSendingCache()
            linkNative?.updateLastPong()
            super.onWebsocketPong(conn, f)
        }

        override fun getConnectionLostTimeout(): Int {
            return linkNative?.getConnectionLostTimeout()?:0
        }

        override fun setConnectionLostTimeout(connectionLostTimeout: Int) {
            linkNative?.setConnectionLostTimeout(connectionLostTimeout)
        }

        fun startConnectionLostTimerEx() {
            linkNative?.startConnectionLostTimer()
        }
        public fun stopConnectionLostTimerEx(isError:Boolean = false) {
            linkNative?.stopConnectionLostTimer(isError)
        }

        /*
         * ------------------------心跳---------------------------------------
         */


        /**
        ------------------------自动连接---------------------------------------
         */

        private var autoConnect : IAutoConnect? = null

        fun setAutoConnect(auto:IAutoConnect?){
            autoConnect = auto
            autoConnect?.initAutoConnect(this)
        }

        /**
         * 异常断开 并启动自动重连
         * onClose
         * onError
         */
        private fun abnormalDisconnectionAndAutomaticReconnection() {
            autoConnect?.abnormalDisconnectionAndAutomaticReconnection()
        }

        /**
         * 重置自动连接，用于更新配置
         */
        internal fun resetStartAutoConnect() {
            IMCLog.i("重置抢救，之前记录")
            iLinkNative?.stopConnectionLostTimer()
            autoConnect?.resetStartAutoConnect()
        }

        fun stopAutoConnect(){
            autoConnect?.stopAutoConnect()
        }

        fun startAutoConnect(){
            autoConnect?.startAutoConnect()
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
        internal var javaWebEngine: V2JavaWebEngine? = null
        internal var debug = false
        internal var nst: NST? = null
        internal var imcLogFactory: IIMCLogFactory? = null
        internal var heartbeatMode = mutableMapOf<Int,ILinkNative>() //心跳模式
        internal var defHeartbeatMode = 0
        internal var autoConnect : IAutoConnect? = null

        /**
         *
         * @return 为IMC提供的JavaWebEngine客户端
         */
        fun javaWebEngine(): V2JavaWebEngine =
            javaWebEngine ?: V2JavaWebEngine
                .Builder()
                .build()

        fun javaWebEngine(javaWebEngine: V2JavaWebEngine) = apply {
            this.javaWebEngine = javaWebEngine
        }

        fun setIMCLog(imcLogFactory: IIMCLogFactory) = apply {
            this.imcLogFactory = imcLogFactory
        }


        fun debug(debug: Boolean) = apply {
            this.debug = debug
        }

        fun debug(): Boolean = debug

        fun nst(nst: NST) = apply { this.nst = nst }
        fun nst() = nst

        fun defHeartbeatMode(defHeartbeatMode: Int) = apply { this.defHeartbeatMode = defHeartbeatMode }

        fun addHeartbeatMode(key:Int,value:ILinkNative):Builder{
            heartbeatMode[key] = value
            return this
        }
        fun setAutoConnect(auto:IAutoConnect) = apply {
            autoConnect = auto
        }

        fun removeHeartbeatMode(key:Int):Builder{
            heartbeatMode.remove(key)
            return this
        }

        fun build(): V2JavaWebEngine {
            IMCLog.setIIMCLogFactory(imcLogFactory)
            return V2JavaWebEngine(this)
        }
    }
}