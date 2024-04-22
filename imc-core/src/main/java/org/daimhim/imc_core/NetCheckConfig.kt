package org.daimhim.imc_core

/**
 * @param address 要检测的地址
 * @param count 检测次数
 * @param size 数据大小
 * @param timeout 超时时间
 */
class NetCheckConfig(
    val address: String = "www.baidu.com",
    val count: Int = 2,
    val size: Int = 64,
    val timeout: Long = 3000
)