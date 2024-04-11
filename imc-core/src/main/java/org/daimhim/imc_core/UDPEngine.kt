package org.daimhim.imc_core

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.URI
import java.net.URL

class UDPEngine : IEngine {
    private var serverAddress : URI = URI.create("http://127.0.0.1:80")
    private var isConnect = false
    private var datagramSocket:DatagramSocket? = null

    private val receiveRunnable = object : Runnable{
        override fun run() {
            val data = ByteArray(1024)
            val udpBuffer = DatagramPacket(data,data.size)
            while (isConnect){
                datagramSocket?.receive(udpBuffer)
                imcListenerManager.onMessage(this@UDPEngine,String(udpBuffer.data))
            }
        }

    }
    override fun engineOn(key: String) {
        serverAddress = URI(key)
        datagramSocket = DatagramSocket(serverAddress.port)
        datagramSocket?.reuseAddress = true
        isConnect = true
        Thread(receiveRunnable).start()
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
        println("UDPEngine.send 11")
        if (!isConnect()){
            datagramSocket?.connect(InetAddress.getByName(serverAddress.host),serverAddress.port)
        }
        println("UDPEngine.send 22")
        datagramSocket?.send(DatagramPacket(byteArray,byteArray.size))
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