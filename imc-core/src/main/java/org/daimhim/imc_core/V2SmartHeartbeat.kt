package org.daimhim.imc_core

import java.util.concurrent.Callable

class V2SmartHeartbeat(builder:Builder) : ILinkNative {

    /**
     * 心跳间隔
     */
    private var curHeartbeat = builder.initialHeartbeat
    /**
     * 心跳失败次数
     */
    private var curHearFailure = 0;

    /**
     * 心跳成功次数
     */
    private var curHearSuccess = 0;
    /**
     * 最大心跳失败次数
     */
    private val MAX_HEARTBEAT_FAILURE = builder.maxHeartbeatFailure;

    /**
     * 心跳成功多少次才开始起跳
     */
    private val MAX_HEARTBEAT_SUCCESS = 3

    /**
     * 最小心跳间隔
     */
    private var minHeartbeat = builder.minHeartbeat
    /**
     * 心跳自增步长
     */
    private var heartbeatStep = builder.heartbeatStep;

    /**
     * 同步块
     */
    private val sync = Any()

    /**
     * 心跳调度是否在跑(true = 计时器活动中)
     */
    private var isRunning = false
    /**
     * 是否收到了心跳
     */
    private var hasPongBeenReceived = false

    /**
     * 是否开始心跳探测，必须稳定心跳三下
     */
    private var isStartDetect = builder.isStartDetect

    /**
     * 心跳调度器
     */
    private val timeoutScheduler : ITimeoutScheduler = builder.timeoutScheduler

    private var customHeartbeat : CustomHeartbeat? = builder.customHeartbeat

    private var determineMaximumHeartbeat  = false
    private var webSocketClient : V2JavaWebEngine.WebSocketClientImpl? = null

    /**
     * 反 NAT idle 自适应。
     * 记录最近 3 次连接活的时长(从 startConnectionLostTimer → stopConnectionLostTimer(true) 的间隔)。
     * 如果这些时长接近(±20%),说明对面有一个固定的 idle timeout 反复掐线,SDK 应当把心跳间隔
     * 调小到比这个 timeout 一半还短,绕过 NAT 反复 RST。
     *
     * 安全阈值:
     *  - 时长必须 >= [NAT_DETECT_MIN_LIFETIME_SECONDS](20s),太短的不算 idle pattern
     *  - 调整后的心跳不能低于 [NAT_DETECT_MIN_HEARTBEAT_SECONDS](10s),否则心跳本身耗电
     *  - 自适应只触发一次降级,不会反复缩
     */
    private val recentLifetimes = ArrayDeque<Long>(3)
    @Volatile private var sessionStartMs: Long = 0L
    @Volatile private var natIdleAdaptedThisSession = false

    override fun sendHeartbeat(){
        // A13: WS 未连时静默早退,避免 WebsocketNotConnectedException 上报 INTERNAL noise。
        // 见 V2FixedHeartbeat.sendHeartbeat 同款注释。
        val ws = webSocketClient ?: return
        if (!ws.isOpen) return
        ImcEvents.emit(ImcEvent.HeartbeatSent(IMPL_NAME, intervalSeconds = curHeartbeat))
        if (customHeartbeat == null){
            ws.sendPing()
            return
        }
        if (customHeartbeat?.byteOrString() == true) {
            ws.send(customHeartbeat?.byteHeartbeat())
        } else {
            ws.send(customHeartbeat?.stringHeartbeat())
        }
    }
    private val timeoutCall = object : Callable<Void> {
        override fun call(): Void? {
            onHeartbeatFailure()
            return null
        }
    }
    override fun initLinkNative(webSocketClient: V2JavaWebEngine.WebSocketClientImpl) {
        if (this.webSocketClient == webSocketClient){
            return
        }
        this.webSocketClient = webSocketClient
        timeoutScheduler.setCallback(timeoutCall)
    }

    override fun getConnectionLostTimeout(): Int {
        synchronized(sync){
            return curHeartbeat.toInt()
        }
    }

