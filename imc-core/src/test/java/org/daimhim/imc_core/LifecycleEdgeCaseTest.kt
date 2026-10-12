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
import java.net.URI
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 1.1.17 review 修复:换下 / 终止的 client 迟到建连、Idle 下空转、放弃抢救残留状态、
 * 断线重复上报、看门狗解冻判到期、默认探活方式。
 */
class LifecycleEdgeCaseTest {

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

    private class StuckSched : ITimeoutScheduler {
        val starts = CopyOnWriteArrayList<Long>()
        private var cb: Callable<Void>? = null
        override fun start(time: Long) { starts.add(time) }
        override fun stop() {}
        override fun setCallback(call: Callable<Void>) { cb = call }
        fun fire() { cb!!.call() }
    }

    private class Server(port: Int) : WebSocketServer(InetSocketAddress("127.0.0.1", port)) {
        val started = CountDownLatch(1)
        val opens = AtomicInteger(0)
        override fun onStart() { started.countDown() }
        override fun onOpen(conn: WebSocket, handshake: ClientHandshake) { opens.incrementAndGet() }
        override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {}
        override fun onMessage(conn: WebSocket, message: String) {}
        override fun onError(conn: WebSocket?, ex: Exception) {}
    }

    private class FakeSurveillance(@Volatile var report: NetReport) : NetSurveillance {
        override fun start() {}
        override fun stop() {}
        override fun current(): NetReport = report
        override fun forceProbe(callback: (NetReport) -> Unit) { callback(report) }
        override fun currentProfile(): NetProbeProfile = NetProbeProfile.BALANCED
        override fun register(listener: NetReportListener) {}
        override fun unregister(listener: NetReportListener) {}
    }

    private fun report(recommend: ReconnectAction) = NetReport(
        snapshot = NetSnapshot.OFFLINE,
        lastProbe = null,
        overall = NetVerdict.OK,
        recommend = recommend,
        timestamp = 0L,
    )

    private fun freePort() = ServerSocket(0).use { it.localPort }

