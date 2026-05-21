package org.daimhim.imc_core

import java.util.concurrent.CopyOnWriteArrayList

/**
 * 给抢救逻辑的重连建议
 *
 * `ProgressiveAutoConnect` 等组件应读取这个枚举决定退避时长或是否切换接入点
 */
enum class ReconnectAction {
    /** 立即重试,无延迟 */
    IMMEDIATE,

    /** 常规指数退避 */
    BACKOFF_NORMAL,

    /** 长退避,例如用户没网 */
    BACKOFF_LONG,

    /** 切备用接入点 */
    FAILOVER,

    /** 等用户介入,例如门户认证 / 应用被阻塞 */
    WAIT_USER
}

/**
 * 编排层产出报告
 *
 * @param snapshot 永远是最新的声明层状态
 * @param lastProbe 最近一次主动探测结果,可能过期或为 null
 * @param overall 融合 snapshot + lastProbe 后的最终判定
 * @param recommend 给抢救逻辑的下一步建议
 */
data class NetReport(
    val snapshot: NetSnapshot,
    val lastProbe: ProbeReport?,
    val overall: NetVerdict,
    val recommend: ReconnectAction,
    val timestamp: Long,
    /**
     * 最近一次突发探测(可选)。
     * 只在 [DefaultNetSurveillance.Builder.enableBurst] 开启后才有值,否则 null。
     */
    val lastBurstProbe: BurstProbeReport? = null,
)

fun interface NetReportListener {
    fun onReport(report: NetReport)
}

/**
 * 编排层 (L3) 接口
 *
 * 把声明层 [NetStateMonitor] 与探测层 [NetProber] 信息合流成 [NetReport]
 */
interface NetSurveillance {
    fun start()
    fun stop()

    /** 当前最新报告 */
    fun current(): NetReport

    /** 强制立即探测一次 (绕过限流),回调里给最新报告;无 prober 时直接回当前报告 */
    fun forceProbe(callback: (NetReport) -> Unit)

    /**
     * 运行时切换 burst 周期探测开关。
     *  - 仅在 Builder 配置了 burst 参数后生效(默认实现里 burstIntervalMs 等已设)
     *  - 用于 UI 上让用户随时开/关高耗时探测
     *  - 默认实现为 no-op,自定义实现可不支持
     */
    fun setBurstEnabled(enabled: Boolean) {}

    fun register(listener: NetReportListener)
    fun unregister(listener: NetReportListener)
}

/**
 * 默认编排层实现
 *
 * - 监听声明层变化,按规则决定何时主动探测
 * - 限流:两次自动探测之间至少间隔 `minProbeIntervalMs` (默认 10s)
 * - 防抖:声明层抖动时,等稳定 `debounceMs` (默认 500ms) 再探测
 * - 若未提供 [NetProber] / [ProbeTarget],则退化为纯声明层 (lastProbe 永远为 null)
 *
 * 使用:
 * ```
 * val surveillance = DefaultNetSurveillance.Builder()
 *     .monitor(AndroidNetStateMonitor(context))
 *     .prober(DefaultNetProber())                // 可选
 *     .probeTarget(ProbeTarget("api.example.com", 443, true))   // 可选
 *     .build()
 * surveillance.start()
 * ```
 */
class DefaultNetSurveillance private constructor(builder: Builder) : NetSurveillance {

    private val monitor: NetStateMonitor = builder.monitor
        ?: error("NetStateMonitor is required")
    private val prober: NetProber? = builder.prober
    private val probeTarget: ProbeTarget? = builder.probeTarget
    private val minProbeIntervalMs: Long = builder.minProbeIntervalMs
    private val debounceMs: Long = builder.debounceMs

    /** burst 探测配置(默认关闭) */
    @Volatile
    private var burstEnabled: Boolean = builder.burstEnabled
    private val burstIntervalMs: Long = builder.burstIntervalMs
    private val burstAttempts: Int = builder.burstAttempts
    private val burstPerAttemptTimeoutMs: Int = builder.burstPerAttemptTimeoutMs

    private val listeners = CopyOnWriteArrayList<NetReportListener>()
    private val timer = RapidResponseForceV4()
    private val lock = Any()

    @Volatile
    private var report: NetReport = NetReport(
        snapshot = NetSnapshot.OFFLINE,
        lastProbe = null,
        overall = NetVerdict.OFFLINE,
        recommend = ReconnectAction.BACKOFF_LONG,
        timestamp = 0L
    )

