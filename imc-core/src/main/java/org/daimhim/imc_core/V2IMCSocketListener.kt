package org.daimhim.imc_core

/**
 * 底层消息拦截器 —— 在消息派发给业务 [V2IMCListener] 之前执行,可拦截并消费消息。
 * 通过 [IEngine] 暴露的 socket-listener 注册接口按 [level] 注册。
 *
 * - **优先级 [level]**:数值越小越先收到(升序派发)。业务 [V2IMCListener] 实际挂在
 *   [IMCListenerManager.DEFAULT_LEVEL] (15) 上,因此 level < 15 的拦截器会先于业务监听执行。
 * - **返回值**:`true` = 已消费此消息,**中止**后续(更高 level)listener 的派发;
 *   `false` = 不拦截,继续向后派发。
 * - **回调线程**:WebSocket IO 线程,不要阻塞。
 * - **异常**:被 SDK 吞掉,不影响其它 listener。
 */
interface V2IMCSocketListener {
    /** 拦截文本帧;返回 true 表示消费并中止后续派发,false 继续派发。 */
    fun onMessage(iEngine: IEngine, text: String):Boolean = false
    /** 拦截二进制帧;返回 true 表示消费并中止后续派发,false 继续派发。 */
    fun onMessage(iEngine: IEngine, bytes: ByteArray):Boolean = false
}
