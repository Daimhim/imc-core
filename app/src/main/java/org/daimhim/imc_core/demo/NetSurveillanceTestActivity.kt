package org.daimhim.imc_core.demo

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.daimhim.imc_core.BurstProbeReport
import org.daimhim.imc_core.DefaultNetProber
import org.daimhim.imc_core.DefaultNetSurveillance
import org.daimhim.imc_core.NetProbeProfile
import org.daimhim.imc_core.NetReport
import org.daimhim.imc_core.NetSnapshot
import org.daimhim.imc_core.NetSurveillance
import org.daimhim.imc_core.ProbeReport
import org.daimhim.imc_core.ProbeStage
import org.daimhim.imc_core.ProbeTarget
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 3 层网络监听机制的可视化 demo
 *
 * 启动后会显示:
 *  - L1 声明层:Android 系统推过来的 capabilities / transports / verdict
 *  - L2 探测层:点 "Force Probe" 触发,显示 DNS / TCP / TLS / publicRef 各段耗时与结果
 *  - L3 编排层:融合判定 overall + 给 ProgressiveAutoConnect 的 recommend
 */
class NetSurveillanceTestActivity : AppCompatActivity() {

    private lateinit var surveillance: NetSurveillance
    private val burstProber = DefaultNetProber()

    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    private val reportListener = { r: NetReport ->
        runOnUiThread { render(r) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_net_surveillance)

        val target = ProbeTarget(
            host = "client.jingyingbang.com",
            port = 443,
            useTls = true,
            publicReference = ProbeTarget("www.baidu.com", 443, true)
        )

        surveillance = DefaultNetSurveillance.Builder()
            .monitor(AndroidNetStateMonitor(applicationContext))
            .prober(DefaultNetProber())
            .probeTarget(target)
            // BALANCED 档:500ms debounce + 10s minProbeInterval,burst 默认关。
            // 想换 burst 间隔走 profile copy,不再有独立的 enableBurst 配置点。
            .profile(
                NetProbeProfile.BALANCED.copy(burstIntervalMs = BURST_INTERVAL_MS)
            )
            .build()

        surveillance.register { reportListener(it) }
        surveillance.start()

        findViewById<Button>(R.id.bt_force_probe).setOnClickListener {
            val host = findViewById<EditText>(R.id.et_probe_host).text.toString().trim()
            val portStr = findViewById<EditText>(R.id.et_probe_port).text.toString().trim()
            val port = portStr.toIntOrNull() ?: 443
            log("Force probe → $host:$port")
            // 注意:这里只是更新 UI 演示, surveillance.forceProbe 会用 build 时指定的 target
            // 想动态切 target 的话需要重建 surveillance 或扩展接口
            surveillance.forceProbe { r ->
                runOnUiThread { render(r) }
            }
        }

        findViewById<Button>(R.id.bt_view_log).setOnClickListener {
            startActivity(Intent(this, FullLogActivity::class.java))
        }

        // Burst probe 控件:单次按钮走 prober.probeBurst,checkbox 走 surveillance.setBurstEnabled
        findViewById<Button>(R.id.bt_force_burst).setOnClickListener { doBurst() }
        findViewById<CheckBox>(R.id.cb_enable_burst).setOnCheckedChangeListener { _, on ->
            surveillance.setBurstEnabled(on)
            if (on) {
                log("已启用周期 burst,间隔 ${BURST_INTERVAL_MS}ms — 结果会刷到 NetReport")
            } else {
                log("已关闭周期 burst")
                findViewById<TextView>(R.id.tv_burst).text = "(已关闭)"
            }
        }

        // 渲染初始快照
        render(surveillance.current())
    }

    override fun onDestroy() {
        burstProber.cancel()
        surveillance.stop()
        super.onDestroy()
    }

