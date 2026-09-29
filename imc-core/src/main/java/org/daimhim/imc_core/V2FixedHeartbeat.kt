package org.daimhim.imc_core

import java.util.concurrent.Callable

/**
 * 固定心跳间隔 + 容差窗口的心跳实现。
 *
 * 状态机:
 * ```
 *   onOpen → startConnectionLostTimer → 等 curHeartbeat 秒
 *      ↓
 *      第一次 timer fire:
 *        - 期间收到 pong/任何流量 → 重置,发心跳,再等 curHeartbeat 秒
 *        - 没收到 → 进入"容差窗口",立即发探活心跳,等 curHeartbeat × (factor - 1) 秒
 *      ↓
 *      容差窗口 fire:
 *        - 收到回包 → 退出容差,正常节奏
 *        - 还没回 → 真挂了,触发自动重连
 * ```
 *
 * 总失败检测时间 = `curHeartbeat × toleranceFactor`(默认 1.5,可调)。
 * 业界 IM SDK 普遍用 1.5-2.0 ×,降低弱网偶然延迟导致的假阳率。
 */
class V2FixedHeartbeat(builder: Builder) : ILinkNative {
    private val sync = Any()

    /** 固定心跳间隔(秒) */
    private var curHeartbeat = builder.curHeartbeat

    /** 心跳超时容差倍数,>= 1.0;1.0 = 无容差,与改造前等价 */
    private val toleranceFactor: Double = builder.toleranceFactor

    /** 心跳调度是否在跑(true = 计时器活动中) */
    private var isRunning = false

    /** 自上次 reset 起是否收到过任何返包(pong / message 都算) */
    private var hasPongBeenReceived = false

    /** 当前是否在"容差窗口"内 — 第一次失败后,等额外时间确认 */
    private var isInToleranceWindow = false

    /** 心跳调度器 */
    private val timeoutScheduler: ITimeoutScheduler = builder.timeoutScheduler

    /** 自定义心跳包内容 */
    private var customHeartbeat: CustomHeartbeat? = builder.customHeartbeat

    /**
     * 判活信号源。
     *
     * - [LivenessSource.ANY_INBOUND](默认,历史行为):任意入站帧都算活。适用于
     *   customHeartbeat 且服务器不回 ack 的协议(靠业务流量顺带证明活着)——
     *   缺点是安静账号(无推送)每个周期都会 no-pong 误判。
     * - [LivenessSource.WS_PONG_ONLY]:仅 WS pong 控制帧算活。标准 ping/pong 模式,
     *   可与 [customHeartbeat] 并存(ping 探活 + JSON 心跳继续承担服务端业务职责,
     *   如在线状态上报)。
     */
    private val livenessSource: LivenessSource = builder.livenessSource

    // 代际守卫跨线程读(onClose 线程 / 引擎线程),必须保证可见性
    @Volatile
    private var webSocketClient: V2JavaWebEngine.WebSocketClientImpl? = null

    private val timeoutCall = object : Callable<Void> {
        override fun call(): Void? {
            onTimerTick()
            return null
        }
    }

    init {
        // 早绑定:不要等到 initLinkNative 再注册,否则 webSocketClient 还没挂上时
        // 第一次 startConnectionLostTimer 来了 callback 是空的 — 单测也容易踩到这个坑
        timeoutScheduler.setCallback(timeoutCall)
    }

    override fun initLinkNative(webSocketClient: V2JavaWebEngine.WebSocketClientImpl) {
        if (this.webSocketClient == webSocketClient) return
        synchronized(sync) {
            if (isRunning) {
                // 陈旧计时器(旧 client 世代挂的残留):强制杀掉。
                // RC1 修复:此前旧世代的 isRunning=true 会把新 client 的
                // startConnectionLostTimer 直接 `if (isRunning) return` 拦掉,
                // 新会话从此没有心跳(线上大量 sent=0 会话的根因)。
                timeoutScheduler.stop()
            }
            isRunning = false
            hasPongBeenReceived = false
            isInToleranceWindow = false
            this.webSocketClient = webSocketClient
        }
    }

