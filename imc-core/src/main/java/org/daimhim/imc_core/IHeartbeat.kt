package org.daimhim.imc_core

import java.util.concurrent.Callable

interface ILinkNative{
    fun initLinkNative(webSocketClient: V2JavaWebEngine.WebSocketClientImpl)
    fun getConnectionLostTimeout(): Int
    fun setConnectionLostTimeout(connectionLostTimeout: Int)
    fun updateLastPong()
    fun startConnectionLostTimer()
    fun stopConnectionLostTimer()
    fun sendHeartbeat()
}

interface ITimeoutScheduler{
    fun start(time:Long)
    fun stop()
    fun setCallback(call:Callable<Void>)
}

class RRFTimeoutScheduler : ITimeoutScheduler{

    private val rapidResponseForce = RapidResponseForceV2()
    private val timingTag = "${RRFTimeoutScheduler::class.java.simpleName}_${hashCode()}"
    private var isStop = false
    private val sync = Any()
    init {
        rapidResponseForce.timeoutCallback(object :Comparable<Pair<String,Any?>>{
            override fun compareTo(other: Pair<String, Any?>): Int {
                IMCLog.i("timeoutCallback.compareTo $timingTag")
                if (isStop) return 1
                when(other.first){
                    timingTag -> {
                        // 执行回调
                        call?.call()
                    }
                }
                return 0
            }
        })
    }
    override fun start(time: Long) {
        println("IHeartbeat.RRFTimeoutScheduler.start isStop:$isStop $time")
        synchronized(sync){
            isStop = false
            rapidResponseForce.register(timingTag,null,time)
        }
    }

    override fun stop() {
        println("IHeartbeat.RRFTimeoutScheduler.stop isStop:$isStop")
        synchronized(sync){
            if (isStop) return
            isStop = true
            rapidResponseForce.unRegister(timingTag)
        }
    }
    private var call: Callable<Void>? = null
    override fun setCallback(call: Callable<Void>) {
         this.call = call
    }

}