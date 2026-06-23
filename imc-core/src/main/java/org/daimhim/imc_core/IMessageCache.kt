package org.daimhim.imc_core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/**
 * 缓存的待发送消息(应用层 payload,不含 WebSocket 帧头)。
 *
 * 引擎在断网时把 [V2JavaWebEngine.send] 的内容塞进 [IMessageCache],
 * 重连后由引擎在 flush 时按当前协议层(Draft_6455 等)重新成帧再发出去。
 * 帧不持久化 — 持久化 / 序列化的是 **业务侧 payload**。
 */
sealed class CachedMessage {
    abstract val id: String
    abstract val timestampMs: Long
    abstract val sizeBytes: Int

    data class Text(
        override val id: String,
        val text: String,
        override val timestampMs: Long = System.currentTimeMillis(),
    ) : CachedMessage() {
        // 缓存 / 容量校验路径会反复读 sizeBytes,UTF-8 编码每次重新分配 byte[] 开销可观,
        // 改成 lazy 只算一次。data class 的 equals/hashCode 只看主构造器参数,override 属性不参与,
        // 所以延迟初始化不影响数据类语义。
        @delegate:Transient
        override val sizeBytes: Int by lazy(LazyThreadSafetyMode.PUBLICATION) {
            text.toByteArray(Charsets.UTF_8).size
        }
    }

    class Binary(
        override val id: String,
        val data: ByteArray,
        override val timestampMs: Long = System.currentTimeMillis(),
    ) : CachedMessage() {
        override val sizeBytes: Int get() = data.size

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Binary) return false
            return id == other.id && timestampMs == other.timestampMs && data.contentEquals(other.data)
        }

        override fun hashCode(): Int {
            var r = id.hashCode()
            r = 31 * r + timestampMs.hashCode()
            r = 31 * r + data.contentHashCode()
            return r
        }
    }
}

/**
 * 发送缓存接口。
 *
 * 引擎([V2JavaWebEngine.WebSocketClientImpl])在断网期间按 FIFO 把待发的
 * 文本 / 字节消息推进缓存,重连后调 [pollFirst] 排空。
 *
 * **实现必须线程安全**:引擎自身已在 `cacheSync` 锁内调用,但缓存还可能被
 * 实现自带的 TTL / 淘汰回调访问。
 *
 * 自带实现:
 *  - [InMemoryMessageCache] 默认,纯内存 + FIFO + 字节上限 + 可选 TTL
 *  - [FileMessageCache] 持久化到本地文件,进程重建后可继续重放
 *  - Android SharedPreferences 实现见 demo 模块的 `SharedPrefsMessageCache`
 */
interface IMessageCache {
    /** FIFO push;实现可按容量策略淘汰旧条目 */
    fun put(message: CachedMessage)

    /** FIFO 取头,空时返回 null */
    fun pollFirst(): CachedMessage?

    /** 把刚 [pollFirst] 出来的条目塞回队首 — 用于"flush 中途断了,留给下次回放" */
    fun pushFirst(message: CachedMessage)

    /** 按 id 删除指定条目;返回是否真的删了 */
    fun remove(id: String): Boolean

    /** 当前是否空 */
    fun isEmpty(): Boolean

    /** 清空 */
    fun clear()

    /** 当前条目数 */
    fun size(): Int
}

/**
 * 默认内存缓存。FIFO + 总字节数上限 + 可选 TTL(超时自动淘汰)。
 *
 * @param maxBytes 缓存总字节数上限,超出按 FIFO 从头淘汰。默认 64KB(短消息场景约 60-100 条)
 * @param maxAgeMs 单条最长保留时长;0 关闭 TTL。默认 5s,跟原有 RRFv4 默认对齐
 */
