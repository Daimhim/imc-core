package org.daimhim.imc_core

import java.util.concurrent.Callable

/** 心跳 / 保活底层 SPI,由各 Heartbeat 实现;SDK 内部在收发与定时时回调,业务通常无需直接实现。 */
interface ILinkNative{
    fun initLinkNative(webSocketClient: V2JavaWebEngine.WebSocketClientImpl)
    fun getConnectionLostTimeout(): Int
    fun setConnectionLostTimeout(connectionLostTimeout: Int)
    fun updateLastPong()
    fun startConnectionLostTimer()
    fun stopConnectionLostTimer(isError:Boolean = false)
    fun sendHeartbeat()

    /**
     * 判定一条 incoming text 是否是心跳应答。
     * 命中 → WebSocketClientImpl 会吞掉这条 message,不向业务 listener 转发,避免业务被心跳噪音污染。
     * updateLastPong 依旧会自动触发(任意 incoming 都会),所以不用在这里再触发一次。
     * 默认 false,实现可选地基于 customHeartbeat 委托。
     */
    fun isIncomingHeartbeat(text: String): Boolean = false

    /** 同上的二进制版本。 */
    fun isIncomingHeartbeat(bytes: ByteArray): Boolean = false

    /**
     * 收到 WS 协议层 PONG 控制帧(RFC 6455 opcode=0x0A)时回调。
     *
     * 与 [updateLastPong] 的区别:updateLastPong 对任意入站帧都会被调(消息也算),
     * 本方法**只有** pong 控制帧触发 — 心跳实现可借此区分"服务器在应答我的 ping"
     * 与"普通业务流量"。默认转发 [updateLastPong],行为与加该方法前一致。
     */
    fun onWsPong() = updateLastPong()

    /**
     * 当前挂载的 client([initLinkNative] 最近一次传入者)。
     *
     * 引擎用它做「代际守卫」:同一个 ILinkNative 实例跨 client 世代共享时,
     * 旧 client 迟到的 onClose/onError 不得停掉新 client 的计时器。
     * 默认 null = 不参与守卫,引擎退化为历史行为(实现未适配时兼容)。
     */
    fun currentAttachedClient(): V2JavaWebEngine.WebSocketClientImpl? = null
}

/** 超时定时器抽象:[start] 计时、[stop] 取消、[setCallback] 设到点回调。默认实现 RRFTimeoutScheduler。 */
interface ITimeoutScheduler{
    fun start(time:Long)
    fun stop()
    fun setCallback(call:Callable<Void>)
}

internal class RRFTimeoutScheduler : ITimeoutScheduler{

    private val rapidResponseForce = RapidResponseForceV4()
    private val timingTag = "${RRFTimeoutScheduler::class.java.simpleName}_${hashCode()}"
    private var isStop = false
    private val sync = Any()
    private val timeoutCallback = fun(id:String){
        if (id != timingTag) return
        synchronized(sync){
            if (isStop) return
        }
        call?.call()
    }

    override fun start(time: Long) {
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