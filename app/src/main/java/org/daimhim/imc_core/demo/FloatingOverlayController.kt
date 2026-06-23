package org.daimhim.imc_core.demo

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView

/**
 * 两个独立可拖动悬浮窗。
 *   - statusView : socket 连接状态
 *   - metricsView: 网络丢包率 / 延迟 / 抖动
 * 任意一个失败添加(无 SYSTEM_ALERT_WINDOW 权限)都会抛 WindowManager.BadTokenException,
 * 调用方需提前调 Settings.canDrawOverlays(context) 检查并跳转设置页授权。
 */
class FloatingOverlayController(private val context: Context) {

    private val wm: WindowManager =
        context.applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var statusView: TextView? = null
    private var metricsView: TextView? = null
    private var heartbeatView: TextView? = null

    // 缓存最近一次内容,这样 show() 时能立即把状态打到新建的 TextView 上,
    // 不用等下一个事件 / 下一个 1Hz tick。
    @Volatile private var lastStatusText: CharSequence = "socket\n(idle)"
    @Volatile private var lastStatusColor: Int = Color.WHITE
    @Volatile private var lastMetricsText: CharSequence = "net\n(waiting)"
    @Volatile private var lastHeartbeatText: CharSequence = "心跳\n(idle)"

    fun show() {
        if (statusView == null) {
            val tv = makeTextView(lastStatusText)
            tv.setTextColor(lastStatusColor)
            val lp = makeLayoutParams(x = dp(12), y = dp(120))
            attachDrag(tv, lp)
            wm.addView(tv, lp)
            statusView = tv
        }
        if (metricsView == null) {
            val tv = makeTextView(lastMetricsText)
            val lp = makeLayoutParams(x = dp(12), y = dp(210))
            attachDrag(tv, lp)
            wm.addView(tv, lp)
            metricsView = tv
        }
        if (heartbeatView == null) {
            val tv = makeTextView(lastHeartbeatText)
            val lp = makeLayoutParams(x = dp(12), y = dp(320))
            attachDrag(tv, lp)
            wm.addView(tv, lp)
            heartbeatView = tv
        }
    }

    fun hide() {
        statusView?.let { runCatching { wm.removeViewImmediate(it) } }
        metricsView?.let { runCatching { wm.removeViewImmediate(it) } }
        heartbeatView?.let { runCatching { wm.removeViewImmediate(it) } }
        statusView = null
        metricsView = null
        heartbeatView = null
    }

    fun isShowing(): Boolean =
        statusView != null || metricsView != null || heartbeatView != null

    fun updateStatus(text: String, accent: Int = Color.WHITE) {
        lastStatusText = text
        lastStatusColor = accent
        statusView?.post {
            statusView?.text = text
            statusView?.setTextColor(accent)
        }
    }

    fun updateMetrics(text: String) {
        lastMetricsText = text
        metricsView?.post { metricsView?.text = text }
    }

    fun updateHeartbeat(text: String) {
        lastHeartbeatText = text
        heartbeatView?.post { heartbeatView?.text = text }
    }

    private fun makeTextView(initialText: CharSequence): TextView = TextView(context).apply {
        text = initialText
        setPadding(dp(10), dp(6), dp(10), dp(6))
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        typeface = Typeface.MONOSPACE
        background = GradientDrawable().apply {
            cornerRadius = dp(8).toFloat()
            setColor(Color.argb(204, 0, 0, 0))
        }
    }

    private fun makeLayoutParams(x: Int, y: Int): WindowManager.LayoutParams {
        val type =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
        }
    }

    private fun attachDrag(view: View, lp: WindowManager.LayoutParams) {
        view.setOnTouchListener(object : View.OnTouchListener {
            var downRawX = 0f
            var downRawY = 0f
            var startX = 0
            var startY = 0
            override fun onTouch(v: View, ev: MotionEvent): Boolean {
                when (ev.action) {
                    MotionEvent.ACTION_DOWN -> {
                        downRawX = ev.rawX; downRawY = ev.rawY
                        startX = lp.x; startY = lp.y
                    }
                    MotionEvent.ACTION_MOVE -> {
                        lp.x = startX + (ev.rawX - downRawX).toInt()
                        lp.y = startY + (ev.rawY - downRawY).toInt()
                        runCatching { wm.updateViewLayout(v, lp) }
                    }
                }
                return true
            }
        })
    }

    private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()
}
