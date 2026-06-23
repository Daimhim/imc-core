package org.daimhim.imc_core

/**
 * 业务层消息接收监听。通过 [IEngine.addIMCListener] 注册,可注册多个。
 *
 * - **回调线程**:在 WebSocket IO 线程触发,**不要做阻塞操作**;需要刷新 UI 请自行切到主线程。
 * - **文本 vs 二进制**:按收到的帧类型二选一回调 —— 文本帧走 [onMessage] (String),
 *   二进制帧走 [onMessage] (ByteArray),不会对同一条消息同时触发。
 * - **异常**:回调中抛出的异常会被 SDK 吞掉,不影响其它 listener;如需上报请自行 try-catch。
 * - **可重入**:回调内可安全调用 [IEngine.send];请勿在回调内调用 [IEngine.engineOff]。
 */
interface V2IMCListener {
    /** 收到文本帧时回调。 */
    fun onMessage(text: String){}
    /** 收到二进制帧时回调。 */
    fun onMessage(byteArray: ByteArray){}
}
