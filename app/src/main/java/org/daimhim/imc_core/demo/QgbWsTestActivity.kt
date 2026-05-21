package org.daimhim.imc_core.demo

import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.daimhim.imc_core.DefaultNetProber
import org.daimhim.imc_core.DefaultNetSurveillance
import org.daimhim.imc_core.IMCStatusListener
import org.daimhim.imc_core.BurstProbeReport
import org.daimhim.imc_core.NetReport
import org.daimhim.imc_core.NetSurveillance
import org.daimhim.imc_core.NetTransport
import org.daimhim.imc_core.NetVerdict
import org.daimhim.imc_core.ProbeTarget
import org.daimhim.imc_core.ProbeVerdict
import org.daimhim.imc_core.ReconnectAction
import org.daimhim.imc_core.ReconnectStatus
import org.daimhim.imc_core.ProgressiveAutoConnect
import org.daimhim.imc_core.TimberIMCLog
import org.daimhim.imc_core.V2FixedHeartbeat
import org.daimhim.imc_core.V2IMCListener
import org.daimhim.imc_core.V2JavaWebEngine
import org.daimhim.imc_core.V2SmartHeartbeat

/**
 * QGB 测试环境 WebSocket 演示
 *
 * - URL 模板: wss://client.qgbtech.cn/ws:90?token=%s&name=%s&platform=android&state=%s
 * - token: 从 sgb-management-android 登录日志里抓出后粘贴
 * - name: ImAccount (服务端返的账号 ID, 不是手机号)
 * - state: 0 = 首次连接, 1 = 重连/重试 (本 demo 在每次成功 connect 后自动切到 1)
 * - 接入 NetSurveillance, 让 ProgressiveAutoConnect 按 NetReport.recommend 智能退避
 */
class QgbWsTestActivity : AppCompatActivity() {

    companion object {
        private const val FIXED_HEARTBEAT = 0
        private const val SMART_HEARTBEAT = 1

        private const val URL_TEMPLATE =
            "wss://client.qgbtech.cn/ws:90?token=%s&name=%s&platform=android&state=%s"

        // 目标主机用来配 NetSurveillance 探测点 (端口走默认 443, URL 里的 :90 是路径里写的)
        private const val PROBE_HOST = "client.qgbtech.cn"
        private const val PROBE_PORT = 443

        // 默认填充用,省得每次手输 — Intent extras 优先,这两个只在 EditText 为空时兜底
        private const val DEFAULT_TOKEN =
            "eyJ0eXAiOiJKV1QiLCJhbGciOiJSUzUxMiJ9.eyJzdWIiOiIxMTk5MjQ0MzIxMjU0MTUwMTQ0Iiwic2NvcGUiOiJkZWZhdWx0IiwiaXNzIjoiMTUwMTUxMTIwMDgiLCJsb2dpbiI6MTc3ODY1NTc1MX0.BQdjONVuZhSgMZrimHyplcqO8NYXlGYO-KUA-rjn9EvPRdAQGlIn95L2ukarAC5TOUnxYFImaF7u_YtEtoWaUGqTBNohUmJiCdY5B9xRlWoE23EcXKB6PSXIXIcWvZzG9oFBv9-jz1SbnxMtPK0H4jiHuK4U4B9N71BAD-SAjmA"
        private const val DEFAULT_NAME = "202012221018295"
    }

    private lateinit var engine: V2JavaWebEngine
    private lateinit var surveillance: NetSurveillance
    private lateinit var autoConnect: ProgressiveAutoConnect
    private lateinit var foregroundBinder: EngineForegroundBinder

    private val mainHandler = Handler(Looper.getMainLooper())
    private val reconnectTick = object : Runnable {
        override fun run() {
            renderReconnect()
            mainHandler.postDelayed(this, 1000L)
        }
    }

    // 0 = 首次连接, 后续每次成功后置 1
    @Volatile
    private var state: Int = 0

