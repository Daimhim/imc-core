package org.daimhim.imc_core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 验证 V2JavaWebEngine 缓存自动清理依赖的 V4 计时器语义:
 *  - 注册任务,超时后回调 fire 一次
 *  - 手动 unregister 能取消未触发的任务
 *  - 同名 id 重新 register 会覆盖
 */
class RapidResponseForceV4AutoEvictTest {

    @Test
    fun `任务超时后回调 fire 一次`() {
        val rrf = RapidResponseForceV4()
        val latch = CountDownLatch(1)
        val firedId = arrayOf<String?>(null)
        rrf.register("task-A", timeoutMs = 100L) { id ->
            firedId[0] = id
            latch.countDown()
        }
        assertTrue("100ms 内应触发", latch.await(500, TimeUnit.MILLISECONDS))
        assertEquals("task-A", firedId[0])
    }

    @Test
    fun `unregister 取消未触发任务`() {
        val rrf = RapidResponseForceV4()
        val fired = AtomicInteger(0)
        rrf.register("task-B", timeoutMs = 200L) { fired.incrementAndGet() }
        val removed = rrf.unregister("task-B")
        assertTrue("unregister 应返回 true(任务还在)", removed)
        Thread.sleep(400)
        assertEquals("回调不应该再 fire", 0, fired.get())
    }

    @Test
    fun `同 id 重新 register 覆盖旧 deadline`() {
        val rrf = RapidResponseForceV4()
        val fired = AtomicInteger(0)
        rrf.register("task-C", timeoutMs = 100L) { fired.incrementAndGet() }
        // 在第一次超时前再 register,deadline 重置到 +400ms
        Thread.sleep(50)
        rrf.register("task-C", timeoutMs = 400L) { fired.incrementAndGet() }
        // 老 deadline(100ms)早过了应该不 fire
        Thread.sleep(200)
        assertEquals("覆盖前的任务不应触发", 0, fired.get())
        // 新 deadline(50+400=450ms)还要等
        Thread.sleep(400)
        assertEquals("覆盖后的任务到点 fire 一次", 1, fired.get())
    }

    @Test
    fun `多任务并发各自独立超时`() {
        val rrf = RapidResponseForceV4()
        val latch = CountDownLatch(3)
        val firedIds = java.util.concurrent.CopyOnWriteArrayList<String>()
        rrf.register("t1", timeoutMs = 100L) { id -> firedIds.add(id); latch.countDown() }
        rrf.register("t2", timeoutMs = 200L) { id -> firedIds.add(id); latch.countDown() }
        rrf.register("t3", timeoutMs = 50L)  { id -> firedIds.add(id); latch.countDown() }
        assertTrue(latch.await(500, TimeUnit.MILLISECONDS))
        // 顺序应该按 deadline:t3 (50ms) → t1 (100ms) → t2 (200ms)
        assertEquals(listOf("t3", "t1", "t2"), firedIds)
    }

    @Test
    fun `clear 清空所有任务`() {
        val rrf = RapidResponseForceV4()
        val fired = AtomicInteger(0)
        rrf.register("x", timeoutMs = 100L) { fired.incrementAndGet() }
        rrf.register("y", timeoutMs = 100L) { fired.incrementAndGet() }
        rrf.clear()
        Thread.sleep(300)
        assertEquals(0, fired.get())
    }
}