    /** 代际守卫:告诉引擎本实例当前挂在哪个 client 上;旧 client 的迟到回调据此被拦。 */
    override fun currentAttachedClient(): V2JavaWebEngine.WebSocketClientImpl? = webSocketClient

    /**
     * 返回给 Java-WebSocket 内部 LCD 的超时值。**固定 0 = 关掉 LCD**。
     *
     * 原因:实测中大量活连接被库自带 LCD 机制误杀(等不到 WS-level PONG 即触发 1006 关闭)。LCD 只看
     * **WS-level PONG 控制帧**,而 V2Fixed 走 [customHeartbeat] 路径时根本不发 WS-level PING,
     * Server A 也极少主动发 PONG → LCD 等不到 PONG 就杀活连接。
     *
     * V2Fixed 自管心跳(用 [curHeartbeat] 调度),所以 LCD 这层冗余反而有害,关掉。
     */
    override fun getConnectionLostTimeout(): Int = 0

    /**
     * 上层(Java-WebSocket 库)如果调进来,无视 — 不让 [curHeartbeat] 被库改成 0 把
     * 本类自己的调度也搞挂。本类心跳间隔通过 [Builder.setCurHeartbeat] / [onChangeMode] 设置。
     */
    override fun setConnectionLostTimeout(connectionLostTimeout: Int) { /* intentionally no-op */ }

    override fun isIncomingHeartbeat(text: String): Boolean =
        customHeartbeat?.isHeartbeat(text) == true

    override fun isIncomingHeartbeat(bytes: ByteArray): Boolean =
        customHeartbeat?.isHeartbeat(bytes) == true

    override fun updateLastPong() {
        // WS_PONG_ONLY 模式下普通入站不算判活信号(onOpen 时的初始调用同样忽略):
        // 第一个周期会进一次容差并立刻发探活 ping,pong 回来即恢复 —— 这正好让
        // "服务器是否真的回 pong"在事件流里可见(cause=PONG + RECOVERED_IN_TOLERANCE)。
        if (livenessSource == LivenessSource.WS_PONG_ONLY) return
        markAlive(ImcEvent.HeartbeatPongReceived.CAUSE_INBOUND)
    }

    override fun onWsPong() {
        markAlive(ImcEvent.HeartbeatPongReceived.CAUSE_PONG)
    }

    private fun markAlive(cause: String) {
        val recoveredInTolerance: Boolean
        synchronized(sync) {
            hasPongBeenReceived = true
            recoveredInTolerance = isInToleranceWindow
        }
        ImcEvents.emit(ImcEvent.HeartbeatPongReceived(IMPL_NAME, cause))
        if (recoveredInTolerance) {
            ImcEvents.emit(
                ImcEvent.HeartbeatDegraded(
                    implName = IMPL_NAME,
                    stage = ImcEvent.HeartbeatDegraded.Stage.RECOVERED_IN_TOLERANCE,
                    expectedIntervalSeconds = curHeartbeat,
                )
            )
        }
    }

    override fun startConnectionLostTimer() {
        synchronized(sync) {
            if (isRunning) return
            isRunning = true
            hasPongBeenReceived = false
            isInToleranceWindow = false
            timeoutScheduler.start(curHeartbeat * 1000)
        }
    }

    override fun stopConnectionLostTimer(isError: Boolean) {
        synchronized(sync) {
            if (!isRunning) return
            isRunning = false
            hasPongBeenReceived = false
            isInToleranceWindow = false
            timeoutScheduler.stop()
        }
    }