    private val statusListener = object : IMCStatusListener {
        override fun connectionSucceeded() {
            runOnUiThread {
                findViewById<TextView>(R.id.tv_engine_status).text = "OPEN"
                log("[engine] connectionSucceeded (state was $state)")
                // 首次连上后, 把 state 切到 1, 下一次重连/手动 Connect 走 state=1
                if (state == 0) {
                    state = 1
                    findViewById<TextView>(R.id.tv_state).text = "1 (reconnect)"
                }
            }
        }

        override fun connectionClosed(code: Int, reason: String?) {
            runOnUiThread {
                findViewById<TextView>(R.id.tv_engine_status).text = "CLOSED code=$code"
                log("[engine] connectionClosed code=$code reason=$reason")
            }
        }

        override fun connectionLost(throwable: Throwable) {
            runOnUiThread {
                findViewById<TextView>(R.id.tv_engine_status).text = "LOST ${throwable.javaClass.simpleName}"
                log("[engine] connectionLost ${throwable.message}")
            }
        }
    }

    private val imcListener = object : V2IMCListener {
        override fun onMessage(text: String) {
            runOnUiThread { log("[recv:str] $text") }
        }
        override fun onMessage(byteArray: ByteArray) {
            runOnUiThread { log("[recv:bytes] ${byteArray.size} bytes") }
        }
    }

    private val netReportListener = { r: NetReport ->
        runOnUiThread { renderReport(r) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_qgb_ws_test)

        // 1. NetSurveillance(开启 burst:每 60s 一轮,5 次 TCP 测 RTT/丢包/抖动)
        surveillance = DefaultNetSurveillance.Builder()
            .monitor(AndroidNetStateMonitor(applicationContext))
            .prober(DefaultNetProber())
            .probeTarget(
                ProbeTarget(
                    host = PROBE_HOST,
                    port = PROBE_PORT,
                    useTls = true,
                    publicReference = ProbeTarget("www.baidu.com", 443, true)
                )
            )
            .enableBurst(intervalMs = 60_000L, attempts = 5, perAttemptTimeoutMs = 2_000)
            .build()
        surveillance.register { netReportListener(it) }
        surveillance.start()

        // 2. Engine, ProgressiveAutoConnect 注入 surveillance
        //    心跳参数参考 Mars 调高: Mars 前台 235s 起 / 后台 270s 固定. JVM 栈稳定性弱于 native,
        //    所以保守取 Mars 的约 1/4: 前台 60s 固定, 后台 90s 起自适应增长
        //    autoConnect 用 var 单独存一份,调试面板要拿它的 status() 渲染重连信息
        autoConnect = ProgressiveAutoConnect.Builder()
            .setTimeoutScheduler(AutoConnectAlarmTimeoutScheduler("Qgb-auto"))
            .surveillance(surveillance)
            .onFailover {
                // FAILOVER 钩子: 本 demo 没有备用端点, 仅打日志
                runOnUiThread { log("[failover] surveillance 建议切端点, 本 demo 没有备用 → 返回 false 让 SDK 继续") }
                false
            }
            .build()

        engine = V2JavaWebEngine.Builder()
            .setIMCLog(TimberIMCLog("QgbWs"))
            .addHeartbeatMode(
                FIXED_HEARTBEAT,
                V2FixedHeartbeat.Builder().setCurHeartbeat(60).build()
            )
            .addHeartbeatMode(
                SMART_HEARTBEAT,
                V2SmartHeartbeat.Builder()
                    .setInitialHeartbeat(90L)
                    .setMinHeartbeat(45L)
                    .setHeartbeatStep(15)
                    .setTimeoutScheduler(HeartbeatAlarmTimeoutScheduler("Qgb-heartbeat"))
                    .build()
            )
            .setAutoConnect(autoConnect)
            // KeyProvider:autoConnect 每次重连都现拿 url,这样 state=0→1 翻转能跟着每一次重试走,
            // 而不是固化在首次 engineOn 传入的 url 里。
            .setKeyProvider {
                val token = findViewById<EditText>(R.id.et_token)?.text?.toString()?.trim() ?: ""
                val acc = findViewById<EditText>(R.id.et_account)?.text?.toString()?.trim() ?: ""
                if (token.isEmpty() || acc.isEmpty()) null else buildUrl(token, acc, state)
            }
            .build()

        engine.setIMCStatusListener(statusListener)
        engine.addIMCListener(imcListener)

        // 接 App 前后台 → 心跳模式自动切换 (带 30s 防抖, 避免短暂离开误切)
        //   前台: FIXED_HEARTBEAT (固定 60s, 保活)
        //   后台: SMART_HEARTBEAT (自适应 45~90s 起, 省电)
        foregroundBinder = EngineForegroundBinder(
            engine = engine,
            foregroundMode = FIXED_HEARTBEAT,
            backgroundMode = SMART_HEARTBEAT,
            backgroundDelayMs = EngineForegroundBinder.DEFAULT_BACKGROUND_DELAY_MS  // 30s
        )
        foregroundBinder.attach()

        bindUi()
    }