    @Volatile
    private var lastProbeAt: Long = 0L

    @Volatile
    private var started = false

    /** 上一次声明层 verdict,用来识别 OFFLINE → 在线 的恢复瞬间 */
    @Volatile
    private var previousVerdict: NetVerdict = NetVerdict.OFFLINE

    /**
     * 网络恢复宽限期截止时间。
     * 网络刚从断开恢复时设为 now + [RECOVERY_GRACE_MS];这段时间内只要 overall 不是
     * OFFLINE,recommend 一律强制 IMMEDIATE — 不让"刚恢复时 DNS/TCP 还没就绪"的
     * 探测失败把 autoConnect 拖进退避。
     */
    @Volatile
    private var recoveryGraceUntilMs: Long = 0L

    private val monitorListener = NetStateListener { onSnapshotChanged(it) }

    override fun start() {
        synchronized(lock) {
            if (started) return
            started = true
        }
        monitor.register(monitorListener)
        monitor.start()
        applySnapshot(monitor.current())
        // baseline 探测:非立即,走防抖,系统首次推 capabilities 后再探
        scheduleProbe(immediate = false)
        // burst 探测:配置开启时,第一次立即跑,后续按 burstIntervalMs
        if (burstEnabled) scheduleBurst(initialDelayMs = 0L)
    }

    override fun stop() {
        synchronized(lock) {
            if (!started) return
            started = false
        }
        monitor.unregister(monitorListener)
        monitor.stop()
        timer.clear()
        prober?.cancel()
    }

    override fun current(): NetReport = report

    override fun forceProbe(callback: (NetReport) -> Unit) {
        val p = prober
        val t = probeTarget
        if (p == null || t == null) {
            callback(report)
            return
        }
        timer.unregister(PROBE_TASK_ID)
        runProbe(p, t) { callback(report) }
    }

    override fun setBurstEnabled(enabled: Boolean) {
        val wasEnabled: Boolean
        synchronized(lock) {
            wasEnabled = burstEnabled
            burstEnabled = enabled
            if (!started) return
            if (enabled && !wasEnabled) {
                // 用户刚打开 burst → 立即跑一次,不让等一整个间隔
                scheduleBurst(initialDelayMs = 0L)
            } else if (!enabled && wasEnabled) {
                timer.unregister(BURST_TASK_ID)
            }
        }
    }

    override fun register(listener: NetReportListener) {
        if (!listeners.contains(listener)) listeners.add(listener)
    }

    override fun unregister(listener: NetReportListener) {
        listeners.remove(listener)
    }

    private fun onSnapshotChanged(snapshot: NetSnapshot) {
        // 识别 OFFLINE → 在线 的恢复瞬间,开启宽限期
        val justRecovered = previousVerdict == NetVerdict.OFFLINE &&
            snapshot.verdict != NetVerdict.OFFLINE
        previousVerdict = snapshot.verdict
        if (justRecovered) {
            recoveryGraceUntilMs = System.currentTimeMillis() + RECOVERY_GRACE_MS
            IMCLog.i("[surveillance] 网络恢复, 进入 ${RECOVERY_GRACE_MS}ms 宽限期 → 强制 IMMEDIATE")
        }
        applySnapshot(snapshot)
        when (snapshot.verdict) {
            // 结论明确,不浪费探测
            NetVerdict.OFFLINE,
            NetVerdict.BLOCKED,
            NetVerdict.CAPTIVE_PORTAL -> Unit
            // 直接告诉用户连了但不通,立即探测查根因
            NetVerdict.CONNECTED_NOT_VALIDATED -> scheduleProbe(immediate = true)
            // 其他状态等系统抖动稳定后探一次
            else -> scheduleProbe(immediate = false)
        }
    }

    private fun applySnapshot(snapshot: NetSnapshot) {
        val newReport = computeReport(snapshot, report.lastProbe)
        notifyIfChanged(newReport)
    }

    private fun scheduleProbe(immediate: Boolean) {
        val p = prober ?: return
        val t = probeTarget ?: return

        val now = System.currentTimeMillis()
        if (now - lastProbeAt < minProbeIntervalMs && !immediate) return

        val delay = if (immediate) 0L else debounceMs
        timer.register(PROBE_TASK_ID, delay) { runProbe(p, t, callback = null) }
    }