    override fun sendHeartbeat() {
        // A13: WS 未连时静默早退。否则 webSocketClient.send/sendPing 会抛
        // WebsocketNotConnectedException → RRF onTimeout catch 上报为 INTERNAL noise。
        // 触发场景:onError/onClose 触达 stopConnectionLostTimer 之前的窗口里 RRF 恰好 fire。
        val ws = webSocketClient
        if (ws == null) {
            ImcEvents.emit(ImcEvent.HeartbeatTickSkipped(IMPL_NAME, "noClient"))
            return
        }
        if (!ws.isOpen) {
            ImcEvents.emit(ImcEvent.HeartbeatTickSkipped(IMPL_NAME, "notOpen"))
            return
        }
        val payload = customHeartbeat
        val sendWsPing = payload == null || livenessSource == LivenessSource.WS_PONG_ONLY
        ImcEvents.emit(
            ImcEvent.HeartbeatSent(IMPL_NAME, intervalSeconds = curHeartbeat)
        )
        if (sendWsPing) ws.sendPing()
        if (payload != null) {
            if (payload.byteOrString()) {
                ws.send(payload.byteHeartbeat())
            } else {
                ws.send(payload.stringHeartbeat())
            }
        }
    }

    /** 计时器一次触发 — 走 2 阶段判定 */
    private fun onTimerTick() {
        // #1: 迟到 tick 不得复活已停的计时器。RRFTimeoutScheduler 已有代际守卫,
        // 这里再兜一层 —— 换成自定义 ITimeoutScheduler(无代际)时同样安全。
        if (!synchronized(sync) { isRunning }) return
        // A13: WS 已断时不再续期 timer,避免 RRF 持续被无意义 reschedule。
        // 上层 stopConnectionLostTimer 会在毫秒级跟进,这里只是兜底。
        val ws = webSocketClient
        if (ws == null || !ws.isOpen) {
            synchronized(sync) {
                isRunning = false
                hasPongBeenReceived = false
                isInToleranceWindow = false
            }
            // A13 早退不再静默:让"计时器因 ws 非开而死亡"在事件流里可见
            ImcEvents.emit(
                ImcEvent.HeartbeatTickSkipped(
                    IMPL_NAME,
                    if (ws == null) "tickNoClient" else "tickNotOpen"
                )
            )
            return
        }
        val action: Action = synchronized(sync) {
            when {
                // NONE:只发不判死 —— 服务器收心跳续命但不回 ack 时,避免周期性误杀
                livenessSource == LivenessSource.NONE -> Action.RESET_AND_CONTINUE
                hasPongBeenReceived -> Action.RESET_AND_CONTINUE
                !isInToleranceWindow -> Action.ENTER_TOLERANCE
                else -> Action.FAIL
            }
        }
        when (action) {
            Action.RESET_AND_CONTINUE -> {
                synchronized(sync) {
                    hasPongBeenReceived = false
                    isInToleranceWindow = false
                }
                sendHeartbeat()
                timeoutScheduler.start(curHeartbeat * 1000)
            }
            Action.ENTER_TOLERANCE -> {
                val toleranceMs = ((curHeartbeat * 1000L).toDouble() * (toleranceFactor - 1.0))
                    .toLong()
                    .coerceAtLeast(1_000L)
                synchronized(sync) { isInToleranceWindow = true }
                ImcEvents.emit(
                    ImcEvent.HeartbeatDegraded(
                        implName = IMPL_NAME,
                        stage = ImcEvent.HeartbeatDegraded.Stage.ENTERED_TOLERANCE,
                        expectedIntervalSeconds = curHeartbeat,
                    )
                )
                sendHeartbeat()  // 主动探活
                timeoutScheduler.start(toleranceMs)
            }
            Action.FAIL -> {
                ImcEvents.emit(ImcEvent.HeartbeatFailed(IMPL_NAME, consecutive = 1))
                // 必须强 close 才能真正触发重连:容差窗口超时不代表 TCP 断,socket 通常仍 isOpen=true,
                // 调 startAutoConnect 会被 ProgressiveAutoConnect 的 isOpen 早返回挡掉,变成软告警(看到日志
                // "触发重连"但没有 [AUTOCONNECT scheduled] —— 实测线上跑 11h 见过两次)。
                // close() 走 onClose / onError → abnormalDisconnectionAndAutomaticReconnection →
                // autoConnect 自然接管,退避状态由 autoConnect 自己维护,不会被打回起点。
                webSocketClient?.close()
            }
        }
    }

