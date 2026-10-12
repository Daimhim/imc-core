package org.daimhim.imc_core

import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 1.1.17 重连路径收敛回归:
 *  - #6 stopAutoConnect(健康 kick 的落点)不再抹掉 A8 换网检测基线
 *  - R1 连接尝试在途时换网,也要换掉在途尝试(历史两条分支都落空)
 *  - #9 换 client 统一走 replaceClient:强制重建只起一条新连接,旧 client 收场不唤醒 autoConnect
 *  - 内部重启不得把已 engineOff 的引擎拉起来
 */
class ReconnectPathUnificationTest {

    // ── 公共基建 ──────────────────────────────────────────────────────────

    private class RecSched : ITimeoutScheduler {
        val starts = CopyOnWriteArrayList<Long>()
        private var cb: Callable<Void>? = null
        override fun start(time: Long) { starts.add(time) }
        override fun stop() {}
        override fun setCallback(call: Callable<Void>) { cb = call }
        fun fire() { cb!!.call() }
    }

    private class FakeSurveillance(initial: NetReport) : NetSurveillance {
        private val listeners = CopyOnWriteArrayList<NetReportListener>()
        @Volatile var report: NetReport = initial
        override fun start() {}
        override fun stop() {}
        override fun current(): NetReport = report
        override fun forceProbe(callback: (NetReport) -> Unit) {}
        override fun currentProfile(): NetProbeProfile = NetProbeProfile.BALANCED
        override fun register(listener: NetReportListener) { listeners.add(listener) }
        override fun unregister(listener: NetReportListener) { listeners.remove(listener) }
        fun push(next: NetReport) {
            report = next
            listeners.forEach { it.onReport(next) }
        }
    }

    private fun report(vararg transports: NetTransport) = NetReport(
        snapshot = NetSnapshot(
            verdict = NetVerdict.OK, capabilities = emptySet(), transports = transports.toSet(),
            linkUpKbps = 0, linkDownKbps = 0, signalStrengthDbm = null, timestamp = 0L,
        ),
        lastProbe = null,
        overall = NetVerdict.OK,
        recommend = ReconnectAction.BACKOFF_NORMAL,
        timestamp = 0L,
    )

    private val events = CopyOnWriteArrayList<ImcEvent>()
    private val sink = ImcEventSink { events.add(it) }
    private val toStop = ArrayList<() -> Unit>()

    @Before
    fun setUp() { ImcEvents.subscribe(sink) }

    @After
    fun tearDown() {
        ImcEvents.unsubscribe(sink)
        toStop.forEach { try { it() } catch (_: Exception) {} }
    }

    private fun transportsChangedEvents() = events.count {
        it is ImcEvent.AutoConnectAborted && it.reason.startsWith("transports-changed")
    }

    private fun newAuto(sched: RecSched, surv: FakeSurveillance): ProgressiveAutoConnect =
        ProgressiveAutoConnect.Builder()
            .setTimeoutScheduler(sched)
            .surveillance(surv)
            .build()
            .also { a -> toStop.add { a.dispose() } }

    // ── #6 ───────────────────────────────────────────────────────────────

    @Test
    fun stopAutoConnect_keepsTransportsBaseline() {
        val surv = FakeSurveillance(report(NetTransport.WIFI))
        val auto = newAuto(RecSched(), surv)
        surv.push(report(NetTransport.WIFI))          // 建立基线

        // 健康时 makeConnection / onNetworkChange 的历史落点:LEGACY_START → stopAutoConnect
        auto.stopAutoConnect()
        surv.push(report(NetTransport.CELLULAR))      // 紧随其后的换网

        assertEquals("换网必须被 A8 识别(修前基线被 stop 抹掉 → 漏检)", 1, transportsChangedEvents())
    }

    // ── R1 ───────────────────────────────────────────────────────────────

