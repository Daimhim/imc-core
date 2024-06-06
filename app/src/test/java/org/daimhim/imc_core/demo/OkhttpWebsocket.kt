package org.daimhim.imc_core.demo

import okhttp3.*
import okio.ByteString
import org.junit.Test
import java.util.concurrent.TimeUnit

class OkhttpWebsocket  {
    @Test
    fun addition_isCorrect() {
        OkHttpClient
            .Builder()
            .pingInterval(5L, TimeUnit.SECONDS)
            .build()
            .newWebSocket(
                Request.Builder().url(
//                    "wss://278b479b.r24.cpolar.top/ws?platform=android&token=eyJ0eXAiOiJKV1QiLCJhbGciOiJSUzUxMiJ9.eyJzdWIiOiIxMTk5MjQ0MzIxMjU0MTUwMTQ0Iiwic2NvcGUiOiJkZWZhdWx0IiwiaXNzIjoiMTUwMTUxMTIwMDgiLCJsb2dpbiI6MTcxNTU4NTQ1Nn0.MSSfcILGEqxX9sICaCKvd7d7_4BkX23BkHlbDG6X2BUNoESDjPCF2qtkfhHEWJZ3FkYdFSABnrKTnXyoxPY9mLA09rf8R5JJMga3zaPR6J_jGzuu3acM_zQKp7qFBRgoC9_ceHRt7pywb16LjASeGBIMCif2mG0OqlPP-NAW2Xs&name=202012221018295"
                    "ws://127.0.0.1:4728/ws?platform=android&token=eyJ0eXAiOiJKV1QiLCJhbGciOiJSUzUxMiJ9.eyJzdWIiOiIxMTk5MjQ0MzIxMjU0MTUwMTQ0Iiwic2NvcGUiOiJkZWZhdWx0IiwiaXNzIjoiMTUwMTUxMTIwMDgiLCJsb2dpbiI6MTcxNTU4NTQ1Nn0.MSSfcILGEqxX9sICaCKvd7d7_4BkX23BkHlbDG6X2BUNoESDjPCF2qtkfhHEWJZ3FkYdFSABnrKTnXyoxPY9mLA09rf8R5JJMga3zaPR6J_jGzuu3acM_zQKp7qFBRgoC9_ceHRt7pywb16LjASeGBIMCif2mG0OqlPP-NAW2Xs&name=202012221018295"
                ).build(), webSocketListenerImpl
            )
        while (true){
            Thread.sleep(5000)
        }
    }

    private val webSocketListenerImpl = object : WebSocketListener() {
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            super.onClosed(webSocket, code, reason)
            println("OkhttpWebsocket.onClosed")
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            super.onClosing(webSocket, code, reason)
            println("OkhttpWebsocket.onClosing")
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            super.onFailure(webSocket, t, response)
            println("OkhttpWebsocket.onFailure")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            super.onMessage(webSocket, text)
            println("OkhttpWebsocket.onMessage text")
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            super.onMessage(webSocket, bytes)
            println("OkhttpWebsocket.onMessage bytes")
        }

        override fun onOpen(webSocket: WebSocket, response: Response) {
            super.onOpen(webSocket, response)
            println("OkhttpWebsocket.onOpen")
        }
    }
}