    private fun runProbe(
        p: NetProber,
        t: ProbeTarget,
        callback: (() -> Unit)?
    ) {
        lastProbeAt = System.currentTimeMillis()
        p.probe(t) { probeReport ->
            val newReport = computeReport(monitor.current(), probeReport, report.lastBurstProbe)
            notifyIfChanged(newReport)
            callback?.invoke()
        }
    }

    /**
     * burst 调度:启动 / 用户启用时传 [initialDelayMs] = 0 立即跑;
     * 跑完后递归调用,默认使用 [burstIntervalMs] 排下一次
     */
    private fun scheduleBurst(initialDelayMs: Long = burstIntervalMs) {
        if (!burstEnabled) return
        val p = prober ?: return
        val t = probeTarget ?: return
        timer.register(BURST_TASK_ID, initialDelayMs) {
            p.probeBurst(
                target = t,
                attempts = burstAttempts,
                perAttemptTimeoutMs = burstPerAttemptTimeoutMs,
            ) { burstReport ->
                val newReport = NetReport(
                    snapshot = report.snapshot,
                    lastProbe = report.lastProbe,
                    overall = report.overall,
                    recommend = report.recommend,
                    timestamp = System.currentTimeMillis(),
                    lastBurstProbe = burstReport,
                )
                notifyIfChanged(newReport)
                // 排下一次
                synchronized(lock) {
                    if (started) scheduleBurst()
                }
            }
        }
    }

    private fun computeReport(
        snapshot: NetSnapshot,
        probe: ProbeReport?,
        burstReport: BurstProbeReport? = report.lastBurstProbe,
    ): NetReport {
        val overall = mergeVerdict(snapshot, probe)
        val action = recommend(probe, overall)
        return NetReport(snapshot, probe, overall, action, System.currentTimeMillis(), burstReport)
    }

    /**
     * 融合规则:
     *  - 声明层强信号优先 (BLOCKED / OFFLINE / CAPTIVE_PORTAL 直接定终)
     *  - 其余情况下,探测层精化判定 (例如把 CHECKING_CONNECTIVITY 提级为 CONNECTED_NOT_VALIDATED)
     *  - 探测层若超时,信任声明层
     */
    private fun mergeVerdict(snapshot: NetSnapshot, probe: ProbeReport?): NetVerdict {
        when (snapshot.verdict) {
            NetVerdict.BLOCKED,
            NetVerdict.OFFLINE,
            NetVerdict.CAPTIVE_PORTAL -> return snapshot.verdict
            else -> Unit
        }
        if (probe != null) {
            return when (probe.verdict) {
                ProbeVerdict.NO_INTERNET -> NetVerdict.OFFLINE
                ProbeVerdict.DNS_FAILURE,
                ProbeVerdict.SERVER_DOWN,
                ProbeVerdict.TLS_FAILURE,
                ProbeVerdict.HTTP_DEGRADED -> NetVerdict.CONNECTED_NOT_VALIDATED
                ProbeVerdict.PROBE_TIMEOUT -> snapshot.verdict
                ProbeVerdict.SERVER_REACHABLE -> NetVerdict.OK
            }
        }
        return snapshot.verdict
    }

    /**
     * 决策表:overall + 探测细节 → ReconnectAction
     */
    private fun recommend(
        probe: ProbeReport?,
        overall: NetVerdict
    ): ReconnectAction {
        // 网络恢复宽限期内:只要不是明确离线,一律 IMMEDIATE。
        // 覆盖两种情况 —— 恢复瞬间的 snapshot,以及宽限期里晚到的探测结果。
        if (overall != NetVerdict.OFFLINE &&
            System.currentTimeMillis() < recoveryGraceUntilMs
        ) {
            return ReconnectAction.IMMEDIATE
        }
        return recommendByVerdict(probe, overall)
    }

