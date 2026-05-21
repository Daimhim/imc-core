package org.daimhim.imc_core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * 计时相关的测试都依赖墙钟，因此每个断言都留 200~500ms 余量，避免 CI 上偶发抖动
 */
class RapidResponseForceV4Test {

    /** 默认每条断言等待的最大墙钟时间 */
    private val joinMs = 3_000L

    @Test
    fun register_singleTask_firesAfterTimeout() {
        val timer = RapidResponseForceV4()
        val latch = CountDownLatch(1)
        val firedId = AtomicReference<String?>(null)
        val started = System.currentTimeMillis()

        timer.register("a", 200) { id ->
            firedId.set(id)
            latch.countDown()
        }

        assertTrue("回调未在预期时间内触发", latch.await(joinMs, TimeUnit.MILLISECONDS))
        val elapsed = System.currentTimeMillis() - started
        assertEquals("a", firedId.get())
        assertTrue("回调过早触发: ${elapsed}ms", elapsed >= 150)
        assertTrue("回调过晚触发: ${elapsed}ms", elapsed < 1_500)
    }

    @Test
    fun register_multipleTasks_firesInDeadlineOrder() {
        val timer = RapidResponseForceV4()
        val order = Collections.synchronizedList(mutableListOf<String>())
        val latch = CountDownLatch(3)

        timer.register("slow", 600) { order.add(it); latch.countDown() }
        timer.register("mid", 300) { order.add(it); latch.countDown() }
        timer.register("fast", 100) { order.add(it); latch.countDown() }

        assertTrue(latch.await(joinMs, TimeUnit.MILLISECONDS))
        assertEquals(listOf("fast", "mid", "slow"), order.toList())
    }

    @Test
    fun register_closerTaskAfterStart_firesBeforeOriginal() {
        val timer = RapidResponseForceV4()
        val latch = CountDownLatch(2)
        val order = Collections.synchronizedList(mutableListOf<Pair<String, Long>>())
        val start = System.currentTimeMillis()

        timer.register("far", 1_500) {
            order.add(it to (System.currentTimeMillis() - start))
            latch.countDown()
        }
        // 等 worker 已经在 wait(1500) 之后，再插入一个更近的任务
        Thread.sleep(150)
        timer.register("near", 200) {
            order.add(it to (System.currentTimeMillis() - start))
            latch.countDown()
        }

        assertTrue(latch.await(joinMs, TimeUnit.MILLISECONDS))
        assertEquals("near", order[0].first)
        assertEquals("far", order[1].first)
        val nearAt = order[0].second
        assertTrue("near 应在约 350ms 触发, 实际 ${nearAt}ms", nearAt in 250..900)
    }

    @Test
    fun unregister_preventsCallback() {
        val timer = RapidResponseForceV4()
        val fired = AtomicInteger(0)
        timer.register("a", 300) { fired.incrementAndGet() }
        assertTrue(timer.unregister("a"))
        Thread.sleep(600)
        assertEquals("已注销的任务不应再回调", 0, fired.get())
    }

    @Test
    fun unregister_returnsFalseForUnknownId() {
        val timer = RapidResponseForceV4()
        assertFalse(timer.unregister("nope"))
    }

    @Test
    fun unregister_returnsFalseAfterTimeout() {
        val timer = RapidResponseForceV4()
        val latch = CountDownLatch(1)
        timer.register("a", 100) { latch.countDown() }
        assertTrue(latch.await(joinMs, TimeUnit.MILLISECONDS))
        Thread.sleep(50)
        assertFalse("已超时的任务 unregister 应返回 false", timer.unregister("a"))
    }

    @Test
    fun clear_dropsAllPendingTasks() {
        val timer = RapidResponseForceV4()
        val fired = AtomicInteger(0)
        timer.register("a", 300) { fired.incrementAndGet() }
        timer.register("b", 400) { fired.incrementAndGet() }
        timer.register("c", 500) { fired.incrementAndGet() }
        assertEquals(3, timer.size())

        timer.clear()
        assertEquals(0, timer.size())

        Thread.sleep(700)
        assertEquals("clear 后所有任务都不应触发", 0, fired.get())
    }

    @Test
    fun register_sameId_overridesPreviousDeadlineAndCallback() {
        val timer = RapidResponseForceV4()
        val latch = CountDownLatch(1)
        val whoFired = AtomicReference<String?>(null)

        timer.register("a", 2_000) { whoFired.set("first") }
        Thread.sleep(50)
        timer.register("a", 150) {
            whoFired.set("second")
            latch.countDown()
        }

        assertTrue(latch.await(joinMs, TimeUnit.MILLISECONDS))
        assertEquals("second", whoFired.get())

        // 再等一段时间，第一次注册的回调不应该再触发
        Thread.sleep(2_200)
        assertEquals("second", whoFired.get())
    }

