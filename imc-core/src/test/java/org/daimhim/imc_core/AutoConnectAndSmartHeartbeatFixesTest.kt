package org.daimhim.imc_core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.URI
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 1.1.17 小修回归:
 *  - #7 指数退避翻倍后夹到 maxReconnectDelay(历史会越过上限,60s 配置实际退到 64s)
 *  - #11 IMMEDIATE 调度延时可配,不再写死 1000ms
 *  - #8 V2SmartHeartbeat 补 RC1(换代强杀陈旧计时器)/ RC3(代际守卫)
 */
class AutoConnectAndSmartHeartbeatFixesTest {

    /** 记录 start(delay);手动 fire() 触发回调 */
    private class RecSched : ITimeoutScheduler {
        val starts = CopyOnWriteArrayList<Long>()
        val stops = AtomicInteger(0)
        private var cb: Callable<Void>? = null
        override fun start(time: Long) { starts.add(time) }
        override fun stop() { stops.incrementAndGet() }
        override fun setCallback(call: Callable<Void>) { cb = call }
        fun fire() { cb!!.call() }
    }

    private fun newClient(): V2JavaWebEngine.WebSocketClientImpl =
        V2JavaWebEngine.WebSocketClientImpl(URI("ws://localhost:1"), timeout = 0)

    // ── #7 ───────────────────────────────────────────────────────────────

    @Test
    fun backoff_isClampedTo_maxReconnectDelay() {
        val sched = RecSched()
        val auto = ProgressiveAutoConnect.Builder()
            .initReconnectDelay(1000, TimeUnit.MILLISECONDS)
            .maxReconnectDelay(3000, TimeUnit.MILLISECONDS)
            .setTimeoutScheduler(sched)
            .build()
        // 定时回调是在挂 client 时绑定的(initAutoConnect → setCallback)
        auto.initAutoConnect(newClient())
        // 不注入会退回 client.reconnect():未连过的 client 上 closeBlocking 永久阻塞
        auto.setReconnector { }
        try {
            auto.startAutoConnect()
            repeat(2) {
                sched.fire()                                       // 定时到 → 发起一次 attempt
                auto.abnormalDisconnectionAndAutomaticReconnection() // attempt 失败 → 排下一轮
            }
            // 历史实现:2000, 4000, 4000(只判翻倍前 < max)
            assertEquals(listOf(2000L, 3000L, 3000L), sched.starts)
        } finally {
            auto.dispose()
        }
    }

    // ── #11 ──────────────────────────────────────────────────────────────

    @Test
    fun immediateDelay_isConfigurable() {
        val sched = RecSched()
        val surv = object : NetSurveillance {
            override fun start() {}
            override fun stop() {}
            override fun current() = NetReport(
                snapshot = NetSnapshot.OFFLINE, lastProbe = null, overall = NetVerdict.OK,
                recommend = ReconnectAction.IMMEDIATE, timestamp = 0L,
            )
            override fun forceProbe(callback: (NetReport) -> Unit) {}
            override fun currentProfile() = NetProbeProfile.BALANCED
            override fun register(listener: NetReportListener) {}
            override fun unregister(listener: NetReportListener) {}
        }
        val auto = ProgressiveAutoConnect.Builder()
            .immediateDelay(200, TimeUnit.MILLISECONDS)
            .setTimeoutScheduler(sched)
            .surveillance(surv)
            .build()
        try {
            auto.startAutoConnect()
            assertEquals(listOf(200L), sched.starts)
        } finally {
            auto.dispose()
        }
    }

    // ── #8 ───────────────────────────────────────────────────────────────

    @Test
    fun smartHeartbeat_rearmsTimer_onNewClientGeneration() {
        val sched = RecSched()
        val hb = V2SmartHeartbeat.Builder().setInitialHeartbeat(30).setTimeoutScheduler(sched).build()
        val clientA = newClient()
        val clientB = newClient()

        clientA.onChangeMode(hb)
        assertEquals(1, sched.starts.size)

        // spawn 重连:同一心跳实例挂到新 client。修前 isRunning 残留 → 新 client 不起计时器
        clientB.onChangeMode(hb)
        assertEquals("换代后必须重排计时器(RC1)", 2, sched.starts.size)
    }

    @Test
    fun smartHeartbeat_lateStopFromOldClient_isGuarded() {
        val sched = RecSched()
        val hb = V2SmartHeartbeat.Builder().setInitialHeartbeat(30).setTimeoutScheduler(sched).build()
        val clientA = newClient()
        val clientB = newClient()
        clientA.onChangeMode(hb)
        clientB.onChangeMode(hb)

        val stopsBefore = sched.stops.get()
        clientA.stopConnectionLostTimerEx(true)
        assertEquals("旧 client 迟到的 stop 应被代际守卫拦下(RC3)", stopsBefore, sched.stops.get())

        clientB.stopConnectionLostTimerEx(true)
        assertEquals(stopsBefore + 1, sched.stops.get())
    }
}