    @Test
    fun transportsChanged_whileAttemptInFlight_replacesAttempt() {
        val sched = RecSched()
        val surv = FakeSurveillance(report(NetTransport.WIFI))
        val auto = newAuto(sched, surv)
        val reasons = CopyOnWriteArrayList<RestartReason>()
        auto.initAutoConnect(V2JavaWebEngine.WebSocketClientImpl(URI("ws://localhost:1")))
        auto.setReconnector { reasons.add(it) }
        surv.push(report(NetTransport.WIFI))

        auto.startAutoConnect()
        sched.fire()                                  // attempt 在途
        assertEquals(listOf(RestartReason.SCHEDULED), reasons.toList())

        surv.push(report(NetTransport.CELLULAR))
        assertEquals(
            "在途尝试遇换网必须换掉(R1)",
            listOf(RestartReason.SCHEDULED, RestartReason.TRANSPORT_CHANGED), reasons.toList(),
        )

        surv.push(report())                           // 换到无网:换了也连不上,不踢
        assertEquals(2, reasons.size)
    }

    // ── #9 + engineOff 竞态(本地 server 集成)─────────────────────────────

    /** 记录 abnormal 调用次数,并留住引擎注入的 reconnector 供测试直接触发 */
    private class ProbeAutoConnect : IAutoConnect {
        val abnormal = AtomicInteger(0)
        @Volatile var injected: IReconnector? = null
        override fun initAutoConnect(webSocketClient: V2JavaWebEngine.WebSocketClientImpl) {}
        override fun abnormalDisconnectionAndAutomaticReconnection() { abnormal.incrementAndGet() }
        override fun resetStartAutoConnect() {}
        override fun startAutoConnect() {}
        override fun stopAutoConnect() {}
        override fun setReconnector(reconnector: IReconnector) { injected = reconnector }
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

    private fun waitUntil(timeoutMs: Long = 5_000, cond: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return true
            Thread.sleep(20)
        }
        return cond()
    }

    private fun connectedEngine(): Triple<V2JavaWebEngine, ProbeAutoConnect, CountingServer> {
        val port = ServerSocket(0).use { it.localPort }
        val server = CountingServer(port).apply { isReuseAddr = true; start() }
        toStop.add { server.stop(500) }
        assertTrue(server.started.await(3, TimeUnit.SECONDS))
        val auto = ProbeAutoConnect()
        val engine = V2JavaWebEngine.Builder()
            .addHeartbeatMode(0, V2FixedHeartbeat.Builder().setCurHeartbeat(600).build())
            .setAutoConnect(auto)
            // 有 KeyProvider 时 engineOff 后仍能拿到 key —— 正是「内部重启拉起已关引擎」的竞态前提
            .setKeyProvider { "ws://127.0.0.1:$port" }
            .build()
        toStop.add { engine.engineOff() }
        engine.engineOn("ws://127.0.0.1:$port")
        assertTrue("应连上", waitUntil { engine.engineState() == IEngineState.ENGINE_OPEN })
        return Triple(engine, auto, server)
    }

    @Test
    fun forcedRebuild_opensExactlyOneNewConnection_andOldCloseIsIgnored() {
        val (engine, auto, server) = connectedEngine()
        assertEquals(1, server.opens.get())

        auto.injected!!.restart(RestartReason.TRANSPORT_CHANGED)

        assertTrue("应建立新连接", waitUntil { server.opens.get() >= 2 })
        assertTrue(waitUntil { engine.engineState() == IEngineState.ENGINE_OPEN })
        Thread.sleep(500)                             // 给旧 client 的收场回调留时间
        assertEquals("只应起一条新连接(#9 修前:另起线程 + autoConnect 两条路径)", 2, server.opens.get())
        assertEquals("被换下的旧 client 收场不得唤醒 autoConnect", 0, auto.abnormal.get())
        assertEquals(IEngineState.ENGINE_OPEN, engine.engineState())
    }

    @Test
    fun internalRestart_afterEngineOff_doesNotResurrectEngine() {
        val (engine, auto, server) = connectedEngine()
        val reconnector = auto.injected!!
        engine.engineOff()

        reconnector.restart(RestartReason.SCHEDULED)
        Thread.sleep(500)

        assertEquals(IEngineState.ENGINE_CLOSED, engine.engineState())
        assertEquals("engineOff 后不得被内部重启拉起", 1, server.opens.get())
    }
}
