package org.daimhim.imc_core.demo

import org.daimhim.imc_core.NetVerdict

/**
 * 进程级单例。统一记录 WS + 网络状态历史(1Hz 一个 sample),
 * 供 NetWaveActivity 画波浪图回看,默认保留 30 分钟。
 *
 *   外部:每发生事件时 setWs / setNet 更新 last* 缓存;不做事件去重。
 *   ticker:1Hz 调 tickAppend(),把 last* 快照写入 buffer。
 *
 * 这样事件密度高也只压缩成 1Hz,事件密度低空隙也会延续上次状态,
 * 画出的波浪不会因为没有事件就出现空白。
 */
object NetWaveStore {

    enum class WsState { IDLE, CONNECTING, OPEN, CLOSED, LOST }

    data class Sample(
        val timestampMs: Long,
        val wsState: WsState,
        val netVerdict: NetVerdict?,
        val rttMs: Long?,
        val lossRate: Float?,
        val jitterMs: Float?,
        // 心跳: hbMode (FIXED/SMART/null) + 间隔 + 本秒是否有发/失败事件 (delta 检测,非全局累计)
        val hbMode: String?,
        val hbIntervalSec: Int,
        val hbSentInTick: Boolean,
        val hbFailedInTick: Boolean,
    )

    const val MAX_SAMPLES = 1800              // 30 min @ 1Hz
    const val SAMPLE_INTERVAL_MS = 1000L      // 1Hz

    private val buffer = ArrayDeque<Sample>(MAX_SAMPLES)
    private val lock = Any()

    @Volatile var lastWsState: WsState = WsState.IDLE
        private set
    @Volatile var lastNetVerdict: NetVerdict? = null
        private set
    @Volatile var lastRttMs: Long? = null
        private set
    @Volatile var lastLossRate: Float? = null
        private set
    @Volatile var lastJitterMs: Float? = null
        private set

    // 心跳 last* 缓存
    @Volatile var lastHbMode: String? = null
        private set
    @Volatile var lastHbIntervalSec: Int = 0
        private set
    @Volatile var lastHbSent: Int = 0
        private set
    @Volatile var lastHbRecv: Int = 0
        private set
    @Volatile var lastHbFail: Int = 0
        private set
    @Volatile var lastHbSentMs: Long = 0L
        private set
    @Volatile var lastHbFailureMs: Long = 0L
        private set

    // 上次 tickAppend 时看到的 hbSentMs / hbFailureMs,用于在下一个 tick 做 delta 检测
    private var prevTickSentMs = 0L
    private var prevTickFailureMs = 0L

    fun setWs(s: WsState) {
        lastWsState = s
    }

    fun setNet(
        verdict: NetVerdict?,
        rttMs: Long?,
        lossRate: Float?,
        jitterMs: Float?,
    ) {
        lastNetVerdict = verdict
        lastRttMs = rttMs
        lastLossRate = lossRate
        lastJitterMs = jitterMs
    }

    fun setHeartbeat(
        mode: String?,
        intervalSec: Int,
        sent: Int,
        recv: Int,
        fail: Int,
        lastSentMs: Long,
        lastFailureMs: Long,
    ) {
        lastHbMode = mode
        lastHbIntervalSec = intervalSec
        lastHbSent = sent
        lastHbRecv = recv
        lastHbFail = fail
        lastHbSentMs = lastSentMs
        lastHbFailureMs = lastFailureMs
    }

    fun tickAppend(now: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            val sent = lastHbSentMs > 0L && lastHbSentMs != prevTickSentMs
            val failed = lastHbFailureMs > 0L && lastHbFailureMs != prevTickFailureMs
            buffer.addLast(
                Sample(
                    timestampMs = now,
                    wsState = lastWsState,
                    netVerdict = lastNetVerdict,
                    rttMs = lastRttMs,
                    lossRate = lastLossRate,
                    jitterMs = lastJitterMs,
                    hbMode = lastHbMode,
                    hbIntervalSec = lastHbIntervalSec,
                    hbSentInTick = sent,
                    hbFailedInTick = failed,
                )
            )
            prevTickSentMs = lastHbSentMs
            prevTickFailureMs = lastHbFailureMs
            while (buffer.size > MAX_SAMPLES) buffer.removeFirst()
        }
    }

    fun snapshot(): List<Sample> = synchronized(lock) { buffer.toList() }

    fun clear() {
        synchronized(lock) {
            buffer.clear()
            prevTickSentMs = 0L
            prevTickFailureMs = 0L
        }
    }
}
