package org.daimhim.imc_core

/**
 * 探测节奏档位(profile)。封装 [NetSurveillance] 在某种"场景"下应当用多激进的频率探测。
 *
 * 设计来源:对齐心跳模块的 [V2JavaWebEngine.Builder.addHeartbeatMode] / `engine.onChangeMode(key)`
 * —— 网络探测也应当跟随业务场景(前台/后台/灭屏/低电量)动态调档,而不是构造时锁死一组参数。
 *
 * 字段含义:
 *  - [debounceMs] / [minProbeIntervalMs] / [probeTimeoutMs] 控制 **baseline 探测** 的频率和单次预算
 *  - [burstEnabled] / [burstIntervalMs] / [burstAttempts] / [burstPerAttemptTimeoutMs] 控制 **周期 burst 探测**
 *
 * 内置档位:
 *  - [AGGRESSIVE]   用户在等消息 / 业务关键期,5s 限流 + 15s burst
 *  - [BALANCED]     默认前台,10s 限流 + 不开 burst
 *  - [BACKGROUND]   应用后台,60s 限流 + 关 burst + 拉长单段超时
 *  - [LOW_POWER]    极致省电,5min 限流 + 关 burst
 *
 * 业务也可以自定义 profile 实例。运行时调 [NetSurveillance.setProfile] 切换。
 */
data class NetProbeProfile(
    /** 声明层抖动后的去抖延迟,< 这个时间内连续抖动只算一次 */
    val debounceMs: Long,
    /** 两次自动 baseline probe 之间的最小间隔,限流硬地板 */
    val minProbeIntervalMs: Long,
    /** 单次完整 probe 的整体超时(传给 [NetProber.probe] 的 timeoutMs) */
    val probeTimeoutMs: Long,
    /** 是否启用周期性 burst 探测 */
    val burstEnabled: Boolean,
    /** burst 探测周期(下次 burst 间隔) */
    val burstIntervalMs: Long,
    /** 单次 burst 内执行的 TCP attempts 次数 */
    val burstAttempts: Int,
    /** burst 内单次 TCP attempt 超时 */
    val burstPerAttemptTimeoutMs: Int,
) {
    companion object {
        /** 用户在等消息 / 业务关键期:5s 限流 + 15s burst,适合前台对话场景 */
        val AGGRESSIVE = NetProbeProfile(
            debounceMs = 300L,
            minProbeIntervalMs = 5_000L,
            probeTimeoutMs = 8_000L,
            burstEnabled = true,
            burstIntervalMs = 15_000L,
            burstAttempts = 5,
            burstPerAttemptTimeoutMs = 2_000,
        )

        /** 默认前台:10s 限流 + 不开 burst,事件驱动型,大多数场景就用它 */
        val BALANCED = NetProbeProfile(
            debounceMs = 500L,
            minProbeIntervalMs = 10_000L,
            probeTimeoutMs = 12_000L,
            burstEnabled = false,
            burstIntervalMs = 30_000L,
            burstAttempts = 5,
            burstPerAttemptTimeoutMs = 2_000,
        )

        /** 应用后台:60s 限流 + 关 burst + 长 debounce */
        val BACKGROUND = NetProbeProfile(
            debounceMs = 2_000L,
            minProbeIntervalMs = 60_000L,
            probeTimeoutMs = 15_000L,
            burstEnabled = false,
            burstIntervalMs = 120_000L,
            burstAttempts = 3,
            burstPerAttemptTimeoutMs = 3_000,
        )

        /** 极致省电:5min 限流 + 长 debounce + 关 burst,适合灭屏 / Doze / 低电量 */
        val LOW_POWER = NetProbeProfile(
            debounceMs = 5_000L,
            minProbeIntervalMs = 300_000L,
            probeTimeoutMs = 20_000L,
            burstEnabled = false,
            burstIntervalMs = 600_000L,
            burstAttempts = 3,
            burstPerAttemptTimeoutMs = 5_000,
        )
    }
}

/**
 * 探测目标
 *
 * @param host 主机名 (DNS 解析对象)
 * @param port 端口
 * @param useTls TCP 连上后是否做 TLS 握手计时
 * @param healthPath 例如 "/healthz",非空时会发一次 HTTP GET 测健康
 * @param publicReference 公网参考点,用来区分 "我家服务挂了" vs "用户没网";
 *                       常用 `ProbeTarget("www.baidu.com", 443, useTls = true)`
 */
data class ProbeTarget(
    val host: String,
    val port: Int,
    val useTls: Boolean,
    val healthPath: String? = null,
    val publicReference: ProbeTarget? = null
)

/**
 * 单段探测结果
 */
data class ProbeStage(
    val success: Boolean,
    val elapsedMs: Long,
    val error: String? = null
)

/**
 * 探测层判定
 */
enum class ProbeVerdict {
    /** 全段通过,服务端可达 */
    SERVER_REACHABLE,

    /** 公网参考点通,我方接入 TCP 失败 → 应切备用 endpoint */
    SERVER_DOWN,

    /** DNS 解析失败 → 可能 DNS 劫持 / 配置问题 */
    DNS_FAILURE,

    /** TCP 通但 TLS 失败 → 证书 / 时钟 / 中间人 */
    TLS_FAILURE,

    /** 链路通但 /healthz 异常 → 服务端业务问题 */
    HTTP_DEGRADED,

