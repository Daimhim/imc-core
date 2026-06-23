package org.daimhim.imc_core.demo

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import org.daimhim.imc_core.NetVerdict

/**
 * 四条 lane 横排:
 *   1) WS 状态条     (OPEN/绿  CONNECTING/橙  CLOSED/灰  LOST/红  IDLE/中灰)
 *   2) 网络状态条    (NetVerdict 颜色编码)
 *   3) HB 事件条     (蓝 tick = 发出心跳; 红 tick = 失败)
 *   4) RTT 折线     (0 ~ 动态 maxRtt;有中线参考)
 * 每个 sample 占 pxPerSample(默认 2dp),View 宽度随 samples 数量伸缩。
 * 各 lane 共享 1 分钟一根的竖向虚线网格,便于跨 lane 对齐时刻。
 * 单击设 focus,粉色 cursor;横向 drag 让 HorizontalScrollView 处理。
 */
class NetWaveView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    companion object {
        val HB_LANE_BG: Int = Color.parseColor("#F0F0F0")
        val HB_SENT: Int = Color.parseColor("#1E88E5")
        val HB_FAIL: Int = Color.parseColor("#E53935")

        @JvmStatic
        fun wsColor(s: NetWaveStore.WsState): Int = when (s) {
            NetWaveStore.WsState.OPEN -> Color.parseColor("#43A047")
            NetWaveStore.WsState.CONNECTING -> Color.parseColor("#FFB300")
            NetWaveStore.WsState.CLOSED -> Color.parseColor("#9E9E9E")
            NetWaveStore.WsState.LOST -> Color.parseColor("#E53935")
            NetWaveStore.WsState.IDLE -> Color.parseColor("#BDBDBD")
        }

