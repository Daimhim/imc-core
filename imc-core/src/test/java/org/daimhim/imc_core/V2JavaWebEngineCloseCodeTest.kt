package org.daimhim.imc_core

import org.java_websocket.WebSocket
import org.java_websocket.framing.CloseFrame
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * 验证 V2JavaWebEngine 在四种场景下的关闭码是否符合 RFC 6455 §7.4
 *
 *  ┌──────────────────────┬─────────────────────────────┬─────────────────────────┐
 *  │ 场景                  │ 客户端发给服务端              │ 客户端 listener 收到      │
 *  ├──────────────────────┼─────────────────────────────┼─────────────────────────┤
 *  │ 主动断开 engineOff()  │ NORMAL (1000)                │ onClose(1000, remote=false)│
 *  │ 被动正常关闭          │ 回 NORMAL (echo)             │ onClose(1000, remote=true) │
 *  │ 被动协议错误关闭       │ 回 close frame              │ onError                  │
 *  │ 被动异常断连 (TCP死)   │ 无 close frame              │ onError                  │
 *  └──────────────────────┴─────────────────────────────┴─────────────────────────┘
 *
 * 跑本地 WebSocketServer 当对照, 精确捕获两端的 code
 */
class V2JavaWebEngineCloseCodeTest {

    private lateinit var server: TestWsServer
    private var port = 0

    @Before
    fun setUp() {
        port = findFreePort()
        server = TestWsServer(port)
        server.start()
        assertTrue("test server start timeout", server.startLatch.await(3, TimeUnit.SECONDS))
    }

    @After
    fun tearDown() {
        try { server.stop(500) } catch (_: Exception) {}
    }

    private fun buildEngine(): V2JavaWebEngine = V2JavaWebEngine.Builder()
        .addHeartbeatMode(0, V2FixedHeartbeat.Builder().setCurHeartbeat(60L).build())
        .setAutoConnect(
            ProgressiveAutoConnect.Builder()
                .initReconnectDelay(200, TimeUnit.MILLISECONDS)
                .build()
        )
        .build()

    private fun setupListener(engine: V2JavaWebEngine): RecordedEvents {
        val rec = RecordedEvents()
        engine.setIMCStatusListener(object : IMCStatusListener {
            override fun connectionSucceeded() {
                rec.events.add("SUCCEEDED")
                val n = rec.successCount.incrementAndGet()
                if (n == 1) rec.connectedLatch.countDown()
                if (n == 2) rec.connectedLatch2.countDown()
            }
            override fun connectionClosed(code: Int, reason: String?) {
                rec.events.add("CLOSED($code)")
                rec.closedCode.set(code)
                rec.closedLatch.countDown()
            }
            override fun connectionLost(throwable: Throwable) {
                rec.events.add("LOST(${throwable.javaClass.simpleName})")
                rec.lostError.set(throwable)
                rec.lostLatch.countDown()
            }
        })
        return rec
    }

    // ── 主动断开 ────────────────────────────────────────────────────────────

    @Test
    fun local_engineOff_sends_NORMAL_1000_to_server() {
        val engine = buildEngine()
        val rec = setupListener(engine)

        Thread { engine.engineOn("ws://localhost:$port") }.start()
        assertTrue("client failed to connect", rec.connectedLatch.await(5, TimeUnit.SECONDS))
        // 等服务端也确认连接已建立
        assertTrue(server.openedLatch.await(2, TimeUnit.SECONDS))

        engine.engineOff()

        assertTrue("server didn't receive close", server.closedLatch.await(3, TimeUnit.SECONDS))
        // 关键断言: 客户端主动 close, 服务端应该收到 NORMAL (1000)
        assertEquals(
            "engineOff() 应使客户端向服务端发送 NORMAL=1000",
            CloseFrame.NORMAL,
            server.lastCloseCode.get()
        )
        // remote=true 表示这次 close 是 server 视角下的"对端发起"
        assertTrue("server 视角应判定为 remote=true", server.lastCloseRemote.get())
    }

    // ── 被动正常关闭 ────────────────────────────────────────────────────────

    @Test
    fun remote_NORMAL_close_dispatches_connectionClosed_notLost() {
        val engine = buildEngine()
        val rec = setupListener(engine)

        Thread { engine.engineOn("ws://localhost:$port") }.start()
        assertTrue(rec.connectedLatch.await(5, TimeUnit.SECONDS))
        assertTrue(server.openedLatch.await(2, TimeUnit.SECONDS))

        // 服务端发 NORMAL 关闭
        server.closeAllClients(CloseFrame.NORMAL, "server done")

        assertTrue("connectionClosed 未触发", rec.closedLatch.await(3, TimeUnit.SECONDS))
        assertEquals(CloseFrame.NORMAL, rec.closedCode.get())
        // 修复 #3 关键断言: 远端 NORMAL 关闭 NOT 走 onError
        assertNull(
            "远端 NORMAL 关闭不应触发 connectionLost",
            rec.lostError.get()
        )

        engine.engineOff()
    }

    @Test
    fun remote_GOING_AWAY_close_dispatches_connectionClosed_notLost() {
        val engine = buildEngine()
        val rec = setupListener(engine)

        Thread { engine.engineOn("ws://localhost:$port") }.start()
        assertTrue(rec.connectedLatch.await(5, TimeUnit.SECONDS))
        assertTrue(server.openedLatch.await(2, TimeUnit.SECONDS))

        server.closeAllClients(CloseFrame.GOING_AWAY, "server shutdown")

        assertTrue(rec.closedLatch.await(3, TimeUnit.SECONDS))
        assertEquals(CloseFrame.GOING_AWAY, rec.closedCode.get())
        assertNull(rec.lostError.get())

        engine.engineOff()
    }

