package org.daimhim.imc_core.demo

import com.custom.socket_connect.INetConnST
import com.custom.socket_connect.NetCheckState
import org.daimhim.imc_core.NetCheckConfig
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 采集延迟（ms）
 * 采集丢包率(%)
 */
class NetStateCheck : INetConnST {
    private val executorService: ExecutorService = Executors.newSingleThreadExecutor()

    private var netState: NetCheckState = NetCheckState.UNKNOWN

    /**
     * @Description dns ping 检测结果
     * @param address 要检测的地址
     * @param count 检测次数
     * @param size 数据大小
     * @param timeout 超时时间
     * @param callback 检测回调 ping:true成功，false失败；dns:true成功，false失败
     */
    override fun dnsPingResult(
        config: NetCheckConfig,
        callback: (ping: Boolean, dns: Boolean) -> Unit
    ) {
        var pingResult = false
        var dnsResult = false
        try {
            //dns域名解析，检测该域名是否可达
            dns(config.address).get(config.timeout, TimeUnit.MILLISECONDS).also {
                dnsResult = it
            }
            //ping检测只获取是否成功，无关具体结果
//            ping(config.address, config.count, config.size, config.timeout).get(timeout, TimeUnit.MILLISECONDS)?.also {
//                pingResult = it.first == 100
//            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        callback.invoke(pingResult, dnsResult)
    }

    /**
     * @Description 网络是否可用检测
     * @param address 检测网址
     * @param count 检测次数
     * @param size 数据包大小
     * @param timeout 超时时间 MILLISECONDS
     */
    override fun pingCheck(
        address: String,
        count: Int,
        size: Int,
        timeout: Long,
        callback: (arriveRate: Int, timeMilliseconds: String) -> Unit
    ) {
        ping(address, count, size, timeout).get(timeout / 1000, TimeUnit.SECONDS).also {
            callback.invoke(it.first, it.second)
        }
    }

    /**
     * @Description DNS网络检测
     * @param hostName 主机地址
     * @param timeout 超时时间 MILLISECONDS
     * @param callback 检测结果回调
     */
    override fun dnsCheck(
        hostName: String,
        timeout: Long,
        callback: (isSuccess: Boolean) -> Unit
    ) {
        dns(hostName).get(timeout, TimeUnit.MILLISECONDS).also {
            callback.invoke(it)
        }

    }

    /**
     * @Description DNS检测
     * @param hostName 域名
     * @return Future<Boolean> 延迟结果true:成功；false:失败
     */
    private fun dns(hostName: String) = executorService.submit<Boolean> {
        val inetAddress = InetAddress.getByName(hostName)
        !inetAddress.hostAddress.isNullOrEmpty()
    }

    /**
     * @Description 网络是否可用检测
     * @param address 检测网址
     * @param count 检测次数
     * @param size 数据包大小
     * @param timeout 超时时间 SECONDS
     * @return Future<Pair<Int, String>> first:成功率；second:耗时时间（ms）
     */
    private fun ping(
        address: String,
        count: Int,
        size: Int,
        timeout: Long
    ) = executorService.submit<Pair<Int, String>> {
        /**
         * -c 参数指定发送的回显请求数。
         * -w 参数指定超时时间
         * -s 参数指定发送的数据包大小
         * 发送 4 个回显请求，每个请求的超时时间为 5秒，数据包大小为 64 字节
         */
        val command = "ping -c $count -w $timeout -s $size $address"
        val process = Runtime.getRuntime().exec(command)
        try {
            val list = ArrayList<String>()
            BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val lineTemp = line!!
                    list.add(lineTemp)
                }
            }
            val resultStr = list[list.size - 2]
                .replace("[A-Za-z\\s+]".toRegex(), "")
                .split(",")
            val rate = 100 - (resultStr[2].replace("%", "").toInt())
            return@submit Pair(rate, resultStr[3])
        } catch (e: Exception) {
            e.printStackTrace()
        }
        Pair(0, timeout.toString())
    }
}