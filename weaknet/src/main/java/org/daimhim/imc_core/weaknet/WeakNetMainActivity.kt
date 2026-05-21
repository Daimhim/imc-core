package org.daimhim.imc_core.weaknet

import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale

/**
 * QNET 风格弱网模拟器 UI(Phase 3)
 *
 * 工作流:
 *  1. 点"目标 app"按钮,弹搜索对话框选 app(留空 = 全局)
 *  2. 点"启动 VPN" → 系统授权 → 起 [WeakNetVpnService]
 *  3. 调 chaos 参数 → 点"应用注入参数",立即热更新 service 内的 ChaosConfig
 *  4. 目标 app 的字节流被注入延迟 / 抖动 / 丢包 / 限速 / 阈值断连
 */
class WeakNetMainActivity : AppCompatActivity() {

    companion object {
        private val BW_STEPS = longArrayOf(
            0,
            1_024, 2_048, 4_096, 8_192,
            16_384, 32_768, 65_536, 131_072,
            262_144, 524_288, 1_048_576
        )
        private const val VISIBLE_LOG_COUNT = 200
        private const val REQ_OVERLAY = 1001
    }

    private lateinit var presetStore: ChaosConfigStore
    private lateinit var presetAdapter: ArrayAdapter<String>
    private lateinit var lastConfig: LastConfigStore
    private var currentPresetName: String? = null

    /** null = 全局 */
    private var targetPackage: String? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 节流刷新:多条日志高速到来时,合并到一帧刷一次 */
    private val refreshRunnable = Runnable {
        refreshLogList()
        refreshVpnState()
        refreshPending = false
    }
    @Volatile private var refreshPending = false

    private val vpnListener: (String) -> Unit = { msg -> LogStore.append(msg) }
    private val logListener: () -> Unit = {
        if (!refreshPending) {
            refreshPending = true
            mainHandler.postDelayed(refreshRunnable, 200)
        }
    }

