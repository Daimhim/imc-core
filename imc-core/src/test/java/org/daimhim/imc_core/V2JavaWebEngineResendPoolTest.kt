package org.daimhim.imc_core

import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 引擎层的重发池行为(真实 socket):
 *  - 未启动 / engineOff 后:带 ID 也 REJECTED,旧 send 返回 false
 *  - 断线期间:带 ID 入池、不带 ID 不缓存;连上后池中消息恰好送达一次,不带 ID 的永不补发
 *  - engineOff:整池 SESSION_END 回收
 */
class V2JavaWebEngineResendPoolTest {

    /** 不接管退避:断线后引擎停在 Reconnecting(client=null),由测试手动 engineOn 推进 */
    private class ManualAutoConnect : IAutoConnect {
        override fun initAutoConnect(webSocketClient: V2JavaWebEngine.WebSocketClientImpl) {}
        override fun abnormalDisconnectionAndAutomaticReconnection() {}
        override fun resetStartAutoConnect() {}
        override fun startAutoConnect() {}
        override fun stopAutoConnect() {}
        override fun isActive(): Boolean = true
    }

    private class RecordingServer(port: Int) : WebSocketServer(InetSocketAddress("127.0.0.1", port)) {
        val started = CountDownLatch(1)
        val received = CopyOnWriteArrayList<String>()
        override fun onStart() { started.countDown() }
        override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {}
        override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {}
        override fun onMessage(conn: WebSocket, message: String) { received.add(message) }
        override fun onError(conn: WebSocket?, ex: Exception) {}
    }

    private val toStop = ArrayList<() -> Unit>()

    @After
    fun tearDown() { toStop.forEach { try { it() } catch (_: Exception) {} } }

    private fun newEngine(): V2JavaWebEngine = V2JavaWebEngine.Builder()
        .addHeartbeatMode(0, V2FixedHeartbeat.Builder().setCurHeartbeat(600).build())
        .setAutoConnect(ManualAutoConnect())
        .build()
        .also { e -> toStop.add { e.engineOff() } }

    private fun freePort() = ServerSocket(0).use { it.localPort }

    private fun waitUntil(timeoutMs: Long = 5_000, cond: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return true
            Thread.sleep(20)
        }
        return cond()
    }

    /** engineOn 到一个没人监听的端口,等它落到 Reconnecting(断线中) */
    private fun disconnectedEngine(port: Int): V2JavaWebEngine {
        val engine = newEngine()
        engine.engineOn("ws://127.0.0.1:$port")
        assertTrue("应落到断线态", waitUntil { engine.engineState() == IEngineState.ENGINE_FAILED })
        return engine
    }

    @Test
    fun `引擎未启动 - 带ID也拒绝 - 旧send返回false`() {
        val engine = newEngine()
        assertEquals(SendResult.REJECTED, engine.send("x", "biz-1"))
        assertFalse(engine.send("x"))
        assertEquals(0, engine.resendPool().size())
    }

    @Test
    fun `断线期间入池 - 连上后恰好送达一次 - 不带ID的不补发`() {
        val port = freePort()
        val engine = disconnectedEngine(port)

        assertEquals(SendResult.QUEUED, engine.send("hello-1", "biz-1"))
        assertEquals(SendResult.QUEUED, engine.send("hello-2", "biz-2"))
        assertEquals(SendResult.QUEUED, engine.send("hello-1-v2", "biz-1")) // 同 ID 去重,保留最新
        assertFalse("不带 ID 断线时返回 false", engine.send("no-id"))
        assertEquals(listOf("biz-2", "biz-1"), engine.resendPool().ids())

        val server = RecordingServer(port).apply { start() }
        toStop.add { server.stop(500) }
        assertTrue(server.started.await(3, TimeUnit.SECONDS))
        engine.engineOn("ws://127.0.0.1:$port")

        assertTrue("池中消息应被送达", waitUntil { server.received.size >= 2 })
        Thread.sleep(200)
        assertEquals(listOf("hello-2", "hello-1-v2"), server.received.toList())
        assertEquals(ResendStatus.State.FLUSHED, engine.resendPool().status("biz-1").state)
        assertEquals(0, engine.resendPool().size())

        assertEquals(SendResult.SENT, engine.send("hello-3", "biz-3"))
        assertTrue(engine.send("plain"))
        assertTrue(waitUntil { server.received.size >= 4 })
        Thread.sleep(200)
        assertEquals(listOf("hello-2", "hello-1-v2", "hello-3", "plain"), server.received.toList())
        assertFalse("断线时不带 ID 的消息绝不能被补发", server.received.contains("no-id"))
    }

    @Test
    fun `业务先 take 的消息 - 连上后不再由内核发出`() {
        val port = freePort()
        val engine = disconnectedEngine(port)
        engine.send("keep", "biz-keep")
        engine.send("mine", "biz-mine")
        assertTrue(engine.resendPool().take("biz-mine"))

        val server = RecordingServer(port).apply { start() }
        toStop.add { server.stop(500) }
        assertTrue(server.started.await(3, TimeUnit.SECONDS))
        engine.engineOn("ws://127.0.0.1:$port")
        assertTrue(waitUntil { server.received.isNotEmpty() })
        Thread.sleep(200)
        assertEquals(listOf("keep"), server.received.toList())
    }

    @Test
    fun `engineOff - 整池 SESSION_END 回收 - 之后再发被拒`() {
        val events = CopyOnWriteArrayList<ImcEvent>()
        val sink = ImcEventSink { events.add(it) }
        ImcEvents.subscribe(sink)
        try {
            val engine = disconnectedEngine(freePort())
            engine.send("a", "biz-off-a")
            engine.send("b", "biz-off-b")
            engine.engineOff()

            val pool = engine.resendPool()
            assertEquals(0, pool.size())
            listOf("biz-off-a", "biz-off-b").forEach { id ->
                assertEquals(ImcEvent.EvictReason.SESSION_END, pool.status(id).evictReason)
                assertEquals(1, events.filterIsInstance<ImcEvent.MessageEvicted>().count { it.id == id })
            }
            assertEquals(SendResult.REJECTED, engine.send("c", "biz-off-c"))
        } finally {
            ImcEvents.unsubscribe(sink)
        }
    }
}