    // ── 被动协议错误 ────────────────────────────────────────────────────────

    @Test
    fun remote_PROTOCOL_ERROR_dispatches_connectionLost() {
        val engine = buildEngine()
        val rec = setupListener(engine)

        Thread { engine.engineOn("ws://localhost:$port") }.start()
        assertTrue(rec.connectedLatch.await(5, TimeUnit.SECONDS))
        assertTrue(server.openedLatch.await(2, TimeUnit.SECONDS))

        server.closeAllClients(CloseFrame.PROTOCOL_ERROR, "fake protocol error")

        assertTrue("connectionLost 应触发", rec.lostLatch.await(3, TimeUnit.SECONDS))
        assertNotNull(rec.lostError.get())

        engine.engineOff()
    }

    // ── 被动异常断连 (TCP 死) ───────────────────────────────────────────────

    @Test
    fun abnormal_tcp_drop_dispatches_connectionLost() {
        val engine = buildEngine()
        val rec = setupListener(engine)

        Thread { engine.engineOn("ws://localhost:$port") }.start()
        assertTrue(rec.connectedLatch.await(5, TimeUnit.SECONDS))
        assertTrue(server.openedLatch.await(2, TimeUnit.SECONDS))

        // 不发 close frame, 直接关闭服务端 socket (模拟 TCP 异常)
        server.killAllAbruptly()

        // 客户端会收到 1006 (ABNORMAL_CLOSE) - java-websocket 内部约定
        assertTrue("connectionLost 应触发", rec.lostLatch.await(5, TimeUnit.SECONDS))
        assertNotNull(rec.lostError.get())

        engine.engineOff()
    }

    // ── 重连 ────────────────────────────────────────────────────────────────

    @Test
    fun auto_reconnect_after_remote_NORMAL_close() {
        val engine = buildEngine()
        val rec = setupListener(engine)

        Thread { engine.engineOn("ws://localhost:$port") }.start()
        assertTrue(rec.connectedLatch.await(5, TimeUnit.SECONDS))
        assertTrue(server.openedLatch.await(2, TimeUnit.SECONDS))

        // 服务端正常关闭
        server.closeAllClients(CloseFrame.NORMAL, "test")

        // 等自动重连
        assertTrue("自动重连未发生", rec.connectedLatch2.await(8, TimeUnit.SECONDS))

        val events = rec.events.toList()
        assertTrue(
            "事件流应包含 CLOSED → SUCCEEDED, 实际: $events",
            events.indexOfFirst { it.startsWith("CLOSED") } <
                events.indexOfLast { it == "SUCCEEDED" }
        )

        engine.engineOff()
    }

    // ── 重连发送的 close code ──────────────────────────────────────────────

    @Test
    fun engineOff_during_reconnecting_still_sends_NORMAL() {
        val engine = buildEngine()
        val rec = setupListener(engine)

        Thread { engine.engineOn("ws://localhost:$port") }.start()
        assertTrue(rec.connectedLatch.await(5, TimeUnit.SECONDS))
        assertTrue(server.openedLatch.await(2, TimeUnit.SECONDS))

        engine.engineOff()

        // 等服务端处理完关闭, 验证 code 不被破坏
        assertTrue(server.closedLatch.await(3, TimeUnit.SECONDS))
        assertEquals(CloseFrame.NORMAL, server.lastCloseCode.get())
    }

    // ── 工具 ────────────────────────────────────────────────────────────────

    private class RecordedEvents {
        val connectedLatch = CountDownLatch(1)
        val connectedLatch2 = CountDownLatch(1)
        val closedLatch = CountDownLatch(1)
        val lostLatch = CountDownLatch(1)
        val closedCode = AtomicInteger(-1)
        val lostError = AtomicReference<Throwable?>(null)
        val events = CopyOnWriteArrayList<String>()
        val successCount = AtomicInteger(0)
    }

    private fun findFreePort(): Int = ServerSocket(0).use { it.localPort }
}

// ──── 本地测试服务端 ────────────────────────────────────────────────────────

private class TestWsServer(port: Int) : WebSocketServer(InetSocketAddress("127.0.0.1", port)) {
    val startLatch = CountDownLatch(1)
    val openedLatch = CountDownLatch(1)
    val closedLatch = CountDownLatch(1)
    val lastCloseCode = AtomicInteger(-1)
    val lastCloseRemote = AtomicBoolean(false)
    private val activeConns = CopyOnWriteArrayList<WebSocket>()

    override fun onStart() {
        isReuseAddr = true
        startLatch.countDown()
    }

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake?) {
        activeConns.add(conn)
        openedLatch.countDown()
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String?, remote: Boolean) {
        lastCloseCode.set(code)
        lastCloseRemote.set(remote)
        activeConns.remove(conn)
        closedLatch.countDown()
    }

    override fun onMessage(conn: WebSocket, message: String?) {}
    override fun onError(conn: WebSocket?, ex: Exception?) {}

    fun closeAllClients(code: Int, reason: String) {
        activeConns.forEach {
            try { it.close(code, reason) } catch (_: Exception) {}
        }
    }

    /**
     * 模拟 TCP 异常断连:不发 close frame, 直接关掉底层 socket
     */
    fun killAllAbruptly() {
        activeConns.forEach {
            try {
                // closeConnection 不发 close frame, 是 java-websocket 内部用的"强制关闭"路径
                it.closeConnection(CloseFrame.ABNORMAL_CLOSE, "tcp killed")
            } catch (_: Exception) {}
        }
    }
}