class InMemoryMessageCache @JvmOverloads constructor(
    private val maxBytes: Int = DEFAULT_MAX_BYTES,
    private val maxAgeMs: Long = DEFAULT_MAX_AGE_MS,
) : IMessageCache {

    companion object {
        const val DEFAULT_MAX_BYTES = 64 * 1024
        const val DEFAULT_MAX_AGE_MS = 5_000L
    }

    private val lock = Any()
    private val queue = ArrayDeque<CachedMessage>()
    private var occupied: Int = 0
    private val ttl: RapidResponseForceV4? =
        if (maxAgeMs > 0) RapidResponseForceV4() else null

    private val onTtlExpire: (String) -> Unit = { id ->
        var evictedId: String? = null
        synchronized(lock) {
            val idx = queue.indexOfFirst { it.id == id }
            if (idx >= 0) {
                val m = queue.removeAt(idx)
                occupied -= m.sizeBytes
                evictedId = id
            }
        }
        // 锁外 emit:RRFv4 回调本就在 worker 线程,不会再串到 cacheSync 上
        evictedId?.let { ImcEvents.emit(ImcEvent.MessageEvicted(it, ImcEvent.EvictReason.TTL_EXPIRED)) }
    }

    override fun put(message: CachedMessage) {
        // 锁内淘汰,锁外 emit;evictedIds 一次性收集
        val evictedIds = ArrayList<String>(0)
        synchronized(lock) {
            var newOccupied = occupied + message.sizeBytes
            // 字节超限 → FIFO 从头淘汰
            while (newOccupied > maxBytes && queue.isNotEmpty()) {
                val evicted = queue.removeFirst()
                newOccupied -= evicted.sizeBytes
                ttl?.unregister(evicted.id)
                evictedIds.add(evicted.id)
            }
            queue.addLast(message)
            occupied = newOccupied
            if (maxAgeMs > 0) ttl?.register(message.id, maxAgeMs, onTtlExpire)
        }
        for (id in evictedIds) ImcEvents.emit(ImcEvent.MessageEvicted(id, ImcEvent.EvictReason.OVER_CAPACITY))
    }

    override fun pollFirst(): CachedMessage? = synchronized(lock) {
        val m = queue.removeFirstOrNull() ?: return@synchronized null
        occupied -= m.sizeBytes
        ttl?.unregister(m.id)
        m
    }

    override fun pushFirst(message: CachedMessage) {
        val evictedIds = ArrayList<String>(0)
        synchronized(lock) {
            var newOccupied = occupied + message.sizeBytes
            while (newOccupied > maxBytes && queue.isNotEmpty()) {
                val evicted = queue.removeLast()
                newOccupied -= evicted.sizeBytes
                ttl?.unregister(evicted.id)
                evictedIds.add(evicted.id)
            }
            queue.addFirst(message)
            occupied = newOccupied
            if (maxAgeMs > 0) ttl?.register(message.id, maxAgeMs, onTtlExpire)
        }
        for (id in evictedIds) ImcEvents.emit(ImcEvent.MessageEvicted(id, ImcEvent.EvictReason.OVER_CAPACITY))
    }

    override fun remove(id: String): Boolean = synchronized(lock) {
        val idx = queue.indexOfFirst { it.id == id }
        if (idx < 0) return@synchronized false
        val m = queue.removeAt(idx)
        occupied -= m.sizeBytes
        ttl?.unregister(id)
        true
    }

    override fun isEmpty(): Boolean = synchronized(lock) { queue.isEmpty() }

    override fun clear() {
        val clearedIds = ArrayList<String>()
        synchronized(lock) {
            queue.forEach {
                ttl?.unregister(it.id)
                clearedIds.add(it.id)
            }
            queue.clear()
            occupied = 0
        }
        for (id in clearedIds) ImcEvents.emit(ImcEvent.MessageEvicted(id, ImcEvent.EvictReason.EXPLICIT_CLEAR))
    }

    override fun size(): Int = synchronized(lock) { queue.size }
}