    /** 公网参考点也不通 → 用户真的没网 */
    NO_INTERNET,

    /** 整体超时,网络极差 */
    PROBE_TIMEOUT
}

/**
 * 一次完整的多段探测报告
 *
 * 缺省阶段(没启用 / 没配置)对应字段为 null
 */
data class ProbeReport(
    val target: ProbeTarget,
    val dns: ProbeStage,
    val tcp: ProbeStage,
    val tls: ProbeStage?,
    val httpHealth: ProbeStage?,
    val publicRef: ProbeStage?,
    val verdict: ProbeVerdict,
    val timestamp: Long
)

/**
 * 突发探测报告 — 同一目标做 [attempts] 次 TCP 连接,统计 RTT 分布。
 *
 * 用来度量单次探测看不到的指标:
 *  - 丢包率 ≈ 1 - successes/attempts(TCP-level loss,非 ICMP 包级)
 *  - 抖动 = RTT 的标准差
 *  - 延迟分布 p50/p95
 *
 * `latenciesMs` 只包含成功的 attempt;失败次数 = attempts - successes
 */
data class BurstProbeReport(
    val target: ProbeTarget,
    val attempts: Int,
    val successes: Int,
    val latenciesMs: List<Long>,
    val timestamp: Long,
) {
    /** 0.0 ~ 1.0;attempts=0 时返回 0.0 */
    val lossRate: Double
        get() = if (attempts == 0) 0.0 else 1.0 - successes.toDouble() / attempts

    val minMs: Long? get() = latenciesMs.minOrNull()
    val maxMs: Long? get() = latenciesMs.maxOrNull()
    val meanMs: Double? get() = if (latenciesMs.isEmpty()) null else latenciesMs.average()

    /**
     * 抖动:RTT 序列的样本标准差(Bessel 修正,除以 `n-1`);< 2 次成功无意义返回 null。
     *
     * 行业惯例 RTT 抖动用 sample variance 而非 population variance,小样本(n=5)下
     * 两者会差 ~12%,样本估计更不偏。
     */
    val jitterMs: Double?
        get() {
            val n = latenciesMs.size
            if (n < 2) return null
            val mean = latenciesMs.average()
            val variance = latenciesMs.sumOf { (it - mean) * (it - mean) } / (n - 1)
            return Math.sqrt(variance)
        }

    val p50Ms: Long? get() = percentile(50)
    val p95Ms: Long? get() = percentile(95)

    private fun percentile(p: Int): Long? {
        if (latenciesMs.isEmpty()) return null
        val sorted = latenciesMs.sorted()
        val idx = ((sorted.size - 1) * p / 100).coerceIn(0, sorted.size - 1)
        return sorted[idx]
    }
}

/**
 * 探测层接口
 *
 * 通常在 [NetSurveillance] 编排下调用,也可以让上层直接拿来做单次诊断
 */
interface NetProber {
    /**
     * 执行一次探测,回调在后台线程触发
     *
     * @param target 目标端点
     * @param timeoutMs 整体超时,超过则 verdict=PROBE_TIMEOUT。**默认 12s**,理由:
     *   [DefaultNetProber] 单段 cap 默认 2s,完整链路 DNS+TCP+TLS+HTTP+publicRef ≈ 10s,
     *   + 一段缓冲。如果调用方传入更小的值,实现会逐段用 `min(perStageTimeoutMs, 剩余总预算)`
     *   分摊,避免单段把预算吃光。
     * @param callback 探测完成后的回调,**应在后台线程触发**(实现方需保证异步)
     */
    fun probe(target: ProbeTarget, timeoutMs: Long = 12_000L, callback: (ProbeReport) -> Unit)

    /**
     * 突发探测:顺序做 [attempts] 次 TCP 连接,每次间隔 [intervalMs]。
     * 用来量化 RTT 分布 / 丢包 / 抖动,而不是判定单次连通性。
     *
     * 总耗时上限 = attempts × perAttemptTimeoutMs + (attempts-1) × intervalMs。
     * 默认配置(5 × 2s + 4 × 200ms)极差网下 ≤ 10.8s,通常 < 2s。
     *
     * **回调线程契约**:实现**应在独立线程异步触发** [callback],不要在 [probeBurst] 调用栈
     * 上同步回调 —— `NetSurveillance.scheduleBurst` 会在 callback 里递归排下一次,
     * 同步回调链可能积压调用栈、并把上层锁卡住。默认实现的"立即空报告"行为是为没配 prober 时
     * 留一条空走路径,**不要**作为业务实现的模板。
     *
     * 默认实现回空报告 — 实现类必须重写为真实测量(并保证异步回调)。
     */
    fun probeBurst(
        target: ProbeTarget,
        attempts: Int = 5,
        intervalMs: Long = 200L,
        perAttemptTimeoutMs: Int = 2_000,
        callback: (BurstProbeReport) -> Unit,
    ) {
        callback(BurstProbeReport(target, 0, 0, emptyList(), System.currentTimeMillis()))
    }

    /** 取消正在进行的探测(尽力而为) */
    fun cancel()

    /**
     * 仅取消 burst 路径的探测,baseline probe 不受影响。
     * 默认实现退化为 [cancel];实现类应区分两条 inflight。
     */
    fun cancelBurst() = cancel()
}