    private fun startServer(port: Int): Server {
        val server = Server(port)
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

    /** 首次解析卡住直到 [release],之后立即返回 127.0.0.1:模拟换下 / 登出时卡在 DNS 的连接尝试 */
    private class GatedDns {
        val gate = CountDownLatch(1)
        private val calls = AtomicInteger(0)
        val cache = DnsCache(resolver = {
            if (calls.getAndIncrement() == 0) gate.await(10, TimeUnit.SECONDS)
            arrayOf(InetAddress.getByAddress(it, byteArrayOf(127, 0, 0, 1)))
        })
        fun release() = gate.countDown()
    }

    private fun newEngine(
        url: String,
        sched: StuckSched = StuckSched(),
        dnsCache: DnsCache = DnsCache(),
        surveillance: NetSurveillance? = null,
    ): V2JavaWebEngine {
        val auto = ProgressiveAutoConnect.Builder()
            .setTimeoutScheduler(sched)
            .apply { surveillance?.let { surveillance(it) } }
            .build()
        return V2JavaWebEngine.Builder()
            .addHeartbeatMode(0, V2FixedHeartbeat.Builder().setCurHeartbeat(600).build())
            .addHeartbeatMode(1, V2FixedHeartbeat.Builder().setCurHeartbeat(600).build())
            .defHeartbeatMode(0)
            .setForegroundModes(0)
            .setAutoConnect(auto)
            .setDnsCache(dnsCache)
            .setKeyProvider { url }
            .build()
            .also { e -> toStop.add { e.engineOff() } }
    }

    // ── #1 换下 / 终止的 client 迟到建连 ────────────────────────────────

    @Test
    fun supersededClient_openingLate_isClosed_andDoesNotHijackEngine() {
        val port = freePort()
        val server = startServer(port)
        val dns = GatedDns()
        val url = "ws://imc.test:$port"
        val engine = newEngine(url, dnsCache = dns.cache)

        engine.engineOn(url)                           // A 卡在 DNS,close() 对它是 no-op
        Thread.sleep(200)
        engine.reconnectNow()                          // 换成 B,B 正常连上
        assertTrue(waitUntil { engine.engineState() == IEngineState.ENGINE_OPEN })
        assertEquals(1, server.opens.get())

        dns.release()                                  // A 迟到:建连成功后应立即自关
        assertTrue("A 应连上过服务端", waitUntil { server.opens.get() == 2 })
        assertTrue("A 不得留成僵尸连接", waitUntil { server.connections.size == 1 })
        Thread.sleep(300)
        assertEquals(1, server.connections.size)
        assertEquals(IEngineState.ENGINE_OPEN, engine.engineState())
        assertTrue("B 仍可写", engine.send("hi"))
    }

    @Test
    fun engineOffDuringDns_lateOpen_isClosed() {
        val port = freePort()
        val server = startServer(port)
        val dns = GatedDns()
        val url = "ws://imc.test:$port"
        val engine = newEngine(url, dnsCache = dns.cache)

        engine.engineOn(url)
        Thread.sleep(200)
        engine.engineOff()                             // 登出时连接尝试还卡在 DNS
        dns.release()

        assertTrue(waitUntil { server.opens.get() == 1 })
        assertTrue("登出后不得留在线连接", waitUntil { server.connections.isEmpty() })
        assertEquals(IEngineState.ENGINE_CLOSED, engine.engineState())
    }

    // ── #2 Idle / dispose 后不空转;复用引擎重新登录能恢复抢救 ─────────────

    @Test
    fun idleEngine_kicksDoNotStartAutoConnect() {
        val sched = StuckSched()
        val engine = newEngine("ws://127.0.0.1:${freePort()}", sched)
        engine.onNetworkChange(1)                      // 登录前的网络回调
        engine.makeConnection()
        assertTrue(sched.starts.isEmpty())
    }

    @Test
    fun afterEngineOff_kicksAreNoop_andEngineOnRevivesAutoConnect() {
        val sched = StuckSched()
        val url = "ws://127.0.0.1:${freePort()}"       // 没有服务端 → 连接失败 → 排退避
        val engine = newEngine(url, sched)
        engine.engineOn(url)
        assertTrue(waitUntil { sched.starts.isNotEmpty() })

        engine.engineOff()
        sched.starts.clear()
        engine.onNetworkChange(1)
        engine.makeConnection()
        Thread.sleep(200)
        assertTrue("登出后不得起抢救", sched.starts.isEmpty())

        engine.engineOn(url)                           // 复用同一个引擎重新登录
        assertTrue("autoConnect 应恢复工作", waitUntil { sched.starts.isNotEmpty() })
    }

    @Test
    fun disposedAutoConnect_ignoresPokes_untilRevived() {
        val sched = StuckSched()
        val auto = ProgressiveAutoConnect.Builder().setTimeoutScheduler(sched).build()
        auto.initAutoConnect(V2JavaWebEngine.WebSocketClientImpl(URI("ws://localhost:1")))
        auto.dispose()

        auto.resetStartAutoConnect()
        auto.startAutoConnect()
        auto.abnormalDisconnectionAndAutomaticReconnection()
        assertFalse(auto.onResume())
        assertTrue(sched.starts.isEmpty())

        auto.revive()
        auto.startAutoConnect()
        assertEquals(1, sched.starts.size)
        auto.dispose()
    }

    // ── #3 放弃抢救要清状态 ──────────────────────────────────────────────

    @Test
    fun waitUserDuringInFlightAttempt_clearsState() {
        val sched = StuckSched()
        val surv = FakeSurveillance(report(ReconnectAction.IMMEDIATE))
        val auto = ProgressiveAutoConnect.Builder().setTimeoutScheduler(sched).surveillance(surv).build()
        toStop.add { auto.dispose() }
        auto.initAutoConnect(V2JavaWebEngine.WebSocketClientImpl(URI("ws://localhost:1")))
        auto.setReconnector { }

        auto.startAutoConnect()
        sched.fire()                                   // attempt 在途
        assertTrue(auto.isActive())

        surv.report = report(ReconnectAction.WAIT_USER)
        auto.abnormalDisconnectionAndAutomaticReconnection()

        assertFalse("WAIT_USER 放弃后不得残留在途标记", auto.isActive())
        assertTrue(auto.status().let { !it.isConnecting && !it.isAutoConnectActive })
    }

    @Test
    fun foreground_whileReconnectingWithoutPendingRetry_replaces() {
        val port = freePort()
        val sched = StuckSched()
        val surv = FakeSurveillance(report(ReconnectAction.WAIT_USER))
        val url = "ws://127.0.0.1:$port"
        val engine = newEngine(url, sched, surveillance = surv)
        engine.engineOn(url)                           // 连接失败 + WAIT_USER → 放弃抢救
        assertTrue(waitUntil { engine.engineState() == IEngineState.ENGINE_FAILED })
        assertTrue(waitUntil { events.any { it is ImcEvent.AutoConnectAborted && it.reason == "WAIT_USER" } })

        val server = startServer(port)
        surv.report = report(ReconnectAction.IMMEDIATE)
        engine.onChangeMode(1)
        engine.onChangeMode(0)

        assertEquals(
            listOf(ImcEvent.FastResume.Action.REPLACE),
            events.filterIsInstance<ImcEvent.FastResume>().map { it.action },
        )
        assertTrue(waitUntil { engine.engineState() == IEngineState.ENGINE_OPEN })
        assertEquals(1, server.opens.get())
    }

    // ── #4 默认探活走心跳 ────────────────────────────────────────────────

    @Test
    fun defaultProbe_isHeartbeat() {
        assertEquals(FastResumePolicy.Probe.HEARTBEAT, FastResumePolicy().probe)
    }

    // ── #5 看门狗按注入时钟在解冻后及时判到期 ───────────────────────────

    @Test
    fun watchdog_withWaitSlice_firesSoonAfterClockJump() {
        val offset = AtomicLong(0)
        val clock = { System.nanoTime() / 1_000_000L + offset.get() }
        val rrf = RapidResponseForceV4(clock = clock, maxWaitSliceMs = 100)
        val fired = AtomicBoolean(false)
        rrf.register("w", 10_000) { fired.set(true) }
        Thread.sleep(50)
        offset.addAndGet(20_000)                       // 设备深睡 20s:wait 不计时,注入时钟走了
        assertTrue("应在一个 slice 内判到期", waitUntil(1_000) { fired.get() })
        rrf.clear()
    }

    // ── #7 一次死亡只上报一次 ────────────────────────────────────────────

    @Test
    fun connectFailure_reportedOnce() {
        val sched = StuckSched()
        val url = "ws://127.0.0.1:${freePort()}"
        val engine = newEngine(url, sched)
        engine.engineOn(url)
        assertTrue(waitUntil { events.any { it is ImcEvent.ConnectionClosed } })
        Thread.sleep(200)

        assertEquals(1, events.count { it is ImcEvent.ConnectionLost })
        assertEquals(1, events.count { it is ImcEvent.ConnectionClosed })
        assertEquals(1, sched.starts.size)
    }
}
