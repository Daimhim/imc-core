package org.daimhim.imc_core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 内核重发池(1.1.17)的语义钉子:
 *  - 不带业务 ID 不入池;带 ID 入池、同 ID 去重
 *  - flush FIFO、直发前先冲存量、写失败留池
 *  - take 与 flush 互斥:每条要么被内核写出、要么被业务取走,恰好一次
 *  - TTL 惰性淘汰、容量淘汰、会话结束 / 换账号整池回收,状态可查
 */
class ResendPoolTest {

    /** 可控的写出口:记录写出的 bizId,可模拟断开 / 写到第 N 条抛异常 */
    private class FakeWriter : ResendPool.Writer {
        @Volatile var open = true
        @Volatile var failAfter = Int.MAX_VALUE
        val written = CopyOnWriteArrayList<String>()
        val payloads = CopyOnWriteArrayList<String>()
        override val isOpenForWrite: Boolean get() = open
        override fun write(message: CachedMessage) {
            if (written.size >= failAfter) throw IllegalStateException("socket closed mid-flush")
            written.add(message.id)
            payloads.add((message as? CachedMessage.Text)?.text ?: "<bin>")
        }
    }

    private val now = AtomicLong(1_000_000L)
    private val events = CopyOnWriteArrayList<ImcEvent>()
    private val sink = ImcEventSink { events.add(it) }

    @get:Rule
    val tmp = TemporaryFolder()

    @Before
    fun setUp() { ImcEvents.subscribe(sink) }

    @After
    fun tearDown() { ImcEvents.unsubscribe(sink) }

    private fun pool(
        config: ResendPoolConfig = ResendPoolConfig(),
        storage: IMessageCache = InMemoryMessageCache(maxBytes = Int.MAX_VALUE, maxAgeMs = 0),
    ) = ResendPool(storage, config) { now.get() }

    private fun text(id: String?, body: String = "m-$id") = CachedMessage.Text(id ?: "", body, now.get())

    private fun ResendPool.queue(id: String, body: String = "m-$id") =
        sendOrQueue(id, text(id, body), writer = null, allowQueue = true)

    private fun evicted(id: String) = events.filterIsInstance<ImcEvent.MessageEvicted>().filter { it.id == id }

    // ── 入池规则 ─────────────────────────────────────────────────────

    @Test
    fun `不带业务ID且未连接 - 拒绝且不入池`() {
        val p = pool()
        assertEquals(SendResult.REJECTED, p.sendOrQueue(null, text(null), null, allowQueue = true))
        assertEquals(0, p.size())
        assertTrue(events.none { it is ImcEvent.MessageCached })
    }

    @Test
    fun `带业务ID且未连接 - 入池可查`() {
        val p = pool()
        assertEquals(SendResult.QUEUED, p.queue("a"))
        assertEquals(ResendStatus.State.QUEUED, p.status("a").state)
        assertEquals(listOf("a"), p.ids())
        val cached = events.filterIsInstance<ImcEvent.MessageCached>().single()
        assertEquals("a", cached.id)
    }

    @Test
    fun `不在会话中 - 带ID也拒绝`() {
        val p = pool()
        assertEquals(SendResult.REJECTED, p.sendOrQueue("a", text("a"), null, allowQueue = false))
        assertEquals(ResendStatus.State.UNKNOWN, p.status("a").state)
    }

    @Test
    fun `单条超过池字节上限 - 拒绝`() {
        val p = pool(ResendPoolConfig(maxBytes = 4))
        assertEquals(SendResult.REJECTED, p.queue("a", "12345"))
        assertEquals(0, p.size())
    }

    @Test
    fun `同一业务ID重复入池 - 只保留最新一份`() {
        val p = pool()
        p.queue("a", "v1")
        p.queue("a", "v2")
        assertEquals(1, p.size())
        val w = FakeWriter()
        p.flush(w)
        assertEquals(listOf("a"), w.written)
        assertEquals(listOf("v2"), w.payloads)
    }

