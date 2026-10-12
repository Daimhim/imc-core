package org.daimhim.imc_core

import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.UnknownHostException
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 1.1.17 埋点补齐:状态迁移、外部催重连、旧 client 迟到回调、DNS 回退 / 冷却、配置错误与回调异常。
 */
class EventCoverageTest {

    private val events = CopyOnWriteArrayList<ImcEvent>()
    private val sink = ImcEventSink { events.add(it) }
    private val toStop = ArrayList<() -> Unit>()

    @Before
    fun setUp() { ImcEvents.subscribe(sink) }

    @After
    fun tearDown() {
        ImcEvents.unsubscribe(sink)
        toStop.reversed().forEach { try { it() } catch (_: Exception) {} }
    }

    private class NoopSched : ITimeoutScheduler {
        override fun start(time: Long) {}
        override fun stop() {}
        override fun setCallback(call: Callable<Void>) {}
    }

    private class CountingServer(port: Int) : WebSocketServer(InetSocketAddress("127.0.0.1", port)) {
        val started = CountDownLatch(1)
        val opens = AtomicInteger(0)
        override fun onStart() { started.countDown() }
        override fun onOpen(conn: WebSocket, handshake: ClientHandshake) { opens.incrementAndGet() }
        override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {}
        override fun onMessage(conn: WebSocket, message: String) {}
        override fun onError(conn: WebSocket?, ex: Exception) {}
    }

    private fun freePort() = ServerSocket(0).use { it.localPort }

    private fun startServer(port: Int): CountingServer {
        val server = CountingServer(port)
        server.isReuseAddr = true
        server.start()
        toStop.add { server.stop(500) }
        assertTrue(server.started.await(3, TimeUnit.SECONDS))
        return server
    }

    private fun waitUntil(timeoutMs: Long = 5_000, cond: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return true
            Thread.sleep(20)
        }
        return cond()
    }

    private fun newEngine(keyProvider: IKeyProvider? = null): V2JavaWebEngine {
        val auto = ProgressiveAutoConnect.Builder().setTimeoutScheduler(NoopSched()).build()
        return V2JavaWebEngine.Builder()
            .addHeartbeatMode(0, V2FixedHeartbeat.Builder().setCurHeartbeat(600).build())
            .defHeartbeatMode(0)
            .setAutoConnect(auto)
            .apply { keyProvider?.let { setKeyProvider(it) } }
            .build()
            .also { e -> toStop.add { e.engineOff() } }
    }

    private inline fun <reified T : ImcEvent> of(): List<T> = events.filterIsInstance<T>()

    @Test
    fun engineOn_fromIdle_emitsFullTransitionChain() {
        val port = freePort()
        startServer(port)
        val engine = newEngine()
        engine.engineOn("ws://127.0.0.1:$port")
        assertTrue(waitUntil { engine.engineState() == IEngineState.ENGINE_OPEN })

        val t = of<ImcEvent.EngineStateTransition>()
        assertEquals("Idle", t[0].from)
        assertTrue(t[0].to.startsWith("Connecting"))
        assertEquals("engineOn", t[0].reason)
        assertTrue("onOpen 的 from 应是真实前态", t[1].from.startsWith("Connecting"))
        assertTrue(t[1].to.startsWith("Connected"))
    }

    @Test
    fun reconnectKicked_reportsDisposition() {
        val port = freePort()
        startServer(port)
        val engine = newEngine()
        engine.makeConnection()                       // 未启动
        engine.engineOn("ws://127.0.0.1:$port")
        assertTrue(waitUntil { engine.engineState() == IEngineState.ENGINE_OPEN })
        engine.onNetworkChange(1)                     // 健康

        assertEquals(
            listOf(
                "makeConnection" to ImcEvent.ReconnectKicked.Action.IGNORED_IDLE,
                "network" to ImcEvent.ReconnectKicked.Action.HEALTHY,
            ),
            of<ImcEvent.ReconnectKicked>().map { it.trigger to it.action },
        )
    }

    @Test
    fun replacedClient_closeIsReportedAsStale_notAsConnectionClosed() {
        val port = freePort()
        val server = startServer(port)
        val engine = newEngine()
        engine.engineOn("ws://127.0.0.1:$port")
        assertTrue(waitUntil { engine.engineState() == IEngineState.ENGINE_OPEN })

        engine.reconnectNow()

        assertTrue(waitUntil { server.opens.get() == 2 && engine.engineState() == IEngineState.ENGINE_OPEN })
        assertTrue(waitUntil {
            of<ImcEvent.StaleClientCallback>().any { it.callback == "onClose" && !it.terminated }
        })
        assertTrue("旧 client 的收场不得冒充当前连接断开", of<ImcEvent.ConnectionClosed>().isEmpty())
    }

    @Test
    fun keyProviderThrows_reportedAndFallsBackToLastKey() {
        val port = freePort()
        val server = startServer(port)
        val engine = newEngine(keyProvider = { throw IllegalStateException("token 续期失败") })
        engine.engineOn("ws://127.0.0.1:$port")
        assertTrue(waitUntil { engine.engineState() == IEngineState.ENGINE_OPEN })

        engine.reconnectNow()

        assertTrue(waitUntil { server.opens.get() == 2 })
        assertTrue(of<ImcEvent.InternalError>().any { it.site == "IKeyProvider.provide" })
    }

    @Test
    fun onChangeMode_unknownMode_reportsConfigError() {
        val engine = newEngine()
        engine.onChangeMode(42)
        val err = of<ImcEvent.InternalError>().single()
        assertEquals("V2JavaWebEngine.onChangeMode", err.site)
        assertTrue(err.message!!.contains("42"))
    }

    @Test
    fun socketListenerThrows_reportedAsInternalError() {
        val m = IMCListenerManager()
        m.addIMCSocketListener(0, object : V2IMCSocketListener {
            override fun onMessage(iEngine: IEngine, text: String): Boolean = error("boom")
            
        })
        m.onMessage(newEngine(), "x")
        assertEquals("V2IMCSocketListener.onMessage", of<ImcEvent.InternalError>().single().site)
    }

    @Test
    fun dnsCache_staleFallbackAndCooldown_areReported() {
        val a = InetAddress.getByAddress("h", byteArrayOf(10, 0, 0, 1))
        var now = 0L
        var fail = false
        val cache = DnsCache(
            ttlMs = 1_000,
            resolver = { if (fail) throw UnknownHostException("h") else arrayOf(a) },
            clock = { now },
        )
        cache.resolve("h")
        assertTrue(of<ImcEvent.DnsStaleFallback>().isEmpty())

        fail = true
        now = 1_500
        assertEquals(listOf(a), cache.resolve("h"))
        val fb = of<ImcEvent.DnsStaleFallback>().single()
        assertEquals("h", fb.host)
        assertEquals(500L, fb.staleAgeMs)

        cache.markFailed(a, cooldownMs = 3_000)
        val cooled = of<ImcEvent.DnsAddressCooled>().single()
        assertEquals("10.0.0.1", cooled.address)
        assertEquals(3_000L, cooled.cooldownMs)
        assertFalse(of<ImcEvent.InternalError>().any())
    }
}
