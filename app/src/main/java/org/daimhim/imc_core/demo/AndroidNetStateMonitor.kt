package org.daimhim.imc_core.demo

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import org.daimhim.imc_core.NetCap
import org.daimhim.imc_core.NetSnapshot
import org.daimhim.imc_core.NetStateListener
import org.daimhim.imc_core.NetStateMonitor
import org.daimhim.imc_core.NetTransport
import org.daimhim.imc_core.NetVerdict
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Android 平台的声明层监听实现
 *
 * 直接对接 [ConnectivityManager.NetworkCallback],把回调里能拿到的
 * NetworkCapabilities / LinkProperties / blockedStatus 折叠成 [NetSnapshot]
 *
 * - API 22 起可用,内部对高版本能力位做了版本守卫
 * - 监听 default network,跟随系统当前主网络变化
 * - 回调可能从 Binder 线程触发,通知监听器时用快照、不持锁
 */
class AndroidNetStateMonitor(context: Context) : NetStateMonitor {

    private val cm: ConnectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val listeners = CopyOnWriteArrayList<NetStateListener>()
    private val lock = Any()

    @Volatile
    private var snapshot: NetSnapshot = NetSnapshot.OFFLINE

    @Volatile
    private var started = false

    // 记录最近一次拿到的能力位,在 onBlockedStatusChanged 单独到达时使用
    @Volatile
    private var lastCapabilities: NetworkCapabilities? = null

    @Volatile
    private var lastBlocked: Boolean = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            // 等 onCapabilitiesChanged 拿到能力位再算 verdict
        }

        override fun onLost(network: Network) {
            lastCapabilities = null
            updateSnapshot(NetSnapshot.OFFLINE.copy(timestamp = now()))
        }

        override fun onUnavailable() {
            lastCapabilities = null
            updateSnapshot(NetSnapshot.OFFLINE.copy(timestamp = now()))
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            lastCapabilities = caps
            updateSnapshot(buildSnapshot(caps, lastBlocked))
        }

        override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
            lastBlocked = blocked
            val caps = lastCapabilities ?: return
            updateSnapshot(buildSnapshot(caps, blocked))
        }
    }

    override fun start() {
        synchronized(lock) {
            if (started) return
            started = true
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        // 用 default network callback 的话能拿到当前主网络变化;
        // registerNetworkCallback 只关注 "符合 request" 的所有网络,选 default 更贴近用户体验
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            cm.registerDefaultNetworkCallback(callback)
        } else {
            cm.registerNetworkCallback(request, callback)
        }
    }

    override fun stop() {
        synchronized(lock) {
            if (!started) return
            started = false
        }
        try {
            cm.unregisterNetworkCallback(callback)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        lastCapabilities = null
        lastBlocked = false
        updateSnapshot(NetSnapshot.OFFLINE.copy(timestamp = now()))
    }

    override fun current(): NetSnapshot = snapshot

    override fun register(listener: NetStateListener) {
        if (!listeners.contains(listener)) listeners.add(listener)
    }

    override fun unregister(listener: NetStateListener) {
        listeners.remove(listener)
    }

    private fun updateSnapshot(next: NetSnapshot) {
        synchronized(lock) {
            if (snapshot == next) return
            snapshot = next
        }
        listeners.forEach {
            try {
                it.onChange(next)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    @SuppressLint("NewApi")
    private fun buildSnapshot(caps: NetworkCapabilities, isBlocked: Boolean): NetSnapshot {
        val capSet = mutableSetOf<NetCap>()
        for (cap in NetCap.values()) {
            if (!isCapSupported(cap)) continue
            try {
                if (caps.hasCapability(cap.rawValue)) capSet.add(cap)
            } catch (_: Throwable) {
                // 个别 ROM 不识别某些 cap,忽略
            }
        }

        val transportSet = mutableSetOf<NetTransport>()
        for (t in NetTransport.values()) {
            if (!isTransportSupported(t)) continue
            try {
                if (caps.hasTransport(t.rawValue)) transportSet.add(t)
            } catch (_: Throwable) {
            }
        }

        val signalDbm: Int? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val s = caps.signalStrength
                if (s == Int.MIN_VALUE) null else s
            } catch (_: Throwable) {
                null
            }
        } else null

        val verdict = computeVerdict(capSet, isBlocked)

        return NetSnapshot(
            verdict = verdict,
            capabilities = capSet,
            transports = transportSet,
            linkUpKbps = caps.linkUpstreamBandwidthKbps,
            linkDownKbps = caps.linkDownstreamBandwidthKbps,
            signalStrengthDbm = signalDbm,
            timestamp = now()
        )
    }

    /**
     * verdict 优先级:BLOCKED > CAPTIVE_PORTAL > CONNECTED_NOT_VALIDATED >
     *               SUSPENDED > CONGESTED > CHECKING_CONNECTIVITY > OK
     *
     * OFFLINE 不在这里产生 —— OFFLINE 由 onLost / onUnavailable 直接驱动
     */
    private fun computeVerdict(caps: Set<NetCap>, isBlocked: Boolean): NetVerdict {
        if (isBlocked) return NetVerdict.BLOCKED

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (NetCap.CAPTIVE_PORTAL in caps) return NetVerdict.CAPTIVE_PORTAL
            if (NetCap.VALIDATED !in caps) {
                // M 之前 VALIDATED 这个位不存在,所以这一判断只在 M+ 有效
                if (NetCap.INTERNET in caps) return NetVerdict.CONNECTED_NOT_VALIDATED
                return NetVerdict.CHECKING_CONNECTIVITY
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if (NetCap.NOT_SUSPENDED !in caps) return NetVerdict.SUSPENDED
            if (NetCap.NOT_CONGESTED !in caps) return NetVerdict.CONGESTED
        }

        if (NetCap.INTERNET !in caps) return NetVerdict.CHECKING_CONNECTIVITY

        return NetVerdict.OK
    }

    private fun isCapSupported(cap: NetCap): Boolean = when (cap) {
        NetCap.VALIDATED, NetCap.CAPTIVE_PORTAL ->
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
        NetCap.NOT_CONGESTED, NetCap.NOT_SUSPENDED ->
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
        NetCap.TEMPORARILY_NOT_METERED ->
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
        else -> true
    }

    private fun isTransportSupported(t: NetTransport): Boolean = when (t) {
        NetTransport.WIFI_AWARE ->
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
        NetTransport.LOWPAN ->
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1
        else -> true
    }

    private fun now() = System.currentTimeMillis()
}
