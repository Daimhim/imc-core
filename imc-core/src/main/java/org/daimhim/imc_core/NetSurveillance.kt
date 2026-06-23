package org.daimhim.imc_core

import java.util.concurrent.CopyOnWriteArrayList

/**
 * 给抢救逻辑的重连建议
 *
 * `ProgressiveAutoConnect` 等组件应读取这个枚举决定退避时长或是否切换接入点
 */
enum class ReconnectAction {
    /**
     * 立即重试。
     *
     * **注意**:这只是"决策层"信号,真正的调度延迟由 [IAutoConnect] 实现决定。
     * 默认 [ProgressiveAutoConnect] 把 IMMEDIATE 映射成 1s 最小延迟
     * ([ProgressiveAutoConnect.IMMEDIATE_DELAY_MS]),原因是 AlarmManager
     * 在部分 ROM 上对短延时(<1s) 会被批处理吞掉。
     */
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

    /**
     * 强制立即探测一次 (绕过限流),回调里给最新报告;无 prober 时直接回当前报告。
     *
     * **副作用**:本次探测的 `lastProbeAt` 会被更新,因此随后 `minProbeIntervalMs` 内
     * 触发的自动 `scheduleProbe(immediate=false)` 仍会被限流跳过。换句话说 forceProbe
     * 会消费一次限流配额,而不是单纯"插队"。
     */
    fun forceProbe(callback: (NetReport) -> Unit)

    /**
     * 当前生效的探测档位。
     */
    fun currentProfile(): NetProbeProfile

    /**
     * 运行时切换探测档位。所有 baseline / burst 参数立即按新 profile 生效。
     *
     * 切换语义:
     *  - 频率参数(`debounceMs` / `minProbeIntervalMs` / `probeTimeoutMs` / `burstAttempts` /
     *    `burstPerAttemptTimeoutMs`):下次 scheduleProbe / 下次 burst 自动用新值
     *  - `burstEnabled` 翻转:开 → 立即跑一次 + 排周期;关 → 取消已排定时器 + cancelBurst 中断进行中
     *  - `burstIntervalMs` 变化:重排下一次 burst,本次跑完按新间隔
     *
     * 默认实现 no-op(实现可不支持档位切换)。
     */
    fun setProfile(profile: NetProbeProfile) {}

    /**
     * 运行时单独切换 burst 开关,等价于 `setProfile(currentProfile().copy(burstEnabled = enabled))`。
     *
     * 保留独立 API 是因为前台/后台切换里 burst 开关是最常调的旋钮,业务调一行更顺手。
     * 默认实现 no-op,自定义实现可不支持。
     */
    fun setBurstEnabled(enabled: Boolean) {}

    fun register(listener: NetReportListener)
    fun unregister(listener: NetReportListener)
}

/**
 * 默认编排层实现
 *
 * - 监听声明层变化,按规则决定何时主动探测
 * - 探测频率 / burst 行为由 [NetProbeProfile] 控制,可运行时 [setProfile] 切换档位
 * - 若未提供 [NetProber] / [ProbeTarget],则退化为纯声明层 (lastProbe 永远为 null)
 *
 * 使用:
 * ```
 * val surveillance = DefaultNetSurveillance.Builder()
 *     .monitor(AndroidNetStateMonitor(context))
 *     .prober(DefaultNetProber())                // 可选
 *     .probeTarget(ProbeTarget("api.example.com", 443, true))   // 可选
 *     .profile(NetProbeProfile.BALANCED)         // 默认就是 BALANCED,可省
 *     .build()
 * surveillance.start()
 *
 * // 业务前台/后台切换时:
 * surveillance.setProfile(NetProbeProfile.AGGRESSIVE)
 * surveillance.setProfile(NetProbeProfile.BACKGROUND)
 * ```
 */
class DefaultNetSurveillance private constructor(builder: Builder) : NetSurveillance {

    private val monitor: NetStateMonitor = builder.monitor
        ?: error("NetStateMonitor is required")
    private val prober: NetProber? = builder.prober
    private val probeTarget: ProbeTarget? = builder.probeTarget

    /**
     * 当前 profile —— 探测频率 / burst 参数的单一来源。
     * 所有 scheduleProbe / scheduleBurst / probe 都读这个字段,@Volatile 保证跨线程可见。
     */
    @Volatile
    private var currentProfile: NetProbeProfile = builder.profile

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
        ImcEvents.emit(ImcEvent.SurveillanceStarted())
        // 顺序很关键:
        //  1) monitor.start() —— 让平台层先订阅 NetworkCallback / NetCheckDetect 等。
        //     在此之前 monitor.current() 必然是 OFFLINE 初始值,直接 applySnapshot 会
        //     给 listener 推一个虚假的"刚启动就离线"报告。
        //  2) register listener —— 错过 start 那一瞬的事件 OK,系统首次 onCapabilitiesChanged 会补。
        //  3) applySnapshot 只在 monitor.current() 已经有真实数据时才推一次。OFFLINE 初始值就跳过,
        //     等 monitor 的 onCapabilitiesChanged 自然触发。
        monitor.start()
        monitor.register(monitorListener)
        val initial = monitor.current()
        if (initial != NetSnapshot.OFFLINE || initial.timestamp > 0L) {
            // monitor.current() 已被回调更新过(非初始 OFFLINE),信任它
            applySnapshot(initial)
        }
        // baseline 探测:非立即,走防抖,系统首次推 capabilities 后再探
        scheduleProbe(immediate = false)
        // burst 探测:profile.burstEnabled 时,第一次立即跑,后续按 burstIntervalMs
        if (currentProfile.burstEnabled) scheduleBurst(initialDelayMs = 0L)
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
        // stop 是 surveillance 的终态;listener 应当一并清掉,避免再次 start 时老 listener 漏接。
        // 业务想在 restart 后继续收报告应在 start 之后重新 register。
        listeners.clear()
        ImcEvents.emit(ImcEvent.SurveillanceStopped())
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

