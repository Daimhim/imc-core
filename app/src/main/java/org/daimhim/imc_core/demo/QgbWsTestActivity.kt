package org.daimhim.imc_core.demo

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.daimhim.imc_core.DefaultNetProber
import org.daimhim.imc_core.DnsCache
import org.daimhim.imc_core.DefaultNetSurveillance
import org.daimhim.imc_core.IEngineState
import org.daimhim.imc_core.IMCStatusListener
import org.daimhim.imc_core.BurstProbeReport
import org.daimhim.imc_core.NetProbeProfile
import org.daimhim.imc_core.NetReport
import org.daimhim.imc_core.NetSurveillance
import org.daimhim.imc_core.NetTransport
import org.daimhim.imc_core.NetVerdict
import org.daimhim.imc_core.ProbeTarget
import org.daimhim.imc_core.ProbeVerdict
import org.daimhim.imc_core.ReconnectAction
import org.daimhim.imc_core.ReconnectStatus
import org.daimhim.imc_core.ProgressiveAutoConnect
import org.daimhim.imc_core.V2FixedHeartbeat
import org.daimhim.imc_core.V2IMCListener
import org.daimhim.imc_core.V2JavaWebEngine

/**
 * WebSocket 演示
 *
 * - URL 模板: wss://<your-server>/ws?token=%s&name=%s&platform=android&state=%s
 * - token: 由调用方提供
 * - name: 业务账号 ID
 * - state: 0 = 首次连接, 1 = 重连/重试 (本 demo 在每次成功 connect 后自动切到 1)
 * - 接入 NetSurveillance, 让 ProgressiveAutoConnect 按 NetReport.recommend 智能退避
 */
class QgbWsTestActivity : AppCompatActivity() {

    companion object {
        private const val FIXED_HEARTBEAT = 0    // 前台 30s
        private const val SMART_HEARTBEAT = 1    // 自适应(本服务端不用,仅对照)
        private const val NONE_HEARTBEAT = 2     // 对照测试:空心跳,测服务端纯空闲超时
        private const val PINGPONG_HEARTBEAT = 3 // 对照测试:WS 协议层 ping/pong
        private const val BG_FIXED_HEARTBEAT = 4 // 后台 45s
        private const val REQ_OVERLAY_PERMISSION = 0x4F56
        private const val REQ_LOGIN = 0x4C47       // 登录页返回,回填 token/name

        // 心跳测试档:0=正常(前后台自动切) 1=禁用心跳 2=WS ping/pong
        private const val TEST_NORMAL = 0
        private const val TEST_DISABLED = 1
        private const val TEST_PINGPONG = 2

        // Server A (example) wss 接入,token/name/state 走 buildUrl 模板填入
        private const val URL_TEMPLATE = "wss://your-im-server.example.com/ws?token=%s&name=%s&platform=android&state=%s"

        // 目标主机用来配 NetSurveillance 探测点 (example wss → probe 走 443 TLS)
        private const val PROBE_HOST = "your-im-server.example.com"
        private const val PROBE_PORT = 443

        // 默认留空 —— 运行时粘贴 JWT/账号或走 Intent extras 注入;切勿把真实 token/账号硬编码提交。
        private const val DEFAULT_TOKEN =
            ""
        private const val DEFAULT_NAME = ""

        // 上次连接记录的 SharedPreferences
        private const val PREFS_NAME = "qgb_ws_test"
        private const val KEY_LAST_TOKEN = "last_token"
        private const val KEY_LAST_NAME = "last_name"
        private const val KEY_LAST_STATE = "last_state"
    }

    private lateinit var engine: V2JavaWebEngine
    private lateinit var surveillance: NetSurveillance
    private lateinit var autoConnect: ProgressiveAutoConnect
    private lateinit var foregroundBinder: EngineForegroundBinder
    private lateinit var overlay: FloatingOverlayController

    private val qgbHeartbeat = QGBHeartbeat()   // dataPing 心跳实现 + 统计载体(QGBHeartbeat is CustomHeartbeat)
    private val reconnectRecorder = ReconnectRecorder()
    private val noopHeartbeat = NoOpHeartbeat()
    // A12: demo 心跳走 V2FixedHeartbeat + QGBHeartbeat(dataPing,payload=JSON 含 HEART_BEAT)。
    // 这条路径让 SDK 的 HeartbeatSent / HeartbeatPongReceived / HeartbeatDegraded / HeartbeatFailed
    // 事件全部 emit,改进 3 路径才生效。旧的 PingPongHeartbeat(WS protocol ping)已删。
    // 前台 30s / 后台 45s,toleranceFactor=1.5。
    private lateinit var fixedHeartbeat: V2FixedHeartbeat
    private lateinit var bgFixedHeartbeat: V2FixedHeartbeat

