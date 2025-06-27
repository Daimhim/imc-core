package org.daimhim.imc_core

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.URI
import java.nio.ByteBuffer

class UDPEngine(
    build:Builder
) : IEngine {
    private var serverAddress : URI = URI.create("http://127.0.0.1:0")
    private var isConnect = false
    private var datagramSocket:DatagramSocket? = null
    private val syncUDP = Any()

    private val receiveRunnable = object : Runnable{
        override fun run() {
            while (isConnect && thread?.isInterrupted == false){
                try {
                    build.receiveParser.parser(this@UDPEngine,imcListenerManager,datagramSocket)
                }catch (e:Exception){
                    e.printStackTrace()
                    IMCLog.e(e)
                }
                if (!isConnect() && isConnect){
                    makeConnection()
                }
            }
        }

    }
    private var thread : Thread? = null
    private var stayOnline  :StayOnline? = null

    override fun engineOn(key: String) {
        synchronized(syncUDP){
            if (isConnect()){
                throw IllegalStateException("请先断开，在重新连接。")
            }
            serverAddress = URI(key)
            datagramSocket = DatagramSocket(0)
            datagramSocket?.reuseAddress = true
            makeConnection()
            println("UDPEngine.engineOn 连接成功")
            isConnect = true
            imcStatusListener?.connectionSucceeded()
            thread = Thread(receiveRunnable)
            thread?.start()
        }
    }

    fun getLocalPort():Int{
        return datagramSocket?.localPort?:0;
    }

    override fun engineOff() {
        thread?.interrupt()
        datagramSocket?.close()
        imcStatusListener?.connectionClosed(-1,"主动关闭")
        thread = null
        datagramSocket = null
        isConnect = false
    }

    override fun engineState(): Int {
        return if (isConnect()){
            IEngineState.ENGINE_OPEN
        }else{
            IEngineState.ENGINE_CLOSED
        }
    }

    fun isConnect():Boolean{
        return datagramSocket?.isConnected == true
    }
    override fun send(byteArray: ByteArray): Boolean {
        return send(DatagramPacket(byteArray,byteArray.size))
    }

    private fun send(packet:DatagramPacket):Boolean{
        datagramSocket?.send(packet)
        return true
    }

    override fun send(text: String): Boolean {
        val toByteArray = text.toByteArray(Charsets.UTF_8)
        return send(byteArray = toByteArray)
    }


    override fun onChangeMode(mode: Int) {

    }

    override fun onNetworkChange(networkState: Int) {

    }

    override fun makeConnection() {
        if (isConnect()){
            return
        }
        datagramSocket?.connect(InetAddress.getByName(serverAddress.host),serverAddress.port)
    }

    class StayOnline(
        private val iEngine: IEngine,
        private val customContent:CustomHeartbeat,
        var imcStatusListener: IMCStatusListener?
    ) {
        // 心跳间隔
        val HEARTBEAT_INTERVAL = "心跳间隔_${hashCode()}"
        val rapidResponseForce = RapidResponseForceV2()
        // 最后一次心跳响应
        private var lastPong = System.currentTimeMillis()
        var maximumInterval : Long = 5 * 1000
        private val asyn = Any()
        var inProgress = false
        init {
            rapidResponseForce.timeoutCallback(object : Comparable<Pair<String, Any?>>{
                override fun compareTo(other: Pair<String, Any?>): Int {
                    if (other.first == HEARTBEAT_INTERVAL){
                        // 检查上次
                        examine()
                        // 继续本次
                        execution()
                    }
                    return 0
                }

            })
            iEngine.addIMCListener(object : V2IMCListener{
                override fun onMessage(byteArray: ByteArray) {
                    update()
                }

                override fun onMessage(text: String) {
                    update()
                }
            })
        }
        fun start() {
            if (inProgress){
                return
            }
            execution()
        }

        fun stop() {
            synchronized(asyn) {
                inProgress = false
                rapidResponseForce.unRegister(HEARTBEAT_INTERVAL)
            }
        }
        fun examine(){
            // 响应时间超出，抛出异常
            if (System.currentTimeMillis() - lastPong > maximumInterval){
                imcStatusListener?.connectionLost(IllegalStateException("心跳超时"))
            }
        }
        fun execution(){
            synchronized(asyn){
                if (customContent.byteOrString())
                    iEngine.send(customContent.stringHeartbeat())
                else
                    iEngine.send(customContent.byteHeartbeat())
                rapidResponseForce.register(HEARTBEAT_INTERVAL,"",maximumInterval)
                inProgress = true
            }
        }
        fun update() {
            synchronized(asyn) {
                lastPong = System.currentTimeMillis()
            }
        }
    }
    //--------------------------------监听相关-----------------------------------------------
    internal val imcListenerManager = IMCListenerManager()
    var imcStatusListener: IMCStatusListener? = null
        set(value) {
            stayOnline?.imcStatusListener = value
            field = value
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

    public class Builder{
        internal var receiveParser:UDPReceiveParser = DefUDPReceiveParser()
        internal var customContent:CustomHeartbeat = DefCustomHeartbeat()

        fun setReceiveParser(receiveParser:UDPReceiveParser):Builder{
            this.receiveParser = receiveParser
            return this
        }
        fun setCustomContent(customContent:CustomHeartbeat):Builder{
            this.customContent = customContent
            return this
        }
        fun builder():UDPEngine{
            return UDPEngine(this)
        }
    }

    class DefUDPReceiveParser : UDPReceiveParser{
        private val data = ByteArray(1024)
        private lateinit var udpBuffer : DatagramPacket
        override fun parser(iEngine: IEngine, imcListenerManager: IMCListenerManager, datagramSocket: DatagramSocket?) {
            udpBuffer = DatagramPacket(data,data.size)
            datagramSocket?.receive(udpBuffer)
            imcListenerManager.onMessage(iEngine,String(udpBuffer.data.copyOfRange(0,udpBuffer.length)))
        }
    }
    class DefCustomHeartbeat : CustomHeartbeat{
        override fun isHeartbeat(iEngine: IEngine, bytes: ByteArray): Boolean {
            TODO("Not yet implemented")
        }

        override fun isHeartbeat(iEngine: IEngine, text: String): Boolean {
            TODO("Not yet implemented")
        }

        override fun byteHeartbeat(): ByteArray {
            TODO("Not yet implemented")
        }

        override fun stringHeartbeat(): String {
            TODO("Not yet implemented")
        }

        override fun byteOrString(): Boolean {
            TODO("Not yet implemented")
        }

    }
}