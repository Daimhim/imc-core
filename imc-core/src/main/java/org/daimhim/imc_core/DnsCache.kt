package org.daimhim.imc_core

import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap

/**
 * 端侧 DNS 缓存(简易 TTL + 最近一次成功兜底)。
 *
 * 设计目的:Wi-Fi 抖动 / 运营商 DNS 偶发故障(连续 UnknownHostException)时,
 * 端侧有缓存即可直接拿上次成功的 IP 续命,对上层完全无感。
 *
 * 策略:
 *  - 每次成功的 [InetAddress.getAllByName] 结果按 (host → addrs, expiresAt) 缓存
 *  - 默认 TTL = 300s,模仿主流 DNS 客户端
 *  - [resolve] 在 TTL 内直接返回缓存,过期则触发 fresh resolve;fresh 失败时**fallback 到上次成功的结果**
 *    (即使过期),直到 [STALE_MAX_TTL_MS] 上限(默认 24 小时)
 *  - 完全 fresh 失败且无任何历史 → 抛出原始的 [UnknownHostException]
 *
 * 线程安全:[resolve] 多线程并发安全;同 host 并发 fresh resolve 可能重复跑,
 * 不做去重(简单优先)。
 *
 * 不缓存:
 *  - localhost / 127.x / IP 字面量(getAllByName 实际不走 DNS,无需缓存)
 */
class DnsCache(
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val staleMaxTtlMs: Long = STALE_MAX_TTL_MS,
    /** 仅供测试注入;默认走 [InetAddress.getAllByName] */
    private val resolver: (String) -> Array<InetAddress> = InetAddress::getAllByName,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private data class Entry(
        val addrs: List<InetAddress>,
        val freshUntilMs: Long,
        val staleUntilMs: Long,
    )

    private val cache = ConcurrentHashMap<String, Entry>()

    /**
     * 解析 host,优先返回新鲜缓存;过期则 fresh resolve;fresh 失败回退到 stale 缓存;
     * 完全无可用 → 抛出最后一次 fresh 的 [UnknownHostException]。
     */
    @Throws(UnknownHostException::class)
    fun resolve(host: String): List<InetAddress> {
        val now = clock()
        val cached = cache[host]
        if (cached != null && now < cached.freshUntilMs) {
            return preferHealthy(cached.addrs, now)
        }
        return try {
            val fresh = resolver(host).toList()
            cache[host] = Entry(
                addrs = fresh,
                freshUntilMs = now + ttlMs,
                staleUntilMs = now + staleMaxTtlMs,
            )
            preferHealthy(fresh, now)
        } catch (e: UnknownHostException) {
            if (cached != null && now < cached.staleUntilMs) {
                // fresh 失败 + stale 在 stale-window 内 → 兜底用上次的 IP
                return preferHealthy(cached.addrs, now)
            }
            throw e
        }
    }

    /** address → 冷却截止时刻。见 [markFailed] */
    private val failedUntil = ConcurrentHashMap<InetAddress, Long>()

    /**
     * 标记某个地址刚连接失败(TCP 层:拒绝 / 超时 / 无路由)。冷却期内 [resolve] 把它排到最后。
     *
     * 为什么需要:Java-WebSocket 的 DnsResolver 只能返回单个地址,引擎取首个。首个地址不通
     * (典型:双栈主机的 IPv6 在当前网络不可达)时,每次重连都撞同一个地址,等满连接超时再退避。
     * 标记后下一次尝试自动换下一个地址;全部被标记时按最早解冻的优先,不会无地址可用。
     */
    fun markFailed(address: InetAddress, cooldownMs: Long = FAILED_COOLDOWN_MS) {
        failedUntil[address] = clock() + cooldownMs
    }

    /** 冷却中的地址排后(稳定排序,保留解析器给的优先级);冷却过期的顺手清掉 */
    private fun preferHealthy(addrs: List<InetAddress>, now: Long): List<InetAddress> {
        if (failedUntil.isEmpty() || addrs.size < 2) return addrs
        val (cooling, healthy) = addrs.partition { a ->
            val until = failedUntil[a] ?: return@partition false
            if (now >= until) {
                failedUntil.remove(a, until)
                false
            } else true
        }
        if (cooling.isEmpty()) return addrs
        return healthy + cooling.sortedBy { failedUntil[it] ?: 0L }
    }

    /** 主动失效一个 host(给业务在发现服务端 IP 改了时调) */
    fun invalidate(host: String) {
        cache.remove(host)
    }

    /** 清空 */
    fun clear() {
        cache.clear()
        failedUntil.clear()
    }

    companion object {
        const val DEFAULT_TTL_MS = 300_000L          // 5min
        const val STALE_MAX_TTL_MS = 24 * 3600_000L  // 24h
        const val FAILED_COOLDOWN_MS = 300_000L      // 5min
    }
}
