package org.daimhim.imc_core

import java.net.DatagramSocket

interface UDPReceiveParser {
    fun parser(iEngine: IEngine,imcListenerManager : IMCListenerManager,datagramSocket: DatagramSocket?)
}