    // 心跳测试档(TEST_NORMAL / TEST_DISABLED / TEST_PINGPONG)
    @Volatile private var testMode = TEST_NORMAL
    @Volatile private var binderAttached = false
    // 本次连接计时:connectionSucceeded 记起点,断开时算"连上→断"存活时长
    @Volatile private var connectedAtMs = 0L

    private val prefs by lazy { getSharedPreferences(PREFS_NAME, MODE_PRIVATE) }

    // 解析 URL 时给 EditText 回填会再次触发 TextWatcher,用这个标记防止递归解析
    @Volatile private var suppressUrlParse = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val reconnectTick = object : Runnable {
        override fun run() {
            renderReconnect()
            renderHeartbeat()
            mainHandler.postDelayed(this, 1000L)
        }
    }

    // 1Hz 把 last* 状态写入 NetWaveStore + 推心跳浮窗。
    // 这条 tick 用 Activity 的生命周期(onCreate ~ onDestroy),即使进后台 Activity onPause/onStop
    // 也还在 RESUMED→STARTED→CREATED 之间,Handler 仍然跑。所以后台模式切换才能被采到。
    private val sampleTick = object : Runnable {
        override fun run() {
            pollHeartbeatTelemetry()
            NetWaveStore.tickAppend()
            mainHandler.postDelayed(this, NetWaveStore.SAMPLE_INTERVAL_MS)
        }
    }

    // 0 = 首次连接, 后续每次成功后置 1
    @Volatile
    private var state: Int = 0

    private val statusListener = object : IMCStatusListener {
        override fun connectionSucceeded() {
            NetWaveStore.setWs(NetWaveStore.WsState.OPEN)
            reconnectRecorder.onConnected()
            qgbHeartbeat.onConnected()
            connectedAtMs = System.currentTimeMillis()
            runOnUiThread {
                findViewById<TextView>(R.id.tv_engine_status).text = "OPEN"
                log("[engine] connectionSucceeded (state was $state) ${testModeTag()}")
                overlay.updateStatus("socket\nOPEN", Color.parseColor("#7CFC00"))
                // 首次连上后, 把 state 切到 1, 下一次重连/手动 Connect 走 state=1
                if (state == 0) {
                    state = 1
                    findViewById<TextView>(R.id.tv_state).text = "1 (reconnect)"
                }
            }
        }

        override fun connectionClosed(code: Int, reason: String?) {
            NetWaveStore.setWs(NetWaveStore.WsState.CLOSED)
            reconnectRecorder.onClosed(code, reason)
            val alive = survivalTag()
            runOnUiThread {
                findViewById<TextView>(R.id.tv_engine_status).text = "CLOSED code=$code"
                log("[engine] connectionClosed code=$code reason=$reason $alive")
                overlay.updateStatus("socket\nCLOSED $code", Color.parseColor("#FFA500"))
            }
        }

        override fun connectionLost(throwable: Throwable) {
            NetWaveStore.setWs(NetWaveStore.WsState.LOST)
            val detail = "${throwable.javaClass.simpleName}: ${throwable.message ?: "-"}"
            val alive = survivalTag()
            // 心跳"上次失败"以 connectionLost 为信号(SDK 内部判失败也会走 onClose/onError → connectionLost)。
            // 严格意义这不一定 100% 是心跳超时,可能是 onError 其它路径;但调试面板能看到时刻 + 原因就够用。
            qgbHeartbeat.markFailure(detail)
            reconnectRecorder.onLost(detail)
            runOnUiThread {
                findViewById<TextView>(R.id.tv_engine_status).text = "LOST ${throwable.javaClass.simpleName}"
                log("[engine] connectionLost ${throwable.message} $alive")
                overlay.updateStatus("socket\nLOST ${throwable.javaClass.simpleName}", Color.parseColor("#FF6B6B"))
            }
        }
    }

