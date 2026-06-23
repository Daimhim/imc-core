package org.daimhim.imc_core

/**
 * 连接生命周期监听。通过 [IEngine.setIMCStatusListener] 注册(单个,后设覆盖先设)。
 *
 * 典型时序:握手完成 → [connectionSucceeded];之后因异常中断 → [connectionLost],
 * 或收到关闭 / 主动 [IEngine.engineOff] → [connectionClosed]。
 * 若启用了自动重连,[connectionLost] / [connectionClosed] 之后引擎会按退避策略重连。
 *
 * 回调在 WebSocket IO 线程触发,需要刷新 UI 请自行切到主线程。
 */
interface IMCStatusListener {
    /**
     * 连接关闭时回调。
     * @param code WebSocket close code(1000=正常关闭,1006=异常断开 等,见 RFC 6455)
     * @param reason 关闭原因,可能为 null
     */
    fun connectionClosed(code: Int, reason: String?)
    /** 连接因异常中断时回调,[throwable] 为底层异常。 */
    fun connectionLost(throwable: Throwable)
    /** 握手完成、连接可用;此后可正常 [IEngine.send]。 */
    fun connectionSucceeded()
}
