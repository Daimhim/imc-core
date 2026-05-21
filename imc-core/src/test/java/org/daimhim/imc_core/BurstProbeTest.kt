package org.daimhim.imc_core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 验证 BurstProbeReport 的统计字段计算正确,以及 DefaultNetProber.probeBurst 工作
 */
class BurstProbeTest {

    @Test
    fun `全部成功时 loss=0 stats 全有值`() {
        val r = BurstProbeReport(
            target = ProbeTarget("h", 1, false),
            attempts = 5, successes = 5,
            latenciesMs = listOf(80L, 100L, 90L, 110L, 95L),
            timestamp = 0L,
        )
        assertEquals(0.0, r.lossRate, 0.001)
        assertEquals(80L, r.minMs)
        assertEquals(110L, r.maxMs)
        assertEquals(95.0, r.meanMs!!, 0.001)
        // stddev 验算:数据 [80,100,90,110,95] mean=95
        // diffs: -15,5,-5,15,0 → squares 225,25,25,225,0 → mean 100 → sqrt 10
        assertEquals(10.0, r.jitterMs!!, 0.001)
        // p50 / p95
        assertNotNull(r.p50Ms)
        assertNotNull(r.p95Ms)
    }

    @Test
    fun `部分失败时 loss 正确`() {
        val r = BurstProbeReport(
            target = ProbeTarget("h", 1, false),
            attempts = 10, successes = 7,
            latenciesMs = listOf(50L, 55L, 60L, 52L, 58L, 51L, 53L),
            timestamp = 0L,
        )
        assertEquals(0.3, r.lossRate, 0.001)
        assertEquals(7, r.latenciesMs.size)
    }

    @Test
    fun `全部失败时 latency 字段为 null`() {
        val r = BurstProbeReport(
            target = ProbeTarget("h", 1, false),
            attempts = 5, successes = 0,
            latenciesMs = emptyList(),
            timestamp = 0L,
        )
        assertEquals(1.0, r.lossRate, 0.001)
        assertNull(r.minMs)
        assertNull(r.maxMs)
        assertNull(r.meanMs)
        assertNull(r.jitterMs)
        assertNull(r.p50Ms)
        assertNull(r.p95Ms)
    }

    @Test
    fun `单次成功时 jitter null mean 正常`() {
        val r = BurstProbeReport(
            target = ProbeTarget("h", 1, false),
            attempts = 3, successes = 1,
            latenciesMs = listOf(120L),
            timestamp = 0L,
        )
        // 2/3 失败
        assertTrue(abs(r.lossRate - 0.6667) < 0.001)
        assertEquals(120.0, r.meanMs!!, 0.001)
        assertNull("不到 2 次成功 jitter 无意义", r.jitterMs)
    }

    @Test
    fun `attempts=0 时 lossRate=0`() {
        val r = BurstProbeReport(
            target = ProbeTarget("h", 1, false),
            attempts = 0, successes = 0,
            latenciesMs = emptyList(),
            timestamp = 0L,
        )
        assertEquals(0.0, r.lossRate, 0.001)
    }

    @Test
    fun `DefaultNetProber probeBurst 端到端_拒绝连接的本地端口 全失败`() {
        val prober = DefaultNetProber()
        var got: BurstProbeReport? = null
        val latch = java.util.concurrent.CountDownLatch(1)
        prober.probeBurst(
            target = ProbeTarget("127.0.0.1", 1, false), // 端口 1 99% 不可达
            attempts = 3,
            intervalMs = 50L,
            perAttemptTimeoutMs = 300,
        ) { report ->
            got = report
            latch.countDown()
        }
        assertTrue(latch.await(5, java.util.concurrent.TimeUnit.SECONDS))
        val r = got!!
        assertEquals(3, r.attempts)
        assertEquals(0, r.successes)
        assertEquals(1.0, r.lossRate, 0.001)
        assertTrue(r.latenciesMs.isEmpty())
    }
}