    override fun currentProfile(): NetProbeProfile = currentProfile

    override fun setProfile(profile: NetProbeProfile) {
        val oldProfile = synchronized(lock) {
            val old = currentProfile
            if (old == profile) return
            currentProfile = profile
            if (started) applyProfileChange(old, profile)
            old
        }
        ImcEvents.emit(ImcEvent.NetProfileChanged(oldProfile, profile))
    }

    override fun setBurstEnabled(enabled: Boolean) {
        // 等价于 setProfile(currentProfile.copy(burstEnabled = enabled));
        // 走相同路径让 burst 切换逻辑只在一处。
        setProfile(currentProfile.copy(burstEnabled = enabled))
    }

    /**
     * profile 切换的副作用处理(必须持 lock 调用)。
     *  - burstEnabled 翻转 → 起/停 burst 定时器
     *  - 仅 burstIntervalMs 变 → 重排下一次 burst
     *  - 频率参数变化(debounce / minInterval / probeTimeout)→ 自然在下次 scheduleProbe 生效,无需立即重排
     */
    private fun applyProfileChange(old: NetProbeProfile, new: NetProbeProfile) {
        when {
            new.burstEnabled && !old.burstEnabled -> {
                scheduleBurst(initialDelayMs = 0L)
            }
            !new.burstEnabled && old.burstEnabled -> {
                timer.unregister(BURST_TASK_ID)
                // 已经在 prober.probeBurst 里跑的那一次也一并中断,避免"关了还在探"的尾巴。
                // cancelBurst 默认实现退化为 cancel,自定义 prober 可精细区分 baseline / burst。
                try { prober?.cancelBurst() } catch (e: Exception) { e.printStackTrace() }
            }
            new.burstEnabled && old.burstEnabled && new.burstIntervalMs != old.burstIntervalMs -> {
                // 周期变了但仍开着 → 按新周期重排下一次。
                timer.unregister(BURST_TASK_ID)
                scheduleBurst(initialDelayMs = new.burstIntervalMs)
            }
            else -> { /* 其它字段下次 scheduleProbe 自然生效 */ }
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
        val profile = currentProfile

        val now = System.currentTimeMillis()
        if (now - lastProbeAt < profile.minProbeIntervalMs && !immediate) return

        val delay = if (immediate) 0L else profile.debounceMs
        timer.register(PROBE_TASK_ID, delay) { runProbe(p, t, callback = null) }
    }

    private fun runProbe(
        p: NetProber,
        t: ProbeTarget,
        callback: (() -> Unit)?
    ) {
        lastProbeAt = System.currentTimeMillis()
        // probeTimeoutMs 由 profile 决定,前台档可以更激进
        p.probe(t, currentProfile.probeTimeoutMs) { probeReport ->
            val newReport = computeReport(monitor.current(), probeReport, report.lastBurstProbe)
            notifyIfChanged(newReport)
            callback?.invoke()
        }
    }

    /**
     * burst 调度:启动 / 用户启用时传 [initialDelayMs] = 0 立即跑;
     * 跑完后递归调用,使用当时 profile 的 burstIntervalMs 排下一次。
     *
     * 注意 register 用的 task id 是固定的 [BURST_TASK_ID],RRFv4 同 id 自动覆盖,
     * profile 切换里 unregister + 新 register 是干净的。
     */
    private fun scheduleBurst(initialDelayMs: Long = currentProfile.burstIntervalMs) {
        val profile = currentProfile
        if (!profile.burstEnabled) return
        val p = prober ?: return
        val t = probeTarget ?: return
        timer.register(BURST_TASK_ID, initialDelayMs) {
            // 这里再次读最新 currentProfile,因为 callback 触发时 profile 可能已经被改过
            val curProfile = currentProfile
            p.probeBurst(
                target = t,
                attempts = curProfile.burstAttempts,
                perAttemptTimeoutMs = curProfile.burstPerAttemptTimeoutMs,
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
                // 排下一次。读 currentProfile 而不是闭包外 profile,跟随档位切换。
                synchronized(lock) {
                    if (started && currentProfile.burstEnabled) scheduleBurst()
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
     *  - 探测层若超时:
     *      * 声明层 = OK → 降级为 CONNECTED_NOT_VALIDATED(probe 超时说明应用接入点大概率卡了,
     *        让 recommend 走 BACKOFF_NORMAL 而不是被 OK 推向 IMMEDIATE 风暴)
     *      * 其他声明层 → 信任声明层
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
                ProbeVerdict.PROBE_TIMEOUT ->
                    if (snapshot.verdict == NetVerdict.OK) NetVerdict.CONNECTED_NOT_VALIDATED
                    else snapshot.verdict
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
        // 网络恢复宽限期内:对"软"终态(NOT_VALIDATED / SUSPENDED / CONGESTED / OK 等)强制 IMMEDIATE,
        // 不让"刚恢复时 DNS/TCP 还没就绪"的探测失败把 autoConnect 拖进退避。
        //
        // **但硬终态保持自己的语义**:
        //  - OFFLINE: 都没网,IMMEDIATE 毫无意义
        //  - CAPTIVE_PORTAL: 等用户认证,IMMEDIATE 只会浪费资源
        //  - BLOCKED: 应用被网络策略拦,IMMEDIATE 同上
        // 这些走 recommendByVerdict 各自的 WAIT_USER / BACKOFF_LONG 路径。
        val isHardTerminal = overall == NetVerdict.OFFLINE ||
            overall == NetVerdict.CAPTIVE_PORTAL ||
            overall == NetVerdict.BLOCKED
        if (!isHardTerminal && System.currentTimeMillis() < recoveryGraceUntilMs) {
            return ReconnectAction.IMMEDIATE
        }
        return recommendByVerdict(probe, overall)
    }

    private fun recommendByVerdict(
        probe: ProbeReport?,
        overall: NetVerdict
    ): ReconnectAction = when (overall) {
        NetVerdict.OFFLINE -> ReconnectAction.BACKOFF_LONG
        // P2 修复:BLOCKED 来自 Android onBlockedStatusChanged(blocked=true),典型是后台应用进 Doze /
        // 数据保护 / 后台数据限制时 OS 临时挡住 socket —— 它会随 Doze 维护窗口 / 应用回前台 / 网络变化
        // **自动解除**,不需要用户介入。映射成 WAIT_USER(永久放弃、等用户手动 makeConnection)会让后台
        // 连接被 Doze 挡一下就再也不重连(06-15 实测:03:39 进 BLOCKED 一直到 04:29 才靠 OS 翻回 OK 恢复,
        // 中间 ~50min 完全不抢救)。改成 BACKOFF_LONG:慢速续命重试(省电),block 解除时
        // onBlockedStatusChanged(false) → verdict 回 OK → onSurveillanceUpdate 立刻重排 IMMEDIATE 恢复。
        NetVerdict.BLOCKED -> ReconnectAction.BACKOFF_LONG
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
        val prev: NetReport
        val changedSnapshot: Boolean
        val changedReport: Boolean
        synchronized(lock) {
            prev = report
            if (sameMeaning(next, prev)) return
            // 拆 snapshot 变化 vs report 整体变化两条事件,UI 可分开渲染
            changedSnapshot = !sameSnapshot(next.snapshot, prev.snapshot)
            changedReport = next.overall != prev.overall ||
                next.recommend != prev.recommend ||
                !sameProbe(next.lastProbe, prev.lastProbe)
            report = next
        }
        // 锁外:本地业务 listener
        listeners.forEach {
            try {
                it.onReport(next)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        // 锁外:全局事件总线
        if (changedSnapshot) {
            val s = next.snapshot
            ImcEvents.emit(
                ImcEvent.NetSnapshotChanged(
                    verdict = s.verdict,
                    capabilities = s.capabilities,
                    transports = s.transports,
                    linkUpKbps = s.linkUpKbps,
                    linkDownKbps = s.linkDownKbps,
                    signalStrengthDbm = s.signalStrengthDbm,
                )
            )
        }
        if (changedReport) {
            ImcEvents.emit(
                ImcEvent.NetReportChanged(
                    overall = next.overall,
                    recommend = next.recommend,
                    probeVerdict = next.lastProbe?.verdict,
                )
            )
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

    // NetSnapshot.equals 已忽略 timestamp,直接用 ==
    private fun sameSnapshot(a: NetSnapshot, b: NetSnapshot): Boolean = a == b

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

        /** 初始 profile;默认 [NetProbeProfile.BALANCED] */
        internal var profile: NetProbeProfile = NetProbeProfile.BALANCED

        fun monitor(m: NetStateMonitor) = apply { this.monitor = m }
        fun prober(p: NetProber) = apply { this.prober = p }
        fun probeTarget(t: ProbeTarget) = apply { this.probeTarget = t }

        /**
         * 设置初始探测档位。运行时仍可通过 [NetSurveillance.setProfile] 切换。
         *
         * 不调 = [NetProbeProfile.BALANCED] 默认前台档。
         */
        fun profile(p: NetProbeProfile) = apply { this.profile = p }

        fun build(): DefaultNetSurveillance = DefaultNetSurveillance(this)
    }
}
