package org.daimhim.imc_core.demo

import org.java_websocket.WebSocket
import org.java_websocket.client.WebSocketClient
import org.java_websocket.framing.Framedata
import org.java_websocket.framing.PingFrame
import org.java_websocket.handshake.ServerHandshake
import org.junit.Test
import java.lang.Exception
import java.net.URI
import java.nio.ByteBuffer

class WebSocketClientTest {

    @Test
    fun addition_isCorrect() {
        val javaWebSocketClient = JavaWebSocketClient(URI("wss://278b479b.r24.cpolar.top/ws?platform=android&token=eyJ0eXAiOiJKV1QiLCJhbGciOiJSUzUxMiJ9.eyJzdWIiOiIxMTk5MjQ0MzIxMjU0MTUwMTQ0Iiwic2NvcGUiOiJkZWZhdWx0IiwiaXNzIjoiMTUwMTUxMTIwMDgiLCJsb2dpbiI6MTcxNTU4NTQ1Nn0.MSSfcILGEqxX9sICaCKvd7d7_4BkX23BkHlbDG6X2BUNoESDjPCF2qtkfhHEWJZ3FkYdFSABnrKTnXyoxPY9mLA09rf8R5JJMga3zaPR6J_jGzuu3acM_zQKp7qFBRgoC9_ceHRt7pywb16LjASeGBIMCif2mG0OqlPP-NAW2Xs&name=202012221018295"))
        javaWebSocketClient.connectionLostTimeout = 5
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

        override fun onPreparePing(conn: WebSocket?): PingFrame {
            println("onPreparePing")
            return super.onPreparePing(conn)
        }

        override fun onWebsocketPong(conn: WebSocket?, f: Framedata?) {
            super.onWebsocketPong(conn, f)
            println("onWebsocketPong")
        }

    }
}