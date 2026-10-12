package org.daimhim.imc_core

import org.java_websocket.WebSocket
import org.java_websocket.framing.Framedata
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
import java.net.URI
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 1.1.17 快速恢复(回前台 / reconnectNow)+ DNS 失败地址轮换。
 */
class FastResumeTest {

    // ── 公共基建 ──────────────────────────────────────────────────────────

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

    /** 可拨快的时钟:真实流逝 + 偏移(模拟后台冻结 / 设备休眠) */
    private class JumpClock {
        val offset = AtomicLong(0)
        fun now(): Long = System.nanoTime() / 1_000_000L + offset.get()
        fun jump(ms: Long) { offset.addAndGet(ms) }
    }

    /** 不自动触发的调度器:模拟后台被押后的闹钟;fire() 手动触发 */
    private class StuckSched : ITimeoutScheduler {
        val starts = CopyOnWriteArrayList<Long>()
        private var cb: Callable<Void>? = null
        override fun start(time: Long) { starts.add(time) }
        override fun stop() {}
        override fun setCallback(call: Callable<Void>) { cb = call }
        fun fire() { cb!!.call() }
    }

    private open class CountingServer(port: Int) : WebSocketServer(InetSocketAddress("127.0.0.1", port)) {
        val started = CountDownLatch(1)
        val opens = AtomicInteger(0)
        override fun onStart() { started.countDown() }
        override fun onOpen(conn: WebSocket, handshake: ClientHandshake) { opens.incrementAndGet() }
        override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {}
        override fun onMessage(conn: WebSocket, message: String) {}
        override fun onError(conn: WebSocket?, ex: Exception) {}
    }

    /** 吞 ping 不回 pong:模拟后台期间对端已失联的半开连接 */
    private class PingSwallowServer(port: Int) : CountingServer(port) {
        override fun onWebsocketPing(conn: WebSocket?, f: Framedata?) {}
    }

    private fun freePort() = ServerSocket(0).use { it.localPort }

