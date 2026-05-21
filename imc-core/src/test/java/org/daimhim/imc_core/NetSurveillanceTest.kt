package org.daimhim.imc_core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * 编排层 [DefaultNetSurveillance] 的融合规则与决策表测试
 *
 * 用 [FakeNetStateMonitor] / [FakeNetProber] 替代真实平台 API,避免依赖网络
 */
class NetSurveillanceTest {

    private fun snap(
        verdict: NetVerdict,
        caps: Set<NetCap> = emptySet(),
        transports: Set<NetTransport> = emptySet()
    ) = NetSnapshot(
        verdict = verdict,
        capabilities = caps,
        transports = transports,
        linkUpKbps = 0, linkDownKbps = 0, signalStrengthDbm = null,
        timestamp = System.currentTimeMillis()
    )

    private fun stage(ok: Boolean, ms: Long = 10L, err: String? = null) =
        ProbeStage(ok, ms, err)

    private fun probeReport(
        verdict: ProbeVerdict,
        dnsOk: Boolean = true,
        tcpOk: Boolean = true,
        tlsOk: Boolean? = null,
        httpOk: Boolean? = null,
        publicOk: Boolean? = null
    ) = ProbeReport(
        target = ProbeTarget("api.example.com", 443, true),
        dns = stage(dnsOk),
        tcp = stage(tcpOk),
        tls = tlsOk?.let { stage(it) },
        httpHealth = httpOk?.let { stage(it) },
        publicRef = publicOk?.let { stage(it) },
        verdict = verdict,
        timestamp = System.currentTimeMillis()
    )

    private fun buildSurveillance(
        monitor: NetStateMonitor,
        prober: NetProber? = null
    ) = DefaultNetSurveillance.Builder()
        .monitor(monitor)
        .apply { prober?.let { prober(it) } }
        .probeTarget(ProbeTarget("api.example.com", 443, true))
        .debounceMs(0L)
        .minProbeIntervalMs(0L)
        .build()

    // ── 融合规则:声明层强信号优先 ────────────────────────────────────────────

    @Test
    fun merge_blocked_wins_over_anyProbe() {
        val monitor = FakeNetStateMonitor(snap(NetVerdict.BLOCKED))
        val prober = FakeNetProber(probeReport(ProbeVerdict.SERVER_REACHABLE))
        val sv = buildSurveillance(monitor, prober)

        sv.start()
        assertEquals(NetVerdict.BLOCKED, sv.current().overall)
        assertEquals(ReconnectAction.WAIT_USER, sv.current().recommend)
        sv.stop()
    }

    @Test
    fun merge_offline_snapshot_wins_over_serverReachable_probe() {
        val monitor = FakeNetStateMonitor(snap(NetVerdict.OFFLINE))
        val prober = FakeNetProber(probeReport(ProbeVerdict.SERVER_REACHABLE))
        val sv = buildSurveillance(monitor, prober)

        sv.start()
        assertEquals(NetVerdict.OFFLINE, sv.current().overall)
        assertEquals(ReconnectAction.BACKOFF_LONG, sv.current().recommend)
        sv.stop()
    }

    @Test
    fun merge_captivePortal_wins() {
        val monitor = FakeNetStateMonitor(snap(NetVerdict.CAPTIVE_PORTAL))
        val sv = buildSurveillance(monitor)

        sv.start()
        assertEquals(NetVerdict.CAPTIVE_PORTAL, sv.current().overall)
        assertEquals(ReconnectAction.WAIT_USER, sv.current().recommend)
        sv.stop()
    }

    // ── 融合规则:探测层精化判定 ────────────────────────────────────────────

    @Test
    fun probe_serverDown_overrides_okSnapshot_toConnectedNotValidated() {
        val monitor = FakeNetStateMonitor(snap(NetVerdict.OK))
        val prober = FakeNetProber(
            probeReport(ProbeVerdict.SERVER_DOWN, tcpOk = false, publicOk = true)
        )
        val sv = buildSurveillance(monitor, prober)

        sv.start()
        waitForProbe(sv)

        assertEquals(NetVerdict.CONNECTED_NOT_VALIDATED, sv.current().overall)
        assertEquals(ReconnectAction.FAILOVER, sv.current().recommend)
        sv.stop()
    }