    private val imcListener = object : V2IMCListener {
        override fun onMessage(text: String) {
            // 1) 进收件箱(列表 UI 看)
            MessageInbox.appendText(text)
            // 2) 进 LogStore(整体事件流看;长文本会自动截断在 preview)
            runOnUiThread { log("[recv:str] ${text.take(120)}${if (text.length > 120) "…" else ""}") }
        }
        override fun onMessage(byteArray: ByteArray) {
            MessageInbox.appendBinary(byteArray)
            runOnUiThread { log("[recv:bytes] ${byteArray.size} bytes") }
        }
    }

    private val netReportListener = { r: NetReport ->
        runOnUiThread { renderReport(r) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_qgb_ws_test)

        // P1: 一个共享 DnsCache —— 同时喂给 NetProber(探测路径)和 Engine(WS 建连路径),
        // 探测成功解析出的 IP 会预热,真正建连/重连时命中缓存,DNS 抖动时回退历史 IP。
        val dnsCache = DnsCache()

        // 1. NetSurveillance(开启 burst:每 60s 一轮,5 次 TCP 测 RTT/丢包/抖动)
        surveillance = DefaultNetSurveillance.Builder()
            .monitor(AndroidNetStateMonitor(applicationContext))
            .prober(DefaultNetProber(dnsCache = dnsCache))
            .probeTarget(
                ProbeTarget(
                    host = PROBE_HOST,
                    port = PROBE_PORT,
                    useTls = true,
                    publicReference = ProbeTarget("www.baidu.com", 443, true)
                )
            )
            .profile(
                NetProbeProfile.BALANCED.copy(
                    burstEnabled = true,
                    burstIntervalMs = 60_000L,
                )
            )
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

        // A12: 正式心跳 = V2FixedHeartbeat + QGBHeartbeat 当 customHeartbeat(dataPing app-layer JSON)。
        //   payload = {"cmdType":"HEART_BEAT"},服务端回 "...HEART_BEAT..." 即被 QGBHeartbeat.isHeartbeat 命中
        //   → WebSocketClientImpl 吞掉不向业务转发 (A1),并触发 updateLastPong。
        //   服务端空闲超时实测 60s,前台 30s(2× 余量) / 后台 45s(留 15s 余量 + AlarmManager 抗 Doze)。
        fixedHeartbeat = V2FixedHeartbeat.Builder()
            .setCurHeartbeat(30L)
            .setToleranceFactor(1.5)
            .setCustomHeartbeat(qgbHeartbeat)
            .build()
        bgFixedHeartbeat = V2FixedHeartbeat.Builder()
            .setCurHeartbeat(45L)
            .setToleranceFactor(1.5)
            .setTimeoutScheduler(HeartbeatAlarmTimeoutScheduler("Qgb-heartbeat"))
            .setCustomHeartbeat(qgbHeartbeat)
            .build()

        engine = V2JavaWebEngine.Builder()
            .addHeartbeatMode(FIXED_HEARTBEAT, fixedHeartbeat)
            .addHeartbeatMode(BG_FIXED_HEARTBEAT, bgFixedHeartbeat)
            .addHeartbeatMode(NONE_HEARTBEAT, noopHeartbeat)
            .setAutoConnect(autoConnect)
            .setDnsCache(dnsCache)
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
        //   前台: FIXED 30s   后台: FIXED 45s (都压在服务端 60s 空闲超时下)
        foregroundBinder = EngineForegroundBinder(
            engine = engine,
            foregroundMode = FIXED_HEARTBEAT,
            backgroundMode = BG_FIXED_HEARTBEAT,
            backgroundDelayMs = EngineForegroundBinder.DEFAULT_BACKGROUND_DELAY_MS,  // 30s
            // 联动 surveillance:前台积极探测、后台降档省电
            surveillance = surveillance,
            foregroundProfile = org.daimhim.imc_core.NetProbeProfile.BALANCED,
            backgroundProfile = org.daimhim.imc_core.NetProbeProfile.BACKGROUND,
        )
        foregroundBinder.attach()
        binderAttached = true

        overlay = FloatingOverlayController(applicationContext)

        // 启动 1Hz 状态采样,Activity 销毁时停。autoFollow 真要跨 Activity 保留可移到 Application。
        mainHandler.post(sampleTick)

        bindUi()
    }

