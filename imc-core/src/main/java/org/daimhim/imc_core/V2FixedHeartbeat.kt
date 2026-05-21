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

    /** 是否已停止心跳调度(false = 计时器活动中) */
    private var isStopHeartbeat = false

    /** 自上次 reset 起是否收到过任何返包(pong / message 都算) */
    private var hasPongBeenReceived = false

    /** 当前是否在"容差窗口"内 — 第一次失败后,等额外时间确认 */
    private var isInToleranceWindow = false

    /** 心跳调度器 */
    private val timeoutScheduler: ITimeoutScheduler = builder.timeoutScheduler

    /** 自定义心跳包内容 */
    private var customHeartbeat: CustomHeartbeat? = builder.customHeartbeat

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
        this.webSocketClient = webSocketClient
    }

    override fun getConnectionLostTimeout(): Int {
        synchronized(sync) { return curHeartbeat.toInt() }
    }

    override fun setConnectionLostTimeout(connectionLostTimeout: Int) {
        synchronized(sync) { curHeartbeat = connectionLostTimeout.toLong() }
    }

    override fun updateLastPong() {
        synchronized(sync) {
            hasPongBeenReceived = true
        }
    }

    override fun startConnectionLostTimer() {
        IMCLog.i("IHeartbeat.startConnectionLostTimer")
        synchronized(sync) {
            if (isStopHeartbeat) return
            isStopHeartbeat = true
            hasPongBeenReceived = false
            isInToleranceWindow = false
            timeoutScheduler.start(curHeartbeat * 1000)
        }
    }

    override fun stopConnectionLostTimer(isError: Boolean) {
        synchronized(sync) {
            if (!isStopHeartbeat) return
            isStopHeartbeat = false
            hasPongBeenReceived = false
            isInToleranceWindow = false
            timeoutScheduler.stop()
        }
    }

    override fun sendHeartbeat() {
        IMCLog.i("IHeartbeat.V2FixedHeartbeat ${webSocketClient == null}")
        if (customHeartbeat == null) {
            webSocketClient?.sendPing()
            return
        }
        if (customHeartbeat?.byteOrString() == true) {
            webSocketClient?.send(customHeartbeat?.byteHeartbeat())
        } else {
            webSocketClient?.send(customHeartbeat?.stringHeartbeat())
        }
    }

    /** 计时器一次触发 — 走 2 阶段判定 */
    private fun onTimerTick() {
        val action: Action = synchronized(sync) {
            when {
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
                IMCLog.i("IHeartbeat 进入容差窗口 +${toleranceMs}ms (factor=$toleranceFactor)")
                sendHeartbeat()  // 主动探活
                timeoutScheduler.start(toleranceMs)
            }
            Action.FAIL -> {
                IMCLog.i("IHeartbeat 容差窗口超时,触发重连")
                // 心跳失败只「确保 autoConnect 在跑」(幂等),不清零退避状态。
                // 之前 resetStartAutoConnect 会把 reconnectDelay 重置回 init,
                // 导致每次心跳 tick 都把退避状态打回起点,4b 指数退避失效。
                webSocketClient?.startAutoConnect()
            }
        }
    }

    private enum class Action { RESET_AND_CONTINUE, ENTER_TOLERANCE, FAIL }

    class Builder {
        /** 心跳间隔(秒),可动态变更 */
        internal var curHeartbeat: Long = 5L

        /** 心跳超时容差倍数;1.5 = 60s 间隔时,90s 才判失。1.0 退化为旧行为 */
        internal var toleranceFactor: Double = 1.5

        internal var timeoutScheduler: ITimeoutScheduler = RRFTimeoutScheduler()
        internal var customHeartbeat: CustomHeartbeat? = null

        fun setCustomHeartbeat(customHeartbeat: CustomHeartbeat): Builder {
            this.customHeartbeat = customHeartbeat
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
