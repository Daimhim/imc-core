package org.daimhim.imc_core

/**
 * [IEngine.send] 带业务 ID 版本的结果。
 *
 *  - [SENT]     已写入**当前已打开**的 socket(不保证服务端已收到,可靠性仍需业务 ack)
 *  - [QUEUED]   连接未打开,已按业务 ID 进入重发池,由内核在下次连上时发出
 *  - [REJECTED] 既没发出也没入池,**一个字节都没出设备**:未提供业务 ID / 引擎未启动或已 engineOff /
 *               单条超过池容量。业务可放心自行重发
 */
enum class SendResult { SENT, QUEUED, REJECTED }

/**
 * 重发池里某个业务 ID 的状态。
 *
 * @param state       见 [State]
 * @param evictReason 仅 [State.EVICTED] / [State.TAKEN] 时有值
 * @param atMs        进入该状态的时刻(池时钟);[State.UNKNOWN] 为 0
 */
data class ResendStatus(
    val state: State,
    val evictReason: ImcEvent.EvictReason? = null,
    val atMs: Long = 0L,
) {
    enum class State {
        /** 在池中等待连上后发出 */
        QUEUED,
        /** 已写入 socket(直发或池中 flush) */
        FLUSHED,
        /** 被内核丢弃,不会再发:TTL / 池满 / 会话结束 / 换账号 */
        EVICTED,
        /** 被业务 [IResendPool.take] / [IResendPool.drain] 取走,内核不会再发 */
        TAKEN,
        /** 池不认识这个 ID(从未入池,或状态记录已过保留期) */
        UNKNOWN,
    }

    companion object {
        @JvmField
        val UNKNOWN = ResendStatus(State.UNKNOWN)
    }
}

/**
 * 重发池配置。
 *
 * @param maxCount        池中最多条数,超出淘汰最老的([ImcEvent.EvictReason.OVER_CAPACITY])
 * @param maxBytes        池中 payload 总字节上限,超出淘汰最老的;单条超过它直接 [SendResult.REJECTED]
 * @param ttlMs           单条在池中最长停留时间,超时淘汰([ImcEvent.EvictReason.TTL_EXPIRED]);0 = 不限
 * @param statusMaxCount  终态(FLUSHED / EVICTED / TAKEN)记录最多保留条数
 * @param statusTtlMs     终态记录最长保留时间;0 = 不限
 */
data class ResendPoolConfig @JvmOverloads constructor(
    val maxCount: Int = 200,
    val maxBytes: Int = 256 * 1024,
    val ttlMs: Long = 60_000L,
    val statusMaxCount: Int = 256,
    val statusTtlMs: Long = 10 * 60_000L,
)

/**
 * 内核重发池(**可选能力**,业务自行决定用不用)。
 *
 * ## 入池规则
 * 只有调用 [IEngine.send] 时传了**业务唯一 ID**(bizId)、且当时连接未打开的消息才会入池;
 * 不传 bizId 的消息在未连接时直接 [SendResult.REJECTED],内核**不缓存、不重发**。
 * 同一 bizId 重复入池只保留最新一份,内核层面不会因业务重复调用而双发。
 *
 * ## 与业务自有重发的互斥约定
 * 业务要自己重发某条消息前,**必须先 [take]**:返回 true(已从池中摘除)或状态为
 * EVICTED / UNKNOWN 才可以发。[take] 与内核 flush 共用同一把锁,不会出现两边同时发出同一条。
 *
 * ## 回收
 *  - `engineOff`(退出登录 / 会话结束):整池清空,逐条 [ImcEvent.MessageEvicted] (SESSION_END)
 *  - [bindOwner] 换了账号:整池清空(OWNER_CHANGED),绝不会被下一个账号的连接发出去
 *  - TTL / 池满:淘汰并发事件。TTL 是**惰性**检查(入池 / flush / 查询 / [sweep] 时),
 *    不额外开定时器,Doze 下照样成立
 * 内核只在发出([ImcEvent.MessageFlushed])或丢弃([ImcEvent.MessageEvicted])时通知,
 * 两个事件的 `id` 就是 bizId。
 */
interface IResendPool {
    /** 查询 bizId 当前状态;会顺带做一次惰性 TTL 清理 */
    fun status(bizId: String): ResendStatus

    /** 原子摘除:true = 已从池中拿走,内核保证不会再发它;false = 不在池中(已发出 / 已丢弃 / 不认识) */
    fun take(bizId: String): Boolean

