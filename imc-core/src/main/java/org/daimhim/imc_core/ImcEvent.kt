package org.daimhim.imc_core

/**
 * IMC 内部结构化事件。
 *
 * 设计目标:让上层(监控面板 / 业务上报 / 自动化测试)拿到带类型的运行时状态变化,
 * 而不是文本日志字符串。事件总线见 [ImcEvents]。
 *
 * **不是文本日志**:这里没有 "TextLog" 兜底。每个事件都是明确的状态变化或度量结果,
 * 字段全部结构化。调试用文本输出走独立的 [IMCLog] 通路,跟事件系统解耦。
 *
 * 分类参考 [Category];每个具体事件子类都标明自己属于哪一类,UI 上做筛选用。
 *
 * **线程**:emit 可能在任意线程(WebSocket IO / 心跳 / autoConnect alarm / 网络回调)。
 * Sink 实现必须自己保证线程安全;UI 渲染需要切到主线程。
 */
sealed class ImcEvent {
    /** 事件发生的 epoch 毫秒;由 emit 处填充,sink 不应改 */
    abstract val timestamp: Long

    /** 大类,UI 筛选用 */
    abstract val category: Category

    enum class Category {
        /** 引擎生命周期、状态机、连接级事件 */
        ENGINE,
        /** 心跳收发与失败 */
        HEARTBEAT,
        /** 发送缓存 put / flush / evict */
        CACHE,
        /** 自动重连调度 / 退避 / failover */
        AUTOCONNECT,
        /** 声明层 / 编排层产出 */
        NETWORK,
        /** 主动探测结果 (baseline + burst) */
        PROBE,
        /** imc-core 内部错误(本不该发生但被 catch 住的异常,sink 抛出的异常等) */
        INTERNAL,
    }

    /**
     * imc-core 内部捕获到的异常。
     *
     * 替代之前散落各处的 `IMCLog.e(e, "...")` 调用。**这不是业务错误,业务侧通常不需要响应**,
     * 但应当作为运行健康度信号上报到监控:出现频率高意味着某条假设被打破。
     *
     * 来源典型场景:
     *  - [ImcEvents.emit] 时 sink.onEvent 抛了异常
     *  - 持久化缓存写盘失败 / blob 解析失败
     *  - 探测线程被中断
     *  - 自定义回调(KeyProvider / OnFailoverHandler / V2IMCListener)抛了异常
     */
    data class InternalError(
        val site: String,                      // 出错位置标识,如 "ImcEvents.emit" / "FileMessageCache.flushToFile"
        val errorClass: String,
        val message: String?,
        override val timestamp: Long = System.currentTimeMillis(),
    ) : ImcEvent() {
        override val category get() = Category.INTERNAL
    }

    // ── ENGINE ──────────────────────────────────────────────────────────────

    /** Engine 状态机切换。`from` / `to` 用 EngineState 的 toString,保留 key 信息 */
    data class EngineStateTransition(
        val from: String,
        val to: String,
        val reason: String? = null,
        override val timestamp: Long = System.currentTimeMillis(),
    ) : ImcEvent() {
        override val category get() = Category.ENGINE
    }

    /** WebSocket 握手 101 成功 */
    data class ConnectionOpened(
        val httpStatus: Int?,
        val httpStatusMessage: String?,
        override val timestamp: Long = System.currentTimeMillis(),
    ) : ImcEvent() {
        override val category get() = Category.ENGINE
    }

    /** 收到 onClose;`remote=true` 是对端主动关 */
    data class ConnectionClosed(
        val code: Int,
        val reason: String?,
        val remote: Boolean,
        override val timestamp: Long = System.currentTimeMillis(),
    ) : ImcEvent() {
        override val category get() = Category.ENGINE
    }

    /** 收到 onError / 异常关闭 */
    data class ConnectionLost(
        val errorClass: String,
        val message: String?,
        override val timestamp: Long = System.currentTimeMillis(),
    ) : ImcEvent() {
        override val category get() = Category.ENGINE
    }