    @Test
    fun probe_dnsFailure_with_publicOk_recommends_backoffNormal() {
        val monitor = FakeNetStateMonitor(snap(NetVerdict.OK))
        val prober = FakeNetProber(
            probeReport(ProbeVerdict.DNS_FAILURE, dnsOk = false, tcpOk = false, publicOk = true)
        )
        val sv = buildSurveillance(monitor, prober)

        sv.start()
        waitForProbe(sv)

        assertEquals(NetVerdict.CONNECTED_NOT_VALIDATED, sv.current().overall)
        // publicOk + !tcpOk → FAILOVER (建议切备用接入)
        assertEquals(ReconnectAction.FAILOVER, sv.current().recommend)
        sv.stop()
    }

    @Test
    fun probe_noInternet_promotes_to_offline() {
        val monitor = FakeNetStateMonitor(snap(NetVerdict.OK))
        val prober = FakeNetProber(
            probeReport(ProbeVerdict.NO_INTERNET, tcpOk = false, publicOk = false)
        )
        val sv = buildSurveillance(monitor, prober)

        sv.start()
        waitForProbe(sv)

        assertEquals(NetVerdict.OFFLINE, sv.current().overall)
        assertEquals(ReconnectAction.BACKOFF_LONG, sv.current().recommend)
        sv.stop()
    }

    @Test
    fun probe_serverReachable_with_okSnapshot_recommendsImmediate() {
        val monitor = FakeNetStateMonitor(snap(NetVerdict.OK))
        val prober = FakeNetProber(probeReport(ProbeVerdict.SERVER_REACHABLE))
        val sv = buildSurveillance(monitor, prober)

        sv.start()
        waitForProbe(sv)

        assertEquals(NetVerdict.OK, sv.current().overall)
        assertEquals(ReconnectAction.IMMEDIATE, sv.current().recommend)
        sv.stop()
    }

    @Test
    fun probe_timeout_does_not_overrideSnapshot() {
        val monitor = FakeNetStateMonitor(snap(NetVerdict.CONGESTED))
        val prober = FakeNetProber(probeReport(ProbeVerdict.PROBE_TIMEOUT))
        val sv = buildSurveillance(monitor, prober)

        sv.start()
        waitForProbe(sv)

        assertEquals(NetVerdict.CONGESTED, sv.current().overall)
        assertEquals(ReconnectAction.BACKOFF_NORMAL, sv.current().recommend)
        sv.stop()
    }

    @Test
    fun probe_tlsFailure_recommends_backoffNormal_when_publicNotProbed() {
        val monitor = FakeNetStateMonitor(snap(NetVerdict.OK))
        val prober = FakeNetProber(
            probeReport(ProbeVerdict.TLS_FAILURE, tlsOk = false, publicOk = true)
        )
        val sv = buildSurveillance(monitor, prober)

        sv.start()
        waitForProbe(sv)

        assertEquals(NetVerdict.CONNECTED_NOT_VALIDATED, sv.current().overall)
        // publicOk + tcpOk → 不切, 走 BACKOFF_NORMAL
        assertEquals(ReconnectAction.BACKOFF_NORMAL, sv.current().recommend)
        sv.stop()
    }

    // ── 监听器流程 ────────────────────────────────────────────────────────────

    @Test
    fun listener_fires_on_snapshotChange() {
        val monitor = FakeNetStateMonitor(snap(NetVerdict.OK))
        val sv = buildSurveillance(monitor)
        val received = CopyOnWriteArrayList<NetReport>()
        sv.register { received.add(it) }

        sv.start()
        monitor.emit(snap(NetVerdict.OFFLINE))
        monitor.emit(snap(NetVerdict.CAPTIVE_PORTAL))

        // 至少有 OFFLINE 与 CAPTIVE_PORTAL 两次
        val verdicts = received.map { it.overall }
        assertTrue("未收到 OFFLINE: $verdicts", verdicts.contains(NetVerdict.OFFLINE))
        assertTrue("未收到 CAPTIVE_PORTAL: $verdicts", verdicts.contains(NetVerdict.CAPTIVE_PORTAL))
        sv.stop()
    }

