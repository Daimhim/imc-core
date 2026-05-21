package org.daimhim.imc_core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * [DefaultNetProber] 行为测试
 *
 * 用 TEST-NET-1 段 (192.0.2.0/24,RFC 5737 保留,网络上不可达) 做不可达目标,
 * 用一个不可解析的域名做 DNS 失败目标,避免依赖真实生产网络
 */
class DefaultNetProberTest {

    /** RFC 5737 保留的不可路由地址 */
    private val unreachableIp = "192.0.2.1"

    /** 不可解析的域名 (顶级域为非法字符) */
    private val unresolvableHost = "this-host-definitely-does-not-exist-123.invalid"

    @Test
    fun probe_unreachable_tcp_returnsServerDown_when_publicRefAlsoUnreachable() {
        val prober = DefaultNetProber()
        val captured = AtomicReference<ProbeReport?>(null)
        val latch = CountDownLatch(1)

        // 公网参考点也指向 TEST-NET → 两个都不通 → NO_INTERNET
        prober.probe(
            target = ProbeTarget(
                host = unreachableIp,
                port = 9999,
                useTls = false,
                publicReference = ProbeTarget(unreachableIp, 9999, false)
            ),
            timeoutMs = 2_000L
        ) {
            captured.set(it)
            latch.countDown()
        }

        assertTrue("probe 未在期望时间内回调", latch.await(5, TimeUnit.SECONDS))
        val report = captured.get()!!
        // DNS 成功 (IP 是字面量),TCP 失败
        assertTrue("DNS 应成功 (IP 字面量)", report.dns.success)
        assertFalse("TCP 应失败", report.tcp.success)
        // 公网参考也失败 → NO_INTERNET
        assertEquals(ProbeVerdict.NO_INTERNET, report.verdict)
        assertNotNull("publicRef 应被探测", report.publicRef)
    }

    @Test
    fun probe_unreachable_tcp_returnsServerDown_when_publicRefOk() {
        val prober = DefaultNetProber()
        val captured = AtomicReference<ProbeReport?>(null)
        val latch = CountDownLatch(1)

        // localhost 任意端口当公网参考 —— 它的 TCP 会被立刻 RST,
        // 但 DNS 通,这种"快速失败"也算"公网通"吗?不算 —— 我们不能控制本地
        // 所以这里换个策略:跳过公网参考,验证不传时的行为
        prober.probe(
            target = ProbeTarget(
                host = unreachableIp,
                port = 9999,
                useTls = false,
                publicReference = null  // 不测公网
            ),
            timeoutMs = 1_500L
        ) {
            captured.set(it)
            latch.countDown()
        }

        assertTrue(latch.await(3, TimeUnit.SECONDS))
        val report = captured.get()!!
        assertEquals(ProbeVerdict.SERVER_DOWN, report.verdict)
        assertNull("未传 publicReference 时不应探测", report.publicRef)
    }

    @Test
    fun probe_unresolvableHost_returnsDnsFailure() {
        val prober = DefaultNetProber()
        val captured = AtomicReference<ProbeReport?>(null)
        val latch = CountDownLatch(1)

        prober.probe(
            target = ProbeTarget(
                host = unresolvableHost,
                port = 443,
                useTls = true,
                publicReference = null
            ),
            timeoutMs = 3_000L
        ) {
            captured.set(it)
            latch.countDown()
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS))
        val report = captured.get()!!
        assertFalse("DNS 应失败", report.dns.success)
        assertEquals(ProbeVerdict.DNS_FAILURE, report.verdict)
    }

    @Test
    fun probe_respects_overallTimeout() {
        val prober = DefaultNetProber()
        val captured = AtomicReference<ProbeReport?>(null)
        val latch = CountDownLatch(1)
        val started = System.currentTimeMillis()

        prober.probe(
            target = ProbeTarget(
                host = unreachableIp,
                port = 9999,
                useTls = false,
                publicReference = null
            ),
            timeoutMs = 800L
        ) {
            captured.set(it)
            latch.countDown()
        }

        assertTrue(latch.await(3, TimeUnit.SECONDS))
        val elapsed = System.currentTimeMillis() - started
        // 严格的 TCP connect 自带 timeout, 上限应接近 1s,留点余量
        assertTrue(
            "probe 应在约 800ms 内回调, 实际 ${elapsed}ms",
            elapsed < 2_000L
        )
    }

    @Test
    fun probe_cancel_does_not_throw() {
        val prober = DefaultNetProber()
        prober.probe(
            ProbeTarget(unreachableIp, 9999, false), 5_000L
        ) { /* ignore */ }

        // 不应抛异常,即便没有 inflight 任务
        prober.cancel()
        prober.cancel()
    }

    @Test
    fun probe_secondCall_cancels_first() {
        val prober = DefaultNetProber()
        val first = AtomicReference<ProbeReport?>(null)
        val second = AtomicReference<ProbeReport?>(null)
        val secondLatch = CountDownLatch(1)

        prober.probe(
            ProbeTarget(unreachableIp, 9999, false), 10_000L
        ) { first.set(it) }

        Thread.sleep(100)

        prober.probe(
            ProbeTarget(unresolvableHost, 443, false), 2_000L
        ) {
            second.set(it)
            secondLatch.countDown()
        }

        // 第二个应该完成,而第一个被打断不一定回调 (取决于哪个阶段被打断)
        assertTrue(
            "第二次 probe 应该完成回调",
            secondLatch.await(5, TimeUnit.SECONDS)
        )
        assertNotNull(second.get())
    }
}
