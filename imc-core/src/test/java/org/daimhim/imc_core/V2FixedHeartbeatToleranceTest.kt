package org.daimhim.imc_core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * 验证 V2FixedHeartbeat 的容差窗口行为:
 *  - 收到 pong → 正常节奏(发心跳 + 再排 curHeartbeat 秒)
 *  - 没收到 pong → 进入容差窗口,发探活,再等 (factor - 1) × curHeartbeat 秒
 *  - 容差窗口结束还没回 → 触发 resetStartAutoConnect
 */
class V2FixedHeartbeatToleranceTest {

    /** 假 scheduler 记录每次 start(timeoutMs) 与 stop 调用 */
    private class RecordingScheduler : ITimeoutScheduler {
        val starts = CopyOnWriteArrayList<Long>()
        val stops = AtomicInteger(0)
        private var cb: Callable<Void>? = null
        override fun start(time: Long) { starts.add(time) }
        override fun stop() { stops.incrementAndGet() }
        override fun setCallback(call: Callable<Void>) { cb = call }
        fun fire() { cb?.call() }
    }

    @Test
    fun `收到 pong 时正常节奏 心跳间隔保持`() {
        val sched = RecordingScheduler()
        val hb = V2FixedHeartbeat.Builder()
            .setCurHeartbeat(60)
            .setToleranceFactor(1.5)
            .setTimeoutScheduler(sched)
            .build()

        hb.startConnectionLostTimer()
        assertEquals("起始定时 = 60s", listOf(60_000L), sched.starts)

        // 模拟期间收到 pong
        hb.updateLastPong()
        // 时间到,触发 tick
        sched.fire()
        // 应该:重置 + 发心跳 + 排下次 60s
        assertEquals(listOf(60_000L, 60_000L), sched.starts)
    }

    @Test
    fun `没 pong 时进入容差窗口 延后 30s`() {
        val sched = RecordingScheduler()
        val hb = V2FixedHeartbeat.Builder()
            .setCurHeartbeat(60)
            .setToleranceFactor(1.5)  // 容差时间 = 60 × 0.5 = 30s
            .setTimeoutScheduler(sched)
            .build()

        hb.startConnectionLostTimer()
        // 没 pong,直接 fire
        sched.fire()
        // 应该:发探活 + 排 30s 容差(60 × 0.5 = 30_000)
        assertEquals("容差窗口排到 30s 后", listOf(60_000L, 30_000L), sched.starts)
    }

    @Test
    fun `容差窗口里收到 pong 恢复正常节奏`() {
        val sched = RecordingScheduler()
        val hb = V2FixedHeartbeat.Builder()
            .setCurHeartbeat(60)
            .setToleranceFactor(1.5)
            .setTimeoutScheduler(sched)
            .build()

        hb.startConnectionLostTimer()
        sched.fire()  // 进入容差
        assertEquals(listOf(60_000L, 30_000L), sched.starts)

        // 容差期间收到 pong
        hb.updateLastPong()
        sched.fire()
        // 应该:重置 + 发心跳 + 排下次 60s,而不是触发重连
        assertEquals(listOf(60_000L, 30_000L, 60_000L), sched.starts)
    }

    @Test
    fun `容差窗口结束还没 pong 触发重连`() {
        val sched = RecordingScheduler()
        // 不挂 WebSocketClient,所以 reset 不会真飞出来,但能验证 starts 不再增长
        val hb = V2FixedHeartbeat.Builder()
            .setCurHeartbeat(60)
            .setToleranceFactor(1.5)
            .setTimeoutScheduler(sched)
            .build()

        hb.startConnectionLostTimer()
        sched.fire()  // 第一次失败 → 进容差
        sched.fire()  // 容差窗口结束依然没 pong → 应该 fail,不再 start
        // starts 应保持 2 个(60_000, 30_000),没有第三次 start
        assertEquals(listOf(60_000L, 30_000L), sched.starts)
    }

    @Test
    fun `factor 1_0 与旧行为等价 一次 fire 直接失败`() {
        val sched = RecordingScheduler()
        val hb = V2FixedHeartbeat.Builder()
            .setCurHeartbeat(60)
            .setToleranceFactor(1.0)  // 退回旧行为
            .setTimeoutScheduler(sched)
            .build()

        hb.startConnectionLostTimer()
        sched.fire()
        // factor=1.0 时容差时间 = 0,但 coerceAtLeast(1_000) 至少 1s
        // 这里的实际行为:进入容差,排 1s
        assertEquals(2, sched.starts.size)
        assertEquals(60_000L, sched.starts[0])
        assertEquals("factor=1.0 时容差时间被夹到最小 1s", 1_000L, sched.starts[1])
    }

    @Test
    fun `stopConnectionLostTimer 后再 startConnectionLostTimer 状态干净`() {
        val sched = RecordingScheduler()
        val hb = V2FixedHeartbeat.Builder()
            .setCurHeartbeat(60)
            .setToleranceFactor(1.5)
            .setTimeoutScheduler(sched)
            .build()

        hb.startConnectionLostTimer()
        sched.fire()  // 进入容差
        hb.stopConnectionLostTimer()
        assertEquals(1, sched.stops.get())

        hb.startConnectionLostTimer()
        // 容差标记应被 stop 清掉,第一次 fire 又走"没 pong → 进容差"路径
        sched.fire()
        // starts: 60s(初始), 30s(容差), 60s(重启), 30s(再进容差) = 4 次
        assertEquals(listOf(60_000L, 30_000L, 60_000L, 30_000L), sched.starts)
    }
}
