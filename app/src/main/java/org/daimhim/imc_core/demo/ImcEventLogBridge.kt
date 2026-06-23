package org.daimhim.imc_core.demo

import org.daimhim.imc_core.ImcEvent
import org.daimhim.imc_core.ImcEventSink
import org.daimhim.imc_core.ImcEvents

/**
 * 把 imc-core 的结构化 [ImcEvent] 渲染成可读单行,落到 [LogStore] / [FileLogger]。
 *
 * 早期 demo 通过 `IIMCLogFactory` 拿 SDK 文本日志,Phase 3 收敛后 SDK 不再产文本日志,
 * 这层桥负责把结构化事件还原成"看起来跟旧日志差不多"的字符串,FullLogActivity 上沿用原视图。
 *
 * 启动:`ImcEventLogBridge.attach()`,在 [StartApp] 里调一次就行;无 detach 入口,
 * 进程级单例,直到进程退出。
 *
 * 落地两处:
 *  - [LogStore](内存,FullLogActivity 渲染时间轴用,tag="SDK")
 *  - [FileLogger](本地文件,事后 adb pull 排查用,tag="SDK")
 *
 * 渲染策略:每个 [ImcEvent] 子类对应一行,字段拼成 key=value,长字符串(如 reason)截断到 200 字符,
 * 避免 InternalError 的 stack-like message 把日志列撑爆。
 */
object ImcEventLogBridge {

    private val sink = ImcEventSink { event -> append(event) }

    @Volatile
    private var attached = false

    /** 启动桥接;幂等。Application.onCreate 里调一次。 */
    fun attach() {
        if (attached) return
        synchronized(this) {
            if (attached) return
            ImcEvents.subscribe(sink)
            attached = true
        }
    }

    /** 测试 / 卸载场景 */
    fun detach() {
        if (!attached) return
        synchronized(this) {
            if (!attached) return
            ImcEvents.unsubscribe(sink)
            attached = false
        }
    }

    private fun append(event: ImcEvent) {
        val line = renderEvent(event)
        LogStore.appendEvent(event, line, levelOf(event))
    }

    /**
     * 事件 → 日志级别映射。
     * 真错误 / 失败信号 → ERROR;watchdog/abort 这类警告 → WARN;其余信息流 → INFO。
     */
    private fun levelOf(e: ImcEvent): LogStore.Level = when (e) {
        is ImcEvent.InternalError,
        is ImcEvent.HeartbeatFailed,
        is ImcEvent.ConnectionLost -> LogStore.Level.ERROR

        is ImcEvent.HandshakeWatchdogFired,
        is ImcEvent.AutoConnectAborted,
        is ImcEvent.AutoConnectProbeRequested,
        is ImcEvent.HeartbeatDegraded,
        is ImcEvent.MessageEvicted,
        is ImcEvent.FailoverInvoked -> LogStore.Level.WARN

        // 关闭码区分:正常 (1000/1001) 是 INFO,其它是 WARN
        is ImcEvent.ConnectionClosed ->
            if (e.code == 1000 || e.code == 1001) LogStore.Level.INFO else LogStore.Level.WARN

        else -> LogStore.Level.INFO
    }

