package org.daimhim.imc_core

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.URI

class UDPEngine : IEngine {
    private var serverAddress : URI = URI.create("http://127.0.0.1:80")
    private var isConnect = false
    private var datagramSocket:DatagramSocket? = null
    private val syncUDP = Any()

    private val receiveRunnable = object : Runnable{
        override fun run() {
            val data = ByteArray(1024)
            var udpBuffer : DatagramPacket
            while (isConnect){
                udpBuffer = DatagramPacket(data,data.size)
                datagramSocket?.receive(udpBuffer)
                println("UDPEngine.run111")
                println("run address:${udpBuffer.address.hostAddress} socketAddress:${(udpBuffer.socketAddress as java.net.InetSocketAddress)}")
                println("UDPEngine.run222")
                imcListenerManager.onMessage(this@UDPEngine,String(udpBuffer.data.copyOfRange(0,udpBuffer.length)))
                send(udpBuffer)
            }
        }

    }
    override fun engineOn(key: String) {
        synchronized(syncUDP){
            if (isConnect()){
                throw IllegalStateException("请先断开，在重新连接。")
            }
            serverAddress = URI(key)
            datagramSocket = DatagramSocket(0)
            datagramSocket?.reuseAddress = true
            datagramSocket?.connect(InetAddress.getByName(serverAddress.host),serverAddress.port)
            println("UDPEngine.engineOn")
            isConnect = true
            Thread(receiveRunnable).start()
        }
    }

    fun getLocalPort():Int{
        return datagramSocket?.localPort?:0;
    }

    override fun engineOff() {
        datagramSocket?.close()
        datagramSocket = null
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

    }
    //--------------------------------监听相关-----------------------------------------------
    internal val imcListenerManager = IMCListenerManager()
    var imcStatusListener: IMCStatusListener? = null
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
}