    // ── flush / 直发 ────────────────────────────────────────────────

    @Test
    fun `flush 按 FIFO 写出 - 状态变 FLUSHED`() {
        val p = pool()
        listOf("a", "b", "c").forEach { p.queue(it) }
        val w = FakeWriter()
        p.flush(w)
        assertEquals(listOf("a", "b", "c"), w.written)
        assertEquals(0, p.size())
        listOf("a", "b", "c").forEach { assertEquals(ResendStatus.State.FLUSHED, p.status(it).state) }
        assertEquals(listOf("a", "b", "c"),
            events.filterIsInstance<ImcEvent.MessageFlushed>().map { it.id })
        // 一轮 flush 只发一次汇总
        assertEquals(3, events.filterIsInstance<ImcEvent.CacheFlushBatch>().single().flushed)
    }

    @Test
    fun `连接已打开时直发 - 先冲存量再发本条`() {
        val p = pool()
        p.queue("a")
        p.queue("b")
        val w = FakeWriter()
        assertEquals(SendResult.SENT, p.sendOrQueue("c", text("c"), w, allowQueue = true))
        assertEquals(listOf("a", "b", "c"), w.written)
        assertEquals(ResendStatus.State.FLUSHED, p.status("c").state)
    }

    @Test
    fun `不带ID直发 - 已打开就发出`() {
        val p = pool()
        val w = FakeWriter()
        assertEquals(SendResult.SENT, p.sendOrQueue(null, text(null, "hb"), w, allowQueue = true))
        assertEquals(listOf("hb"), w.payloads)
    }

    @Test
    fun `flush 中途写失败 - 剩余条目留在池里按原顺序`() {
        val p = pool()
        listOf("a", "b", "c").forEach { p.queue(it) }
        val w = FakeWriter().apply { failAfter = 1 }
        p.flush(w)
        assertEquals(listOf("a"), w.written)
        assertEquals(listOf("b", "c"), p.ids())
        assertEquals(ResendStatus.State.QUEUED, p.status("b").state)

        val w2 = FakeWriter()
        p.flush(w2)
        assertEquals(listOf("b", "c"), w2.written)
    }

    @Test
    fun `直发失败 - 带ID落回池里`() {
        val p = pool()
        val w = FakeWriter().apply { failAfter = 0 }
        assertEquals(SendResult.QUEUED, p.sendOrQueue("a", text("a"), w, allowQueue = true))
        assertEquals(ResendStatus.State.QUEUED, p.status("a").state)
    }

    @Test
    fun `写出口已关闭 - flush 不动池子`() {
        val p = pool()
        p.queue("a")
        p.flush(FakeWriter().apply { open = false })
        assertEquals(listOf("a"), p.ids())
    }

    // ── take / drain ────────────────────────────────────────────────

    @Test
    fun `take 摘除后内核不再发 - 状态 TAKEN`() {
        val p = pool()
        p.queue("a")
        p.queue("b")
        assertTrue(p.take("a"))
        assertFalse("重复 take 应返回 false", p.take("a"))
        val w = FakeWriter()
        p.flush(w)
        assertEquals(listOf("b"), w.written)
        val st = p.status("a")
        assertEquals(ResendStatus.State.TAKEN, st.state)
        assertEquals(ImcEvent.EvictReason.TAKEN, st.evictReason)
        assertFalse("已 flush 的不能再 take", p.take("b"))
    }

    @Test
    fun `take 与 flush 并发 - 每条恰好被写出或被取走一次`() {
        repeat(20) { round ->
            val p = pool(ResendPoolConfig(maxCount = 10_000, maxBytes = Int.MAX_VALUE))
            val ids = (0 until 300).map { "r$round-$it" }
            ids.forEach { p.queue(it) }
            val w = FakeWriter()
            val taken = ConcurrentHashMap.newKeySet<String>()
            val pool = Executors.newFixedThreadPool(4)
            val start = CountDownLatch(1)
            repeat(3) { t ->
                pool.execute {
                    start.await()
                    ids.filterIndexed { i, _ -> i % 3 == t }.reversed().forEach { if (p.take(it)) taken.add(it) }
                }
            }
            pool.execute { start.await(); p.flush(w) }
            start.countDown()
            pool.shutdown()
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))

