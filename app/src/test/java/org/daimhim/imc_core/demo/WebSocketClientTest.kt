package org.daimhim.imc_core.demo

import org.java_websocket.WebSocket
import org.java_websocket.client.WebSocketClient
import org.java_websocket.framing.Framedata
import org.java_websocket.handshake.ServerHandshake
import org.junit.Test
import java.lang.Exception
import java.net.URI
import java.nio.ByteBuffer

class WebSocketClientTest {

    @Test
    fun addition_isCorrect() {
        val javaWebSocketClient = JavaWebSocketClient(URI("wss://47c2f4ee.r12.cpolar.top"))
        javaWebSocketClient.connect()
        while (true){
            Thread.sleep(5000)
        }
    }

    class JavaWebSocketClient(serverUri: URI?) : WebSocketClient(serverUri) {
        override fun onOpen(p0: ServerHandshake?) {
            println("onOpen")
        }

        override fun onMessage(bytes: ByteBuffer?) {
            println("onMessage ByteBuffer")
        }
        override fun onMessage(p0: String?) {
            println("onMessage String")
        }

        override fun onClose(p0: Int, p1: String?, p2: Boolean) {
            println("onClose")
        }

        override fun onError(p0: Exception?) {
            println("onError")
        }

        override fun onWebsocketPing(conn: WebSocket?, f: Framedata?) {
            super.onWebsocketPing(conn, f)
            println("onWebsocketPing")
        }

        override fun onWebsocketPong(conn: WebSocket?, f: Framedata?) {
            super.onWebsocketPong(conn, f)
            println("onWebsocketPong")
        }
    }
}