package org.daimhim.imc_core

import java.util.concurrent.Callable

class V2FixedHeartbeat(builder: Builder) : ILinkNative {
    private val sync = Any()
    /**
     * 固定心跳间隔
     */
    private var curHeartbeat = builder.curHeartbeat

    /**
     * 是否停止了心跳
     */
    private var isStopHeartbeat = false
    /**
     * 是否收到了心跳
     */
    private var hasPongBeenReceived = false
    /**
     * 心跳调度器
     */
    private val timeoutScheduler : ITimeoutScheduler = builder.timeoutScheduler

    /**
     * 自定义心态内容
     */
    private var customHeartbeat: CustomHeartbeat? = builder.customHeartbeat
    private var webSocketClient: V2JavaWebEngine.WebSocketClientImpl? = null

    /**
     * 心跳回调
     */
    private val timeoutCall = object : Callable<Void> {
        override fun call(): Void? {
            IMCLog.i("IHeartbeat.timeoutScheduler $hasPongBeenReceived")
            // 心跳成功
            if (hasPongBeenReceived){
                hasPongBeenReceived = false // 重置心跳成功
                sendHeartbeat() // 发送心跳
                timeoutScheduler.start(curHeartbeat * 1000) // 开始下一次心跳计时
                return null
            }
            // 唤起重新链接,其内部会调用停止心跳
            webSocketClient?.resetStartAutoConnect()
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
        IMCLog.i("IHeartbeat.startConnectionLostTimer")
        synchronized(sync) {
            if (isStopHeartbeat) return
            isStopHeartbeat = true
            hasPongBeenReceived = false
            timeoutScheduler.start(curHeartbeat * 1000)
        }
    }

    override fun stopConnectionLostTimer() {
        synchronized(sync){
            if (!isStopHeartbeat) return
            isStopHeartbeat = false
            hasPongBeenReceived = false
            timeoutScheduler.stop()
        }
    }

    override fun sendHeartbeat(){
        IMCLog.i("IHeartbeat.V2FixedHeartbeat ${webSocketClient == null}")
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

    class Builder {
        /**
         * 心跳间隔，可动态变更
         */
        internal var curHeartbeat : Long = 5L
        // 心跳调度器
        internal var timeoutScheduler : ITimeoutScheduler = RRFTimeoutScheduler()
        /**
         * 自定义心跳
         */
        internal var customHeartbeat: CustomHeartbeat? = null

        fun setCustomHeartbeat(customHeartbeat: CustomHeartbeat) : Builder {
            this.customHeartbeat = customHeartbeat
            return this
        }

        fun setCurHeartbeat(curHeartbeat: Long) : Builder {
            this.curHeartbeat = curHeartbeat
            return this
        }

        fun setTimeoutScheduler(timeoutScheduler: ITimeoutScheduler) : Builder {
            this.timeoutScheduler = timeoutScheduler
            return this
        }
        fun build(): V2FixedHeartbeat {
            return V2FixedHeartbeat(this)
        }
    }
}