    @Test
    fun callback_canReRegisterSelfWithoutDeadlock() {
        val timer = RapidResponseForceV4()
        val fireCount = AtomicInteger(0)
        val done = CountDownLatch(3)
        val self = AtomicReference<(String) -> Unit>()

        self.set { id ->
            fireCount.incrementAndGet()
            done.countDown()
            if (fireCount.get() < 3) {
                timer.register(id, 100, self.get())
            }
        }
        timer.register("loop", 100, self.get())

        assertTrue("回调里 re-register 死锁或未触发", done.await(joinMs, TimeUnit.MILLISECONDS))
        assertEquals(3, fireCount.get())
    }

    @Test
    fun callback_canUnregisterAnotherTask() {
        val timer = RapidResponseForceV4()
        val bFired = AtomicInteger(0)
        val aFired = CountDownLatch(1)

        timer.register("b", 300) { bFired.incrementAndGet() }
        timer.register("a", 100) {
            timer.unregister("b")
            aFired.countDown()
        }

        assertTrue(aFired.await(joinMs, TimeUnit.MILLISECONDS))
        Thread.sleep(500)
        assertEquals("a 在回调里注销了 b，b 不应再触发", 0, bFired.get())
    }

    @Test
    fun workerThread_exitsAfterAllTasksFire() {
        val timer = RapidResponseForceV4()
        val latch = CountDownLatch(1)
        timer.register("a", 100) { latch.countDown() }

        assertTrue(latch.await(joinMs, TimeUnit.MILLISECONDS))
        // 触发完后线程退出有一点延迟（要再走一圈循环判断空集）
        val deadline = System.currentTimeMillis() + 1_500
        while (timer.isRunning() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
        assertFalse("空闲后 worker 线程应当自动退出", timer.isRunning())
    }

    @Test
    fun workerThread_restartsAfterIdle() {
        val timer = RapidResponseForceV4()
        val first = CountDownLatch(1)
        timer.register("a", 100) { first.countDown() }
        assertTrue(first.await(joinMs, TimeUnit.MILLISECONDS))

        // 等 worker 退出
        val deadline = System.currentTimeMillis() + 1_500
        while (timer.isRunning() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
        assertFalse(timer.isRunning())

        // 再次 register 应能重新拉起线程并触发回调
        val second = CountDownLatch(1)
        timer.register("b", 100) { second.countDown() }
        assertTrue("空闲后再次注册应能正常触发", second.await(joinMs, TimeUnit.MILLISECONDS))
    }

    @Test
    fun callback_exceptionDoesNotKillWorker() {
        val timer = RapidResponseForceV4()
        val good = CountDownLatch(1)

        timer.register("boom", 100) { error("intentional") }
        timer.register("good", 250) { good.countDown() }

        assertTrue("回调抛异常后 worker 应继续工作", good.await(joinMs, TimeUnit.MILLISECONDS))
    }

    @Test
    fun size_reflectsRegisteredTasks() {
        val timer = RapidResponseForceV4()
        assertEquals(0, timer.size())
        timer.register("a", 5_000) {}
        timer.register("b", 5_000) {}
        assertEquals(2, timer.size())
        timer.unregister("a")
        assertEquals(1, timer.size())
        timer.clear()
        assertEquals(0, timer.size())
    }

    @Test
    fun concurrentRegister_allTasksEventuallyFire() {
        val timer = RapidResponseForceV4()
        val threadCount = 8
        val perThread = 10
        val total = threadCount * perThread
        val latch = CountDownLatch(total)
        val fired = AtomicInteger(0)

        val threads = (0 until threadCount).map { t ->
            Thread {
                repeat(perThread) { i ->
                    timer.register("t-$t-$i", (50L..200L).random()) {
                        fired.incrementAndGet()
                        latch.countDown()
                    }
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        assertTrue(
            "并发场景下应有全部 $total 个任务触发, 实际 ${fired.get()}",
            latch.await(5_000, TimeUnit.MILLISECONDS)
        )
        assertEquals(total, fired.get())
    }

    @Test
    fun timeoutClose_toZero_firesImmediately() {
        val timer = RapidResponseForceV4()
        val latch = CountDownLatch(1)
        val start = System.currentTimeMillis()
        timer.register("now", 0) { latch.countDown() }

        assertTrue(latch.await(joinMs, TimeUnit.MILLISECONDS))
        val elapsed = System.currentTimeMillis() - start
        assertTrue("0ms 超时应近乎立即触发, 实际 ${elapsed}ms", elapsed < 500)
    }
}
