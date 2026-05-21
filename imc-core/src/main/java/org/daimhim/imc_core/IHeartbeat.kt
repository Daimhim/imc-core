package org.daimhim.imc_core

import java.util.concurrent.Callable

interface ILinkNative{
    fun initLinkNative(webSocketClient: V2JavaWebEngine.WebSocketClientImpl)
    fun getConnectionLostTimeout(): Int
    fun setConnectionLostTimeout(connectionLostTimeout: Int)
    fun updateLastPong()
    fun startConnectionLostTimer()
    fun stopConnectionLostTimer(isError:Boolean = false)
    fun sendHeartbeat()
}

interface ITimeoutScheduler{
    fun start(time:Long)
    fun stop()
    fun setCallback(call:Callable<Void>)
}

class RRFTimeoutScheduler : ITimeoutScheduler{

    private val rapidResponseForce = RapidResponseForceV4()
    private val timingTag = "${RRFTimeoutScheduler::class.java.simpleName}_${hashCode()}"
    private var isStop = false
    private val sync = Any()
    private val timeoutCallback = fun(id:String){
        IMCLog.i("timeoutCallback.compareTo id $id")
        if (id != timingTag) return
        synchronized(sync){
            if (isStop) return
        }
        call?.call()
    }

    override fun start(time: Long) {
        IMCLog.i("RRFTimeoutScheduler.start isStop=$isStop time=${time}ms tag=$timingTag")
        synchronized(sync){
            isStop = false
            // 关键:必须把调用方传入的 time 透传给 RRFv4,否则 register 会用默认 MAX_TIMEOUT_TIME=5s
            // 旧版漏传导致心跳/容差窗口实际都在 5s 触发,跟配置值无关
            rapidResponseForce.register(
                id = timingTag,
                timeoutMs = time,
                onTimeout = timeoutCallback,
            )
        }
    }

    override fun stop() {
        println("IHeartbeat.RRFTimeoutScheduler.stop isStop:$isStop")
        synchronized(sync){
            if (isStop) return
            isStop = true
            rapidResponseForce.unregister(id = timingTag)
        }
    }
    private var call: Callable<Void>? = null
    override fun setCallback(call: Callable<Void>) {
         this.call = call
    }

}