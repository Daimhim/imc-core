package org.daimhim.imc_core.demo

import org.daimhim.imc_core.ILinkNative
import org.daimhim.imc_core.V2JavaWebEngine

/**
 * 对照测试用的"空心跳":所有方法都是 no-op,**永不启动计时器、永不发任何帧**。
 * 切到这个模式后连接进入纯空闲,用来测服务端在没有任何心跳时多久主动断开(idle timeout)。
 */
class NoOpHeartbeat : ILinkNative {
    @Volatile private var timeout = 600

    override fun initLinkNative(webSocketClient: V2JavaWebEngine.WebSocketClientImpl) {}
    override fun getConnectionLostTimeout(): Int = timeout
    override fun setConnectionLostTimeout(connectionLostTimeout: Int) { timeout = connectionLostTimeout }
    override fun updateLastPong() {}
    override fun startConnectionLostTimer() {}   // 关键:不排计时器 → 永不触发 sendHeartbeat
    override fun stopConnectionLostTimer(isError: Boolean) {}
    override fun sendHeartbeat() {}
}