    override fun setConnectionLostTimeout(connectionLostTimeout: Int) {
        synchronized(sync){
            curHeartbeat = connectionLostTimeout.toLong()
        }
    }

    override fun isIncomingHeartbeat(text: String): Boolean =
        customHeartbeat?.isHeartbeat(text) == true

    override fun isIncomingHeartbeat(bytes: ByteArray): Boolean =
        customHeartbeat?.isHeartbeat(bytes) == true

    override fun updateLastPong() {
        synchronized(sync) {
            hasPongBeenReceived = true
        }
        ImcEvents.emit(ImcEvent.HeartbeatPongReceived(IMPL_NAME))
    }

    override fun startConnectionLostTimer() {
        synchronized(sync){
            if (isRunning) return
            isRunning = true
            hasPongBeenReceived = false
            sessionStartMs = System.currentTimeMillis()
            timeoutScheduler.start(curHeartbeat * 1000)
        }
    }

    override fun stopConnectionLostTimer(isError:Boolean) {
        // A3: 记录本次连接生命周期,供 NAT idle 自适应检测使用
        val lifetimeSec = if (sessionStartMs > 0L && isError) {
            (System.currentTimeMillis() - sessionStartMs) / 1000L
        } else 0L
        synchronized(sync){
            if (!isRunning) return
            isRunning = false
            hasPongBeenReceived = false
            timeoutScheduler.stop()
            if (isError){
                onHeartbeatFailure(true)
            }
        }
        if (lifetimeSec >= NAT_DETECT_MIN_LIFETIME_SECONDS) {
            maybeAdaptToNatIdle(lifetimeSec)
        }
    }

    /**
     * A3: 看最近 3 次连接生命周期是否接近,接近(±20%)且没自适应过 → 把心跳间隔降到 lifetime / 2。
     */
    private fun maybeAdaptToNatIdle(lifetimeSec: Long) {
        val newInterval = synchronized(sync) {
            if (natIdleAdaptedThisSession) return@synchronized -1L
            while (recentLifetimes.size >= 3) recentLifetimes.removeFirst()
            recentLifetimes.addLast(lifetimeSec)
            if (recentLifetimes.size < 3) return@synchronized -1L
            val min = recentLifetimes.min()
            val max = recentLifetimes.max()
            val avg = recentLifetimes.average()
            val within20Pct = (max - min).toDouble() / avg.coerceAtLeast(1.0) <= 0.2
            if (!within20Pct) return@synchronized -1L
            val candidate = (avg / 2).toLong().coerceAtLeast(NAT_DETECT_MIN_HEARTBEAT_SECONDS)
            if (candidate >= curHeartbeat) return@synchronized -1L  // 不会"调高"
            natIdleAdaptedThisSession = true
            curHeartbeat = candidate
            candidate
        }
        if (newInterval > 0) {
            ImcEvents.emit(ImcEvent.HeartbeatSent(IMPL_NAME, intervalSeconds = newInterval))
            // 没有专门的 NatIdleAdapted 事件,先复用 HeartbeatSent 触达 sink;后续需要可加专属事件。
        }
    }