    private val vpnPrepareLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                doStartVpn()
            } else {
                toast("用户拒绝 VPN 授权")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_weak_net_main)
        presetStore = ChaosConfigStore(this)
        lastConfig = LastConfigStore(this)
        bindChaosUi()
        bindAppPicker()
        bindVpnControls()
        bindChaosApply()
        bindFloatToggle()
        bindLogView()
        bindPresetUi()
        refreshPresetSpinner()
        WeakNetVpnService.addListener(vpnListener)
        LogStore.addListener(logListener)
        refreshLogList()
        refreshVpnState()
        handleAutomationExtras(intent)
    }

    /**
     * 自动化通道:
     *   --es targetPackage <pkg>  — 预选目标 app
     *   --es preset <name>        — 加载指定预设到 UI 并应用
     *   --ez autoStart true       — 装完之后自动点"启动 VPN"
     *
     * 用于 adb 驱动的批量测试,避免每个预设都要手点。
     */
    private fun handleAutomationExtras(intent: Intent?) {
        intent ?: return
        intent.getStringExtra("targetPackage")?.let { pkg ->
            targetPackage = pkg
            updatePickerLabel()
            LogStore.append("[ui] 自动化指定目标 app = $pkg")
        }
        intent.getStringExtra("preset")?.let { name ->
            val cfg = presetStore.load(name)
            if (cfg != null) {
                loadConfigIntoUi(cfg)
                findViewById<EditText>(R.id.et_preset_name).setText(name)
                currentPresetName = name
                LogStore.append("[ui] 自动化加载预设: $name → $cfg")
            } else {
                LogStore.append("[ui] 自动化预设不存在: $name")
            }
        }
        if (intent.getBooleanExtra("autoStart", false)) {
            findViewById<Button>(R.id.bt_vpn_start).post {
                LogStore.append("[ui] 自动化触发 VPN 启动")
                findViewById<Button>(R.id.bt_vpn_start).performClick()
            }
        }
    }

    override fun onDestroy() {
        WeakNetVpnService.removeListener(vpnListener)
        LogStore.removeListener(logListener)
        super.onDestroy()
    }

    // ── App picker ─────────────────────────────────────────────────────

    private fun bindAppPicker() {
        findViewById<Button>(R.id.bt_pick_app).setOnClickListener {
            AppPickerDialog(this, initialPackage = targetPackage) { picked ->
                targetPackage = picked
                updatePickerLabel()
            }.show()
        }
        updatePickerLabel()
    }

    private fun updatePickerLabel() {
        val btn = findViewById<Button>(R.id.bt_pick_app)
        val pkg = targetPackage
        if (pkg == null) {
            btn.text = "全局(不限制 app)"
        } else {
            val label = try {
                val pm = packageManager
                val info = pm.getApplicationInfo(pkg, 0)
                pm.getApplicationLabel(info).toString()
            } catch (_: PackageManager.NameNotFoundException) { pkg }
            btn.text = "$label  ·  $pkg"
        }
    }

    // ── VPN 控制 ───────────────────────────────────────────────────────

    private fun bindVpnControls() {
        findViewById<Button>(R.id.bt_vpn_start).setOnClickListener { onClickStartVpn() }
        findViewById<Button>(R.id.bt_vpn_stop).setOnClickListener { onClickStopVpn() }
    }

    private fun onClickStartVpn() {
        if (WeakNetVpnService.isRunning()) { toast("VPN 已在运行"); return }
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            LogStore.append("[ui] 请求 VPN 授权")
            vpnPrepareLauncher.launch(prepareIntent)
        } else {
            doStartVpn()
        }
    }

    private fun doStartVpn() {
        val allowed = targetPackage?.let { arrayOf(it) } ?: emptyArray()
        val cfg = buildConfigFromUi()
        // 记下来,悬浮窗 / 自动化也能复用
        lastConfig.save(targetPackage, currentPresetName, cfg)
        val cfgJson = with(ChaosConfigStore.Companion) { cfg.toJson() }.toString()
        val intent = Intent(this, WeakNetVpnService::class.java).apply {
            putExtra(WeakNetVpnService.EXTRA_ALLOWED_APPS, allowed)
            putExtra(WeakNetVpnService.EXTRA_CHAOS_CONFIG, cfgJson)
        }
        startService(intent)
        LogStore.append("[ui] 启动 VPN,目标=${targetPackage ?: "全局"}")
        mainHandler.postDelayed({ refreshVpnState() }, 300)
    }

    private fun onClickStopVpn() {
        if (!WeakNetVpnService.isRunning()) { toast("VPN 未启动"); return }
        val intent = Intent(this, WeakNetVpnService::class.java).apply {
            action = WeakNetVpnService.ACTION_STOP
        }
        startService(intent)
        LogStore.append("[ui] 停止 VPN")
        mainHandler.postDelayed({ refreshVpnState() }, 300)
    }

    private fun refreshVpnState() {
        val running = WeakNetVpnService.isRunning()
        val (pkts, bytes, flowStats) = WeakNetVpnService.stats()
        findViewById<TextView>(R.id.tv_vpn_state).text =
            if (running) "VPN: 运行中  包=$pkts  字节=$bytes  $flowStats"
            else "VPN: 未启动"
    }

    // ── Chaos UI ───────────────────────────────────────────────────────

    private fun bindChaosUi() {
        val tvL = findViewById<TextView>(R.id.tv_latency_label)
        findViewById<SeekBar>(R.id.sb_latency).setOnSeekBarChangeListener(simpleProgress {
            tvL.text = "基础延迟: $it ms"
        })

        val tvJ = findViewById<TextView>(R.id.tv_jitter_label)
        findViewById<SeekBar>(R.id.sb_jitter).setOnSeekBarChangeListener(simpleProgress {
            tvJ.text = "抖动: $it ms"
        })

        val tvD = findViewById<TextView>(R.id.tv_drop_label)
        findViewById<SeekBar>(R.id.sb_drop).setOnSeekBarChangeListener(simpleProgress {
            tvD.text = "丢包率: $it %"
        })

        val tvBw = findViewById<TextView>(R.id.tv_bw_label)
        findViewById<SeekBar>(R.id.sb_bw).apply {
            max = BW_STEPS.size - 1
            setOnSeekBarChangeListener(simpleProgress { idx ->
                val v = BW_STEPS[idx]
                tvBw.text = if (v == 0L) "带宽限速: 不限"
                            else "带宽限速: $v B/s (${humanBw(v)})"
            })
        }
    }

    private fun bindChaosApply() {
        findViewById<Button>(R.id.bt_apply_chaos).setOnClickListener {
            val cfg = buildConfigFromUi()
            // 任何 apply 都更新 last config,悬浮窗读得到最新参数
            lastConfig.save(targetPackage, currentPresetName, cfg)
            if (WeakNetVpnService.isRunning()) {
                WeakNetVpnService.applyChaos(this, cfg)
                toast("已应用注入参数")
            } else {
                LogStore.append("[ui] VPN 未启动,参数已记下,启动后生效")
                toast("VPN 未启动 — 启动时会用当前参数")
            }
        }
    }

    // ── 悬浮窗 ─────────────────────────────────────────────────────────

    private fun bindFloatToggle() {
        val btn = findViewById<Button>(R.id.bt_toggle_float)
        btn.text = if (FloatingStatusService.isVisible()) "关闭悬浮窗" else "开启悬浮窗"
        btn.setOnClickListener {
            if (FloatingStatusService.isVisible()) {
                FloatingStatusService.hide(this)
                btn.text = "开启悬浮窗"
                return@setOnClickListener
            }
            // 申请 overlay 权限(Android 6+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"),
                )
                startActivityForResult(intent, REQ_OVERLAY)
                toast("请授予悬浮窗权限,然后再点一次")
                return@setOnClickListener
            }
            // 启动前先把当前 UI 的参数同步过去
            lastConfig.save(targetPackage, currentPresetName, buildConfigFromUi())
            FloatingStatusService.show(this)
            btn.text = "关闭悬浮窗"
        }
    }

    private fun buildConfigFromUi(): ChaosConfig {
        val bwIdx = findViewById<SeekBar>(R.id.sb_bw).progress
        return ChaosConfig(
            baseLatencyMs = findViewById<SeekBar>(R.id.sb_latency).progress.toLong(),
            jitterMs = findViewById<SeekBar>(R.id.sb_jitter).progress.toLong(),
            dropChunkPercent = findViewById<SeekBar>(R.id.sb_drop).progress,
            maxBytesPerSecond = BW_STEPS[bwIdx],
            disconnectAfterBytes = findViewById<EditText>(R.id.et_disconnect_bytes).text
                .toString().toLongOrNull() ?: 0L,
            disconnectAfterMs = findViewById<EditText>(R.id.et_disconnect_ms).text
                .toString().toLongOrNull() ?: 0L,
            rejectNewConnections = findViewById<CheckBox>(R.id.cb_reject_new).isChecked,
        )
    }

    private fun loadConfigIntoUi(cfg: ChaosConfig) {
        findViewById<SeekBar>(R.id.sb_latency).progress = cfg.baseLatencyMs.toInt()
        findViewById<SeekBar>(R.id.sb_jitter).progress = cfg.jitterMs.toInt()
        findViewById<SeekBar>(R.id.sb_drop).progress = cfg.dropChunkPercent
        val bwIdx = BW_STEPS.indexOf(cfg.maxBytesPerSecond).let { if (it < 0) 0 else it }
        findViewById<SeekBar>(R.id.sb_bw).progress = bwIdx
        findViewById<EditText>(R.id.et_disconnect_bytes).setText(
            if (cfg.disconnectAfterBytes > 0) cfg.disconnectAfterBytes.toString() else ""
        )
        findViewById<EditText>(R.id.et_disconnect_ms).setText(
            if (cfg.disconnectAfterMs > 0) cfg.disconnectAfterMs.toString() else ""
        )
        findViewById<CheckBox>(R.id.cb_reject_new).isChecked = cfg.rejectNewConnections
    }

    // ── 日志列表(随上面布局一起滚)──────────────────────────────────

    private fun bindLogView() {
        findViewById<Button>(R.id.bt_log_fullscreen).setOnClickListener {
            startActivity(Intent(this, FullLogActivity::class.java))
        }
    }

    /** 主页只显示最近 [VISIBLE_LOG_COUNT] 条;完整历史在 FullLogActivity */
    private fun refreshLogList() {
        val container = findViewById<LinearLayout>(R.id.ll_log_container)
        val snap = LogStore.snapshot()
        val sub = if (snap.size > VISIBLE_LOG_COUNT) snap.subList(0, VISIBLE_LOG_COUNT) else snap
        container.removeAllViews()
        val inflater = LayoutInflater.from(this)
        for (line in sub) {
            val tv = inflater.inflate(R.layout.item_log, container, false) as TextView
            tv.text = line
            container.addView(tv)
        }
    }

    // ── 配置预设 ───────────────────────────────────────────────────────

    private fun bindPresetUi() {
        presetAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, mutableListOf())
        presetAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        findViewById<Spinner>(R.id.sp_presets).adapter = presetAdapter

        findViewById<Button>(R.id.bt_preset_save).setOnClickListener { savePreset() }
        findViewById<Button>(R.id.bt_preset_load).setOnClickListener { loadSelectedPreset() }
        findViewById<Button>(R.id.bt_preset_delete).setOnClickListener { deleteSelectedPreset() }
    }

    private fun refreshPresetSpinner(select: String? = null) {
        val names = presetStore.listNames()
        presetAdapter.clear()
        if (names.isEmpty()) {
            presetAdapter.add("(无)")
        } else {
            presetAdapter.addAll(names)
        }
        presetAdapter.notifyDataSetChanged()
        if (select != null) {
            val idx = names.indexOf(select)
            if (idx >= 0) findViewById<Spinner>(R.id.sp_presets).setSelection(idx)
        }
    }

    private fun selectedPresetName(): String? {
        val names = presetStore.listNames()
        if (names.isEmpty()) return null
        val pos = findViewById<Spinner>(R.id.sp_presets).selectedItemPosition
        return names.getOrNull(pos)
    }

    private fun savePreset() {
        val name = findViewById<EditText>(R.id.et_preset_name).text.toString().trim()
        if (name.isEmpty()) { toast("先填预设名称"); return }
        val cfg = buildConfigFromUi()
        val existing = presetStore.listNames().contains(name)
        val doSave = {
            presetStore.save(name, cfg)
            refreshPresetSpinner(select = name)
            LogStore.append("[preset] 保存: $name")
            toast("已保存: $name")
        }
        if (existing) {
            AlertDialog.Builder(this)
                .setTitle("覆盖预设")
                .setMessage("已存在同名预设「$name」,是否覆盖?")
                .setPositiveButton("覆盖") { _, _ -> doSave() }
                .setNegativeButton("取消", null)
                .show()
        } else {
            doSave()
        }
    }

    private fun loadSelectedPreset() {
        val name = selectedPresetName() ?: run { toast("没有可加载的预设"); return }
        val cfg = presetStore.load(name) ?: run { toast("加载失败"); return }
        loadConfigIntoUi(cfg)
        findViewById<EditText>(R.id.et_preset_name).setText(name)
        currentPresetName = name
        LogStore.append("[preset] 加载: $name → $cfg")
        toast("已加载: $name")
    }

    private fun deleteSelectedPreset() {
        val name = selectedPresetName() ?: run { toast("没有可删除的预设"); return }
        AlertDialog.Builder(this)
            .setTitle("删除预设")
            .setMessage("确认删除「$name」?")
            .setPositiveButton("删除") { _, _ ->
                if (presetStore.delete(name)) {
                    refreshPresetSpinner()
                    LogStore.append("[preset] 删除: $name")
                    toast("已删除: $name")
                } else {
                    toast("删除失败")
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ── 工具 ──────────────────────────────────────────────────────────

    private fun simpleProgress(onChange: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
            onChange(progress)
        }
        override fun onStartTrackingTouch(seekBar: SeekBar?) {}
        override fun onStopTrackingTouch(seekBar: SeekBar?) {}
    }

    private fun humanBw(bytesPerSec: Long): String = when {
        bytesPerSec >= 1024 * 1024 -> "${bytesPerSec / 1024 / 1024} MB/s"
        bytesPerSec >= 1024 -> "${bytesPerSec / 1024} KB/s"
        else -> "$bytesPerSec B/s"
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