/**
 * 文件持久化的发送缓存。
 *
 * - 全队列序列化成一份二进制文件,每次变更都重写(典型 64KB 量级,写盘量可接受)
 * - 通过 `<name>.tmp` 写完再 rename,降低半截写坏的概率
 * - 进程重启时自动从文件恢复;格式异常 / 版本不匹配 / owner 不匹配会重置文件
 *
 * 二进制布局(big-endian, Java DataOutput 默认):
 * ```
 * MAGIC(4)="IMCC"  VERSION(1)=2  OWNER-UTF(short+UTF8)  COUNT(4)
 * 重复 COUNT 次:
 *   id-UTF  timestamp(8)  type(1: 0=text 1=binary)  len(4)  payload(len)
 * ```
 * VERSION=2 在 V1 基础上加了 OWNER 字段;读到 V1 文件会按 unsupported 重置。
 *
 * @param file 持久化目标文件;路径不存在会自动建父目录
 * @param owner 持有者标识(典型:当前账号 ID)。null = 不做隔离检查,沿用 V1 行为;
 *              非 null 时,加载若发现文件里 owner 跟当前不一致 → **自动 reset**。
 *              典型用法:App 重启或账号切换后,用新账号 ID 创建实例,旧账号残留自动清理。
 * @param maxBytes 字节上限,超出按 FIFO 淘汰,默认 64KB
 */
class FileMessageCache @JvmOverloads constructor(
    private val file: File,
    private val owner: String? = null,
    private val maxBytes: Int = DEFAULT_MAX_BYTES,
) : IMessageCache {

    companion object {
        const val DEFAULT_MAX_BYTES = 64 * 1024
        internal const val MAGIC = 0x494D4343 // "IMCC"
        /** v2: 加了 OWNER 字段;读到 v1 文件按 unsupported 重置(一次性丢失,可接受) */
        internal const val VERSION: Byte = 2
    }

    private val lock = Any()
    private val queue = ArrayDeque<CachedMessage>()
    private var occupied: Int = 0

    init {
        loadFromFile()
    }

    private fun loadFromFile() {
        if (!file.exists() || file.length() == 0L) return
        try {
            DataInputStream(file.inputStream().buffered()).use { dis ->
                val magic = dis.readInt()
                if (magic != MAGIC) {
                    file.delete(); return
                }
                val version = dis.readByte()
                if (version != VERSION) {
                    file.delete(); return
                }
                val storedOwner = dis.readUTF()
                // owner 隔离:当前 owner 非空且跟文件里的不一致 → 旧账号残留,直接清掉
                if (owner != null && storedOwner != owner) {
                    file.delete(); return
                }
                val count = dis.readInt()
                for (i in 0 until count) {
                    queue.addLast(readRecord(dis) ?: continue)
                }
                occupied = queue.sumOf { it.sizeBytes }
            }
        } catch (e: Exception) {
            ImcEvents.emit(ImcEvent.InternalError(
                site = "FileMessageCache.loadFromFile",
                errorClass = e.javaClass.simpleName,
                message = "${e.message} (file=$file)",
            ))
            try { file.delete() } catch (_: Exception) {}
            queue.clear(); occupied = 0
        }
    }

    private fun flushToFile() {
        try {
            val parent = file.parentFile
            if (parent != null && !parent.exists()) parent.mkdirs()
            val tmp = if (parent != null) File(parent, file.name + ".tmp") else File(file.path + ".tmp")
            DataOutputStream(tmp.outputStream().buffered()).use { dos ->
                dos.writeInt(MAGIC)
                dos.writeByte(VERSION.toInt())
                dos.writeUTF(owner ?: "")
                dos.writeInt(queue.size)
                for (m in queue) writeRecord(dos, m)
            }
            if (!tmp.renameTo(file)) {
                // Windows 等平台 rename 不能覆盖,先删再 rename
                file.delete()
                tmp.renameTo(file)
            }
        } catch (e: Exception) {
            ImcEvents.emit(ImcEvent.InternalError(
                site = "FileMessageCache.flushToFile",
                errorClass = e.javaClass.simpleName,
                message = "${e.message} (file=$file)",
            ))
        }
    }

    override fun put(message: CachedMessage) {
        synchronized(lock) {
            var newOccupied = occupied + message.sizeBytes
            while (newOccupied > maxBytes && queue.isNotEmpty()) {
                val evicted = queue.removeFirst()
                newOccupied -= evicted.sizeBytes
            }
            queue.addLast(message)
            occupied = newOccupied
            flushToFile()
        }
    }

    override fun pollFirst(): CachedMessage? = synchronized(lock) {
        val m = queue.removeFirstOrNull() ?: return@synchronized null
        occupied -= m.sizeBytes
        flushToFile()
        m
    }

    override fun pushFirst(message: CachedMessage) {
        synchronized(lock) {
            var newOccupied = occupied + message.sizeBytes
            // 同 InMemoryMessageCache:外部直接调可能越界,从队尾淘汰,保留"插队最先发"语义。
            while (newOccupied > maxBytes && queue.isNotEmpty()) {
                val evicted = queue.removeLast()
                newOccupied -= evicted.sizeBytes
            }
            queue.addFirst(message)
            occupied = newOccupied
            flushToFile()
        }
    }

    override fun remove(id: String): Boolean = synchronized(lock) {
        val idx = queue.indexOfFirst { it.id == id }
        if (idx < 0) return@synchronized false
        val m = queue.removeAt(idx)
        occupied -= m.sizeBytes
        flushToFile()
        true
    }

    override fun isEmpty(): Boolean = synchronized(lock) { queue.isEmpty() }

    override fun clear() {
        synchronized(lock) {
            queue.clear()
            occupied = 0
            try { file.delete() } catch (_: Exception) {}
        }
    }

    override fun size(): Int = synchronized(lock) { queue.size }
}

