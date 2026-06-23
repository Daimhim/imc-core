package org.daimhim.imc_core


interface CustomHeartbeat {
    fun isHeartbeat(bytes: ByteArray): Boolean
    fun isHeartbeat(text: String): Boolean

    fun byteHeartbeat(): ByteArray
    fun stringHeartbeat(): String

    fun byteOrString(): Boolean
}

/**
 * 内建的「dataPing」实现 —— 用一条 app-layer 文本 payload(默认 "HEART_BEAT")当心跳。
 *
 * 设计目的:绕开中间盒 / NAT 仅基于 WS protocol-level ping frame (opcode=0x09) 判断 idle
 * 的策略 —— 部分运营商 DPI / SLB / 家庭网关会忽略 ping frame,把 5min 无 app-layer 流量的
 * 长连接当 idle 直接 RST。文本 payload 让中间盒看到"业务流量",有效续命。
 *
 * 服务端协议约定(二选一):
 *  - **echo 模式**:服务端收到 "HEART_BEAT" 后原样回 "HEART_BEAT",SDK 收到后调用
 *    [V2JavaWebEngine.WebSocketClientImpl.updateLastPong]。
 *  - **静默模式**:服务端不响应,但任意业务回包到达即可 reset 容差。此时建议把
 *    [V2FixedHeartbeat] 的容差因子 (toleranceFactor) 拉到 ≥ 2.0 防止假阳。
 *
 * 使用:
 * ```kotlin
 * V2FixedHeartbeat.Builder()
 *     .setCustomHeartbeat(StringPingCustomHeartbeat())   // payload = "HEART_BEAT"
 *     .setCurHeartbeat(30L)
 *     .build()
 * ```
 *
 * 默认 payload 选 "HEART_BEAT" 是 IMC 项目历史约定(业务协议层已对齐),如果业务侧另有
 * 协议,可通过构造参数覆盖。
 */
open class StringPingCustomHeartbeat(
    private val payload: String = DEFAULT_PAYLOAD,
) : CustomHeartbeat {
    override fun isHeartbeat(bytes: ByteArray): Boolean = false
    override fun isHeartbeat(text: String): Boolean = text == payload
    override fun byteHeartbeat(): ByteArray = ByteArray(0)
    override fun stringHeartbeat(): String = payload
    override fun byteOrString(): Boolean = false

    companion object {
        const val DEFAULT_PAYLOAD = "HEART_BEAT"
    }
}
