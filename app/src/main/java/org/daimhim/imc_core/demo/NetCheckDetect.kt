package org.daimhim.imc_core.demo

import com.custom.socket_connect.INetDetect
import com.custom.socket_connect.NetCheckState

class NetCheckDetect : INetDetect {

    fun checkNetState(callback: (state: Int) -> Unit) {
        var state = NetCheckState.UNKNOWN.ordinal
        val netStateCheck = NetStateCheck()
        netStateCheck.dnsPingResult("www.baidu.com", 2, 64, 3000) { ping, dns ->
            if (!dns) {
                state = NetCheckState.OFFLINE.ordinal
                return@dnsPingResult
            }
            if (!ping) {
                state = NetCheckState.BAD.ordinal
                return@dnsPingResult
            }
            if (ping && dns) {
                state = NetCheckState.GOOD.ordinal
                return@dnsPingResult
            }
            state = NetCheckState.UNKNOWN.ordinal
            callback.invoke(state)
        }
    }

    override fun netRequest() {
    }

    override fun onNetFailure() {
    }

    override fun onNetSuccess() {
    }


}