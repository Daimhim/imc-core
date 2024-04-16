package org.daimhim.imc_core

import org.jetbrains.annotations.Nullable

interface IConnST {
    /**
     * 检测网络连接状态
     */
    fun checkNetConnectState()

    /**
     * 检测网络是否可用
     */
    fun isNetworkAvailable(
        address: String,
        count: Int = 1,
        timeout: Long = 1000,
        callback: (isAvailable: Boolean) -> Unit
    )


}