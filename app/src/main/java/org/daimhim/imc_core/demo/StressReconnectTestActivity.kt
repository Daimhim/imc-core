package org.daimhim.imc_core.demo

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.daimhim.imc_core.IMCStatusListener
import org.daimhim.imc_core.TimberIMCLog
import org.daimhim.imc_core.V2FixedHeartbeat
import org.daimhim.imc_core.V2JavaWebEngine
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 重连压测面板。手动跑 N 次 engineOff → 等 gap → engineOn → 等 onOpen 的循环,
 * 收集"重连成功率 + 重连时延分布",并在前/后采样 java heap / VmRSS 用来观察内存增长。
 *
 * 测试基线:
 *  - 1000 次循环,gap=500ms,success rate ≥ 99.5%
 *  - 跑完 RSS 增长 ≤ 5MB
 *  - p95 reconnect 时延 ≤ 2s(给"网络大切换 2s 内"留余量)
 */
class StressReconnectTestActivity : AppCompatActivity() {

    companion object {
        private const val URL_TEMPLATE =
            "wss://client.qgbtech.cn/ws:90?token=%s&name=%s&platform=android&state=1"
        // 每次循环 onOpen 最长等多久,超了算 fail
        private const val CONNECT_TIMEOUT_MS = 30_000L
        // 每 N 次打一次内存快照
        private const val MEM_SAMPLE_EVERY = 100
    }

    private lateinit var engine: V2JavaWebEngine
    private val running = AtomicBoolean(false)
    private var worker: Thread? = null

    // 等当前一次循环的 onOpen
    @Volatile private var pendingLatch: CountDownLatch? = null
    private val lastErrorRef = AtomicReference<String?>(null)

    private val statusListener = object : IMCStatusListener {
        override fun connectionSucceeded() {
            pendingLatch?.countDown()
        }
        override fun connectionClosed(code: Int, reason: String?) {
            // 故意 engineOff 触发的关,不影响下次循环的 latch
        }
        override fun connectionLost(throwable: Throwable) {
            lastErrorRef.set("${throwable.javaClass.simpleName}: ${throwable.message}")
            // 这里不 countDown,让上层 30s 超时统一裁定 fail
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_stress_reconnect)
        // 压测可能 1h+,屏幕亮着方便看进度;真正长时间用 adb keepalive 或 wakelock 更稳
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 用最朴素的引擎:固定 60s 心跳, 没有 surveillance、没有 autoConnect — 只测 SDK 本体的 engineOn/Off 路径。
        // 持久化缓存挂在私有目录文件,跑完看是否泄漏文件句柄。
        engine = V2JavaWebEngine.Builder()
            .setIMCLog(TimberIMCLog("Stress"))
            .addHeartbeatMode(0, V2FixedHeartbeat.Builder().setCurHeartbeat(60).build())
            .setMessageCache(
                org.daimhim.imc_core.FileMessageCache(File(filesDir, "stress_msg_cache.bin"))
            )
            .build()
        engine.setIMCStatusListener(statusListener)

        // 预填(跟 QgbWsTestActivity 同样的默认,方便直接复用)
        findViewById<EditText>(R.id.et_stress_token).setText(
            intent.getStringExtra("token") ?: DEFAULT_TOKEN
        )
        findViewById<EditText>(R.id.et_stress_name).setText(
            intent.getStringExtra("name") ?: DEFAULT_NAME
        )

        findViewById<Button>(R.id.bt_stress_start).setOnClickListener { onStartClick() }
        findViewById<Button>(R.id.bt_stress_stop).setOnClickListener { onStopClick() }
        findViewById<Button>(R.id.bt_stress_reset).setOnClickListener { onResetClick() }
        findViewById<Button>(R.id.bt_stress_log).setOnClickListener {
            startActivity(Intent(this, FullLogActivity::class.java))
        }
        renderMemory("baseline")
        renderStats(null)

        // adb 自动驱动:--ez autoStart true 时启动后自动点 Start
        // 可叠加 --ei cycles N --ei gap M 来定制规模
        if (intent?.getBooleanExtra("autoStart", false) == true) {
            intent.getIntExtra("cycles", -1).takeIf { it > 0 }?.let {
                findViewById<EditText>(R.id.et_stress_cycles).setText(it.toString())
            }
            intent.getIntExtra("gap", -1).takeIf { it > 0 }?.let {
                findViewById<EditText>(R.id.et_stress_gap).setText(it.toString())
            }
            findViewById<Button>(R.id.bt_stress_start).post {
                findViewById<Button>(R.id.bt_stress_start).performClick()
            }
        }
    }