    override fun onDestroy() {
        super.onDestroy()
        foregroundBinder.detach()
        try { engine.engineOff() } catch (_: Exception) {}
        surveillance.stop()
        overlay.hide()
        mainHandler.removeCallbacks(sampleTick)
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

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_LOGIN && resultCode == RESULT_OK) {
            // 登录页已把 token/name 写进 prefs,这里回填到输入框
            val tok = prefs.getString(KEY_LAST_TOKEN, null)
            val name = prefs.getString(KEY_LAST_NAME, null)
            if (!tok.isNullOrEmpty()) findViewById<EditText>(R.id.et_token).setText(tok)
            if (!name.isNullOrEmpty()) findViewById<EditText>(R.id.et_account).setText(name)
            state = 0
            findViewById<TextView>(R.id.tv_state).text = "0 (first)"
            log("[login] 已回填登录获取的 token/name,state 复位 0")
            toast("已回填登录参数")
            return
        }
        if (requestCode == REQ_OVERLAY_PERMISSION) {
            val granted =
                Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)
            if (granted) {
                overlay.show()
                findViewById<Button>(R.id.bt_toggle_overlay).text = "悬浮窗(开)"
                log("[ui] 悬浮窗权限已授予, 自动打开")
            } else {
                toast("悬浮窗权限未授予")
            }
        }
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
        // 任意 EditText 变化:先尝试当成完整 URL 解析,失败再走预览刷新
        val onAnyEdit = {
            if (suppressUrlParse) {
                refreshPreview()
            } else if (!tryParseQgbUrl(etToken.text.toString())
                && !tryParseQgbUrl(etAccount.text.toString())
            ) {
                refreshPreview()
            }
        }
        etToken.addTextChangedListener(SimpleTextWatcher(onAnyEdit))
        etAccount.addTextChangedListener(SimpleTextWatcher(onAnyEdit))

        // 优先级:Intent extras → 上次缓存 → DEFAULT_*
        intent?.let {
            it.getStringExtra("token")?.let { v -> etToken.setText(v) }
            it.getStringExtra("name")?.let { v -> etAccount.setText(v) }
            it.getStringExtra("state")?.toIntOrNull()?.let { v ->
                state = v
                findViewById<TextView>(R.id.tv_state).text = "$v ${if (v == 0) "(first)" else "(reconnect)"}"
            }
        }
        // 恢复缓存(intent 没给的话才用)
        if (etToken.text.isNullOrEmpty()) {
            prefs.getString(KEY_LAST_TOKEN, null)?.takeIf { it.isNotEmpty() }
                ?.let { etToken.setText(it); log("[cache] 恢复上次 token") }
        }
        if (etAccount.text.isNullOrEmpty()) {
            prefs.getString(KEY_LAST_NAME, null)?.takeIf { it.isNotEmpty() }
                ?.let { etAccount.setText(it); log("[cache] 恢复上次 name=$it") }
        }
        if (intent?.getStringExtra("state") == null && prefs.contains(KEY_LAST_STATE)) {
            val v = prefs.getInt(KEY_LAST_STATE, 0)
            state = v
            findViewById<TextView>(R.id.tv_state).text = "$v ${if (v == 0) "(first)" else "(reconnect)"}"
            log("[cache] 恢复上次 state=$v")
        }
        // 仍为空再走硬编码默认
        if (etToken.text.isNullOrEmpty()) etToken.setText(DEFAULT_TOKEN)
        if (etAccount.text.isNullOrEmpty()) etAccount.setText(DEFAULT_NAME)
        refreshPreview()

        findViewById<Button>(R.id.bt_connect).setOnClickListener {
            // 已在 OPEN / CONNECTING 时再点 CONNECT,V2JavaWebEngine.dispatchEngineOn 同 key 是 no-op,
            // 不会触发 connectionSucceeded 回调把 UI 翻回 "OPEN" —— 老代码直接乐观改成 "CONNECTING..."
            // 然后就永远卡在那。这里先查引擎状态挡掉,防 UI 假死。
            val s = engine.engineState()
            if (s == IEngineState.ENGINE_OPEN || s == IEngineState.ENGINE_CONNECTING) {
                toast(if (s == IEngineState.ENGINE_OPEN) "已连接,无需重复点击" else "正在连接中…")
                return@setOnClickListener
            }
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
            NetWaveStore.setWs(NetWaveStore.WsState.CONNECTING)
            // 缓存这次的 token + name + state,下次启动自动恢复
            prefs.edit()
                .putString(KEY_LAST_TOKEN, token)
                .putString(KEY_LAST_NAME, acc)
                .putInt(KEY_LAST_STATE, state)
                .apply()
            Thread { engine.engineOn(url) }.start()
        }

        findViewById<Button>(R.id.bt_disconnect).setOnClickListener {
            log("[ui] Disconnect")
            NetWaveStore.setWs(NetWaveStore.WsState.CLOSED)
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

        findViewById<Button>(R.id.bt_wave_chart).setOnClickListener {
            startActivity(Intent(this, NetWaveActivity::class.java))
        }

        findViewById<Button>(R.id.bt_login).setOnClickListener {
            startActivityForResult(Intent(this, QgbLoginActivity::class.java), REQ_LOGIN)
        }

        updateTestModeButton()
        findViewById<Button>(R.id.bt_hb_test).setOnClickListener {
            // 循环切档:正常(WS ping/pong) → 禁用心跳(测服务端空闲超时) → 正常
            val next = (testMode + 1) % 2
            applyTestMode(next)
        }

        findViewById<Button>(R.id.bt_toggle_overlay).setOnClickListener { v ->
            val btn = v as Button
            if (overlay.isShowing()) {
                overlay.hide()
                btn.text = "悬浮窗(关)"
                log("[ui] 悬浮窗 关")
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                    && !Settings.canDrawOverlays(this)
                ) {
                    toast("请先授予悬浮窗权限")
                    startActivityForResult(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")
                        ),
                        REQ_OVERLAY_PERMISSION
                    )
                    return@setOnClickListener
                }
                overlay.show()
                btn.text = "悬浮窗(开)"
                log("[ui] 悬浮窗 开")
            }
        }

        findViewById<Button>(R.id.bt_inbox).setOnClickListener {
            startActivity(Intent(this, MessageInboxActivity::class.java))
        }

        findViewById<Button>(R.id.bt_scenario).setOnClickListener {
            ScenarioDialog.show(
                activity = this,
                engine = engine,
                surveillance = surveillance,
                heartbeatModes = linkedMapOf(
                    "前台 (FIXED_HEARTBEAT)" to FIXED_HEARTBEAT,
                    "后台 (BG_FIXED_HEARTBEAT)" to BG_FIXED_HEARTBEAT,
                    "禁用心跳 (NONE_HEARTBEAT)" to NONE_HEARTBEAT,
                ),
                onLog = { log(it) },
            )
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

    /**
     * 把粘贴进 EditText 的完整 URL 拆成 token + name 自动赋值。
     * 例: wss://your-im-server.example.com/ws?name=XXX&token=YYY&platform=web → 拆 token / name 到两个框。
     * 返回 true 表示成功解析过(调用方应跳过 refreshPreview,因 setText 会再次触发 watcher)。
     */
    private fun tryParseQgbUrl(input: String): Boolean {
        if (suppressUrlParse) return false
        val s = input.trim()
        if (s.length < 16 || !s.contains("?")) return false
        if (!s.contains("token=") && !s.contains("name=")) return false
        val lower = s.lowercase()
        if (!(lower.startsWith("ws://") || lower.startsWith("wss://")
                    || lower.startsWith("http://") || lower.startsWith("https://"))
        ) return false
        return try {
            val uri = Uri.parse(s)
            val token = uri.getQueryParameter("token")
            val name = uri.getQueryParameter("name")
            if (token.isNullOrEmpty() && name.isNullOrEmpty()) return false
            suppressUrlParse = true
            try {
                if (!token.isNullOrEmpty()) {
                    findViewById<EditText>(R.id.et_token).setText(token)
                }
                if (!name.isNullOrEmpty()) {
                    findViewById<EditText>(R.id.et_account).setText(name)
                }
            } finally {
                suppressUrlParse = false
            }
            val parts = mutableListOf<String>()
            if (!token.isNullOrEmpty()) parts.add("token(${token.length}字符)")
            if (!name.isNullOrEmpty()) parts.add("name=$name")
            toast("URL 已解析 → ${parts.joinToString(" + ")}")
            log("[paste] URL 解析成功 → ${parts.joinToString(", ")}")
            true
        } catch (e: Exception) {
            log("[paste] URL 解析失败: ${e.message}")
            false
        }
    }

    private fun renderReconnect() {
        val s = autoConnect.status()
        val nowMs = System.currentTimeMillis()
        findViewById<TextView>(R.id.tv_reconnect).text = buildString {
            append("策略参数 : init=").append(s.initReconnectDelayMs).append("ms / max=")
                .append(s.maxReconnectDelayMs).append("ms").append('\n')
            append("当前 delay: ").append(s.currentReconnectDelayMs).append("ms")
                .append("  本轮重试: ").append(s.retryCount).append(" 次").append('\n')
            append("状态     : ").append(reconnectStateCn(s)).append('\n')
            val a = s.lastAction
            if (a != null) {
                append("策略来源 : surveillance → ").append(a.name)
                    .append(" (").append(actionCn(a)).append(")").append('\n')
            } else {
                append("策略来源 : 指数退避 (无 surveillance 或抢救未启动)").append('\n')
            }

            // 累计统计(跨重连周期,连上不清零)
            append("累计     : 断开 ").append(reconnectRecorder.disconnectCount.get())
                .append(" · 重连成功 ").append(reconnectRecorder.reconnectSuccessCount.get())
                .append(" · 失败 ").append(reconnectRecorder.failureCount.get())

            // 最近连接事件记录(新→旧,取 6 条)
            val recent = reconnectRecorder.recent(6)
            if (recent.isNotEmpty()) {
                append('\n').append("记录     :")
                for (e in recent) {
                    val mark = when (e.type) {
                        ReconnectRecorder.EventType.CONNECTED -> "✓"
                        ReconnectRecorder.EventType.CLOSED -> "✕"
                        ReconnectRecorder.EventType.LOST -> "✕"
                    }
                    append("\n  ").append(hbTsFmt.format(Date(e.timestampMs)))
                        .append(" ").append(mark).append(" ").append(e.detail)
                        .append("  (").append(agoOrDash(e.timestampMs, nowMs)).append(")")
                }
            }
        }
    }

    private val hbTsFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    /** 心跳遥测当前快照,sampleTick / reconnectTick 共享。 */
    private data class HeartbeatSnapshot(
        val cur: Int,
        val modeLabel: String?,   // null = 未 attach (面板显示 "—")
        val intervalSec: Int,     // <=0 时表示无法读取
        val sent: Int, val recv: Int, val fail: Int, val pending: Int,
        val lastSentMs: Long, val lastRecvMs: Long,
        val lastFailureMs: Long, val lastFailureReason: String,
    )

    private fun snapshotHeartbeat(): HeartbeatSnapshot {
        val cur = foregroundBinder.currentMode
        val intervalSec = when (cur) {
            FIXED_HEARTBEAT -> fixedHeartbeat.getConnectionLostTimeout()
            BG_FIXED_HEARTBEAT -> bgFixedHeartbeat.getConnectionLostTimeout()
            else -> -1
        }
        val sent = qgbHeartbeat.sentCount.get()
        val recv = qgbHeartbeat.recvCount.get()
        return HeartbeatSnapshot(
            cur = cur,
            modeLabel = when (cur) {
                FIXED_HEARTBEAT -> "PINGPONG前台"
                BG_FIXED_HEARTBEAT -> "PINGPONG后台"
                else -> null
            },
            intervalSec = intervalSec,
            sent = sent, recv = recv,
            fail = qgbHeartbeat.failCount.get(),
            pending = (sent - recv).coerceAtLeast(0),
            lastSentMs = qgbHeartbeat.lastSentMs.get(),
            lastRecvMs = qgbHeartbeat.lastRecvMs.get(),
            lastFailureMs = qgbHeartbeat.lastFailureMs.get(),
            lastFailureReason = qgbHeartbeat.lastFailureReason,
        )
    }

    /**
     * 后台也跑的心跳采集:把 ForegroundBinder.currentMode 同步给 NetWaveStore + 浮窗。
     * 不碰 Activity 的 TextView,因为后台时 findViewById 仍然能拿到但视觉上没意义,
     * 留给 onResume 期间的 renderHeartbeat 做最终渲染。
     */
    private fun pollHeartbeatTelemetry() {
        val s = snapshotHeartbeat()
        val nowMs = System.currentTimeMillis()

        // 1. 灌入 NetWaveStore (波浪图 HB 泳道 + 状态栏心跳行 用这里的快照)
        NetWaveStore.setHeartbeat(
            mode = s.modeLabel,
            intervalSec = if (s.intervalSec > 0) s.intervalSec else 0,
            sent = s.sent, recv = s.recv, fail = s.fail,
            lastSentMs = s.lastSentMs,
            lastFailureMs = s.lastFailureMs,
        )

        // 2. 心跳浮窗 (即便 Activity 在后台 WindowManager 仍能更新)
        val modeLabelDisplay = s.modeLabel ?: "—"
        overlay.updateHeartbeat(buildString {
            append("心跳 ").append(modeLabelDisplay)
            if (s.intervalSec > 0) append(" ").append(s.intervalSec).append("s")
            append('\n')
            append("↑").append(s.sent).append(" ↓").append(s.recv)
            if (s.pending > 0) append(" 未").append(s.pending)
            append(" 失").append(s.fail).append('\n')
            if (s.lastSentMs > 0) {
                append("发 ").append(agoOrDash(s.lastSentMs, nowMs))
            } else {
                append("(等首次心跳)")
            }
        })
    }

    // ── 心跳对照测试 ─────────────────────────────────────────────
    /** 切测试档。引擎调用走后台线程(onChangeMode 内有 send,可能抢 cacheSync 锁) */
    private fun applyTestMode(mode: Int) {
        testMode = mode
        when (mode) {
            TEST_DISABLED -> {
                if (binderAttached) { foregroundBinder.detach(); binderAttached = false }
                Thread { engine.onChangeMode(NONE_HEARTBEAT) }.start()
                log("[test] 心跳档=禁用心跳 → 测服务端纯空闲超时(连上后不发任何帧)")
            }
            else -> {
                // 正常:重新挂前后台 binder(attach 会立即切回前台 WS ping/pong 30s)
                if (!binderAttached) { foregroundBinder.attach(); binderAttached = true }
                log("[test] 心跳档=正常(前台/后台 WS ping/pong 30s)")
            }
        }
        updateTestModeButton()
    }

    private fun updateTestModeButton() {
        findViewById<Button>(R.id.bt_hb_test).text = "心跳测试: " + when (testMode) {
            TEST_DISABLED -> "禁用(测空闲超时)"
            else -> "正常(WS ping/pong)"
        }
    }

    private fun testModeTag(): String = when (testMode) {
        TEST_DISABLED -> "禁用心跳"
        else -> "WS ping/pong"
    }

    /** 连上→断的存活时长;用完清零,避免下次误算 */
    private fun survivalTag(): String {
        val c = connectedAtMs
        if (c <= 0) return ""
        val sec = ((System.currentTimeMillis() - c) / 1000).coerceAtLeast(0)
        connectedAtMs = 0
        return "★本次连接存活 ${sec}s (${testModeTag()})"
    }

    private fun renderHeartbeat() {
        val s = snapshotHeartbeat()
        val nowMs = System.currentTimeMillis()
        val isFixedActive = s.cur == FIXED_HEARTBEAT || s.cur == BG_FIXED_HEARTBEAT
        val isSmartActive = s.cur == SMART_HEARTBEAT
        val activeIntervalSec = s.intervalSec

        findViewById<TextView>(R.id.tv_heartbeat).text = buildString {
            // 1. 模式 & 间隔(服务端空闲超时实测 60s,FIXED 前台30s/后台45s 都在其下)
            append("模式 : ").append(s.modeLabel ?: "—")
            if (s.cur < 0) append("  (未 attach)")
            append('\n')

            if (isFixedActive && activeIntervalSec > 0) {
                append("间隔 : ").append(activeIntervalSec).append("s · 容差 1.5x · 真实超时 ")
                    .append((activeIntervalSec * 1.5).toInt()).append("s · 服务端踢 60s\n")
            } else if (isSmartActive) {
                append("间隔 : ").append(activeIntervalSec).append("s · 自适应(不建议,会探到>60s)\n")
            } else {
                append("间隔 : -\n")
            }

            // 2. 累计计数
            append("累计 : 发送 ").append(s.sent)
                .append("  收到 ").append(s.recv)
                .append("  未应答 ").append(s.pending)
                .append("  失败 ").append(s.fail).append('\n')

            // 3. 最近发送 / 收到
            append("最近 : 发送 ").append(agoOrDash(s.lastSentMs, nowMs))
                .append("  收到 ").append(agoOrDash(s.lastRecvMs, nowMs)).append('\n')

            // 4. 上次失败
            if (s.lastFailureMs == 0L) {
                append("上次失败 : -")
            } else {
                append("上次失败 : ").append(hbTsFmt.format(Date(s.lastFailureMs)))
                    .append(" (").append(agoOrDash(s.lastFailureMs, nowMs)).append(")\n          ")
                    .append(s.lastFailureReason)
            }
        }
    }

    private fun agoOrDash(thenMs: Long, nowMs: Long): String {
        if (thenMs <= 0) return "-"
        val sec = ((nowMs - thenMs) / 1000).coerceAtLeast(0)
        return when {
            sec < 60 -> "${sec}s 前"
            sec < 3600 -> "${sec / 60}m${sec % 60}s 前"
            else -> "${sec / 3600}h${(sec % 3600) / 60}m 前"
        }
    }

    private fun reconnectStateCn(s: ReconnectStatus): String = when {
        !s.isAutoConnectActive -> "空闲 / 未抢救"
        s.isConnecting -> "正在重连中"
        s.isWaitingNextRetry -> "等下次重连 (${s.currentReconnectDelayMs}ms 后)"
        else -> "已激活"
    }

    // 网络状态变更检测,避免每个 NetReport 都刷日志;只在 overall / probe 变化时记一行
    private var lastLoggedOverall: NetVerdict? = null
    private var lastLoggedProbe: ProbeVerdict? = null
    private var lastLoggedBurst: BurstProbeReport? = null

    private fun renderReport(r: NetReport) {
        // 网络状态埋点(调查关键):overall 或 probe 判定变化时落日志
        val curProbe = r.lastProbe?.verdict
        if (r.overall != lastLoggedOverall || curProbe != lastLoggedProbe) {
            val transports = r.snapshot.transports.joinToString("/") { transportCn(it) }.ifEmpty { "—" }
            log("[net] ${verdictCn(r.overall)} · $transports" +
                (curProbe?.let { " · 探测=${probeVerdictCn(it)}" } ?: "") +
                " · 建议=${actionCn(r.recommend)}")
            lastLoggedOverall = r.overall
            lastLoggedProbe = curProbe
        }
        // burst 结果(延迟/丢包/抖动)每轮记一行 —— lastBurstProbe 在两轮间不变,引用变了才是新一轮
        r.lastBurstProbe?.let { b ->
            if (b.attempts > 0 && b !== lastLoggedBurst) {
                lastLoggedBurst = b
                val lossPct = "%.0f".format(b.lossRate * 100)
                if (b.successes == 0) {
                    log("[net] burst 全失败 ${b.attempts}× · 丢包 $lossPct%")
                } else {
                    log("[net] burst RTT ${b.minMs}/${b.meanMs?.toLong()}/${b.maxMs}ms" +
                        (b.jitterMs?.let { " · 抖动 %.0fms".format(it) } ?: "") +
                        " · 丢包 $lossPct%")
                }
            }
        }

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

        overlay.updateMetrics(buildOverlayMetrics(r))

        // 灌入 NetWaveStore,波浪图回看的 NET / RTT / loss / jitter 全部来自这里
        val b = r.lastBurstProbe
        NetWaveStore.setNet(
            verdict = r.overall,
            rttMs = b?.meanMs?.toLong(),
            lossRate = b?.lossRate?.toFloat(),
            jitterMs = b?.jitterMs?.toFloat(),
        )
    }

    /**
     * 浮窗内容 100% 取自 imc-core 的 NetSurveillance:
     *   L1 NetStateMonitor → transports / overall verdict
     *   L2 NetProber       → lastProbe.verdict (DNS/TCP/TLS/HTTP 分阶段判定)
     *   burst              → 延迟 / 抖动 / 丢包
     */
    private fun buildOverlayMetrics(r: NetReport): String = buildString {
        val transports = r.snapshot.transports
            .joinToString("/") { transportCn(it) }
            .ifEmpty { "—" }
        append(verdictCn(r.overall)).append(" · ").append(transports).append('\n')

        val p = r.lastProbe?.verdict
        if (p != null) {
            append(probeVerdictCn(p)).append('\n')
        } else {
            append("(等待探测…)").append('\n')
        }

        val b = r.lastBurstProbe
        if (b == null || b.attempts == 0) {
            append("(等待 burst…)")
            return@buildString
        }
        val lossPct = "%.0f".format(b.lossRate * 100)
        if (b.successes == 0) {
            append("丢包 ").append(lossPct).append("%  全失败 (")
                .append(b.attempts).append("×)")
            return@buildString
        }
        append("延迟 ").append(b.minMs).append("/")
            .append(b.meanMs?.toLong()).append("/")
            .append(b.maxMs).append("ms").append('\n')
        if (b.jitterMs != null) {
            append("抖动 ").append("%.0f".format(b.jitterMs!!)).append("ms · ")
        }
        append("丢包 ").append(lossPct).append("%")
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