    /** 取走池中全部消息(FIFO 顺序),全部标记 TAKEN。典型用于退出登录前把未发消息写回业务存储 */
    fun drain(): List<CachedMessage>

    /** 池中全部 bizId(FIFO 顺序) */
    fun ids(): List<String>

    /** 池中条数 */
    fun size(): Int

    /**
     * 绑定池的持有者(典型:当前账号 ID)。
     * 从一个非空 owner 换到另一个 owner 时整池清空(OWNER_CHANGED)并清掉状态记录;
     * 从 null 首次绑定不清空。
     */
    fun bindOwner(owner: String?)

    fun currentOwner(): String?

    /** 主动触发一次 TTL / 状态记录清理 */
    fun sweep()
}

/**
 * [IResendPool] 的实现。存储委托给 [IMessageCache](默认内存,可换 [FileMessageCache] 做持久化);
 * 容量 / TTL / 状态 / owner 等策略都在本类,存储层只负责按 FIFO 存取。
 *
 * ⚠ 自定义存储不要自带 TTL / 容量淘汰(或设得比 [ResendPoolConfig] 宽松),否则存储层悄悄丢掉的
 * 条目要等本池 TTL 到期才会以 TTL_EXPIRED 通知出来。
 *
 * 锁顺序:本类 lock → storage 内部锁,反向不会发生。
 */
