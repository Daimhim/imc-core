package org.daimhim.imc_core.demo

import org.daimhim.imc_core.IDetectionResults
import org.daimhim.imc_core.NST
import org.daimhim.imc_core.NetCheckConfig
import org.daimhim.imc_core.RapidResponseForceV2
import java.util.concurrent.TimeUnit

val UNKNOWN = 0//网络状态未知
val GOOD = 1//网络状态良好
val BAD = -1//网络差
val OFFLINE = -2//无网络

class NetCheckDetect : NST {
    //停止检测
    private var pauseCheck = false

    //网络检测结果
    private var netState: Int = UNKNOWN

    //检测任务id
    private val countDownId = "countDownId"

    //倒计时工具
    private val countDown = RapidResponseForceV2()

    //网络检测工具类
    private val netStateCheck by lazy { NetStateCheck() }

    //网络检测配置类
    private val netCheckConfig by lazy { NetCheckConfig() }

    //注册监听集合管理
    private val registerList = mutableListOf<IDetectionResults>()

    init {
        countDown.timeoutCallback(object : Comparable<Pair<String, Any?>> {
            override fun compareTo(other: Pair<String, Any?>): Int {
                activeSniffing()
                return 0
            }
        })
    }

    /**
     * @Description 网络检测
     */
    override fun activeSniffing() {
        if (pauseCheck) return
        netStateCheck.dnsPingResult(NetCheckConfig()) { ping, dns ->
            if (pauseCheck) return@dnsPingResult
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
            notifyState()
        }
    }

    /**
     * @Description 间隔网络检测
     * @param intervalTime 间隔时间（ms）
     * @param count 检测次数 -1：无限次数；
     */
    override fun intervalSniffing(intervalTime: TimeUnit, count: Int) {
        if (count == 0 || pauseCheck) return
        if (count < 0) {
            while (!pauseCheck) {
                netStateCheck.dnsPingResult(netCheckConfig) { ping, dns ->
                    if (pauseCheck) return@dnsPingResult
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
                    notifyState()
                    countDown.register(countDownId, 0, 5000)
                }
            }
            return
        }
        for (index in 0 until count) {
            netStateCheck.dnsPingResult(netCheckConfig) { ping, dns ->
                if (pauseCheck) return@dnsPingResult
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
                notifyState()
                countDown.register(countDownId, 0, 5000)
            }
        }
    }

    /**
     * @Description 取消网络状态检测
     */
    override fun cancelSniffing() {
        pauseCheck = true
    }

    /**
     * @Description 获取最后一次网络探测结果
     * @return  UNKNOWN = 0：网络状态未知
     *          GOOD = 1：网络状态良好
     *          BAD = -1：网络差
     *          OFFLINE = -2：无网络
     */
    override fun getLastResult(): Int {
        return netState
    }

    private fun notifyState() {
        if (registerList.isNullOrEmpty()) return
        try {
            registerList.forEach {
                it.onResults(getLastResult())
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
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