    private enum class Action { RESET_AND_CONTINUE, ENTER_TOLERANCE, FAIL }

    /** 判活信号源,见 [V2FixedHeartbeat.livenessSource] */
    enum class LivenessSource {
        /** 任意入站帧都算活(历史默认) */
        ANY_INBOUND,

        /** 仅 WS pong 控制帧算活;可与 customHeartbeat 并存(ping 判活 + JSON 心跳保业务) */
        WS_PONG_ONLY,

        /**
         * 不判活,心跳只管发送(服务器"收心跳续命但不回 ack"时使用)。
         *
         * 背景:实测有服务端把 WS ping / JSON 心跳都计入空闲流量刷新(连接因此长活),
         * 却不回任何 pong/ack —— 此时 ANY_INBOUND 与 WS_PONG_ONLY 都会在安静账号上
         * 周期性误杀健康连接。NONE 把"心跳"退化成纯 keepalive 发送器:每周期发心跳
         * 并重排计时器,永不 ENTER_TOLERANCE / FAIL。断连检测交给 socket onError /
         * autoConnect(网络切换、TCP RST 等绝大多数场景都会触发 onError)。
         *
         * 代价:纯静默黑洞(TCP 半开且无任何 IO 错误)不再被主动判死,恢复依赖下一次
         * write 失败或网络层信号;可配合 NetSurveillance 的 NetworkCallback 兜底。
         */
        NONE,
    }

    private companion object {
        const val IMPL_NAME = "V2Fixed"
    }

    class Builder {
        /** 心跳间隔(秒),可动态变更 */
        internal var curHeartbeat: Long = 5L

        /** 心跳超时容差倍数;1.5 = 60s 间隔时,90s 才判失。1.0 退化为旧行为 */
        internal var toleranceFactor: Double = 1.5

        internal var timeoutScheduler: ITimeoutScheduler = RRFTimeoutScheduler()
        internal var customHeartbeat: CustomHeartbeat? = null
        internal var livenessSource: LivenessSource = LivenessSource.ANY_INBOUND

        fun setCustomHeartbeat(customHeartbeat: CustomHeartbeat): Builder {
            this.customHeartbeat = customHeartbeat
            return this
        }

        /**
         * 判活信号源。默认 [LivenessSource.ANY_INBOUND](历史行为,兼容)。
         *
         * 服务器对 WS ping 回 pong 的场景建议 [LivenessSource.WS_PONG_ONLY]:
         *  - 不设 customHeartbeat → 纯 ping/pong 心跳;
         *  - 设 customHeartbeat → 双轨:每周期发 WS ping(判活)+ 自定义 JSON(承担
         *    服务端业务职责,如在线状态),适用于"服务器不回 JSON 心跳 ack 但有
         *    app-layer 职责"的协议。
         */
        fun setLivenessSource(source: LivenessSource): Builder {
            this.livenessSource = source
            return this
        }

        fun setCurHeartbeat(curHeartbeat: Long): Builder {
            this.curHeartbeat = curHeartbeat
            return this
        }

        fun setToleranceFactor(factor: Double): Builder {
            require(factor >= 1.0) { "toleranceFactor 必须 >= 1.0,当前 $factor" }
            this.toleranceFactor = factor
            return this
        }

        fun setTimeoutScheduler(timeoutScheduler: ITimeoutScheduler): Builder {
            this.timeoutScheduler = timeoutScheduler
            return this
        }

        fun build(): V2FixedHeartbeat = V2FixedHeartbeat(this)
    }
}