class ResendPool internal constructor(
    private val storage: IMessageCache,
    private val config: ResendPoolConfig,
    private val clock: () -> Long,
) : IResendPool {

    @JvmOverloads
    constructor(
        storage: IMessageCache = InMemoryMessageCache(maxBytes = Int.MAX_VALUE, maxAgeMs = 0),
        config: ResendPoolConfig = ResendPoolConfig(),
    ) : this(storage, config, { System.currentTimeMillis() })

    /** 往 socket 写一条的能力,由 WebSocketClientImpl 提供;抽出来便于单测 */
    internal interface Writer {
        val isOpenForWrite: Boolean

        /** 写失败(典型:刚好被关)抛异常,池会把这条留在池里 */
        fun write(message: CachedMessage)
    }

    private class Entry(val enqueuedAt: Long, val sizeBytes: Int)

    private val lock = Any()

    /** bizId → 元数据,迭代顺序 = 入池顺序(与 storage 的 FIFO 一致) */
    private val index = LinkedHashMap<String, Entry>()
    private var occupiedBytes = 0

    /** 终态记录,迭代顺序 = 进入终态的先后 */
    private val terminal = LinkedHashMap<String, ResendStatus>()

    private var owner: String? = null

    init {
        // 存储里可能已有上次进程留下的条目(FileMessageCache),建索引接管
        synchronized(lock) {
            val existing = ArrayList<CachedMessage>()
            while (true) existing.add(storage.pollFirst() ?: break)
            for (m in existing) {
                storage.put(m)
                index.remove(m.id)?.let { occupiedBytes -= it.sizeBytes }
                index[m.id] = Entry(m.timestampMs, m.sizeBytes)
                occupiedBytes += m.sizeBytes
            }
        }
    }

    // ── 引擎内部入口 ────────────────────────────────────────────────

    /**
     * 发送决策,与 flush / take 在同一把锁下完成:
     * 已打开 → 先把池里存量按 FIFO 冲出去,再直发本条;未打开 → 有 bizId 且允许入池则入池,否则拒绝。
     *
     * @param allowQueue 引擎当前是否在会话中(非 Idle / Closing);false 时一律不入池
     */
    internal fun sendOrQueue(
        bizId: String?,
        message: CachedMessage,
        writer: Writer?,
        allowQueue: Boolean,
    ): SendResult {
        val events = ArrayList<ImcEvent>()
        val result = synchronized(lock) {
            val now = clock()
            sweepLocked(now, events)
            if (writer != null && writer.isOpenForWrite) {
                val drained = flushLocked(writer, now, events)
                if (drained) {
                    val written = try {
                        writer.write(message)
                        true
                    } catch (_: Exception) {
                        false
                    }
                    if (written) {
                        if (bizId != null) {
                            // 同 bizId 若还有旧副本在池里(理论上 flush 已冲掉),直发即代表最新,摘掉旧的
                            removeFromPoolLocked(bizId)
                            markTerminalLocked(bizId, ResendStatus(ResendStatus.State.FLUSHED, atMs = now), now)
                        }
                        return@synchronized SendResult.SENT
                    }
                }
                // 冲到一半断了 / 直发失败:落回下面的入池判断
            }
            if (bizId == null || !allowQueue) return@synchronized SendResult.REJECTED
            if (message.sizeBytes > config.maxBytes) return@synchronized SendResult.REJECTED
            // 同 bizId 去重:只保留最新一份
            removeFromPoolLocked(bizId)
            evictForCapacityLocked(message.sizeBytes, now, events)
            storage.put(message)
            index[bizId] = Entry(now, message.sizeBytes)
            occupiedBytes += message.sizeBytes
            terminal.remove(bizId)
            events.add(ImcEvent.MessageCached(bizId, message is CachedMessage.Text, message.sizeBytes))
            SendResult.QUEUED
        }
        emitAll(events)
        return result
    }

    /** 连接打开 / 有入站流量时调用:按 FIFO 把池里的消息冲出去 */
    internal fun flush(writer: Writer) {
        val events = ArrayList<ImcEvent>()
        synchronized(lock) {
            val now = clock()
            sweepLocked(now, events)
            flushLocked(writer, now, events)
        }
        emitAll(events)
    }

    /** 会话结束(engineOff):整池清空,逐条通知 */
    internal fun endSession() {
        val events = ArrayList<ImcEvent>()
        synchronized(lock) {
            evictAllLocked(ImcEvent.EvictReason.SESSION_END, clock(), events)
        }
        emitAll(events)
    }

    // ── IResendPool ─────────────────────────────────────────────────

    override fun status(bizId: String): ResendStatus {
        val events = ArrayList<ImcEvent>()
        val st = synchronized(lock) {
            val now = clock()
            sweepLocked(now, events)
            val e = index[bizId]
            when {
                e != null -> ResendStatus(ResendStatus.State.QUEUED, atMs = e.enqueuedAt)
                else -> terminal[bizId] ?: ResendStatus.UNKNOWN
            }
        }
        emitAll(events)
        return st
    }

    override fun take(bizId: String): Boolean {
        val events = ArrayList<ImcEvent>()
        val taken = synchronized(lock) {
            if (!index.containsKey(bizId)) return@synchronized false
            val now = clock()
            removeFromPoolLocked(bizId)
            markTerminalLocked(bizId, ResendStatus(ResendStatus.State.TAKEN, ImcEvent.EvictReason.TAKEN, now), now)
            events.add(ImcEvent.MessageEvicted(bizId, ImcEvent.EvictReason.TAKEN))
            true
        }
        emitAll(events)
        return taken
    }

    override fun drain(): List<CachedMessage> {
        val events = ArrayList<ImcEvent>()
        val out = ArrayList<CachedMessage>()
        synchronized(lock) {
            val now = clock()
            while (true) {
                val m = storage.pollFirst() ?: break
                val e = index.remove(m.id) ?: continue // 存储里的孤儿条目:不认识,丢掉
                occupiedBytes -= e.sizeBytes
                out.add(m)
                markTerminalLocked(m.id, ResendStatus(ResendStatus.State.TAKEN, ImcEvent.EvictReason.TAKEN, now), now)
                events.add(ImcEvent.MessageEvicted(m.id, ImcEvent.EvictReason.TAKEN))
            }
            // 存储层已悄悄丢掉的索引残留:同样视作被取走(拿不到内容,但内核也不会再发)
            dropStaleIndexLocked(ImcEvent.EvictReason.TTL_EXPIRED, now, events)
        }
        emitAll(events)
        return out
    }

    override fun ids(): List<String> {
        val events = ArrayList<ImcEvent>()
        val ids = synchronized(lock) {
            sweepLocked(clock(), events)
            index.keys.toList()
        }
        emitAll(events)
        return ids
    }

    override fun size(): Int {
        val events = ArrayList<ImcEvent>()
        val n = synchronized(lock) {
            sweepLocked(clock(), events)
            index.size
        }
        emitAll(events)
        return n
    }

    override fun bindOwner(owner: String?) {
        val events = ArrayList<ImcEvent>()
        synchronized(lock) {
            val prev = this.owner
            if (prev == owner) return@synchronized
            if (prev != null) {
                evictAllLocked(ImcEvent.EvictReason.OWNER_CHANGED, clock(), events)
            }
            this.owner = owner
        }
        emitAll(events)
    }

    override fun currentOwner(): String? = synchronized(lock) { owner }

    override fun sweep() {
        val events = ArrayList<ImcEvent>()
        synchronized(lock) { sweepLocked(clock(), events) }
        emitAll(events)
    }

    // ── 锁内实现 ────────────────────────────────────────────────────

    /** @return true = 池已冲空;false = 中途断了(剩余条目留在池里) */
    private fun flushLocked(writer: Writer, now: Long, events: MutableList<ImcEvent>): Boolean {
        if (index.isEmpty()) {
            // 索引空但存储可能有孤儿,顺手清掉,避免它们被当成待发
            while (storage.pollFirst() != null) Unit
            return true
        }
        var flushed = 0
        var complete = true
        while (true) {
            if (!writer.isOpenForWrite) {
                complete = false
                break
            }
            val m = storage.pollFirst() ?: break
            if (!index.containsKey(m.id)) continue // 孤儿:不认识,不发
            try {
                writer.write(m)
            } catch (_: Exception) {
                storage.pushFirst(m)
                complete = false
                break
            }
            val e = index.remove(m.id)!!
            occupiedBytes -= e.sizeBytes
            markTerminalLocked(m.id, ResendStatus(ResendStatus.State.FLUSHED, atMs = now), now)
            events.add(ImcEvent.MessageFlushed(m.id, m.sizeBytes))
            flushed++
        }
        if (complete) {
            // 存储已空但索引还有残留 = 存储层自己丢了,按 TTL 口径通知,不让业务一直等
            dropStaleIndexLocked(ImcEvent.EvictReason.TTL_EXPIRED, now, events)
        }
        if (flushed > 0) {
            events.add(ImcEvent.CacheFlushBatch(flushed = flushed, remaining = index.size))
        }
        return complete
    }

    private fun sweepLocked(now: Long, events: MutableList<ImcEvent>) {
        if (config.ttlMs > 0 && index.isNotEmpty()) {
            val expired = index.entries
                .filter { now - it.value.enqueuedAt >= config.ttlMs }
                .map { it.key }
            for (id in expired) evictLocked(id, ImcEvent.EvictReason.TTL_EXPIRED, now, events)
        }
        pruneTerminalLocked(now)
    }

    private fun evictForCapacityLocked(incomingBytes: Int, now: Long, events: MutableList<ImcEvent>) {
        while (index.isNotEmpty() &&
            (index.size + 1 > config.maxCount || occupiedBytes + incomingBytes > config.maxBytes)
        ) {
            evictLocked(index.keys.first(), ImcEvent.EvictReason.OVER_CAPACITY, now, events)
        }
    }

    private fun evictAllLocked(reason: ImcEvent.EvictReason, now: Long, events: MutableList<ImcEvent>) {
        val ids = index.keys.toList()
        terminal.clear()
        for (id in ids) evictLocked(id, reason, now, events)
        // 存储里不在索引中的孤儿也一并清掉
        while (storage.pollFirst() != null) Unit
        occupiedBytes = 0
    }

    private fun evictLocked(bizId: String, reason: ImcEvent.EvictReason, now: Long, events: MutableList<ImcEvent>) {
        if (!index.containsKey(bizId)) return
        removeFromPoolLocked(bizId)
        markTerminalLocked(bizId, ResendStatus(ResendStatus.State.EVICTED, reason, now), now)
        events.add(ImcEvent.MessageEvicted(bizId, reason))
    }

    private fun dropStaleIndexLocked(reason: ImcEvent.EvictReason, now: Long, events: MutableList<ImcEvent>) {
        if (index.isEmpty()) return
        for (id in index.keys.toList()) {
            index.remove(id)?.let { occupiedBytes -= it.sizeBytes }
            markTerminalLocked(id, ResendStatus(ResendStatus.State.EVICTED, reason, now), now)
            events.add(ImcEvent.MessageEvicted(id, reason))
        }
    }

    private fun removeFromPoolLocked(bizId: String) {
        val e = index.remove(bizId) ?: return
        occupiedBytes -= e.sizeBytes
        storage.remove(bizId)
    }

    private fun markTerminalLocked(bizId: String, status: ResendStatus, now: Long) {
        terminal.remove(bizId)
        terminal[bizId] = status
        pruneTerminalLocked(now)
    }

    private fun pruneTerminalLocked(now: Long) {
        while (terminal.size > config.statusMaxCount) {
            terminal.remove(terminal.keys.first())
        }
        if (config.statusTtlMs > 0) {
            val it = terminal.entries.iterator()
            while (it.hasNext()) {
                val (_, st) = it.next()
                if (now - st.atMs >= config.statusTtlMs) it.remove() else break
            }
        }
    }

    private fun emitAll(events: List<ImcEvent>) {
        for (e in events) ImcEvents.emit(e)
    }
}
