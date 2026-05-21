package org.daimhim.imc_core

import org.java_websocket.framing.CloseFrame
import org.java_websocket.handshake.ServerHandshake
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/**
 * 覆盖 [V2JavaWebEngine.WebSocketClientImpl.onClose] 的分支:
 *
 *  ┌────────────────────────┬─────────────────────────────┐
 *  │ 触发条件                │ 预期回调                      │
 *  ├────────────────────────┼─────────────────────────────┤
 *  │ 本地主动 close()        │ listener.onClose            │
 *  │ 远端 NORMAL (1000)     │ listener.onClose            │
 *  │ 远端 GOING_AWAY (1001) │ listener.onClose            │
 *  │ 远端 PROTOCOL_ERROR    │ listener.onError            │
 *  │ 远端 ABNORMAL (1006)   │ listener.onError            │
 *  │ 本地超时等 remote=false │ listener.onError            │
 *  └────────────────────────┴─────────────────────────────┘
 *
 * 不真实连接,直接对一个未连接的 WebSocketClientImpl 实例调 onClose() 校验分发
 */
class V2JavaWebEngineImplTest {

    private fun newClient(): V2JavaWebEngine.WebSocketClientImpl =
        V2JavaWebEngine.WebSocketClientImpl(URI("ws://localhost:65535"))

    private fun listenerCapture(): Pair<V2JavaWebEngine.JavaWebSocketListener, List<String>> {
        val events = CopyOnWriteArrayList<String>()
        val listener = object : V2JavaWebEngine.JavaWebSocketListener {
            override fun onOpen(handshakedata: ServerHandshake?) { events.add("onOpen") }
            override fun onMessage(message: String) { events.add("onMessage:str") }
            override fun onMessage(bytes: ByteBuffer) { events.add("onMessage:bytes") }
            override fun onClose(code: Int, reason: String?, remote: Boolean) {
                events.add("onClose($code,remote=$remote)")
            }
            override fun onError(ex: Exception) {
                events.add("onError(${ex.javaClass.simpleName})")
            }
        }
        return listener to events
    }

    @Test
    fun onClose_local_proactive_close_dispatches_onClose() {
        val client = newClient()
        val (listener, events) = listenerCapture()
        client.javaWebSocketListener = listener

        client.close()  // 本地主动: 内部置 isClosed = true
        client.onClose(CloseFrame.NORMAL, "user closed", false)

        assertEquals(listOf("onClose(${CloseFrame.NORMAL},remote=false)"), events)
    }

    @Test
    fun onClose_remote_normal_dispatches_onClose_notOnError() {
        val client = newClient()
        val (listener, events) = listenerCapture()
        client.javaWebSocketListener = listener

        client.onClose(CloseFrame.NORMAL, "server kicked me", true)

        assertEquals(
            "远端 NORMAL 关闭应走 onClose 而非 onError",
            listOf("onClose(${CloseFrame.NORMAL},remote=true)"),
            events
        )
    }

    @Test
    fun onClose_remote_goingAway_dispatches_onClose_notOnError() {
        val client = newClient()
        val (listener, events) = listenerCapture()
        client.javaWebSocketListener = listener

        client.onClose(CloseFrame.GOING_AWAY, "server shutdown", true)

        assertEquals(
            "远端 GOING_AWAY 应走 onClose 而非 onError",
            listOf("onClose(${CloseFrame.GOING_AWAY},remote=true)"),
            events
        )
    }

    @Test
    fun onClose_remote_protocolError_dispatches_onError() {
        val client = newClient()
        val (listener, events) = listenerCapture()
        client.javaWebSocketListener = listener

        client.onClose(CloseFrame.PROTOCOL_ERROR, "bad frame", true)

        assertEquals(1, events.size)
        assertTrue(
            "PROTOCOL_ERROR 应走 onError, 实际: ${events.first()}",
            events.first().startsWith("onError")
        )
    }

    @Test
    fun onClose_remote_abnormal_dispatches_onError() {
        val client = newClient()
        val (listener, events) = listenerCapture()
        client.javaWebSocketListener = listener

        client.onClose(CloseFrame.ABNORMAL_CLOSE, "TCP reset", true)

        assertEquals(1, events.size)
        assertTrue(events.first().startsWith("onError"))
    }

    @Test
    fun onClose_local_network_error_dispatches_onError() {
        val client = newClient()
        val (listener, events) = listenerCapture()
        client.javaWebSocketListener = listener

        // 本地角度看到的 timeout, remote=false 且非主动 close
        client.onClose(CloseFrame.NORMAL, "local timeout", false)

        assertEquals(1, events.size)
        assertTrue(
            "本地非主动 close 时, 即使 code=NORMAL 也应走 onError, 实际: ${events.first()}",
            events.first().startsWith("onError")
        )
    }

    @Test
    fun onClose_no_listener_does_not_throw() {
        val client = newClient()
        // 没 set javaWebSocketListener, 任何分支都不应抛异常
        client.onClose(CloseFrame.NORMAL, null, true)
        client.onClose(CloseFrame.PROTOCOL_ERROR, null, true)
        client.onClose(CloseFrame.NORMAL, null, false)
        client.close()
        client.onClose(CloseFrame.NORMAL, null, false)
    }

    @Test
    fun onError_dispatches_to_listener() {
        val client = newClient()
        val (listener, events) = listenerCapture()
        client.javaWebSocketListener = listener

        client.onError(RuntimeException("simulated"))

        // 至少包含 onError 事件
        assertTrue(
            "onError 应被分发, 实际: $events",
            events.any { it.startsWith("onError") }
        )
    }
}

/**
 * 覆盖:V2JavaWebEngine 初次 engineOn 必须记录目标 URL,
 * 否则 onClose 重启路径下没有目标 URL 可用
 */
class V2JavaWebEngineCurrentKeyTest {

    @Test
    fun engineOn_initial_call_records_currentKey() {
        val engine = V2JavaWebEngine.Builder().build()

        try {
            engine.engineOn("ws://192.0.2.1:65535")
        } catch (e: Exception) {
            // 任何连接异常都无所谓, 我们只关心 URL 是否被状态机记录
        }

        // 通过 internal currentKey() 拿到状态机里记录的 URL
        val captured = engine.currentKey()

        assertEquals(
            "engineOn 初次调用必须同步记录目标 URL",
            "ws://192.0.2.1:65535",
            captured
        )

        try { engine.engineOff() } catch (_: Exception) {}
    }
}
