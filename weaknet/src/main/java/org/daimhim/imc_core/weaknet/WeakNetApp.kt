package org.daimhim.imc_core.weaknet

import android.app.Application
import timber.multiplatform.log.DebugTree
import timber.multiplatform.log.Timber

class WeakNetApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Timber.plant(DebugTree())
        seedTestPresets()
    }

    /**
     * 首次启动时种 5 个工业基准 IM 弱网档位。
     * 用户后续可以改、删 — 一旦预设列表非空就不再种,避免覆盖手动改动。
     */
    private fun seedTestPresets() {
        val store = ChaosConfigStore(this)
        if (store.listNames().isNotEmpty()) return

        // 名字不带 shell 元字符,方便 adb am start --es preset xxx 自动化
        // save 把新预设插到表头,倒序保存使 spinner 顺序 = 优秀 → 断网
        //
        // 数值校准:我们的 chaos 是 per-chunk 字节流模型(每个 chunk 都 sleep),
        // 而真实网络是 per-packet RTT(整个 round-trip 加一次)。一次 TLS 握手 ≈ 6-10 个 chunk,
        // 所以这里 per-chunk 数值 ≈ 真实 RTT × 1/4。下面 02-04 的 latency 已按这个比例缩小,
        // 让标签语义更贴近真实网络。
        store.save("05-断网30s", ChaosConfig(disconnectAfterMs = 30_000))
        store.save("04-极弱",
            ChaosConfig(baseLatencyMs = 250, jitterMs = 50, dropChunkPercent = 10, maxBytesPerSecond = 8 * 1024))
        store.save("03-边界",
            ChaosConfig(baseLatencyMs = 125, jitterMs = 30, dropChunkPercent = 3, maxBytesPerSecond = 32 * 1024))
        store.save("02-可接受",
            ChaosConfig(baseLatencyMs = 50, jitterMs = 15, dropChunkPercent = 1, maxBytesPerSecond = 128 * 1024))
        store.save("01-优秀", ChaosConfig())
    }
}
