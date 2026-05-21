package org.daimhim.imc_core.weaknet

/**
 * TCP 字节级 chaos 配置
 *
 * 所有字段在两个方向(c→u, u→c)上**同时生效**。如需单向控制,把 [TcpChaosProxy]
 * 改成持有两份配置即可
 */
data class ChaosConfig(

    // ── 延迟与抖动 ──────────────────────────────────────────────────────

    /** 每个字节块的固定延迟(ms),模拟高 RTT */
    val baseLatencyMs: Long = 0,

    /** 抖动幅度(ms),实际延迟 = baseLatencyMs + random(0..jitterMs] */
    val jitterMs: Long = 0,

    // ── 丢包 ────────────────────────────────────────────────────────────

    /** 字节块丢弃概率 0~100。丢弃后该批字节直接消失,接收端会看到协议错误 / 帧不完整 */
    val dropChunkPercent: Int = 0,

    // ── 带宽限制 ───────────────────────────────────────────────────────

    /** 单向带宽上限(字节/秒),0 = 不限速 */
    val maxBytesPerSecond: Long = 0,

    // ── 异常断开 ───────────────────────────────────────────────────────

    /** 转发够 N 字节后断开;0 = 不触发 */
    val disconnectAfterBytes: Long = 0,

    /** 连接 N 毫秒后断开;0 = 不触发 */
    val disconnectAfterMs: Long = 0,

    // ── 连接级控制 ─────────────────────────────────────────────────────

    /** 直接拒绝新的客户端连接(已建立的不受影响) */
    val rejectNewConnections: Boolean = false,
)