    override fun onDestroy() {
        super.onDestroy()
        foregroundBinder.detach()
        try { engine.engineOff() } catch (_: Exception) {}
        surveillance.stop()
    }

    override fun onResume() {
        super.onResume()
        // 1Hz 刷新重连面板,挑显眼的兜底节奏
        mainHandler.post(reconnectTick)
    }

    override fun onPause() {
        super.onPause()
        mainHandler.removeCallbacks(reconnectTick)
    }

    private fun bindUi() {
        val etToken = findViewById<EditText>(R.id.et_token)
        val etAccount = findViewById<EditText>(R.id.et_account)
        val tvPreview = findViewById<TextView>(R.id.tv_url_preview)

        val refreshPreview = {
            val token = etToken.text.toString().trim()
            val acc = etAccount.text.toString().trim()
            tvPreview.text = buildUrl(token.ifEmpty { "<token>" }, acc.ifEmpty { "<account>" }, state)
        }
        etToken.addTextChangedListener(SimpleTextWatcher(refreshPreview))
        etAccount.addTextChangedListener(SimpleTextWatcher(refreshPreview))
        // 支持 adb shell am start ... --es token <tok> --es name <name> --es state 0 预填
        intent?.let {
            it.getStringExtra("token")?.let { v -> etToken.setText(v) }
            it.getStringExtra("name")?.let { v -> etAccount.setText(v) }
            it.getStringExtra("state")?.toIntOrNull()?.let { v ->
                state = v
                findViewById<TextView>(R.id.tv_state).text = "$v ${if (v == 0) "(first)" else "(reconnect)"}"
            }
        }
        // EditText 还空就拿默认兜底(intent extras 优先)
        if (etToken.text.isNullOrEmpty()) etToken.setText(DEFAULT_TOKEN)
        if (etAccount.text.isNullOrEmpty()) etAccount.setText(DEFAULT_NAME)
        refreshPreview()

        findViewById<Button>(R.id.bt_connect).setOnClickListener {
            // 粘贴的 JWT 里常夹换行 / NBSP / zero-width 空格 → 跑 URI 解析会爆
            // .trim() 只剥首尾,这里把内部所有空白/控制字符也一起删
            val token = etToken.text.toString().filter { it.code > 0x20 && it.code != 0x7F && !it.isWhitespace() }
            val acc = etAccount.text.toString().filter { it.code > 0x20 && it.code != 0x7F && !it.isWhitespace() }
            if (token.isEmpty()) {
                toast("token 不能为空"); return@setOnClickListener
            }
            if (acc.isEmpty()) {
                toast("ImAccount 不能为空"); return@setOnClickListener
            }
            val url = buildUrl(token, acc, state)
            log("[ui] Connect → state=$state")
            findViewById<TextView>(R.id.tv_engine_status).text = "CONNECTING..."
            Thread { engine.engineOn(url) }.start()
        }

        findViewById<Button>(R.id.bt_disconnect).setOnClickListener {
            log("[ui] Disconnect")
            Thread { engine.engineOff() }.start()
            findViewById<TextView>(R.id.tv_engine_status).text = "(disconnected)"
        }

        findViewById<Button>(R.id.bt_reset_state).setOnClickListener {
            state = 0
            findViewById<TextView>(R.id.tv_state).text = "0 (first)"
            refreshPreview()
            log("[ui] state → 0")
        }

        findViewById<Button>(R.id.bt_send).setOnClickListener {
            val text = findViewById<EditText>(R.id.et_send).text.toString()
            if (text.isEmpty()) {
                toast("发送内容不能为空"); return@setOnClickListener
            }
            findViewById<EditText>(R.id.et_send).setText("")
            // engine.send 内部走 synchronized(cacheSync),会和 WebSocket worker 抢锁;
            // 系统繁忙时可能阻塞主线程导致 ANR,与 engineOn/engineOff 一样放后台
            Thread {
                val ok = engine.send(text)
                runOnUiThread { log("[send:str] ok=$ok text=$text") }
            }.start()
        }

        findViewById<TextView>(R.id.tv_state).text = "$state ${if (state == 0) "(first)" else "(reconnect)"}"
        findViewById<TextView>(R.id.tv_engine_status).text = "device=${Build.MODEL}"

        findViewById<Button>(R.id.bt_view_log).setOnClickListener {
            startActivity(Intent(this, FullLogActivity::class.java))
        }

        findViewById<Button>(R.id.bt_stress_entry).setOnClickListener {
            // 把当前面板的 token/name 透传过去,免得再粘贴一次
            val tok = findViewById<EditText>(R.id.et_token).text.toString().trim()
            val acc = findViewById<EditText>(R.id.et_account).text.toString().trim()
            startActivity(Intent(this, StressReconnectTestActivity::class.java).apply {
                if (tok.isNotEmpty()) putExtra("token", tok)
                if (acc.isNotEmpty()) putExtra("name", acc)
            })
        }

        // 自动连(adb 启动时加 --ez autoConnect true)— 所有 listener 装完才能 performClick
        if (intent?.getBooleanExtra("autoConnect", false) == true) {
            findViewById<TextView>(R.id.tv_engine_status).text = "AUTO-CONNECTING..."
            findViewById<Button>(R.id.bt_connect).post {
                findViewById<Button>(R.id.bt_connect).performClick()
            }
        }
    }