    @Test
    fun listener_not_fired_when_nothingChanged() {
        val monitor = FakeNetStateMonitor(snap(NetVerdict.OK))
        val sv = buildSurveillance(monitor)
        val fireCount = AtomicInteger(0)
        sv.register { fireCount.incrementAndGet() }

        sv.start()
        val baseCount = fireCount.get()

        // 同一个 verdict 再发一次
        monitor.emit(snap(NetVerdict.OK))
        monitor.emit(snap(NetVerdict.OK))

        assertEquals("重复同 verdict 不应触发监听器", baseCount, fireCount.get())
        sv.stop()
    }

    @Test
    fun unregister_stopsCallbacks() {
        val monitor = FakeNetStateMonitor(snap(NetVerdict.OK))
        val sv = buildSurveillance(monitor)
        val fireCount = AtomicInteger(0)
        val listener = NetReportListener { fireCount.incrementAndGet() }
        sv.register(listener)

        sv.start()
        sv.unregister(listener)
        val before = fireCount.get()

        monitor.emit(snap(NetVerdict.OFFLINE))
        monitor.emit(snap(NetVerdict.CONGESTED))

        assertEquals("unregister 后不应再收到通知", before, fireCount.get())
        sv.stop()
    }

    // ── 强制探测 ────────────────────────────────────────────────────────────

    @Test
    fun forceProbe_runs_even_without_changes() {
        val monitor = FakeNetStateMonitor(snap(NetVerdict.OK))
        val prober = FakeNetProber(probeReport(ProbeVerdict.SERVER_REACHABLE))
        val sv = buildSurveillance(monitor, prober)

        sv.start()
        val initialCount = prober.probeCount.get()

        val latch = CountDownLatch(1)
        sv.forceProbe { latch.countDown() }
        assertTrue("forceProbe 未在期望时间内回调", latch.await(2, TimeUnit.SECONDS))
        assertTrue(
            "forceProbe 应至少发起一次新探测",
            prober.probeCount.get() > initialCount
        )
        sv.stop()
    }

    @Test
    fun forceProbe_without_prober_returnsCurrentReport() {
        val monitor = FakeNetStateMonitor(snap(NetVerdict.OK))
        val sv = buildSurveillance(monitor)
        val captured = AtomicReference<NetReport?>(null)

        sv.start()
        sv.forceProbe { captured.set(it) }

        assertNotNull("无 prober 时 forceProbe 也应回调当前报告", captured.get())
        assertNull("无 prober 时 lastProbe 应为 null", captured.get()?.lastProbe)
        sv.stop()
    }

    // ── 工具 ────────────────────────────────────────────────────────────────

    /**
     * Surveillance 触发探测是异步的(RapidResponseForceV4 在内部线程跑),
     * 这里轮询直到 lastProbe 不为 null,或超时
     */
    private fun waitForProbe(sv: NetSurveillance, timeoutMs: Long = 2_000L) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (sv.current().lastProbe != null) return
            Thread.sleep(20)
        }
        // 没等到, 测试断言会失败,这里不抛错让断言带上诊断信息
    }
}

// ──── Fakes ─────────────────────────────────────────────────────────────────

private class FakeNetStateMonitor(initial: NetSnapshot) : NetStateMonitor {
    private val listeners = CopyOnWriteArrayList<NetStateListener>()

    @Volatile
    private var current: NetSnapshot = initial

    override fun start() {}
    override fun stop() {}
    override fun current(): NetSnapshot = current

    override fun register(listener: NetStateListener) {
        if (!listeners.contains(listener)) listeners.add(listener)
    }

    override fun unregister(listener: NetStateListener) {
        listeners.remove(listener)
    }

    fun emit(s: NetSnapshot) {
        current = s
        listeners.forEach { it.onChange(s) }
    }
}

private class FakeNetProber(private val response: ProbeReport) : NetProber {
    val probeCount = AtomicInteger(0)

    override fun probe(target: ProbeTarget, timeoutMs: Long, callback: (ProbeReport) -> Unit) {
        probeCount.incrementAndGet()
        // 模拟异步:在另一线程回调,避免在持有 Surveillance 内部锁时被同步调用导致死锁
        Thread {
            callback(response.copy(target = target, timestamp = System.currentTimeMillis()))
        }.apply { isDaemon = true; start() }
    }

    override fun cancel() {}
}
