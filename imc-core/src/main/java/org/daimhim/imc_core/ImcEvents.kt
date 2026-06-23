package org.daimhim.imc_core

import java.util.concurrent.CopyOnWriteArrayList

/**
 * IMC 结构化事件总线(进程级 singleton)。
 *
 * **职责**:imc-core 内部把运行时状态变化 emit 出去,上层(监控 UI / 业务上报 /
 * 自动化测试)通过 [subscribe] 订阅并消费。
 *
 * **不是文本日志**:这里只走结构化事件,文本日志见 [IMCLog]。两套系统并行,
 * 互不依赖,避免日志格式约束事件结构。
 *
 * **线程**:
 *  - [subscribe] / [unsubscribe] 任意线程
 *  - [emit] 由 imc-core 内部在 IO / 心跳 / autoConnect / 网络回调等各种线程调用
 *  - Sink 实现必须自己保证线程安全;UI 渲染要切到主线程
 *
 * **回调契约**:sink.onEvent **不应阻塞 / 不应抛异常**;长操作请自行排到独立线程。
 * 抛出的异常会被吞掉(写到 [IMCLog] 一行)以避免影响 imc-core 的关键路径,
 * 但**不会**重试或保留事件;sink 想要"全量不丢"应自行做持久化。
 *
 * **零订阅零开销**:[sinks] 是 CopyOnWriteArrayList,空时 emit 只是一次 isEmpty 判断。
 */
object ImcEvents {

    private val sinks = CopyOnWriteArrayList<ImcEventSink>()

    /**
     * 全局开关。`false` 时 emit 直接 return,不分发也不计费,可在性能敏感场景临时关闭。
     */
    @Volatile
    var enabled: Boolean = true

    /**
     * 订阅事件。重复订阅同一 sink 实例只会注册一次(基于 == 判断)。
     */
    fun subscribe(sink: ImcEventSink) {
        if (!sinks.contains(sink)) sinks.add(sink)
    }

    fun unsubscribe(sink: ImcEventSink) {
        sinks.remove(sink)
    }

    /** 移除全部 sink,典型用于测试 setUp/tearDown 之间清场 */
    fun clearSubscribers() {
        sinks.clear()
    }

    /** 当前订阅数;调试用 */
    fun subscriberCount(): Int = sinks.size

    /**
     * 内部触发事件。**仅 imc-core 内部调用**,业务侧不应直接 emit。
     *
     * 实现:CopyOnWriteArrayList.forEach 是 snapshot 迭代,中途新增 / 删除 sink 不会
     * 影响本次分发,也不会抛 CME。
     */
    @JvmStatic
    internal fun emit(event: ImcEvent) {
        if (!enabled || sinks.isEmpty()) return
        // InternalError 自己出错时不再升级 → 防止"坏 sink 让 InternalError 无限自激"
        val suppressCascade = event is ImcEvent.InternalError
        for (sink in sinks) {
            try {
                sink.onEvent(event)
            } catch (e: Exception) {
                if (suppressCascade) {
                    // 兜底:总线链路坏了,只能打到 stderr
                    e.printStackTrace()
                } else {
                    // 把 sink 异常作为 InternalError 重新分发,其它正常 sink 仍能拿到信号。
                    // 嵌套深度最大 2:正常事件 → InternalError → printStackTrace(被 suppress)
                    emit(
                        ImcEvent.InternalError(
                            site = "ImcEvents.emit/${event.javaClass.simpleName}",
                            errorClass = e.javaClass.simpleName,
                            message = e.message,
                        )
                    )
                }
            }
        }
    }
}

/**
 * 事件订阅接口。
 *
 * 单方法接口,Kotlin 可直接传 lambda:`ImcEvents.subscribe { event -> ... }`。
 *
 * **注意**:onEvent 在 emit 线程触发(IO / 心跳 / autoConnect 等),所有上层处理
 * 必须自己负责线程切换。
 */
fun interface ImcEventSink {
    fun onEvent(event: ImcEvent)
}

/**
 * 内存 ring buffer:订阅 [ImcEvents],按 FIFO 保留最近 [capacity] 条事件。
 *
 * 典型用法:
 * ```
 * val buffer = ImcEventRingBuffer(capacity = 500)
 * ImcEvents.subscribe(buffer)
 * // ... 某个时刻 ...
 * val recentEvents = buffer.snapshot()  // List<ImcEvent>,UI 渲染时间轴
 * ```
 *
 * **线程安全**:put / snapshot / clear 全部 synchronized,任意线程都能调。
 * snapshot 返回的是 *副本*,迭代过程中 buffer 继续接收新事件,但快照不受影响。
 *
 * **不要订阅多个相同 buffer**:每个事件会被存多份。
 */
class ImcEventRingBuffer(val capacity: Int = DEFAULT_CAPACITY) : ImcEventSink {

    init { require(capacity > 0) { "capacity 必须 > 0,当前 $capacity" } }

    private val lock = Any()
    // ArrayDeque 在头尾增删都是 O(1),适合 ring buffer 语义
    private val deque = ArrayDeque<ImcEvent>(capacity)

    override fun onEvent(event: ImcEvent) {
        synchronized(lock) {
            if (deque.size >= capacity) deque.removeFirst()
            deque.addLast(event)
        }
    }

    /** 返回当前所有事件的副本(时间升序),UI 安全使用 */
    fun snapshot(): List<ImcEvent> = synchronized(lock) { deque.toList() }

    /** 当前缓存条数 */
    fun size(): Int = synchronized(lock) { deque.size }

    /** 清空缓存 */
    fun clear() { synchronized(lock) { deque.clear() } }

    /** 按 category 过滤的快照,UI 分页用 */
    fun snapshot(category: ImcEvent.Category): List<ImcEvent> =
        synchronized(lock) { deque.filter { it.category == category } }

    companion object {
        const val DEFAULT_CAPACITY = 500
    }
}