    private fun buildUrl(token: String, account: String, state: Int): String =
        String.format(URL_TEMPLATE, token, account, state.toString())

    private fun renderReconnect() {
        val s = autoConnect.status()
        findViewById<TextView>(R.id.tv_reconnect).text = buildString {
            append("策略参数 : init=").append(s.initReconnectDelayMs).append("ms / max=")
                .append(s.maxReconnectDelayMs).append("ms").append('\n')
            append("当前 delay: ").append(s.currentReconnectDelayMs).append("ms")
                .append("  重试: ").append(s.retryCount).append(" 次").append('\n')
            append("状态     : ").append(reconnectStateCn(s)).append('\n')
            val a = s.lastAction
            if (a != null) {
                append("策略来源 : surveillance → ").append(a.name)
                    .append(" (").append(actionCn(a)).append(")")
            } else {
                append("策略来源 : 指数退避 (无 surveillance 或抢救未启动)")
            }
        }
    }

    private fun reconnectStateCn(s: ReconnectStatus): String = when {
        !s.isAutoConnectActive -> "空闲 / 未抢救"
        s.isConnecting -> "正在重连中"
        s.isWaitingNextRetry -> "等下次重连 (${s.currentReconnectDelayMs}ms 后)"
        else -> "已激活"
    }

