package org.daimhim.imc_core.demo

import java.util.concurrent.atomic.AtomicInteger

/**
 * App 侧的连接事件记录器。SDK 的 ReconnectStatus.retryCount 在连上后会被 stopAutoConnect 清零,
 * 拿不到「累计断开 / 重连成功 / 失败」这种跨周期统计,所以在这里基于 IMCStatusListener 的三个
 * 回调自己累计 + 留一段最近事件环形缓冲,供调试面板回看。
 *
 *   connectionSucceeded → onConnected()  : 若上次是断开态,算一次重连成功
 *   connectionClosed    → onClosed()      : 计入断开
 *   connectionLost      → onLost()        : 计入断开 + 失败(异常断开)
 */
class ReconnectRecorder {

    enum class EventType { CONNECTED, CLOSED, LOST }

    data class Event(val timestampMs: Long, val type: EventType, val detail: String)

    /** 累计断开次数(CLOSED + LOST) */
    val disconnectCount = AtomicInteger(0)
    /** 累计重连成功次数(断开后又连上,不含首次连接) */
    val reconnectSuccessCount = AtomicInteger(0)
    /** 累计异常失败次数(connectionLost) */
    val failureCount = AtomicInteger(0)

    @Volatile var lastDisconnectMs: Long = 0L
        private set
    @Volatile var lastConnectedMs: Long = 0L
        private set

    // 距上次连上之后是否经历过断开;用于区分「首次连上」和「重连成功」
    @Volatile private var disconnectedSinceConnect = false

    private val lock = Any()
    private val events = ArrayDeque<Event>()

    fun onConnected() {
        val now = System.currentTimeMillis()
        lastConnectedMs = now
        val isReconnect = disconnectedSinceConnect
        if (isReconnect) reconnectSuccessCount.incrementAndGet()
        disconnectedSinceConnect = false
        push(Event(now, EventType.CONNECTED, if (isReconnect) "重连成功" else "首次连上"))
    }

    fun onClosed(code: Int, reason: String?) {
        val now = System.currentTimeMillis()
        lastDisconnectMs = now
        disconnectCount.incrementAndGet()
        disconnectedSinceConnect = true
        push(Event(now, EventType.CLOSED, "code=$code${reason?.takeIf { it.isNotEmpty() }?.let { " $it" } ?: ""}"))
    }

    fun onLost(detail: String) {
        val now = System.currentTimeMillis()
        lastDisconnectMs = now
        disconnectCount.incrementAndGet()
        failureCount.incrementAndGet()
        disconnectedSinceConnect = true
        push(Event(now, EventType.LOST, detail))
    }

    /** 最近事件,新→旧 */
    fun recent(limit: Int = MAX_EVENTS): List<Event> = synchronized(lock) {
        events.toList().asReversed().take(limit)
    }

    fun reset() {
        disconnectCount.set(0)
        reconnectSuccessCount.set(0)
        failureCount.set(0)
        lastDisconnectMs = 0L
        lastConnectedMs = 0L
        disconnectedSinceConnect = false
        synchronized(lock) { events.clear() }
    }

    private fun push(e: Event) {
        synchronized(lock) {
            events.addLast(e)
            while (events.size > MAX_EVENTS) events.removeFirst()
        }
    }

    companion object {
        const val MAX_EVENTS = 20
    }
}