            val writtenSet = w.written.toSet()
            assertEquals("写出不应重复", w.written.size, writtenSet.size)
            assertTrue("写出与取走不应有交集", writtenSet.intersect(taken).isEmpty())
            assertEquals("每条都必须有归宿", ids.toSet(), writtenSet + taken)
            assertEquals(0, p.size())
        }
    }

    @Test
    fun `drain 按 FIFO 取走全部 - 标记 TAKEN`() {
        val p = pool()
        listOf("a", "b").forEach { p.queue(it) }
        val out = p.drain()
        assertEquals(listOf("a", "b"), out.map { it.id })
        assertEquals(0, p.size())
        assertEquals(ResendStatus.State.TAKEN, p.status("a").state)
        val w = FakeWriter()
        p.flush(w)
        assertTrue(w.written.isEmpty())
    }

    // ── TTL / 容量 ───────────────────────────────────────────────────

    @Test
    fun `TTL 惰性淘汰 - 查询时发现过期`() {
        val p = pool(ResendPoolConfig(ttlMs = 1_000))
        p.queue("a")
        now.addAndGet(999)
        assertEquals(ResendStatus.State.QUEUED, p.status("a").state)
        now.addAndGet(1)
        val st = p.status("a")
        assertEquals(ResendStatus.State.EVICTED, st.state)
        assertEquals(ImcEvent.EvictReason.TTL_EXPIRED, st.evictReason)
        assertEquals(ImcEvent.EvictReason.TTL_EXPIRED, evicted("a").single().reason)
    }

    @Test
    fun `TTL 过期条目不会在 flush 时被发出`() {
        val p = pool(ResendPoolConfig(ttlMs = 1_000))
        p.queue("old")
        now.addAndGet(500)
        p.queue("new")
        now.addAndGet(600)
        val w = FakeWriter()
        p.flush(w)
        assertEquals(listOf("new"), w.written)
        assertEquals(ResendStatus.State.EVICTED, p.status("old").state)
    }

    @Test
    fun `超条数上限 - 淘汰最老的`() {
        val p = pool(ResendPoolConfig(maxCount = 2))
        listOf("a", "b", "c").forEach { p.queue(it) }
        assertEquals(listOf("b", "c"), p.ids())
        assertEquals(ImcEvent.EvictReason.OVER_CAPACITY, p.status("a").evictReason)
    }

    @Test
    fun `超字节上限 - 淘汰最老的`() {
        val p = pool(ResendPoolConfig(maxBytes = 10))
        p.queue("a", "12345")
        p.queue("b", "12345")
        p.queue("c", "123")
        assertEquals(listOf("b", "c"), p.ids())
        assertEquals(ImcEvent.EvictReason.OVER_CAPACITY, evicted("a").single().reason)
    }

    // ── 回收 ────────────────────────────────────────────────────────

    @Test
    fun `会话结束 - 整池回收逐条通知 - 之后不会再发`() {
        val p = pool()
        listOf("a", "b").forEach { p.queue(it) }
        p.flush(FakeWriter().apply { failAfter = 0 }) // 保持在池里
        p.endSession()
        assertEquals(0, p.size())
        listOf("a", "b").forEach {
            val st = p.status(it)
            assertEquals(ResendStatus.State.EVICTED, st.state)
            assertEquals(ImcEvent.EvictReason.SESSION_END, st.evictReason)
            assertEquals(ImcEvent.EvictReason.SESSION_END, evicted(it).single().reason)
        }
        val w = FakeWriter()
        p.flush(w)
        assertTrue(w.written.isEmpty())
    }

    @Test
    fun `会话结束 - 清掉此前的终态记录`() {
        val p = pool()
        p.queue("sent")
        p.flush(FakeWriter())
        assertEquals(ResendStatus.State.FLUSHED, p.status("sent").state)
        p.endSession()
        assertEquals(ResendStatus.State.UNKNOWN, p.status("sent").state)
    }

    @Test
    fun `换账号 - 旧账号消息整池回收 - 首次绑定不清空`() {
        val p = pool()
        p.queue("anon")
        p.bindOwner("u1")
        assertEquals("首次绑定不清空", listOf("anon"), p.ids())
        p.bindOwner("u1")
        assertEquals(listOf("anon"), p.ids())
        p.queue("u1-msg")
        p.bindOwner("u2")
        assertEquals(0, p.size())
        assertEquals("u2", p.currentOwner())
        assertEquals(ImcEvent.EvictReason.OWNER_CHANGED, p.status("u1-msg").evictReason)
        val w = FakeWriter()
        p.flush(w)
        assertTrue("旧账号的消息绝不能被新账号的连接发出去", w.written.isEmpty())
    }

    @Test
    fun `终态记录按条数上限滚动`() {
        val p = pool(ResendPoolConfig(statusMaxCount = 2))
        listOf("a", "b", "c").forEach { p.queue(it) }
        p.flush(FakeWriter())
        assertEquals(ResendStatus.State.UNKNOWN, p.status("a").state)
        assertEquals(ResendStatus.State.FLUSHED, p.status("c").state)
    }

    @Test
    fun `终态记录按时间过期`() {
        val p = pool(ResendPoolConfig(statusTtlMs = 1_000))
        p.queue("a")
        p.flush(FakeWriter())
        now.addAndGet(1_000)
        assertEquals(ResendStatus.State.UNKNOWN, p.status("a").state)
    }

    // ── 持久化存储接管 ──────────────────────────────────────────────

    @Test
    fun `持久化存储里已有条目 - 新建池时接管并可发出`() {
        val file = tmp.newFile("pool.bin")
        val storage1 = FileMessageCache(file, owner = "u1", maxBytes = Int.MAX_VALUE)
        val p1 = pool(storage = storage1)
        p1.queue("a")
        p1.queue("b")

        val storage2 = FileMessageCache(file, owner = "u1", maxBytes = Int.MAX_VALUE)
        val p2 = pool(storage = storage2)
        assertEquals(listOf("a", "b"), p2.ids())
        val w = FakeWriter()
        p2.flush(w)
        assertEquals(listOf("a", "b"), w.written)
    }

    @Test
    fun `存储层自己丢了条目 - flush 后以 TTL_EXPIRED 通知不让业务干等`() {
        val storage = InMemoryMessageCache(maxBytes = Int.MAX_VALUE, maxAgeMs = 0)
        val p = pool(storage = storage)
        p.queue("a")
        storage.remove("a") // 模拟存储层悄悄丢弃
        p.flush(FakeWriter())
        assertEquals(ResendStatus.State.EVICTED, p.status("a").state)
        assertEquals(1, evicted("a").size)
    }

    @Test
    fun `事件在锁外发出 - sink 里回调池方法不死锁`() {
        val p = pool()
        val reentered = AtomicInteger()
        val reentrant = ImcEventSink { e ->
            if (e is ImcEvent.MessageCached && e.id == "re") {
                p.status("re"); p.ids(); reentered.incrementAndGet()
            }
        }
        ImcEvents.subscribe(reentrant)
        try {
            val t = Thread { p.queue("re") }.apply { start() }
            t.join(3_000)
            assertFalse("sink 回调池方法发生死锁", t.isAlive)
            assertEquals(1, reentered.get())
        } finally {
            ImcEvents.unsubscribe(reentrant)
        }
    }
}