    private fun renderReport(r: NetReport) {
        val s = r.snapshot
        findViewById<TextView>(R.id.tv_net_report).text = buildString {
            append("overall   : ").append(r.overall).append("  (").append(verdictCn(r.overall)).append(")").append('\n')
            append("recommend : ").append(r.recommend).append("  (").append(actionCn(r.recommend)).append(")").append('\n')
            append("transports: ").append(s.transports.joinToString { "${it.name}(${transportCn(it)})" }).append('\n')
            append("verdict   : ").append(s.verdict).append("  (").append(verdictCn(s.verdict)).append(")").append('\n')
            val p = r.lastProbe?.verdict
            if (p != null) {
                append("probe     : ").append(p).append("  (").append(probeVerdictCn(p)).append(")")
            } else {
                append("probe     : -")
            }
            // burst:延迟 / 丢包 / 抖动
            val b = r.lastBurstProbe
            if (b != null && b.attempts > 0) {
                append('\n').append(renderBurstLine(b))
            }
        }
    }

    /** 简洁单行版,塞进 NetReport 显示 */
    private fun renderBurstLine(b: BurstProbeReport): String = buildString {
        append("burst     : ")
        val lossPct = "%.0f".format(b.lossRate * 100)
        if (b.successes == 0) {
            append("全失败 (")
                .append(b.attempts).append("×) 丢包 ").append(lossPct).append("%")
            return@buildString
        }
        append("RTT ")
            .append(b.minMs).append("/")
            .append(b.meanMs?.toLong()).append("/")
            .append(b.maxMs).append("ms (min/mean/max)  ")
        if (b.jitterMs != null) {
            append("抖动 ").append("%.0f".format(b.jitterMs!!)).append("ms  ")
        }
        append("丢包 ").append(lossPct).append("%")
    }

    private fun probeVerdictCn(v: ProbeVerdict): String = when (v) {
        ProbeVerdict.SERVER_REACHABLE -> "服务端可达"
        ProbeVerdict.SERVER_DOWN -> "服务端不通(公网通)"
        ProbeVerdict.DNS_FAILURE -> "DNS 解析失败"
        ProbeVerdict.TLS_FAILURE -> "TLS 握手失败"
        ProbeVerdict.HTTP_DEGRADED -> "HTTP 异常"
        ProbeVerdict.NO_INTERNET -> "无网络"
        ProbeVerdict.PROBE_TIMEOUT -> "探测超时"
    }

    private fun verdictCn(v: NetVerdict): String = when (v) {
        NetVerdict.OFFLINE -> "离线"
        NetVerdict.BLOCKED -> "被策略拦截"
        NetVerdict.CAPTIVE_PORTAL -> "门户认证"
        NetVerdict.CONNECTED_NOT_VALIDATED -> "已连但未验证"
        NetVerdict.SUSPENDED -> "已挂起"
        NetVerdict.CONGESTED -> "网络拥塞"
        NetVerdict.CHECKING_CONNECTIVITY -> "检测中"
        NetVerdict.OK -> "正常"
    }

    private fun actionCn(a: ReconnectAction): String = when (a) {
        ReconnectAction.IMMEDIATE -> "立即重连"
        ReconnectAction.BACKOFF_NORMAL -> "常规退避"
        ReconnectAction.BACKOFF_LONG -> "长退避"
        ReconnectAction.FAILOVER -> "切备用端点"
        ReconnectAction.WAIT_USER -> "等待用户介入"
    }

    private fun transportCn(t: NetTransport): String = when (t) {
        NetTransport.CELLULAR -> "蜂窝"
        NetTransport.WIFI -> "Wi-Fi"
        NetTransport.BLUETOOTH -> "蓝牙"
        NetTransport.ETHERNET -> "以太网"
        NetTransport.VPN -> "VPN"
        NetTransport.WIFI_AWARE -> "Wi-Fi Aware"
        NetTransport.LOWPAN -> "LoWPAN"
    }

    private fun log(line: String) {
        LogStore.append(line)
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private class SimpleTextWatcher(private val cb: () -> Unit) : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: android.text.Editable?) { cb() }
    }
}
