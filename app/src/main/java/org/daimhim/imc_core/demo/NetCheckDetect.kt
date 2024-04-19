package org.daimhim.imc_core.demo

import com.custom.socket_connect.NetCheckState
import org.daimhim.imc_core.IDetectionResults
import org.daimhim.imc_core.NST
import java.util.concurrent.TimeUnit

val UNKNOWN = 0//网络状态未知
val GOOD = 1//网络状态良好
val BAD = -1//网络差
val OFFLINE = -2//无网络

class NetCheckDetect : NST {
    private val registerList = mutableListOf<IDetectionResults>()
    private val netStateCheck by lazy { NetStateCheck() }
    private var netState:Int = UNKNOWN

    override fun activeSniffing() {
        netStateCheck.dnsPingResult("www.baidu.com", 2, 64, 3000) { ping, dns ->
            if (!dns) {
                netState = OFFLINE
                return@dnsPingResult
            }
            if (!ping) {
                netState = BAD
                return@dnsPingResult
            }
            if (ping && dns) {
                netState = GOOD
                return@dnsPingResult
            }
            netState = NetCheckState.UNKNOWN.ordinal
        }
    }

    override fun intervalSniffing(intervalTime: TimeUnit, count: Int) {


    }

    override fun cancelSniffing() {

    }

    override fun getLastResult(): Int {
        return netState
    }

    override fun register(detectionResults: IDetectionResults) {
        if (registerList.isEmpty() || !registerList.contains(detectionResults)) {
            registerList.add(detectionResults)
        }
    }

    override fun unregister(detectionResults: IDetectionResults) {
        if (registerList.isEmpty()) return
        registerList.remove(detectionResults)
    }
}