package org.daimhim.imc_core

/**
 * 综合网络状态判定
 *
 * 按优先级从高到低应用,只取最严重一项。例如同时存在 CAPTIVE_PORTAL 与 CONGESTED 时,
 * 报 CAPTIVE_PORTAL —— 因为认证不通就连通都谈不上,拥塞与否就无意义
 */
enum class NetVerdict {
    /** onLost / onUnavailable, 无可用网络 */
    OFFLINE,

    /** 当前应用被网络策略阻塞 (onBlockedStatusChanged=true) */
    BLOCKED,

    /** 门户型 Wi-Fi, 需打开网页认证 (酒店 / 机场 / 商场) */
    CAPTIVE_PORTAL,

    /** 已连接但 VALIDATED 标志未置位, 实际不通 */
    CONNECTED_NOT_VALIDATED,

    /** 蜂窝挂起, NOT_SUSPENDED 标志缺失 (API 28+) */
    SUSPENDED,

    /** 网络拥塞, NOT_CONGESTED 标志缺失 (API 28+) */
    CONGESTED,

    /** 系统正在验证 (能力位尚未推送过来) */
    CHECKING_CONNECTIVITY,

    /** 一切正常 */
    OK
}

/**
 * 网络能力位
 *
 * rawValue 直接对齐 Android `NetworkCapabilities.NET_CAPABILITY_*` 常量,
 * 字段子集也覆盖了 HarmonyOS `connection.NetCap`,因此跨平台模型可统一
 */
enum class NetCap(val rawValue: Int) {
    MMS(0),
    NOT_METERED(11),
    INTERNET(12),
    NOT_VPN(15),
    VALIDATED(16),
    CAPTIVE_PORTAL(17),
    NOT_ROAMING(18),
    NOT_CONGESTED(20),
    NOT_SUSPENDED(21),
    TEMPORARILY_NOT_METERED(28);

    companion object {
        fun fromRaw(raw: Int): NetCap? = values().firstOrNull { it.rawValue == raw }
    }
}

/**
 * 传输介质
 *
 * rawValue 对齐 Android `NetworkCapabilities.TRANSPORT_*`
 */
enum class NetTransport(val rawValue: Int) {
    CELLULAR(0),
    WIFI(1),
    BLUETOOTH(2),
    ETHERNET(3),
    VPN(4),
    WIFI_AWARE(5),
    LOWPAN(6);

    companion object {
        fun fromRaw(raw: Int): NetTransport? = values().firstOrNull { it.rawValue == raw }
    }
}

/**
 * 声明层快照
 *
 * 系统主动推过来的网络事实,不含主动探测结果。订阅 [NetStateMonitor] 会
 * 在每次有意义的状态变化时收到新的快照
 */
data class NetSnapshot(
    val verdict: NetVerdict,
    val capabilities: Set<NetCap>,
    val transports: Set<NetTransport>,
    val linkUpKbps: Int,
    val linkDownKbps: Int,
    /** 信号强度 dBm, API 29+ 可拿到, 早期或拿不到时为 null */
    val signalStrengthDbm: Int?,
    val timestamp: Long
) {
    companion object {
        val OFFLINE = NetSnapshot(
            verdict = NetVerdict.OFFLINE,
            capabilities = emptySet(),
            transports = emptySet(),
            linkUpKbps = 0,
            linkDownKbps = 0,
            signalStrengthDbm = null,
            timestamp = 0L
        )
    }
}

/**
 * 单方法回调,可以用 lambda 直接传
 */
fun interface NetStateListener {
    fun onChange(snapshot: NetSnapshot)
}

/**
 * 声明层监听器抽象,实现层负责对接到具体平台 API
 *
 * 通常在 `Application.onCreate()` 里 [start] 一次,在进程退出时 [stop],生命周期与进程同寿
 */
interface NetStateMonitor {
    fun start()
    fun stop()

    /** 拿当前最新快照,如果监听还未启动则返回 [NetSnapshot.OFFLINE] */
    fun current(): NetSnapshot

    fun register(listener: NetStateListener)
    fun unregister(listener: NetStateListener)
}