        @JvmStatic
        fun verdictColor(v: NetVerdict?): Int = when (v) {
            null -> Color.parseColor("#BDBDBD")
            NetVerdict.OK -> Color.parseColor("#43A047")
            NetVerdict.CONNECTED_NOT_VALIDATED -> Color.parseColor("#FFB300")
            NetVerdict.CHECKING_CONNECTIVITY -> Color.parseColor("#FFB300")
            NetVerdict.CONGESTED -> Color.parseColor("#FB8C00")
            NetVerdict.CAPTIVE_PORTAL -> Color.parseColor("#FB8C00")
            NetVerdict.SUSPENDED -> Color.parseColor("#FB8C00")
            NetVerdict.BLOCKED -> Color.parseColor("#E53935")
            NetVerdict.OFFLINE -> Color.parseColor("#E53935")
        }
    }

    private val pxPerSample: Float = dp(2f)

    private val wsLaneH = dp(24f)
    private val netLaneH = dp(24f)
    private val hbLaneH = dp(18f)
    private val rttLaneH = dp(92f)
    private val laneGap = dp(3f)
    private val timeBarH = dp(18f)

    private val totalH: Float
        get() = wsLaneH + laneGap + netLaneH + laneGap + hbLaneH + laneGap + rttLaneH + laneGap + timeBarH

    private val bgPaint = Paint().apply { color = Color.parseColor("#FAFAFA") }
    private val barPaint = Paint().apply { style = Paint.Style.FILL; isAntiAlias = false }
    private val linePaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.2f)
        color = Color.parseColor("#1976D2")
        isAntiAlias = true
    }
    private val cursorPaint = Paint().apply {
        color = Color.parseColor("#E91E63")
        strokeWidth = dp(1.5f)
    }
    private val nowLinePaint = Paint().apply {
        color = Color.parseColor("#1976D2")
        strokeWidth = dp(1f)
    }
    private val minuteGridPaint = Paint().apply {
        color = Color.parseColor("#D0D0D0")
        strokeWidth = dp(0.6f)
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(dp(3f), dp(3f)), 0f)
    }
    private val rttGuidePaint = Paint().apply {
        color = Color.parseColor("#E5E5E5")
        strokeWidth = dp(0.6f)
    }
    private val labelPaint = Paint().apply {
        color = Color.parseColor("#666666")
        textSize = dp(9f)
        isAntiAlias = true
    }
    private val laneLabelPaint = Paint().apply {
        color = Color.parseColor("#9E9E9E")
        textSize = dp(9f)
        isAntiAlias = true
    }

    private var samples: List<NetWaveStore.Sample> = emptyList()
    private var rttMaxCap: Long = 100L
    private var focusIdx: Int = -1
    private val gridPath = Path()

    var onFocusSample: ((NetWaveStore.Sample?) -> Unit)? = null

    init {
        isClickable = true
    }

    fun setSamples(list: List<NetWaveStore.Sample>) {
        samples = list
        if (focusIdx >= list.size) focusIdx = -1
        rttMaxCap = (list.mapNotNull { it.rttMs }.maxOrNull() ?: 100L).coerceAtLeast(100L)
        requestLayout()
        invalidate()
    }

    /** 暴露给 Activity 显示当前 RTT 上限到 status strip */
    fun currentRttCap(): Long = rttMaxCap

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val parentW = MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(suggestedMinimumWidth)
        val w = ((samples.size.coerceAtLeast(1)) * pxPerSample).toInt().coerceAtLeast(parentW)
        setMeasuredDimension(w, totalH.toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        canvas.drawRect(0f, 0f, w, height.toFloat(), bgPaint)

        var y = 0f
        val wsTop = y; val wsBot = wsTop + wsLaneH; y = wsBot + laneGap
        val netTop = y; val netBot = netTop + netLaneH; y = netBot + laneGap
        val hbTop = y; val hbBot = hbTop + hbLaneH; y = hbBot + laneGap
        val rttTop = y; val rttBot = rttTop + rttLaneH; y = rttBot + laneGap
        val timeTop = y

        if (samples.isEmpty()) {
            labelPaint.color = Color.parseColor("#888888")
            canvas.drawText("(无数据 — 等 1Hz 写入)", dp(8f), totalH / 2f, labelPaint)
            return
        }

        val n = samples.size

        // 1) RTT lane 中线 + 0 基线(放在 bars 之下作为底层参考)
        val rttMid = rttTop + rttLaneH / 2f
        canvas.drawLine(0f, rttMid, w, rttMid, rttGuidePaint)
        canvas.drawLine(0f, rttBot, w, rttBot, rttGuidePaint)

        // HB lane 浅灰底
        barPaint.color = HB_LANE_BG
        canvas.drawRect(0f, hbTop, w, hbBot, barPaint)

        // 2) 四 lane 主体:WS / NET / HB bar + RTT polyline
        var prevX: Float? = null
        var prevY: Float? = null
        for (i in 0 until n) {
            val s = samples[i]
            val xL = i * pxPerSample
            val xR = xL + pxPerSample

            barPaint.color = wsColor(s.wsState)
            canvas.drawRect(xL, wsTop, xR, wsBot, barPaint)

            barPaint.color = verdictColor(s.netVerdict)
            canvas.drawRect(xL, netTop, xR, netBot, barPaint)

            // HB:失败优先,任何一秒命中就把整个 sample 列染色
            if (s.hbFailedInTick) {
                barPaint.color = HB_FAIL
                canvas.drawRect(xL, hbTop, xR, hbBot, barPaint)
            } else if (s.hbSentInTick) {
                barPaint.color = HB_SENT
                canvas.drawRect(xL, hbTop, xR, hbBot, barPaint)
            }

            val rtt = s.rttMs
            if (rtt != null) {
                val frac = (rtt.toFloat() / rttMaxCap.toFloat()).coerceIn(0f, 1f)
                val yPt = rttBot - frac * rttLaneH
                val xMid = (xL + xR) / 2f
                if (prevX != null) {
                    canvas.drawLine(prevX, prevY!!, xMid, yPt, linePaint)
                }
                prevX = xMid; prevY = yPt
            } else {
                prevX = null; prevY = null
            }
        }

        // 3) 每分钟一根竖向虚线,跨三个 lane 对齐
        run {
            var i = 60
            while (i < n) {
                val xL = i * pxPerSample
                gridPath.reset()
                gridPath.moveTo(xL, wsTop)
                gridPath.lineTo(xL, rttBot)
                canvas.drawPath(gridPath, minuteGridPaint)
                i += 60
            }
        }

        // 4) 各 lane 左上小标签(浅灰),只标 lane 名,数值由顶部状态条承载
        canvas.drawText("WS", dp(2f), wsTop + dp(9f), laneLabelPaint)
        canvas.drawText("NET", dp(2f), netTop + dp(9f), laneLabelPaint)
        canvas.drawText("HB", dp(2f), hbTop + dp(9f), laneLabelPaint)
        canvas.drawText("RTT", dp(2f), rttTop + dp(9f), laneLabelPaint)
        // RTT 中线 / 上限标签(跟着分钟标尺一起在最左固定位置)
        canvas.drawText("${rttMaxCap}ms", dp(2f), rttTop + dp(18f), laneLabelPaint)
        canvas.drawText("${rttMaxCap / 2}ms", dp(2f), rttMid - dp(2f), laneLabelPaint)

        // 5) 时间刻度:每 60 sample(1 min)画 + 文字
        labelPaint.color = Color.parseColor("#555555")
        val nowMs = samples.last().timestampMs
        var i = 0
        while (i < n) {
            val xL = i * pxPerSample
            val agoSec = ((nowMs - samples[i].timestampMs) / 1000).coerceAtLeast(0L)
            val text = if (agoSec >= 60) "-${agoSec / 60}m" else "now"
            canvas.drawText(text, xL + dp(2f), timeTop + dp(13f), labelPaint)
            i += 60
        }

        // 6) NOW 标识(右边一条蓝色细线)
        canvas.drawLine(w - dp(0.5f), 0f, w - dp(0.5f), rttBot, nowLinePaint)

        // 7) 选中 cursor
        val fi = focusIdx
        if (fi in 0 until n) {
            val xMid = fi * pxPerSample + pxPerSample / 2f
            canvas.drawLine(xMid, 0f, xMid, rttBot, cursorPaint)
        }
    }

    private val gesture = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val idx = (e.x / pxPerSample).toInt().coerceIn(0, (samples.size - 1).coerceAtLeast(0))
            focusIdx = if (samples.isEmpty()) -1 else idx
            onFocusSample?.invoke(if (focusIdx >= 0) samples[focusIdx] else null)
            invalidate()
            return true
        }
    })

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gesture.onTouchEvent(event)
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean = super.performClick()

    private fun dp(v: Float): Float = v * context.resources.displayMetrics.density
}
