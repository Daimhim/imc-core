package org.daimhim.imc_core.weaknet

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs

/**
 * 悬浮窗 Service。
 *
 * 显示:当前预设名、chaos 参数摘要、VPN 运行状态、2 个快捷启停按钮。
 * 可拖动。点 ✕ 关闭。
 *
 * 启动方式:
 *   - 主页"悬浮窗"按钮 → 检查权限 → startService
 * 停止方式:
 *   - 悬浮窗 ✕ 按钮
 *   - 或主页再点一次"悬浮窗"按钮
 *
 * 快捷启动注意:首次启动 VPN 需要从 Activity 发起授权,Service 干不了。
 * 如果系统没记录授权,我们只能 Toast 提示用户去主页授权一次。
 */
class FloatingStatusService : Service() {

    private var wm: WindowManager? = null
    private var floatView: View? = null
    private lateinit var lastConfig: LastConfigStore
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 每秒刷新一次 VPN 包计数 */
    private val refreshRunnable = object : Runnable {
        override fun run() {
            renderStatus()
            mainHandler.postDelayed(this, 1_000)
        }
    }

    /** LogStore 变更 + VPN service 事件都会触发刷新 */
    private val logListener: () -> Unit = { mainHandler.post { renderStatus() } }
    private val vpnListener: (String) -> Unit = { mainHandler.post { renderStatus() } }

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        lastConfig = LastConfigStore(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            removeFloat()
            stopSelf()
            return START_NOT_STICKY
        }
        if (floatView == null) addFloat()
        mainHandler.post(refreshRunnable)
        LogStore.addListener(logListener)
        WeakNetVpnService.addListener(vpnListener)
        renderStatus()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        removeFloat()
        super.onDestroy()
    }

    // ── 视图 ──────────────────────────────────────────────────────────

    private fun addFloat() {
        val w = wm ?: return
        val view = LayoutInflater.from(this).inflate(R.layout.floating_status, null)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 24
        params.y = 240

        view.findViewById<Button>(R.id.bt_float_start).setOnClickListener { onClickStart() }
        view.findViewById<Button>(R.id.bt_float_stop).setOnClickListener { onClickStop() }
        view.findViewById<TextView>(R.id.bt_float_close).setOnClickListener {
            removeFloat(); stopSelf()
        }

        attachDrag(view, params)

        try {
            w.addView(view, params)
            floatView = view
        } catch (e: Exception) {
            Toast.makeText(this, "悬浮窗添加失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun removeFloat() {
        mainHandler.removeCallbacks(refreshRunnable)
        LogStore.removeListener(logListener)
        WeakNetVpnService.removeListener(vpnListener)
        floatView?.let { v ->
            try { wm?.removeView(v) } catch (_: Exception) {}
        }
        floatView = null
    }

    private fun renderStatus() {
        val view = floatView ?: return
        val presetName = lastConfig.presetName() ?: "(未配置)"
        val cfg = lastConfig.config()

        view.findViewById<TextView>(R.id.tv_float_preset).text = "预设: $presetName"
        view.findViewById<TextView>(R.id.tv_float_params).text = paramsString(cfg)

        val running = WeakNetVpnService.isRunning()
        val (pkts, _, flowStats) = WeakNetVpnService.stats()
        view.findViewById<TextView>(R.id.tv_float_status).text =
            if (running) "● 运行 包=$pkts $flowStats" else "○ VPN 未启动"
        view.findViewById<TextView>(R.id.tv_float_status).setTextColor(
            if (running) 0xFF4CD964.toInt() else 0xFFAAAAAA.toInt()
        )
    }

    private fun paramsString(cfg: ChaosConfig): String {
        if (cfg.baseLatencyMs == 0L && cfg.jitterMs == 0L &&
            cfg.dropChunkPercent == 0 && cfg.maxBytesPerSecond == 0L &&
            cfg.disconnectAfterMs == 0L && cfg.disconnectAfterBytes == 0L &&
            !cfg.rejectNewConnections
        ) return "(无 chaos)"
        val bw = if (cfg.maxBytesPerSecond > 0) humanBw(cfg.maxBytesPerSecond) else "∞"
        val parts = mutableListOf<String>()
        if (cfg.baseLatencyMs > 0 || cfg.jitterMs > 0)
            parts.add("${cfg.baseLatencyMs}+${cfg.jitterMs}ms")
        if (cfg.dropChunkPercent > 0) parts.add("${cfg.dropChunkPercent}%丢")
        if (cfg.maxBytesPerSecond > 0) parts.add(bw)
        if (cfg.disconnectAfterMs > 0) parts.add("断${cfg.disconnectAfterMs / 1000}s")
        if (cfg.rejectNewConnections) parts.add("拒新")
        return parts.joinToString(" ")
    }

    private fun humanBw(bytesPerSec: Long): String = when {
        bytesPerSec >= 1024 * 1024 -> "${bytesPerSec / 1024 / 1024}M/s"
        bytesPerSec >= 1024 -> "${bytesPerSec / 1024}K/s"
        else -> "${bytesPerSec}B/s"
    }

    // ── 拖动 ──────────────────────────────────────────────────────────

    private fun attachDrag(view: View, params: WindowManager.LayoutParams) {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        view.setOnTouchListener { v, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = ev.rawX
                    touchY = ev.rawY
                    false  // 还要传给按钮 click,不消费
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (ev.rawX - touchX).toInt()
                    val dy = (ev.rawY - touchY).toInt()
                    if (abs(dx) > 8 || abs(dy) > 8) {
                        params.x = startX + dx
                        params.y = startY + dy
                        try { wm?.updateViewLayout(v, params) } catch (_: Exception) {}
                        true
                    } else false
                }
                else -> false
            }
        }
    }

    // ── 快捷启停 ──────────────────────────────────────────────────────

    private fun onClickStart() {
        if (WeakNetVpnService.isRunning()) {
            Toast.makeText(this, "VPN 已在运行", Toast.LENGTH_SHORT).show()
            return
        }
        // VPN 授权必须从 Activity 发起,Service 起不来
        if (VpnService.prepare(this) != null) {
            Toast.makeText(this, "首次启动请在主页授权", Toast.LENGTH_LONG).show()
            return
        }
        val target = lastConfig.targetPackage()
        val cfg = lastConfig.config()
        val allowed = target?.let { arrayOf(it) } ?: emptyArray()
        val cfgJson = with(ChaosConfigStore.Companion) { cfg.toJson() }.toString()
        val intent = Intent(this, WeakNetVpnService::class.java).apply {
            putExtra(WeakNetVpnService.EXTRA_ALLOWED_APPS, allowed)
            putExtra(WeakNetVpnService.EXTRA_CHAOS_CONFIG, cfgJson)
        }
        startService(intent)
    }

    private fun onClickStop() {
        if (!WeakNetVpnService.isRunning()) {
            Toast.makeText(this, "VPN 未在运行", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(this, WeakNetVpnService::class.java).apply {
            action = WeakNetVpnService.ACTION_STOP
        }
        startService(intent)
    }

    companion object {
        const val ACTION_STOP = "org.daimhim.imc_core.weaknet.FLOAT_STOP"

        /** 当前悬浮窗是否在显示。简单单例标志,够给 toggle 用 */
        @Volatile var visible: Boolean = false
            private set

        fun isVisible(): Boolean = visible

        fun show(ctx: android.content.Context) {
            ctx.startService(Intent(ctx, FloatingStatusService::class.java))
            visible = true
        }

        fun hide(ctx: android.content.Context) {
            val i = Intent(ctx, FloatingStatusService::class.java).apply { action = ACTION_STOP }
            ctx.startService(i)
            visible = false
        }
    }
}