    /**
     * 心跳失败回调
     */
    private fun onHeartbeatFailure(isError:Boolean = false){
        // 心跳成功
        if (hasPongBeenReceived && webSocketClient?.isOpen == true){
            if (!isStartDetect){ //未稳定
                curHearSuccess++ // 累计增加心跳成功一次
                if (curHearSuccess > MAX_HEARTBEAT_SUCCESS){
                    // 心跳成功次数超过3次
                    isStartDetect = true // 开始心跳探测
                }
            }
            else{
                // 增加心跳间隔
                if (!determineMaximumHeartbeat){
                    curHeartbeat += heartbeatStep // 心跳间隔增加
                    curHearFailure = 0 // 重置心跳失败次数
                    curHearSuccess = 0 // 重置心跳成功
                }
            }
            hasPongBeenReceived = false // 重置心跳成功
            sendHeartbeat() // 发送心跳
            timeoutScheduler.start(curHeartbeat * 1000) // 开始下一次心跳计时
            return
        }
        // 心跳失败
        curHearFailure++ // 累计增加心跳失败一次
        if (curHearFailure > MAX_HEARTBEAT_FAILURE){
            // 心跳失败次数超过5次
            if (curHeartbeat > minHeartbeat){
                curHeartbeat -= heartbeatStep // 减少心跳间隔
                curHearFailure = 0 // 重置心跳失败次数
                curHearSuccess = 0 // 重置心跳成功
            }
            determineMaximumHeartbeat = true // 确定最大心跳间隔
        }else{
            // 心跳失败次数小于5次
            determineMaximumHeartbeat = false // 确定最大心跳间隔
        }
        ImcEvents.emit(ImcEvent.HeartbeatFailed(IMPL_NAME, consecutive = curHearFailure))
        // 唤起重新链接,其内部会调用停止心跳
        if (isError){
            return
        }
        // 必须强 close 才能真正触发重连:容差窗口超时不代表 TCP 断,socket 通常仍 isOpen=true,
        // 调 startAutoConnect 会被 ProgressiveAutoConnect 的 isOpen 早返回挡掉,变成软告警(看到日志
        // "触发重连"但没有 [AUTOCONNECT scheduled])。
        // close() 走 onClose / onError → abnormalDisconnectionAndAutomaticReconnection → autoConnect
        // 自然接管,退避状态由 autoConnect 自己维护,不会被打回起点。
        webSocketClient?.close()
    }

    private companion object {
        const val IMPL_NAME = "V2Smart"

        /** 连接生命周期 >= 此值才视为可能的 NAT idle pattern,过短的不算 */
        const val NAT_DETECT_MIN_LIFETIME_SECONDS = 20L

        /** 自适应后的心跳最小间隔,避免无脑往小调 */
        const val NAT_DETECT_MIN_HEARTBEAT_SECONDS = 10L
    }

    class Builder{
        // 初始心跳间隔
        internal var initialHeartbeat = 35L
        // 心跳最大失败次数
        internal var maxHeartbeatFailure = 5
        // 心跳失败后增加的步长
        internal var heartbeatStep = 5
        // 心跳最小间隔
        internal var minHeartbeat = 15L
        /**
         * 是否智能探测
         */
        internal var isStartDetect = false
        // 心跳调度器
        internal var timeoutScheduler : ITimeoutScheduler = RRFTimeoutScheduler()

        /**
         * 自定义心跳内容
         */
        internal var customHeartbeat: CustomHeartbeat? = null

        fun setInitialHeartbeat(initialHeartbeat: Long) : Builder{
            this.initialHeartbeat = initialHeartbeat
            return this
        }

        fun setMaxHeartbeatFailure(maxHeartbeatFailure: Int) : Builder{
            this.maxHeartbeatFailure = maxHeartbeatFailure
            return this
        }
        fun setHeartbeatStep(heartbeatStep: Int) : Builder{
            this.heartbeatStep = heartbeatStep
            return this
        }
        fun setMinHeartbeat(minHeartbeat: Long) : Builder{
            this.minHeartbeat = minHeartbeat
            return this
        }
        fun setTimeoutScheduler(timeoutScheduler: ITimeoutScheduler) : Builder{
            this.timeoutScheduler = timeoutScheduler
            return this
        }
        fun setCustomHeartbeat(customHeartbeat: CustomHeartbeat) : Builder{
            this.customHeartbeat = customHeartbeat
            return this
        }
        fun setIsStartDetect(isStartDetect: Boolean) : Builder{
            this.isStartDetect = isStartDetect
            return this
        }
        fun build() : V2SmartHeartbeat{
            return V2SmartHeartbeat(this)
        }
    }
}