    private fun doBurst() {
        val host = findViewById<EditText>(R.id.et_probe_host).text.toString().trim()
        val port = findViewById<EditText>(R.id.et_probe_port).text.toString().toIntOrNull() ?: 443
        if (host.isEmpty()) return
        val target = ProbeTarget(host, port, useTls = true)
        log("Burst probe → $host:$port (5 次)")
        findViewById<TextView>(R.id.tv_burst).text = "测量中…"
        burstProber.probeBurst(target, attempts = 5, intervalMs = 200L, perAttemptTimeoutMs = 2_000) { r ->
            runOnUiThread {
                findViewById<TextView>(R.id.tv_burst).text = renderBurst(r)
                log("burst 完成: 丢 ${(r.lossRate * 100).toInt()}% mean=${r.meanMs?.toLong()}ms jitter=${r.jitterMs?.toLong()}ms")
            }
        }
    }

    private fun renderBurst(r: BurstProbeReport): String = buildString {
        if (r.attempts == 0) { append("(无数据)"); return@buildString }
        append("attempts=${r.attempts}  成功=${r.successes}  ")
        append("丢包=${"%.1f".format(r.lossRate * 100)}%\n")
        if (r.meanMs != null) {
            append("RTT  min=${r.minMs}  mean=${r.meanMs?.toLong()}  max=${r.maxMs} ms\n")
            if (r.jitterMs != null) {
                append("抖动(stddev)=${"%.1f".format(r.jitterMs!!)} ms\n")
            }
            append("p50=${r.p50Ms}  p95=${r.p95Ms} ms")
        } else {
            append("全部失败")
        }
    }

    companion object {
        private const val BURST_INTERVAL_MS = 30_000L
    }

    private fun render(r: NetReport) {
        findViewById<TextView>(R.id.tv_overall).text = r.overall.name
        findViewById<TextView>(R.id.tv_recommend).text = "recommend: ${r.recommend}"
        findViewById<TextView>(R.id.tv_snapshot).text = renderSnapshot(r.snapshot)
        findViewById<TextView>(R.id.tv_probe).text = renderProbe(r.lastProbe)
        val burst = r.lastBurstProbe
        if (burst != null && burst.attempts > 0) {
            findViewById<TextView>(R.id.tv_burst).text = renderBurst(burst)
        }
        log("overall=${r.overall} recommend=${r.recommend}")
    }

    private fun renderSnapshot(s: NetSnapshot): String = buildString {
        append("verdict     : ").append(s.verdict).append('\n')
        append("transports  : ").append(s.transports.joinToString { it.name }).append('\n')
        append("capabilities: ").append(s.capabilities.joinToString { it.name }).append('\n')
        append("linkUpKbps  : ").append(s.linkUpKbps).append('\n')
        append("linkDownKbps: ").append(s.linkDownKbps).append('\n')
        append("signalDbm   : ").append(s.signalStrengthDbm ?: "-").append('\n')
        append("timestamp   : ").append(formatTime(s.timestamp))
    }

    private fun renderProbe(p: ProbeReport?): String {
        if (p == null) return "(no probe yet)"
        return buildString {
            append("verdict   : ").append(p.verdict).append('\n')
            append("target    : ").append(p.target.host).append(':').append(p.target.port).append('\n')
            append("dns       : ").append(fmtStage(p.dns)).append('\n')
            append("tcp       : ").append(fmtStage(p.tcp)).append('\n')
            append("tls       : ").append(p.tls?.let { fmtStage(it) } ?: "-").append('\n')
            append("httpHealth: ").append(p.httpHealth?.let { fmtStage(it) } ?: "-").append('\n')
            append("publicRef : ").append(p.publicRef?.let { fmtStage(it) } ?: "-").append('\n')
            append("timestamp : ").append(formatTime(p.timestamp))
        }
    }

    private fun fmtStage(stage: ProbeStage): String {
        val mark = if (stage.success) "OK" else "FAIL"
        val err = stage.error?.let { " ($it)" } ?: ""
        return "$mark ${stage.elapsedMs}ms$err"
    }

    private fun formatTime(ms: Long): String =
        if (ms == 0L) "-" else timeFmt.format(Date(ms))

    private fun log(line: String) {
        LogStore.append(line)
    }
}
