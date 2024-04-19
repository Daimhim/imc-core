package org.daimhim.imc_core.demo

import com.custom.socket_connect.INetDetect
import com.custom.socket_connect.NetCheckState
import org.daimhim.imc_core.IDetectionResults
import org.daimhim.imc_core.NST
import java.util.concurrent.TimeUnit

val UNKNOWN = 0
val GOOD = 1
val BAD = -1
val OFFLINE = -2

class NetCheckDetect : NST {

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

    override fun activeSniffing() {
    }

    override fun intervalSniffing(intervalTime: TimeUnit, count: Int) {
    }

    override fun cancelSniffing() {
    }

    override fun getLastResult(): Int {

    }

    override fun register(detectionResults: IDetectionResults) {

    }

    override fun unregister(detectionResults: IDetectionResults) {

    }
}