    private fun renderEvent(e: ImcEvent): String {
        val cat = e.category.name
        return when (e) {
            // ── ENGINE
            is ImcEvent.EngineStateTransition ->
                "[$cat] state ${e.from} → ${e.to}" + (e.reason?.let { " ($it)" } ?: "")
            is ImcEvent.ConnectionOpened ->
                "[$cat] onOpen status=${e.httpStatus} ${e.httpStatusMessage ?: ""}"
            is ImcEvent.ConnectionClosed ->
                "[$cat] onClose code=${e.code} remote=${e.remote} reason=${e.reason}"
            is ImcEvent.ConnectionLost ->
                "[$cat] onError ${e.errorClass}: ${e.message?.trunc()}"
            is ImcEvent.TlsFailure ->
                "[$cat] TLS ${e.stage} ${e.errorClass}" +
                    (if (e.isPeerInitiated) " (peer)" else "") +
                    (e.message?.let { ": ${it.trunc()}" } ?: "")
            is ImcEvent.HandshakeWatchdogFired ->
                "[$cat] handshake watchdog fired (${e.timeoutMs}ms) key=${e.key.trunc(80)}"
            is ImcEvent.EngineKeyChanged ->
                "[$cat] keyChanged old=${e.oldKey?.trunc(60)} new=${e.newKey.trunc(60)}"
            is ImcEvent.EngineStopped ->
                "[$cat] engineOff"

            // ── HEARTBEAT
            is ImcEvent.HeartbeatSent ->
                "[$cat] heartbeat sent (${e.implName}, intvl=${e.intervalSeconds}s)"
            is ImcEvent.HeartbeatPongReceived ->
                "[$cat] pong (${e.implName})"
            is ImcEvent.HeartbeatFailed ->
                "[$cat] heartbeat FAILED (${e.implName}, consec=${e.consecutive})"
            is ImcEvent.HeartbeatModeChanged ->
                "[$cat] heartbeat mode ${e.from} → ${e.to}"
            is ImcEvent.HeartbeatDegraded ->
                "[$cat] heartbeat degraded (${e.implName}, stage=${e.stage}, intvl=${e.expectedIntervalSeconds}s)"

            // ── CACHE
            is ImcEvent.MessageCached ->
                "[$cat] cached id=${e.id} ${if (e.isText) "text" else "bin"} ${e.sizeBytes}B"
            is ImcEvent.MessageFlushed ->
                "[$cat] flushed id=${e.id} ${e.sizeBytes}B"
            is ImcEvent.MessageEvicted ->
                "[$cat] evicted id=${e.id} reason=${e.reason}"
            is ImcEvent.CacheFlushBatch ->
                "[$cat] flush batch done=${e.flushed} remain=${e.remaining}"

            // ── AUTOCONNECT
            is ImcEvent.AutoConnectScheduled ->
                "[$cat] scheduled delay=${e.delayMs}ms action=${e.action} retry=${e.retryCount}"
            is ImcEvent.AutoConnectFired ->
                "[$cat] fired key=${e.key?.trunc(60)}"
            is ImcEvent.AutoConnectAborted ->
                "[$cat] aborted reason=${e.reason}"
            is ImcEvent.FailoverInvoked ->
                "[$cat] failover handled=${e.handledByApp}"
            is ImcEvent.FailoverUnhandled ->
                "[$cat] !! failover unhandled ${e.totalUnhandled} times (threshold=${e.threshold}, 业务漏配 backup URL?)"
            is ImcEvent.AutoConnectProbeRequested ->
                "[$cat] probe requested reason=${e.reason} consec=${e.consecutiveFailures}"
            is ImcEvent.LinkDegraded ->
                "[$cat] !! link degraded reason=${e.reason} timeouts=${e.consecutiveTimeouts}"

            // ── NETWORK
            is ImcEvent.NetSnapshotChanged ->
                "[$cat] snapshot verdict=${e.verdict} transports=${e.transports} caps=${e.capabilities.size}" +
                    (e.signalStrengthDbm?.let { " sig=${it}dBm" } ?: "")
            is ImcEvent.NetReportChanged ->
                "[$cat] report overall=${e.overall} recommend=${e.recommend}" +
                    (e.probeVerdict?.let { " probe=$it" } ?: "")
            is ImcEvent.NetProfileChanged ->
                "[$cat] profile ${profileLabel(e.from)} → ${profileLabel(e.to)}"
            is ImcEvent.SurveillanceStarted -> "[$cat] surveillance started"
            is ImcEvent.SurveillanceStopped -> "[$cat] surveillance stopped"

            // ── PROBE
            is ImcEvent.ProbeStarted ->
                "[$cat] probe → ${e.host}:${e.port}"
            is ImcEvent.ProbeFinished -> {
                val pieces = buildString {
                    append("[$cat] probe done ${e.host}:${e.port} verdict=${e.verdict}")
                    append(" dns=${e.dnsElapsedMs}ms tcp=${e.tcpElapsedMs}ms")
                    e.tlsElapsedMs?.let { append(" tls=${it}ms") }
                    e.httpElapsedMs?.let { append(" http=${it}ms") }
                    e.publicRefSuccess?.let { append(" publicRef=$it") }
                }
                pieces
            }
            is ImcEvent.BurstProbeFinished ->
                "[$cat] burst ${e.host}:${e.port} ${e.successes}/${e.attempts}" +
                    (e.p50Ms?.let { " p50=${it}ms" } ?: "") +
                    (e.p95Ms?.let { " p95=${it}ms" } ?: "") +
                    (e.jitterMs?.let { " jitter=${"%.1f".format(it)}ms" } ?: "") +
                    " loss=${"%.2f".format(e.lossRate)}"

            // ── INTERNAL
            is ImcEvent.InternalError ->
                "[$cat] !! ${e.site} ${e.errorClass}: ${e.message?.trunc()}"
        }
    }

    private fun profileLabel(p: org.daimhim.imc_core.NetProbeProfile): String = when (p) {
        org.daimhim.imc_core.NetProbeProfile.AGGRESSIVE -> "AGGRESSIVE"
        org.daimhim.imc_core.NetProbeProfile.BALANCED -> "BALANCED"
        org.daimhim.imc_core.NetProbeProfile.BACKGROUND -> "BACKGROUND"
        org.daimhim.imc_core.NetProbeProfile.LOW_POWER -> "LOW_POWER"
        else -> "custom(min=${p.minProbeIntervalMs / 1000}s,burst=${p.burstEnabled})"
    }

    private fun String.trunc(limit: Int = 200): String =
        if (length <= limit) this else substring(0, limit) + "...(+${length - limit})"
}
