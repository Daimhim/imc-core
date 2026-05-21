package org.daimhim.imc_core.weaknet

import kotlin.random.Random

/**
 * 字节流上的 chaos 注入器(per-direction 状态)。
 *
 * 一条 flow 通常有两个实例:c→r 一个,r→c 一个。每次有一个 chunk 要发,就调 [apply];
 * apply 自己负责 sleep / 算 drop / 算限速 / 触发断连。
 *
 * 返回值:
 *  - true:这块照常发(可能已经 sleep 过了)
 *  - false:**不要**继续发这块。两种语义:drop 命中,或者触发了 disconnect(回调已被调过)
 *
 * **必须在 worker 线程上 apply**,不能在 VPN reader 线程上,否则跨 flow 阻塞。
 */
class ChaosBytePipe(
    private val configRef: () -> ChaosConfig,
    private val onTriggerDisconnect: (reason: String) -> Unit,
) {
    private val startMs = System.currentTimeMillis()
    private var bytesPassed = 0L
    private var windowStartMs = startMs
    private var bytesInWindow = 0L

    fun apply(len: Int): Boolean {
        val cfg = configRef()

        // 1. 时间阈值断开
        val uptime = System.currentTimeMillis() - startMs
        if (cfg.disconnectAfterMs > 0 && uptime >= cfg.disconnectAfterMs) {
            onTriggerDisconnect("disconnectAfterMs=${cfg.disconnectAfterMs}")
            return false
        }
        // 2. 字节阈值断开
        if (cfg.disconnectAfterBytes > 0 && bytesPassed >= cfg.disconnectAfterBytes) {
            onTriggerDisconnect("disconnectAfterBytes=${cfg.disconnectAfterBytes}")
            return false
        }
        // 3. 整块丢
        if (cfg.dropChunkPercent > 0 && Random.nextInt(100) < cfg.dropChunkPercent) {
            return false
        }
        // 4. 基础延迟 + 抖动
        val jitter = if (cfg.jitterMs > 0) Random.nextLong(cfg.jitterMs) else 0L
        val totalLatency = cfg.baseLatencyMs + jitter
        if (totalLatency > 0) {
            try { Thread.sleep(totalLatency) } catch (_: InterruptedException) { return false }
        }
        // 5. 带宽限速(滑动 1 秒窗口)
        if (cfg.maxBytesPerSecond > 0) {
            val now = System.currentTimeMillis()
            if (now - windowStartMs >= 1000) {
                windowStartMs = now
                bytesInWindow = 0
            }
            if (bytesInWindow + len > cfg.maxBytesPerSecond) {
                val sleepMs = 1000 - (now - windowStartMs)
                if (sleepMs > 0) {
                    try { Thread.sleep(sleepMs) } catch (_: InterruptedException) { return false }
                }
                windowStartMs = System.currentTimeMillis()
                bytesInWindow = 0
            }
            bytesInWindow += len
        }
        bytesPassed += len
        return true
    }

    fun bytesPassed(): Long = bytesPassed
}
