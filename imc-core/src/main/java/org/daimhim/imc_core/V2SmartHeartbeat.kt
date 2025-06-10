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
     * 是否停止了心跳
     */
    private var isStopHeartbeat = false
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

    override fun sendHeartbeat(){
        IMCLog.i("sendHeartbeat V2SmartHeartbeat")
        if (customHeartbeat == null){
            webSocketClient?.sendPing()
            return
        }
        if (customHeartbeat?.byteOrString() == true) {
            webSocketClient?.send(customHeartbeat?.byteHeartbeat())
        } else {
            webSocketClient?.send(customHeartbeat?.stringHeartbeat())
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

    override fun updateLastPong() {
        synchronized(sync) {
            hasPongBeenReceived = true
        }
    }

    override fun startConnectionLostTimer() {
        IMCLog.i("IHeartbeat.startConnectionLostTimer isStopHeartbeat:$isStopHeartbeat $curHeartbeat")
        synchronized(sync){
            if (isStopHeartbeat) return
            isStopHeartbeat = true
            hasPongBeenReceived = false
            timeoutScheduler.start(curHeartbeat * 1000)
        }
    }

    override fun stopConnectionLostTimer(isError:Boolean) {
        IMCLog.i("IHeartbeat.stopConnectionLostTimer isError:${isError} isStopHeartbeat:$isStopHeartbeat")
        synchronized(sync){
            if (!isStopHeartbeat) return
            isStopHeartbeat = false
            hasPongBeenReceived = false
            timeoutScheduler.stop()
            if (isError){
                onHeartbeatFailure(true)
            }
        }
    }

    /**
     * 心跳失败回调
     */
    private fun onHeartbeatFailure(isError:Boolean = false){
        IMCLog.i("IHeartbeat.心跳回调 timeoutScheduler isError${isError} hasPongBeenReceived:$hasPongBeenReceived isStartDetect:$isStartDetect curHearSuccess:$curHearSuccess curHearFailure:$curHearFailure")
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
            IMCLog.i("IHeartbeat.心跳回调 timeoutScheduler start $determineMaximumHeartbeat")
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
        println("IHeartbeat.心跳失败次数：${curHearFailure} determineMaximumHeartbeat ${determineMaximumHeartbeat}")
        // 唤起重新链接，其内部会调用停止心跳
        if (isError){
            return
        }
        webSocketClient?.resetStartAutoConnect()
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