    private fun recommendByVerdict(
        probe: ProbeReport?,
        overall: NetVerdict
    ): ReconnectAction = when (overall) {
        NetVerdict.OFFLINE -> ReconnectAction.BACKOFF_LONG
        NetVerdict.BLOCKED -> ReconnectAction.WAIT_USER
        NetVerdict.CAPTIVE_PORTAL -> ReconnectAction.WAIT_USER
        NetVerdict.CONNECTED_NOT_VALIDATED -> {
            val publicOk = probe?.publicRef?.success == true
            val publicTested = probe?.publicRef != null
            val tcpOk = probe?.tcp?.success == true
            when {
                publicOk && !tcpOk -> ReconnectAction.FAILOVER
                publicTested && !publicOk -> ReconnectAction.BACKOFF_LONG
                else -> ReconnectAction.BACKOFF_NORMAL
            }
        }
        NetVerdict.SUSPENDED -> ReconnectAction.BACKOFF_LONG
        NetVerdict.CONGESTED -> ReconnectAction.BACKOFF_NORMAL
        NetVerdict.CHECKING_CONNECTIVITY -> ReconnectAction.BACKOFF_NORMAL
        NetVerdict.OK -> ReconnectAction.IMMEDIATE
    }

    private fun notifyIfChanged(next: NetReport) {
        synchronized(lock) {
            if (sameMeaning(next, report)) return
            report = next
        }
        listeners.forEach {
            try {
                it.onReport(next)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /** 比较两个 [NetReport] 是否实质相同,忽略 timestamp 字段 */
    private fun sameMeaning(a: NetReport, b: NetReport): Boolean {
        if (a.overall != b.overall || a.recommend != b.recommend) return false
        if (!sameSnapshot(a.snapshot, b.snapshot)) return false
        if (!sameProbe(a.lastProbe, b.lastProbe)) return false
        if (!sameBurst(a.lastBurstProbe, b.lastBurstProbe)) return false
        return true
    }

    private fun sameSnapshot(a: NetSnapshot, b: NetSnapshot): Boolean =
        a.copy(timestamp = 0L) == b.copy(timestamp = 0L)

    private fun sameProbe(a: ProbeReport?, b: ProbeReport?): Boolean {
        if (a == null && b == null) return true
        if (a == null || b == null) return false
        return a.copy(timestamp = 0L) == b.copy(timestamp = 0L)
    }

    private fun sameBurst(a: BurstProbeReport?, b: BurstProbeReport?): Boolean {
        if (a == null && b == null) return true
        if (a == null || b == null) return false
        return a.copy(timestamp = 0L) == b.copy(timestamp = 0L)
    }

    companion object {
        private const val PROBE_TASK_ID = "net-surveillance-probe"
        private const val BURST_TASK_ID = "net-surveillance-burst"

        /** 网络恢复后的宽限期时长 — 这段时间无视探测结果一律推 IMMEDIATE */
        private const val RECOVERY_GRACE_MS = 3_000L
    }

    class Builder {
        internal var monitor: NetStateMonitor? = null
        internal var prober: NetProber? = null
        internal var probeTarget: ProbeTarget? = null
        internal var minProbeIntervalMs: Long = 10_000L
        internal var debounceMs: Long = 500L

        internal var burstEnabled: Boolean = false
        internal var burstIntervalMs: Long = 30_000L
        internal var burstAttempts: Int = 5
        internal var burstPerAttemptTimeoutMs: Int = 2_000

        fun monitor(m: NetStateMonitor) = apply { this.monitor = m }
        fun prober(p: NetProber) = apply { this.prober = p }
        fun probeTarget(t: ProbeTarget) = apply { this.probeTarget = t }
        fun minProbeIntervalMs(ms: Long) = apply { this.minProbeIntervalMs = ms }
        fun debounceMs(ms: Long) = apply { this.debounceMs = ms }

        /**
         * 启用突发探测,周期性产出 RTT 分布 / 丢包 / 抖动。
         *
         * 关闭(默认)时 [NetReport.lastBurstProbe] 永远是 null,这套机制零开销。
         *
         * @param intervalMs 两次 burst 之间的间隔,默认 30s
         * @param attempts 每次 burst 内的 TCP 连接数,默认 5
         * @param perAttemptTimeoutMs 单次 TCP connect 超时,默认 2s
         */
        fun enableBurst(
            intervalMs: Long = 30_000L,
            attempts: Int = 5,
            perAttemptTimeoutMs: Int = 2_000,
        ) = apply {
            this.burstEnabled = true
            this.burstIntervalMs = intervalMs
            this.burstAttempts = attempts
            this.burstPerAttemptTimeoutMs = perAttemptTimeoutMs
        }

        fun build(): DefaultNetSurveillance = DefaultNetSurveillance(this)
    }
}
