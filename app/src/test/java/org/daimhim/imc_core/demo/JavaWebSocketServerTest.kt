package org.daimhim.imc_core.demo

import org.java_websocket.WebSocket
import org.java_websocket.framing.Framedata
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.junit.Test
import java.lang.Exception
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.Date

class JavaWebSocketServerTest  {

    @Test
    fun addition_isCorrect() {
        val webSocketServer = JavaWebSocketServer(3390)
        webSocketServer.start()
        while (true){
            Thread.sleep(5000)
        }
    }

    class JavaWebSocketServer(port:Int) : WebSocketServer(InetSocketAddress(port)) {
        override fun onOpen(p0: WebSocket?, p1: ClientHandshake?) {
            println("onOpen ${p0?.remoteSocketAddress} ${p0?.protocol}")
        }

        override fun onClose(p0: WebSocket?, p1: Int, p2: String?, p3: Boolean) {
            println("onClose ${p0?.remoteSocketAddress} ${p0?.protocol}")
        }

        override fun onMessage(conn: WebSocket?, message: ByteBuffer?) {
            println("onMessage ByteBuffer ${conn?.remoteSocketAddress} ${conn?.protocol}")
        }
        override fun onMessage(p0: WebSocket?, p1: String?) {
            println("onMessage ${p0?.remoteSocketAddress} ${p0?.protocol} ${p1}")
        }

        override fun onError(p0: WebSocket?, p1: Exception?) {
            println("onError ${p0?.remoteSocketAddress} ${p0?.protocol}")
        }

        override fun onStart() {
            println("onStart")
        }

        override fun onWebsocketPing(conn: WebSocket?, f: Framedata?) {
            println("onMessage ${conn?.remoteSocketAddress} ${conn?.protocol}")
            super.onWebsocketPing(conn, f)
        }

        override fun onWebsocketPong(conn: WebSocket?, f: Framedata?) {
            super.onWebsocketPong(conn, f)
            println("onWebsocketPong ${conn?.remoteSocketAddress} ${conn?.protocol} ${(f)?.javaClass?.name} ${Date()}")
        }
    }
}