/**
 * 内部使用:把 [CachedMessage] 序列化到 [DataOutputStream]。
 *
 * FileMessageCache / SharedPrefsMessageCache 用同一份格式,便于跨实现迁移。
 */
internal fun writeRecord(dos: DataOutputStream, m: CachedMessage) {
    dos.writeUTF(m.id)
    dos.writeLong(m.timestampMs)
    when (m) {
        is CachedMessage.Text -> {
            dos.writeByte(0)
            val b = m.text.toByteArray(Charsets.UTF_8)
            dos.writeInt(b.size)
            dos.write(b)
        }
        is CachedMessage.Binary -> {
            dos.writeByte(1)
            dos.writeInt(m.data.size)
            dos.write(m.data)
        }
    }
}

/** 内部使用:从 [DataInputStream] 反序列化一条 [CachedMessage]。未知 type 返回 null */
internal fun readRecord(dis: DataInputStream): CachedMessage? {
    val id = dis.readUTF()
    val ts = dis.readLong()
    val type = dis.readByte().toInt()
    val len = dis.readInt()
    val payload = ByteArray(len)
    dis.readFully(payload)
    return when (type) {
        0 -> CachedMessage.Text(id, String(payload, Charsets.UTF_8), ts)
        1 -> CachedMessage.Binary(id, payload, ts)
        else -> null
    }
}

/** [decodeMessageCacheBlob] 的解码结果 */
data class DecodedMessageBlob(
    /** 文件里记录的 owner;V1 升 V2 后总是有这个字段(可能是 "") */
    val owner: String,
    val messages: List<CachedMessage>,
)

/** 序列化整个队列成 byte[];供 SharedPrefsMessageCache / 其他非文件存储复用。owner 为 null 时存 "" */
fun encodeMessageCacheBlob(queue: List<CachedMessage>, owner: String? = null): ByteArray {
    val baos = ByteArrayOutputStream()
    DataOutputStream(baos).use { dos ->
        dos.writeInt(FileMessageCache.MAGIC)
        dos.writeByte(FileMessageCache.VERSION.toInt())
        dos.writeUTF(owner ?: "")
        dos.writeInt(queue.size)
        for (m in queue) writeRecord(dos, m)
    }
    return baos.toByteArray()
}

/** 反序列化 byte[] 成队列;格式错返回 null */
fun decodeMessageCacheBlob(bytes: ByteArray): DecodedMessageBlob? {
    return try {
        DataInputStream(ByteArrayInputStream(bytes)).use { dis ->
            val magic = dis.readInt()
            if (magic != FileMessageCache.MAGIC) return null
            val version = dis.readByte()
            if (version != FileMessageCache.VERSION) return null
            val owner = dis.readUTF()
            val count = dis.readInt()
            val out = ArrayList<CachedMessage>(count)
            for (i in 0 until count) {
                out.add(readRecord(dis) ?: continue)
            }
            DecodedMessageBlob(owner, out)
        }
    } catch (e: Exception) {
        ImcEvents.emit(ImcEvent.InternalError(
            site = "decodeMessageCacheBlob",
            errorClass = e.javaClass.simpleName,
            message = e.message,
        ))
        null
    }
}
