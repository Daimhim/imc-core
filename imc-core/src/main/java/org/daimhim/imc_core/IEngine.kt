package org.daimhim.imc_core

/**
 * IM 长连接引擎对外接口。典型用法:
 * ```
 * val engine = V2JavaWebEngine.Builder().build()
 * engine.setIMCStatusListener(statusListener)    // 监听连接态
 * engine.addIMCListener(messageListener)          // 收消息
 * engine.engineOn("wss://your-server/ws?token=…") // 连接
 * engine.send("hello")                            // 发送
 * engine.engineOff()                              // 断开
 * ```
 * 实现见 [V2JavaWebEngine]。回调默认在 WebSocket IO 线程触发,刷新 UI 请自行切主线程。
 */
interface IEngine {
    /**
     * 启动引擎并连接到 [key] 指定的服务端。
     *
     * @param key 连接 URL(如 `wss://host/path?token=...`)。若配置了 [IKeyProvider],
     *   后续 autoConnect 重连会改用 Provider 现取的 URL,本次传入的 [key] 仅用于这次连接。
     */
    fun engineOn(key:String)

    /**
     * 熄火
     */
    fun engineOff()
    /**
     * 返回当前引擎状态,取值见 [IEngineState] 常量(如 [IEngineState.ENGINE_OPEN])。
     */
    fun engineState():Int
    /**
     * 发送字节消息。
     *
     * 返回值仅代表「调用瞬间是否处于 Connected 且帧已立即出网」:
     *  - **true** = 当前 socket 已建,消息已直接交给协议层成帧发送
     *  - **false** = 当前不在 Connected 状态,内部已触发一次 [makeConnection],
     *    **且消息已按 FIFO 进入 [IMessageCache]**,连上后会自动 flush —— 这不代表"丢消息"
     *
     * 业务侧不应把 false 当成失败上报,要可靠投递请配合 ack / 业务层重试。
     */
    fun send(byteArray: ByteArray):Boolean
    /**
     * 发送文本消息。返回值语义同 [send] (byteArray):false 仅代表未立即出网,消息已入缓存等重连。
     */
    fun send(text: String):Boolean

    /**
     * 订阅定制监听
     */
    fun addIMCListener(imcListener: V2IMCListener)
    /**
     * Remove i m c listener
     *
     * @param imcListener 待删除的监听
     */
    fun removeIMCListener(imcListener: V2IMCListener)
    /**
     * Add i m c socket listener
     *
     * @param imcSocketListener 待添加的消息接收拦截器
     */
    fun addIMCSocketListener(level:Int = IMCListenerManager.DEFAULT_LEVEL, imcSocketListener: V2IMCSocketListener)
    /**
     * Remove i m c socket listener
     *
     * @param imcSocketListener 待移除的消息接收拦截器
     */
    fun removeIMCSocketListener(imcSocketListener: V2IMCSocketListener)

    /** 设置连接生命周期监听(单个,后设覆盖先设);传 null 清除。 */
    fun setIMCStatusListener(listener: IMCStatusListener?)

    /**
     * 切换心跳模式。
     *
     * @param mode 心跳模式 key,对应 [V2JavaWebEngine.Builder] 里 addHeartbeatMode 注册的档位
     *   (例如前台 / 后台不同心跳间隔)。
     */
    fun onChangeMode(mode:Int)
    /** 通知引擎网络状态变化(由业务侧的网络监听转发触发)。 */
    fun onNetworkChange(networkState:Int)

    /**
     * 主动触发重连
     */
    fun makeConnection()
}