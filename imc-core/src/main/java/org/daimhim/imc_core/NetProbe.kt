package org.daimhim.imc_core

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

    /** 抖动:RTT 序列的标准差;< 2 次成功无意义返回 null */
    val jitterMs: Double?
        get() {
            if (latenciesMs.size < 2) return null
            val mean = latenciesMs.average()
            val variance = latenciesMs.sumOf { (it - mean) * (it - mean) } / latenciesMs.size
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
     * @param timeoutMs 整体超时,超过则 verdict=PROBE_TIMEOUT
     * @param callback 探测完成后的回调
     */
    fun probe(target: ProbeTarget, timeoutMs: Long = 5_000L, callback: (ProbeReport) -> Unit)

    /**
     * 突发探测:顺序做 [attempts] 次 TCP 连接,每次间隔 [intervalMs]。
     * 用来量化 RTT 分布 / 丢包 / 抖动,而不是判定单次连通性。
     *
     * 总耗时上限 = attempts × perAttemptTimeoutMs + (attempts-1) × intervalMs。
     * 默认配置(5 × 2s + 4 × 200ms)极差网下 ≤ 10.8s,通常 < 2s。
     *
     * 默认实现回空报告 — 实现类可重写为真实测量
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
}