    /**
     * TLS 错误细化分类。
     *
     * 在 [ConnectionLost] 之外,**额外**针对 SSL/TLS 类异常 emit 一条分类事件,业务侧可以
     * 用 stage 区分 UX:
     *  - HANDSHAKE → "连不上服务器,检查网络/SSL 拦截"
     *  - READ/WRITE → "连接中断,稍候重试"
     *  - CLOSE_NOTIFY → 同 READ/WRITE,但是对端规范关闭
     *  - PIN_FAILURE → "证书不可信"(配 cert pinning 时)
     *
     * @param stage 哪个阶段炸的
     * @param errorClass 异常类名,如 SSLProtocolException / SSLHandshakeException
     * @param message 异常 message,可能含对端原因
     * @param isPeerInitiated 对端主动关 = true;本地超时 / 抛错 = false
     */
    data class TlsFailure(
        val stage: Stage,
        val errorClass: String,
        val message: String?,
        val isPeerInitiated: Boolean,
        override val timestamp: Long = System.currentTimeMillis(),
    ) : ImcEvent() {
        override val category get() = Category.ENGINE

        enum class Stage { HANDSHAKE, READ, WRITE, CLOSE_NOTIFY, PIN_FAILURE, UNKNOWN }
    }

    /** handshake watchdog 触发了强 close */
    data class HandshakeWatchdogFired(
        val key: String,
        val timeoutMs: Long,
        override val timestamp: Long = System.currentTimeMillis(),
    ) : ImcEvent() {
        override val category get() = Category.ENGINE
    }

    /** engineOn 切到新 URL(KeyProvider 翻转 / 备用 host 切换等) */
    data class EngineKeyChanged(
        val oldKey: String?,
        val newKey: String,
        override val timestamp: Long = System.currentTimeMillis(),
    ) : ImcEvent() {
        override val category get() = Category.ENGINE
    }

    /** engineOff 主动停 */
    data class EngineStopped(
        override val timestamp: Long = System.currentTimeMillis(),
    ) : ImcEvent() {
        override val category get() = Category.ENGINE
    }

    // ── HEARTBEAT ──────────────────────────────────────────────────────────

    data class HeartbeatSent(
        val implName: String,           // "V2Fixed" / "V2Smart"
        val intervalSeconds: Long,
        override val timestamp: Long = System.currentTimeMillis(),
    ) : ImcEvent() {
        override val category get() = Category.HEARTBEAT
    }

    data class HeartbeatPongReceived(
        val implName: String,
        override val timestamp: Long = System.currentTimeMillis(),
    ) : ImcEvent() {
        override val category get() = Category.HEARTBEAT
    }

    /**
     * 心跳判定失败,即将触发 autoConnect 抢救。
     *
     * @param consecutive 连续失败次数;Fixed 实现固定 1,Smart 实现累计
     */
    data class HeartbeatFailed(
        val implName: String,
        val consecutive: Int,
        override val timestamp: Long = System.currentTimeMillis(),
    ) : ImcEvent() {
        override val category get() = Category.HEARTBEAT
    }

    /** engine.onChangeMode 切换心跳实现 */
    data class HeartbeatModeChanged(
        val from: Int,
        val to: Int,
        override val timestamp: Long = System.currentTimeMillis(),
    ) : ImcEvent() {
        override val category get() = Category.HEARTBEAT
    }

    /**
     * 心跳进入「容差窗口」或在容差窗口里收到回包恢复 — 介于正常 [HeartbeatPongReceived] 与
     * [HeartbeatFailed] 之间的中间态信号。
     *
     * 触发场景:
     *  - [Stage.ENTERED_TOLERANCE]:第一次 tick 没收到 pong,V2FixedHeartbeat 进入 (factor-1)×interval
     *    的容差窗口,主动再补一次心跳等回包
     *  - [Stage.RECOVERED_IN_TOLERANCE]:在容差窗口里 pong 终于回来了,这次没挂但已经"擦边过"
     *
     * 设计动机:线上跑 18h 抓到 100+ 次 1006 断开,但 V2FixedHeartbeat 几乎没 emit FAILED —
     * 因为容差窗口吃掉了所有"晚到的 pong",外观看不出心跳健康度下滑。本事件让面板能区分
     * "稳定的 5s 心跳"与"反复擦边的 5+2.5s 心跳",作为弱网早期预警。
     */
    data class HeartbeatDegraded(
        val implName: String,
        val stage: Stage,
        val expectedIntervalSeconds: Long,
        override val timestamp: Long = System.currentTimeMillis(),
    ) : ImcEvent() {
        override val category get() = Category.HEARTBEAT

        enum class Stage { ENTERED_TOLERANCE, RECOVERED_IN_TOLERANCE }
    }

