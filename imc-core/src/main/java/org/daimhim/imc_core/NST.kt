package org.daimhim.imc_core

import java.util.concurrent.TimeUnit

/**
 * Network sniffing tools
 * 网络嗅探工具
 */
interface NST  {
    /**
     * 主动嗅探
     */
    fun activeSniffing()

    /**
     * 间隔嗅探
     * @param intervalTime 间隔时间
     * @param count 间隔次数，-1则为无限次
     */
    fun intervalSniffing(intervalTime:TimeUnit,count:Int)

    /**
     * 取消正在进行的嗅探
     */
    fun cancelSniffing()

    /**
     * 获取最后一次结果
     */
    fun getLastResult():Int

    /**
     * 注册网络监听
     */
    fun register(detectionResults:IDetectionResults)

    /**
     * 解除网络监听注册
     */
    fun unregister(detectionResults:IDetectionResults)
}