    override fun onDestroy() {
        running.set(false)
        worker?.interrupt()
        try { engine.engineOff() } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun onStartClick() {
        if (running.get()) {
            toast("已经在跑"); return
        }
        val token = findViewById<EditText>(R.id.et_stress_token).text.toString().trim()
        val name = findViewById<EditText>(R.id.et_stress_name).text.toString().trim()
        if (token.isEmpty() || name.isEmpty()) {
            toast("token / name 不能为空"); return
        }
        val cycles = findViewById<EditText>(R.id.et_stress_cycles).text.toString()
            .toIntOrNull() ?: 1000
        val gapMs = findViewById<EditText>(R.id.et_stress_gap).text.toString()
            .toLongOrNull() ?: 500L
        val url = String.format(URL_TEMPLATE, token, name)

        running.set(true)
        log("[stress] start cycles=$cycles gap=${gapMs}ms")
        renderMemory("before")
        val memBefore = snapshotMemory()

        worker = Thread({ runStress(url, cycles, gapMs, memBefore) }, "stress-driver").apply { start() }
    }

    private fun onStopClick() {
        if (!running.get()) return
        log("[stress] 手动停止")
        running.set(false)
        worker?.interrupt()
        try { engine.engineOff() } catch (_: Exception) {}
    }

    private fun onResetClick() {
        if (running.get()) {
            toast("先停了再 reset"); return
        }
        try { engine.engineOff() } catch (_: Exception) {}
        lastErrorRef.set(null)
        renderStats(null)
        renderMemory("baseline")
    }

    private fun runStress(url: String, cycles: Int, gapMs: Long, memBefore: MemSnapshot) {
        var ok = 0
        var fail = 0
        val rtts = ArrayList<Long>(cycles)
        var firstFailIdx = -1
        val tStartAll = System.currentTimeMillis()

        for (i in 0 until cycles) {
            if (!running.get()) break

            // off
            try { engine.engineOff() } catch (e: Exception) {
                log("[stress] engineOff#$i ex=${e.message}")
            }
            try { Thread.sleep(gapMs) } catch (_: InterruptedException) { break }

            // on, 等 onOpen 回调
            val latch = CountDownLatch(1)
            pendingLatch = latch
            lastErrorRef.set(null)
            val tStart = System.currentTimeMillis()
            try {
                engine.engineOn(url)
            } catch (e: Exception) {
                log("[stress] engineOn#$i ex=${e.message}")
                fail++
                if (firstFailIdx < 0) firstFailIdx = i
                publishStats(i + 1, ok, fail, rtts, firstFailIdx, tStartAll)
                continue
            }
            val landed = try {
                latch.await(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                false
            }
            pendingLatch = null
            val rtt = System.currentTimeMillis() - tStart
            if (landed) {
                ok++
                rtts.add(rtt)
            } else {
                fail++
                if (firstFailIdx < 0) firstFailIdx = i
                log("[stress] fail#$i timeout/err err=${lastErrorRef.get()} rtt=${rtt}ms")
            }
            publishStats(i + 1, ok, fail, rtts, firstFailIdx, tStartAll)

            if ((i + 1) % MEM_SAMPLE_EVERY == 0) {
                publishMemory("@cycle ${i + 1}")
            }
        }

        // 收尾
        try { engine.engineOff() } catch (_: Exception) {}
        running.set(false)
        val memAfter = snapshotMemory()
        log(
            "[stress] 结束 ok=$ok fail=$fail Δheap=${(memAfter.javaUsedKb - memBefore.javaUsedKb)}KB " +
                "ΔRSS=${(memAfter.rssKb - memBefore.rssKb)}KB"
        )
        runOnUiThread {
            findViewById<TextView>(R.id.tv_stress_mem).text = buildString {
                append("before: ").append(formatMem(memBefore)).append('\n')
                append("after:  ").append(formatMem(memAfter)).append('\n')
                append("Δheap   ").append(memAfter.javaUsedKb - memBefore.javaUsedKb).append("KB  ")
                append("ΔRSS ").append(memAfter.rssKb - memBefore.rssKb).append("KB")
            }
        }
    }

    private fun publishStats(
        done: Int, ok: Int, fail: Int,
        rtts: List<Long>, firstFailIdx: Int, tStartAll: Long,
    ) {
        // 复制 rtts 避免并发(rtts 在 worker 线程改;UI 用快照)
        val rttCopy = ArrayList(rtts)
        val elapsed = System.currentTimeMillis() - tStartAll
        runOnUiThread { renderStats(StatsSnapshot(done, ok, fail, rttCopy, firstFailIdx, elapsed)) }
    }

    private fun publishMemory(tag: String) {
        val mem = snapshotMemory()
        runOnUiThread {
            findViewById<TextView>(R.id.tv_stress_mem).text = "$tag: ${formatMem(mem)}"
        }
    }

    private fun renderStats(s: StatsSnapshot?) {
        val tv = findViewById<TextView>(R.id.tv_stress_stats)
        if (s == null) { tv.text = "(未开始)"; return }
        val successRate = if (s.done == 0) 0.0 else s.ok * 100.0 / s.done
        val sorted = s.rtts.sorted()
        val p50 = pct(sorted, 50)
        val p95 = pct(sorted, 95)
        val mean = if (sorted.isEmpty()) 0L else sorted.sum() / sorted.size
        val max = sorted.lastOrNull() ?: 0L
        val min = sorted.firstOrNull() ?: 0L
        tv.text = buildString {
            append("done   : ").append(s.done).append('\n')
            append("ok / fail : ").append(s.ok).append(" / ").append(s.fail).append('\n')
            append("success%  : ").append("%.2f".format(successRate)).append("%\n")
            append("RTT min / p50 / mean / p95 / max (ms): ")
                .append(min).append(" / ")
                .append(p50).append(" / ")
                .append(mean).append(" / ")
                .append(p95).append(" / ")
                .append(max).append('\n')
            append("第一次 fail @ cycle : ").append(if (s.firstFailIdx < 0) "-" else s.firstFailIdx).append('\n')
            append("elapsed   : ").append(s.elapsedMs / 1000).append("s")
        }
    }

    private fun renderMemory(tag: String) {
        findViewById<TextView>(R.id.tv_stress_mem).text = "$tag: ${formatMem(snapshotMemory())}"
    }

    private fun pct(sorted: List<Long>, p: Int): Long {
        if (sorted.isEmpty()) return 0L
        val idx = ((sorted.size - 1) * p / 100).coerceIn(0, sorted.size - 1)
        return sorted[idx]
    }

    private fun snapshotMemory(): MemSnapshot {
        val rt = Runtime.getRuntime()
        val javaTotal = rt.totalMemory()
        val javaFree = rt.freeMemory()
        val javaUsedKb = (javaTotal - javaFree) / 1024
        val rssKb = readVmRssKb() ?: -1L
        return MemSnapshot(javaUsedKb, rssKb, javaTotal / 1024)
    }

    /** /proc/self/status 里抓 VmRSS:    NNNN kB */
    private fun readVmRssKb(): Long? {
        return try {
            File("/proc/self/status").bufferedReader().use { r ->
                r.lineSequence()
                    .firstOrNull { it.startsWith("VmRSS:") }
                    ?.substringAfter("VmRSS:")
                    ?.trim()
                    ?.substringBefore(" ")
                    ?.toLongOrNull()
            }
        } catch (_: Exception) { null }
    }

    private fun formatMem(m: MemSnapshot): String =
        "javaUsed=${m.javaUsedKb}KB / javaTotal=${m.javaTotalKb}KB / RSS=${m.rssKb}KB"

    private fun log(line: String) = LogStore.append(line)
    private fun toast(msg: String) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    private data class MemSnapshot(val javaUsedKb: Long, val rssKb: Long, val javaTotalKb: Long)

    private data class StatsSnapshot(
        val done: Int,
        val ok: Int,
        val fail: Int,
        val rtts: List<Long>,
        val firstFailIdx: Int,
        val elapsedMs: Long,
    )
}

// 跟 QgbWsTestActivity 同步的占位 token / name,免得反复粘贴
private const val DEFAULT_TOKEN =
    "eyJ0eXAiOiJKV1QiLCJhbGciOiJSUzUxMiJ9.eyJzdWIiOiIxMTk5MjQ0MzIxMjU0MTUwMTQ0Iiwic2NvcGUiOiJkZWZhdWx0IiwiaXNzIjoiMTUwMTUxMTIwMDgiLCJsb2dpbiI6MTc3ODY1NTc1MX0.BQdjONVuZhSgMZrimHyplcqO8NYXlGYO-KUA-rjn9EvPRdAQGlIn95L2ukarAC5TOUnxYFImaF7u_YtEtoWaUGqTBNohUmJiCdY5B9xRlWoE23EcXKB6PSXIXIcWvZzG9oFBv9-jz1SbnxMtPK0H4jiHuK4U4B9N71BAD-SAjmA"
private const val DEFAULT_NAME = "202012221018295"