    // ── CACHE ──────────────────────────────────────────────────────────────

    /** send() 时未连上,消息进缓存 */
    data class MessageCached(
        val id: String,
        val isText: Boolean,
        val sizeBytes: Int,
        override val timestamp: Long = System.currentTimeMillis(),
    ) : ImcEvent() {
        override val category get() = Category.CACHE
    }

    /** 重连后一条消息被 flush 出网 */
    data class MessageFlushed(
        val id: String,
        val sizeBytes: Int,
        override val timestamp: Long = System.currentTimeMillis(),
    ) : ImcEvent() {
        override val category get() = Category.CACHE
    }

    enum class EvictReason { TTL_EXPIRED, OVER_CAPACITY, EXPLICIT_CLEAR }

    /** 消息被丢弃(TTL 过期 / 容量淘汰 / 显式 clear) */
    data class MessageEvicted(
        val id: String,
        val reason: EvictReason,
        override val timestamp: Long = System.currentTimeMillis(),
    ) : ImcEvent() {
        override val category get() = Category.CACHE
    }

    /** 一轮 flush 的汇总(flush 一批后发) */
    data class CacheFlushBatch(
        val flushed: Int,
        val remaining: Int,
        override val timestamp: Long = System.currentTimeMillis(),
    ) : ImcEvent() {
        override val category get() = Category.CACHE
    }

    // ── AUTOCONNECT ────────────────────────────────────────────────────────

    /** 排了一次重连定时器 */
    data class AutoConnectScheduled(
        val delayMs: Long,
        val action: ReconnectAction?,
        val retryCount: Int,
        override val timestamp: Long = System.currentTimeMillis(),
    ) : ImcEvent() {
        override val category get() = Category.AUTOCONNECT
    }

    /** 定时器到点,真正发起重连(调 reconnector / webSocketClient.reconnect) */
    data class AutoConnectFired(
        val key: String?,
        override val timestamp: Long = System.currentTimeMillis(),
    ) : ImcEvent() {
        override val category get() = Category.AUTOCONNECT
    }

    /**
     * 抢救被中止。
     *
     * 典型原因:`WAIT_USER`、`FAILOVER` 已被 App 接管、手动 stopAutoConnect、连接成功后清零。
     */
    data class AutoConnectAborted(
        val reason: String,
        override val timestamp: Long = System.currentTimeMillis(),
    ) : ImcEvent() {
        override val category get() = Category.AUTOCONNECT
    }

    /** 编排层判定 FAILOVER,onFailover 钩子被调用 */
    data class FailoverInvoked(
        val handledByApp: Boolean,
        override val timestamp: Long = System.currentTimeMillis(),
    ) : ImcEvent() {
        override val category get() = Category.AUTOCONNECT
    }

    /**
     * FAILOVER 钩子累计 unhandled 提醒。
     *
     * 触发条件:[FailoverInvoked.handledByApp]=false 累计达到阈值(默认 10 次)。
     * 含义:surveillance 反复推荐切端点,但业务侧没有配 backup URL 或 onFailover 回调,SDK 只能
     * 继续在原 URL 上退避重试 —— 这通常是集成漏配,业务监控该报警提示开发者补 backup endpoint。
     *
     * @param totalUnhandled 自上次 reset 以来累计未处理的 failover 次数
     * @param threshold 触发本次提醒的阈值
     */
    data class FailoverUnhandled(
        val totalUnhandled: Int,
        val threshold: Int,
        override val timestamp: Long = System.currentTimeMillis(),
    ) : ImcEvent() {
        override val category get() = Category.AUTOCONNECT
    }