    private fun <S : CountingServer> startServer(server: S): S {
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

    private fun newEngine(
        port: Int,
        clock: JumpClock,
        policy: FastResumePolicy,
        sched: ITimeoutScheduler = StuckSched(),
    ): V2JavaWebEngine {
        val auto = ProgressiveAutoConnect.Builder()
            .setTimeoutScheduler(sched)
            .setElapsedClock(clock::now)
            .build()
        return V2JavaWebEngine.Builder()
            .addHeartbeatMode(FG, V2FixedHeartbeat.Builder().setCurHeartbeat(600).build())
            .addHeartbeatMode(BG, V2FixedHeartbeat.Builder().setCurHeartbeat(600).build())
            .defHeartbeatMode(FG)
            .setForegroundModes(FG)
            .setFastResumePolicy(policy)
            .setElapsedClock(clock::now)
            .setAutoConnect(auto)
            .setKeyProvider { "ws://127.0.0.1:$port" }
            .build()
            .also { e -> toStop.add { e.engineOff() } }
    }

    private fun V2JavaWebEngine.backgroundThenForeground() {
        onChangeMode(BG)
        onChangeMode(FG)
    }

    private fun fastResumeActions() =
        events.filterIsInstance<ImcEvent.FastResume>().map { it.action }

    private companion object {
        const val FG = 0
        const val BG = 1
    }

    // ── Connected ────────────────────────────────────────────────────────

    @Test
    fun connected_silentLongerThanStaleAfter_isReplacedWithoutProbe() {
        val port = freePort()
        val server = startServer(CountingServer(port))
        val clock = JumpClock()
        val engine = newEngine(port, clock, FastResumePolicy(staleAfterMs = 50_000))
        engine.engineOn("ws://127.0.0.1:$port")
        assertTrue(waitUntil { engine.engineState() == IEngineState.ENGINE_OPEN })

        clock.jump(10 * 60_000L)                      // 后台冻结 10 分钟
        engine.backgroundThenForeground()

        assertEquals(listOf(ImcEvent.FastResume.Action.REPLACE), fastResumeActions())
        assertTrue("应立即重建", waitUntil { server.opens.get() == 2 })
        assertTrue(waitUntil { engine.engineState() == IEngineState.ENGINE_OPEN })
    }

    @Test
    fun connected_probeAnswered_keepsConnection() {
        val port = freePort()
        val server = startServer(CountingServer(port))
        val engine = newEngine(port, JumpClock(), FastResumePolicy(probeTimeoutMs = 500))
        engine.engineOn("ws://127.0.0.1:$port")
        assertTrue(waitUntil { engine.engineState() == IEngineState.ENGINE_OPEN })

        engine.backgroundThenForeground()
        Thread.sleep(1_000)                           // 越过探活超时

        assertEquals(listOf(ImcEvent.FastResume.Action.PROBE), fastResumeActions())
        assertTrue(events.none { it is ImcEvent.FastResumeProbeFailed })
        assertEquals("pong 回来了,不得重建", 1, server.opens.get())
    }

    @Test
    fun connected_probeUnanswered_isReplaced() {
        val port = freePort()
        val server = startServer(PingSwallowServer(port))
        val engine = newEngine(port, JumpClock(), FastResumePolicy(probeTimeoutMs = 500))
        engine.engineOn("ws://127.0.0.1:$port")
        assertTrue(waitUntil { engine.engineState() == IEngineState.ENGINE_OPEN })

        engine.backgroundThenForeground()

        assertTrue("探活超时应判死", waitUntil { events.any { it is ImcEvent.FastResumeProbeFailed } })
        assertTrue("应重建", waitUntil { server.opens.get() == 2 })
    }

    @Test
    fun connected_probeNone_withinStaleAfter_keeps() {
        val port = freePort()
        val server = startServer(PingSwallowServer(port))
        val engine = newEngine(
            port, JumpClock(),
            FastResumePolicy(staleAfterMs = 50_000, probe = FastResumePolicy.Probe.NONE),
        )
        engine.engineOn("ws://127.0.0.1:$port")
        assertTrue(waitUntil { engine.engineState() == IEngineState.ENGINE_OPEN })

        engine.backgroundThenForeground()
        Thread.sleep(300)

        assertEquals(listOf(ImcEvent.FastResume.Action.KEEP), fastResumeActions())
        assertEquals(1, server.opens.get())
    }

    // ── Reconnecting(等退避)───────────────────────────────────────────

    @Test
    fun waitingBackoff_firesImmediately_withoutScheduler() {
        val port = freePort()
        val sched = StuckSched()
        val engine = newEngine(port, JumpClock(), FastResumePolicy(), sched)
        engine.engineOn("ws://127.0.0.1:$port")       // 没有 server → 连接失败 → 排退避
        assertTrue("应进入等退避", waitUntil { sched.starts.isNotEmpty() })
        assertTrue(waitUntil { engine.engineState() == IEngineState.ENGINE_FAILED })

        val server = startServer(CountingServer(port)) // 服务恢复,但闹钟被系统押着不来
        engine.backgroundThenForeground()

        assertEquals(listOf(ImcEvent.FastResume.Action.KICK_AUTO_CONNECT), fastResumeActions())
        assertTrue("不经调度器立即重连", waitUntil { engine.engineState() == IEngineState.ENGINE_OPEN })
        assertEquals(1, server.opens.get())
        assertEquals("调度器只排过断线那一次", 1, sched.starts.size)
    }

    // ── reconnectNow / 去抖 ──────────────────────────────────────────────

    @Test
    fun reconnectNow_replacesHealthyConnection_andKeepsResendPool() {
        val port = freePort()
        val server = startServer(CountingServer(port))
        val engine = newEngine(port, JumpClock(), FastResumePolicy())
        engine.engineOn("ws://127.0.0.1:$port")
        assertTrue(waitUntil { engine.engineState() == IEngineState.ENGINE_OPEN })

        engine.reconnectNow()

        assertTrue(waitUntil { server.opens.get() == 2 })
        assertTrue(waitUntil { engine.engineState() == IEngineState.ENGINE_OPEN })
        assertTrue("不得结束会话", events.none { it is ImcEvent.EngineStopped })
    }

    @Test
    fun foreground_isDebounced() {
        val port = freePort()
        startServer(CountingServer(port))
        val engine = newEngine(port, JumpClock(), FastResumePolicy(probe = FastResumePolicy.Probe.NONE))
        engine.engineOn("ws://127.0.0.1:$port")
        assertTrue(waitUntil { engine.engineState() == IEngineState.ENGINE_OPEN })

        engine.backgroundThenForeground()
        engine.backgroundThenForeground()             // Activity 来回切

        assertEquals(1, fastResumeActions().size)
    }

    @Test
    fun foregroundToForeground_doesNotTrigger() {
        val port = freePort()
        startServer(CountingServer(port))
        val engine = newEngine(port, JumpClock(), FastResumePolicy())
        engine.engineOn("ws://127.0.0.1:$port")
        assertTrue(waitUntil { engine.engineState() == IEngineState.ENGINE_OPEN })

        engine.onChangeMode(FG)                       // 默认档就是前台
        assertTrue(fastResumeActions().isEmpty())
    }

    @Test
    fun idleEngine_foregroundIsNoop() {
        val engine = newEngine(freePort(), JumpClock(), FastResumePolicy())
        engine.backgroundThenForeground()
        assertEquals(listOf(ImcEvent.FastResume.Action.IDLE), fastResumeActions())
        assertEquals(IEngineState.ENGINE_CLOSED, engine.engineState())
    }

    // ── ProgressiveAutoConnect.onResume ─────────────────────────────────

    @Test
    fun onResume_resetsBackoff() {
        val sched = StuckSched()
        val auto = ProgressiveAutoConnect.Builder()
            .initReconnectDelay(1000, TimeUnit.MILLISECONDS)
            .maxReconnectDelay(60_000, TimeUnit.MILLISECONDS)
            .setTimeoutScheduler(sched)
            .build()
        toStop.add { auto.dispose() }
        auto.initAutoConnect(V2JavaWebEngine.WebSocketClientImpl(URI("ws://localhost:1")))
        val reasons = CopyOnWriteArrayList<RestartReason>()
        auto.setReconnector { reasons.add(it) }

        auto.startAutoConnect()
        repeat(4) {
            sched.fire()
            auto.abnormalDisconnectionAndAutomaticReconnection()
        }
        assertEquals(32_000L, sched.starts.last())   // 后台攒下的大退避

        assertTrue("等退避中应立即发起", auto.onResume())
        assertEquals(5, reasons.size)

        auto.abnormalDisconnectionAndAutomaticReconnection()   // 回前台这一次也失败了
        assertEquals("退避应从起点重来,而不是 64s", 2_000L, sched.starts.last())
    }

    @Test
    fun onResume_whenIdle_doesNothing() {
        val sched = StuckSched()
        val auto = ProgressiveAutoConnect.Builder().setTimeoutScheduler(sched).build()
        toStop.add { auto.dispose() }
        assertFalse(auto.onResume())
        assertTrue(sched.starts.isEmpty())
    }

    // ── DnsCache.markFailed(#4)──────────────────────────────────────────

    @Test
    fun dnsCache_failedAddressIsTriedLast_untilCooldownExpires() {
        val a = InetAddress.getByAddress("h", byteArrayOf(10, 0, 0, 1))
        val b = InetAddress.getByAddress("h", byteArrayOf(10, 0, 0, 2))
        var now = 0L
        val cache = DnsCache(resolver = { arrayOf(a, b) }, clock = { now })

        assertEquals(listOf(a, b), cache.resolve("h"))
        cache.markFailed(a, cooldownMs = 1_000)
        assertEquals(listOf(b, a), cache.resolve("h"))

        now = 1_000
        assertEquals("冷却过期后恢复原顺序", listOf(a, b), cache.resolve("h"))
    }

    @Test
    fun dnsCache_allFailed_earliestThawFirst() {
        val a = InetAddress.getByAddress("h", byteArrayOf(10, 0, 0, 1))
        val b = InetAddress.getByAddress("h", byteArrayOf(10, 0, 0, 2))
        val cache = DnsCache(resolver = { arrayOf(a, b) }, clock = { 0L })
        cache.markFailed(a, cooldownMs = 2_000)
        cache.markFailed(b, cooldownMs = 1_000)
        assertEquals(listOf(b, a), cache.resolve("h"))
    }
}
