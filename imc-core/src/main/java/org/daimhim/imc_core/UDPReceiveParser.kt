package org.daimhim.imc_core

import java.net.DatagramSocket

/**
 * 已弃用,仅与 [UDPEngine] 配套使用。UDPEngine 无生产场景验证,
 * 后续大版本会移除。如需 UDP 自行实现 [IEngine] + 数据包解析。
 */
@Deprecated(
    message = "仅与已弃用的 UDPEngine 配套使用,后续大版本会一起移除。",
    level = DeprecationLevel.WARNING
)
interface UDPReceiveParser {
    fun parser(iEngine: IEngine,imcListenerManager : IMCListenerManager,datagramSocket: DatagramSocket?)
}