    /**
     * 链路降级状态(连续 attempt-timeout + probe 显示 TLS 黑洞)。
     *
     * 触发条件:连续 N 次 attempt-timeout-30000ms 且 forceProbe verdict=TLS_FAILURE / PROBE_TIMEOUT。
     * 含义:典型于 Wi-Fi 链路 SSL 拦截 / DPI 吞包 —— TCP 通但 TLS 握手永远不回。SDK 进入降级状态,
     * 退避策略调整:attempt-timeout 临时缩短(避免每次 30s 等死),backoff 上限拉长(节电)。
     *
     * 业务侧典型 UX:UI 提示"网络环境不允许加密连接,请切换 Wi-Fi / 4G / VPN"。
     *
     * 退出条件:任意一次成功 onOpen / 显式 stopAutoConnect → SDK 自动清除降级状态(无单独事件)。
     *
     * @param reason 当前主因(目前固定 TLS_BLACKHOLE,后续可扩展)
     * @param consecutiveTimeouts 触发降级时的连续 timeout 次数
     */
    data class LinkDegraded(
        val reason: Reason,
        val consecutiveTimeouts: Int,
        override val timestamp: Long = System.currentTimeMillis(),
    ) : ImcEvent() {
        override val category get() = Category.AUTOCONNECT

        enum class Reason { TLS_BLACKHOLE }
    }

    /**
     * autoConnect 主动请求一次 NetSurveillance 探测。
     *
     * 触发场景:连续 [ProgressiveAutoConnect.maxImmediateRetries] 次 IMMEDIATE 都没建链,硬地板把动作强降
     * 为 BACKOFF_NORMAL —— 这意味着「编排层认为网络 OK 但实际重连不上」,典型于 OS 层 NetworkCallback
     * 没回调的运营商 NAT 抖动 / DNS 软故障。请 surveillance 跑一次主动探测,让 L3 给出真实 verdict。
     *
     * sink 应只把它当作可观察信号,**不要据此做业务决策** —— 真正的下一步动作仍由后续
     * [NetReportChanged] 流出。
     */
    data class AutoConnectProbeRequested(
        val reason: String,
        val consecutiveFailures: Int,
        override val timestamp: Long = System.currentTimeMillis(),
    ) : ImcEvent() {
        override val category get() = Category.AUTOCONNECT
    }

    // ── NETWORK ────────────────────────────────────────────────────────────

    /** L1 声明层产出新快照(与上一份不同时才 emit) */
    data class NetSnapshotChanged(
        val verdict: NetVerdict,
        val capabilities: Set<NetCap>,
        val transports: Set<NetTransport>,
        val linkUpKbps: Int,
        val linkDownKbps: Int,
        val signalStrengthDbm: Int?,
        override val timestamp: Long = System.currentTimeMillis(),
    ) : ImcEvent() {
        override val category get() = Category.NETWORK
    }

    /** L3 编排层产出新报告(与上一份不同时才 emit) */
    data class NetReportChanged(
        val overall: NetVerdict,
        val recommend: ReconnectAction,
        val probeVerdict: ProbeVerdict?,
        override val timestamp: Long = System.currentTimeMillis(),
    ) : ImcEvent() {
        override val category get() = Category.NETWORK
    }

    /** 探测档位切换 */
    data class NetProfileChanged(
        val from: NetProbeProfile,
        val to: NetProbeProfile,
        override val timestamp: Long = System.currentTimeMillis(),
    ) : ImcEvent() {
        override val category get() = Category.NETWORK
    }

    data class SurveillanceStarted(
        override val timestamp: Long = System.currentTimeMillis(),
    ) : ImcEvent() {
        override val category get() = Category.NETWORK
    }

    data class SurveillanceStopped(
        override val timestamp: Long = System.currentTimeMillis(),
    ) : ImcEvent() {
        override val category get() = Category.NETWORK
    }

    // ── PROBE ──────────────────────────────────────────────────────────────

    data class ProbeStarted(
        val host: String,
        val port: Int,
        override val timestamp: Long = System.currentTimeMillis(),
    ) : ImcEvent() {
        override val category get() = Category.PROBE
    }

    data class ProbeFinished(
        val host: String,
        val port: Int,
        val verdict: ProbeVerdict,
        val dnsElapsedMs: Long,
        val tcpElapsedMs: Long,
        val tlsElapsedMs: Long?,
        val httpElapsedMs: Long?,
        val publicRefSuccess: Boolean?,
        override val timestamp: Long = System.currentTimeMillis(),
    ) : ImcEvent() {
        override val category get() = Category.PROBE
    }

    data class BurstProbeFinished(
        val host: String,
        val port: Int,
        val attempts: Int,
        val successes: Int,
        val p50Ms: Long?,
        val p95Ms: Long?,
        val jitterMs: Double?,
        val lossRate: Double,
        override val timestamp: Long = System.currentTimeMillis(),
    ) : ImcEvent() {
        override val category get() = Category.PROBE
    }
}
