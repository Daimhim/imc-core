package org.daimhim.imc_core.demo

import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * 接收消息收件箱(进程级单例,只在内存里)。
 *
 * 跟 [LogStore] 互补:[LogStore] 是系统事件 / 调试日志的全集,这里只装"业务收到的消息"。
 * 长跑测试时这个列表常常是真正关心的东西 —— 收发是否对得上、有没有掉、来的顺序对不对。
 *
 * **不持久化**:不写 [FileLogger],避免单测两天的消息撑爆磁盘。需要审计的话从 LogStore 找 `[recv:*]` 行。
 *
 * **结构化**:文本和字节是两种 [Kind],字节原样保留以便 hex 视图;预览串(`previewUtf8`)
 * 只用于列表行显示,避免大消息每次都重新 decode UTF-8。
 *
 * **线程安全**:任意线程 [append],UI 线程 [snapshot] 即可。
 */
object MessageInbox {

    enum class Kind { TEXT, BINARY }

    /**
     * 一条接收消息。
     *
     * @param seq 自增序号,从 1 开始;snapshot 用倒序展示,但 seq 对应真实接收顺序
     * @param kind 文本还是字节
     * @param ts 接收时间(epoch ms)
     * @param size 字节数(text 取 UTF-8 编码字节数,binary 直接 data.size)
     * @param text 文本消息原文;binary 为 null
     * @param data 字节消息原始数据;text 为 null
     * @param previewUtf8 列表行显示用:text 直接;binary 把前 [PREVIEW_BYTES] 字节当 UTF-8 试解,失败用 hex
     */
    data class Entry(
        val seq: Long,
        val kind: Kind,
        val ts: Long,
        val size: Int,
        val text: String?,
        val data: ByteArray?,
        val previewUtf8: String,
    ) {
        // ByteArray 默认 equals 是引用比较,data class 自动生成的也跟着 — 自己覆盖避免比较时引用对不上
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Entry) return false
            return seq == other.seq
        }
        override fun hashCode(): Int = seq.hashCode()
    }

    private const val CAP = 1000
    private const val PREVIEW_BYTES = 128

    private val deque = ArrayDeque<Entry>(CAP)
    private val lock = Any()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val seqGen = AtomicLong(0L)
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    fun appendText(text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8).size
        val preview = text.take(PREVIEW_BYTES)
        push(Entry(
            seq = seqGen.incrementAndGet(),
            kind = Kind.TEXT,
            ts = System.currentTimeMillis(),
            size = bytes,
            text = text,
            data = null,
            previewUtf8 = preview,
        ))
    }

    fun appendBinary(data: ByteArray) {
        // 拷贝一份避免外部修改 — 收件箱要长期持有原始字节
        val copy = data.copyOf()
        val preview = previewFromBytes(copy)
        push(Entry(
            seq = seqGen.incrementAndGet(),
            kind = Kind.BINARY,
            ts = System.currentTimeMillis(),
            size = copy.size,
            text = null,
            data = copy,
            previewUtf8 = preview,
        ))
    }

    private fun push(entry: Entry) {
        synchronized(lock) {
            deque.addFirst(entry)
            while (deque.size > CAP) deque.removeLast()
        }
        for (l in listeners) try { l() } catch (_: Exception) {}
    }

    /** 最新→最旧 */
    fun snapshot(): List<Entry> = synchronized(lock) { ArrayList(deque) }

    /** 按 kind / 关键字过滤(关键字搜 previewUtf8,忽略大小写) */
    fun snapshot(kind: Kind? = null, keyword: String? = null): List<Entry> = synchronized(lock) {
        val kw = keyword?.takeIf { it.isNotBlank() }?.lowercase()
        deque.filter { e ->
            (kind == null || e.kind == kind) &&
                (kw == null || e.previewUtf8.lowercase().contains(kw))
        }
    }

    fun clear() {
        synchronized(lock) {
            deque.clear()
            seqGen.set(0L)
        }
        for (l in listeners) try { l() } catch (_: Exception) {}
    }

    fun size(): Int = synchronized(lock) { deque.size }

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }

    fun formatTime(ts: Long): String = timeFmt.format(Date(ts))

    /**
     * 字节消息列表预览:先按 UTF-8 试解,如果包含大量不可见字符就退到 hex。
     * 阈值粗:>20% 控制字符或替换符就判为非文本。
     */
    private fun previewFromBytes(data: ByteArray): String {
        val take = data.size.coerceAtMost(PREVIEW_BYTES)
        val slice = data.copyOf(take)
        val utf8 = try { String(slice, Charsets.UTF_8) } catch (_: Exception) { null }
        if (utf8 != null) {
            val total = utf8.length
            if (total == 0) return "<empty>"
            val bad = utf8.count { c -> c < ' ' && c != '\n' && c != '\t' || c == '�' }
            if (bad * 5 < total) return utf8  // <20% 异常字符当文本看
        }
        // 退化:hex 串
        val sb = StringBuilder(take * 2)
        for (b in slice) sb.append(String.format("%02x", b.toInt() and 0xFF))
        if (data.size > take) sb.append("…")
        return sb.toString()
    }

    /** 把 Entry.data 渲染成 hex dump(给详情页用) */
    fun renderHexDump(data: ByteArray, bytesPerLine: Int = 16): String {
        val sb = StringBuilder()
        var i = 0
        while (i < data.size) {
            val end = (i + bytesPerLine).coerceAtMost(data.size)
            sb.append(String.format("%04x  ", i))
            for (j in i until end) {
                sb.append(String.format("%02x ", data[j].toInt() and 0xFF))
            }
            // 对齐空格
            for (j in end until i + bytesPerLine) sb.append("   ")
            sb.append(' ')
            for (j in i until end) {
                val c = data[j].toInt() and 0xFF
                sb.append(if (c in 32..126) c.toChar() else '.')
            }
            sb.append('\n')
            i += bytesPerLine
        }
        return sb.toString()
    }
}
