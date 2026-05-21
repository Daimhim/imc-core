package org.daimhim.imc_core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * 验证:NetSurveillance 推新 NetReport 时,
 * ProgressiveAutoConnect 在 isWaitingNextAutomaticConnection=true 期间会立即按新策略重排定时器。
 */
class ProgressiveAutoConnectSurveillanceTest {

    /** 假 surveillance,可以手动推 NetReport,只支持 register/unregister 和 current */
    private class FakeSurveillance(initial: NetReport) : NetSurveillance {
        private val listeners = CopyOnWriteArrayList<NetReportListener>()
        @Volatile var report: NetReport = initial
        override fun start() {}
        override fun stop() {}
        override fun current(): NetReport = report
        override fun forceProbe(callback: (NetReport) -> Unit) { callback(report) }
        override fun register(listener: NetReportListener) { listeners.add(listener) }
        override fun unregister(listener: NetReportListener) { listeners.remove(listener) }
        fun push(next: NetReport) {
            report = next
            listeners.forEach { it.onReport(next) }
        }
    }

    /** 假 timeoutScheduler:记录每次 start(delay) 的调用,start 不真正触发 callback */
    private class RecordingScheduler : ITimeoutScheduler {
        val starts = CopyOnWriteArrayList<Long>()
        val stops = AtomicLong(0)
        private var cb: Callable<Void>? = null
        override fun start(time: Long) { starts.add(time) }
        override fun stop() { stops.incrementAndGet() }
        override fun setCallback(call: Callable<Void>) { cb = call }
    }

    private fun makeReport(recommend: ReconnectAction) = NetReport(
        snapshot = NetSnapshot.OFFLINE,
        lastProbe = null,
        overall = NetVerdict.OK,
        recommend = recommend,
        timestamp = 0L,
    )

    @Test
    fun `surveillance 推 IMMEDIATE 时重排到 initDelay`() {
        val sched = RecordingScheduler()
        val surv = FakeSurveillance(makeReport(ReconnectAction.BACKOFF_LONG))
        val auto = ProgressiveAutoConnect.Builder()
            .initReconnectDelay(1000, java.util.concurrent.TimeUnit.MILLISECONDS)
            .maxReconnectDelay(30000, java.util.concurrent.TimeUnit.MILLISECONDS)
            .setTimeoutScheduler(sched)
            .surveillance(surv)
            .build()

        // 触发一次抢救,使 isWaitingNextAutomaticConnection=true
        auto.startAutoConnect()
        // 应该按 BACKOFF_LONG 调度到 maxReconnectDelay=30000
        assertEquals(listOf(30000L), sched.starts)
        val stopsBefore = sched.stops.get()

        // 此时 surveillance 推 IMMEDIATE → 应该 stop 当前定时 + 重排到 initReconnectDelay=1000
        surv.push(makeReport(ReconnectAction.IMMEDIATE))

        assertTrue("应该 stop 当前定时", sched.stops.get() > stopsBefore)
        assertEquals("应有 2 次 start,后一次 = initDelay",
            listOf(30000L, 1000L), sched.starts)
    }

    @Test
    fun `surveillance 推 WAIT_USER 时停止抢救`() {
        val sched = RecordingScheduler()
        val surv = FakeSurveillance(makeReport(ReconnectAction.IMMEDIATE))
        val auto = ProgressiveAutoConnect.Builder()
            .initReconnectDelay(1000, java.util.concurrent.TimeUnit.MILLISECONDS)
            .setTimeoutScheduler(sched)
            .surveillance(surv)
            .build()

        auto.startAutoConnect()
        assertEquals(listOf(1000L), sched.starts)
        val stopsBefore = sched.stops.get()

        surv.push(makeReport(ReconnectAction.WAIT_USER))

        // stopAutoConnect 会调 timeoutScheduler.stop() 但不再 start
        assertTrue("应该停止当前定时", sched.stops.get() > stopsBefore)
        // 不应该有第二次 start
        assertEquals(1, sched.starts.size)
    }

    @Test
    fun `不在等待时 surveillance 变更不响应`() {
        val sched = RecordingScheduler()
        val surv = FakeSurveillance(makeReport(ReconnectAction.IMMEDIATE))
        ProgressiveAutoConnect.Builder()
            .initReconnectDelay(1000, java.util.concurrent.TimeUnit.MILLISECONDS)
            .setTimeoutScheduler(sched)
            .surveillance(surv)
            .build()

        // 没调 startAutoConnect → 不在 waiting 状态
        surv.push(makeReport(ReconnectAction.BACKOFF_LONG))
        surv.push(makeReport(ReconnectAction.WAIT_USER))
        surv.push(makeReport(ReconnectAction.IMMEDIATE))

        assertEquals("没启动抢救,推啥都不调度", 0, sched.starts.size)
        assertEquals(0L, sched.stops.get())
    }
}
