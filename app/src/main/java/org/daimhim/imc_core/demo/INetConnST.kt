package com.custom.socket_connect

import org.daimhim.imc_core.NetCheckConfig
import org.jetbrains.annotations.Nullable

interface INetConnST {


    fun dnsPingResult(config: NetCheckConfig, callback: (ping: Boolean, dns: Boolean) -> Unit)

    /**
     * 检测网络连接状态
     */
    fun dnsCheck(
        hostName: String,
        timeout: Long,
        callback: (isSuccess: Boolean) -> Unit
    )

    /**
     * 检测网络是否可用
     */
    fun pingCheck(
        address: String,
        count: Int,
        size: Int,
        timeout: Long,
        callback: (arriveRate: Int, timeMilliseconds: String) -> Unit
    )

}