package org.daimhim.imc_core.demo

import org.daimhim.imc_core.ImcEvent
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Demo 共享日志缓冲(进程级单例)。
 *
 * **结构化条目**:不再是纯字符串,而是带 [Level] / [Source] / category / tag / 可选 [event] 的 [LogEntry]。
 * 这样 FullLogActivity 能做分类筛选、关键字搜索、按级别着色,SDK 结构化事件能保留原始引用以便深入展示。
 *
 * 两条入口:
 *  - [appendApp] —— demo 自己的字符串埋点(连接尝试、UI 动作、压测进度等)
 *  - [appendEvent] —— SDK 的 [ImcEvent],由 [ImcEventLogBridge] 推入
 *
 * 内存上限 [CAP] 条,溢出丢尾;同时落盘 [FileLogger]。
 */
object LogStore {

    /** 日志级别。颜色 / 筛选用 */
    enum class Level { VERBOSE, INFO, WARN, ERROR }

    /** 来源 — APP 是 demo 自己的埋点,SDK 是 imc-core 通过 ImcEvents 来的 */
    enum class Source { APP, SDK }

    /**
     * 单条日志条目。
     *
     * @param ts 写入时间戳(epoch ms)
     * @param source APP 还是 SDK
     * @param level 级别
     * @param category SDK 来的事件保留原 [ImcEvent.Category];APP 埋点为 null
     * @param tag 自由标签,默认 "APP" / "SDK"
     * @param message 文本内容(SDK 事件已被 renderer 渲染成行)
     * @param event 原始 [ImcEvent],SDK 来的保留;APP 埋点为 null。UI 可点击展开看完整字段。
     */
    data class LogEntry(
        val ts: Long,
        val source: Source,
        val level: Level,
        val category: ImcEvent.Category?,
        val tag: String,
        val message: String,
        val event: ImcEvent? = null,
    )

    private const val CAP = 2000
    private val deque = ArrayDeque<LogEntry>(CAP)
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    private val lock = Any()

    /** Demo 自己埋点:`LogStore.appendApp("用户点了连接按钮")` */
    fun appendApp(message: String, level: Level = Level.INFO, tag: String = "APP") {
        val entry = LogEntry(
            ts = System.currentTimeMillis(),
            source = Source.APP,
            level = level,
            category = null,
            tag = tag,
            message = message,
        )
        push(entry)
    }

    /** SDK 来的结构化事件,由 [ImcEventLogBridge] 调 */
    fun appendEvent(event: ImcEvent, renderedMessage: String, level: Level) {
        val entry = LogEntry(
            ts = event.timestamp,
            source = Source.SDK,
            level = level,
            category = event.category,
            tag = "SDK",
            message = renderedMessage,
            event = event,
        )
        push(entry)
    }

    private fun push(entry: LogEntry) {
        synchronized(lock) {
            deque.addFirst(entry)
            while (deque.size > CAP) deque.removeLast()
        }
        // 同步落本地文件(异步入队);文件存格式化字符串方便 adb pull 直接看
        FileLogger.log(formatForFile(entry), tag = entry.tag)
        for (l in listeners) try { l() } catch (_: Exception) {}
    }

    /** 当前全部条目快照(最新→最旧) */
    fun snapshot(): List<LogEntry> = synchronized(lock) { ArrayList(deque) }

    /**
     * 按条件过滤的快照。
     *
     * @param categories null = 全部;空集 = 只看 APP 埋点(没 category 的)
     * @param keyword null/空 = 不过滤;否则忽略大小写匹配 message
     * @param minLevel null = 不过滤;否则只保留 >= minLevel
     */
    fun snapshot(
        categories: Set<ImcEvent.Category>? = null,
        keyword: String? = null,
        minLevel: Level? = null,
    ): List<LogEntry> = synchronized(lock) {
        val kw = keyword?.takeIf { it.isNotBlank() }?.lowercase()
        deque.filter { entry ->
            (categories == null || (entry.category != null && entry.category in categories)) &&
                (kw == null || entry.message.lowercase().contains(kw)) &&
                (minLevel == null || entry.level.ordinal >= minLevel.ordinal)
        }
    }

    fun clear() {
        synchronized(lock) { deque.clear() }
        for (l in listeners) try { l() } catch (_: Exception) {}
    }

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }

    /** UI 渲染用:把 ts 格式化为 HH:mm:ss.SSS */
    fun formatTime(ts: Long): String = timeFmt.format(Date(ts))

    /** 文件落盘格式:`[HH:mm:ss.SSS] [LEVEL] [CATEGORY|APP] message` */
    private fun formatForFile(entry: LogEntry): String {
        val cat = entry.category?.name ?: entry.source.name
        return "[${entry.level.name[0]}] [$cat] ${entry.message}"
    }

    // ── 兼容旧 API:很多地方还在调 LogStore.append(line) ──
    //   保留字符串入口,默认 Level.INFO,Source=APP

    /** @deprecated 用 [appendApp] / [appendEvent];保留是为了不破坏既有调用点。 */
    fun append(line: String, tag: String = "APP") {
        appendApp(message = line, tag = tag)
    }
}
