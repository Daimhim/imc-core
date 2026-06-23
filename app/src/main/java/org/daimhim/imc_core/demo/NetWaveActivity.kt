package org.daimhim.imc_core.demo

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.View
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.daimhim.imc_core.NetVerdict
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 简化布局:
 *   - tv_now_status  : 1Hz 实时状态条(色块自带图例)
 *   - 波浪图          : 三 lane + 分钟网格,横滑回看
 *   - tv_detail      : 选中时刻 1 行紧凑摘要
 *   - tv_legend      : 默认展开的图例 / 说明,3 段:状态条颜色 / 图表元素 / 使用
 *   - 跟随状态合并进「回到 NOW」按钮文字
 */
class NetWaveActivity : AppCompatActivity() {

    private lateinit var wave: NetWaveView
    private lateinit var scroll: HorizontalScrollView
    private lateinit var tvNow: TextView
    private lateinit var tvDetail: TextView
    private lateinit var tvLegend: TextView
    private lateinit var btJumpNow: Button
    private lateinit var btToggleLegend: Button

    private val handler = Handler(Looper.getMainLooper())
    private val refreshTick = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 1000L)
        }
    }
    private var autoFollow = true
    private val tsFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_net_wave)
        title = "WS / 网络 波浪图"

        wave = findViewById(R.id.wave)
        scroll = findViewById(R.id.scroll_wave)
        tvNow = findViewById(R.id.tv_now_status)
        tvDetail = findViewById(R.id.tv_detail)
        tvLegend = findViewById(R.id.tv_legend)
        btJumpNow = findViewById(R.id.bt_jump_now)
        btToggleLegend = findViewById(R.id.bt_toggle_legend)

        tvLegend.text = buildLegend()

        wave.onFocusSample = { s -> tvDetail.text = renderSampleCompact(s) }

        scroll.viewTreeObserver.addOnScrollChangedListener {
            val child = scroll.getChildAt(0) ?: return@addOnScrollChangedListener
            val maxX = (child.width - scroll.width).coerceAtLeast(0)
            autoFollow = scroll.scrollX >= maxX - 4
            updateFollowButton()
        }

        findViewById<Button>(R.id.bt_clear).setOnClickListener {
            NetWaveStore.clear()
            tvDetail.text = "↑ 点击波浪查看历史时刻详情"
            refresh()
        }

        btJumpNow.setOnClickListener {
            autoFollow = true
            scroll.post { scroll.fullScroll(View.FOCUS_RIGHT) }
            updateFollowButton()
        }

        btToggleLegend.setOnClickListener {
            if (tvLegend.visibility == View.VISIBLE) {
                tvLegend.visibility = View.GONE
                btToggleLegend.text = "展开说明 ▼"
            } else {
                tvLegend.visibility = View.VISIBLE
                btToggleLegend.text = "收起说明 ▲"
            }
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(refreshTick)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refreshTick)
    }

    private fun refresh() {
        val samples = NetWaveStore.snapshot()
        wave.setSamples(samples)
        if (autoFollow) {
            scroll.post { scroll.fullScroll(View.FOCUS_RIGHT) }
        }
        tvNow.text = buildNowStatus()
    }

    private fun updateFollowButton() {
        btJumpNow.text = if (autoFollow) "● 跟随中" else "回到 NOW"
    }

    /**
     * 实时状态条(两行):
     *   ●OPEN     ●OK     RTT 24ms · 抖 4ms · 丢 0%
     *   ♥FIXED 60s   ↑42 ↓41 失0   发 2s 前
     */
    private fun buildNowStatus(): CharSequence {
        val ssb = SpannableStringBuilder()
        appendDot(ssb, NetWaveView.wsColor(NetWaveStore.lastWsState))
        appendBold(ssb, " ${NetWaveStore.lastWsState.name}")

        ssb.append("    ")
        appendDot(ssb, NetWaveView.verdictColor(NetWaveStore.lastNetVerdict))
        appendBold(ssb, " ${NetWaveStore.lastNetVerdict?.name ?: "—"}")

        ssb.append("    ")
        ssb.append("RTT ").append(NetWaveStore.lastRttMs?.let { "${it}ms" } ?: "—")
        ssb.append(" · 抖 ").append(NetWaveStore.lastJitterMs?.let { "%.0fms".format(it) } ?: "—")
        ssb.append(" · 丢 ").append(NetWaveStore.lastLossRate?.let { "%.0f%%".format(it * 100) } ?: "—")

        // 第二行:心跳。颜色用蓝(发送)/红(失败 >0)做强提示
        ssb.append("\n")
        val hbFailed = NetWaveStore.lastHbFail > 0
        val heartColor = if (hbFailed) Color.parseColor("#E53935") else Color.parseColor("#1E88E5")
        appendDotChar(ssb, "♥", heartColor)
        ssb.append(NetWaveStore.lastHbMode ?: "—")
        val interval = NetWaveStore.lastHbIntervalSec
        if (interval > 0) ssb.append(" ").append(interval.toString()).append("s")

        ssb.append("    ")
        val sent = NetWaveStore.lastHbSent
        val recv = NetWaveStore.lastHbRecv
        val pending = (sent - recv).coerceAtLeast(0)
        ssb.append("↑").append(sent.toString())
            .append(" ↓").append(recv.toString())
        if (pending > 0) ssb.append(" 未").append(pending.toString())
        ssb.append(" 失").append(NetWaveStore.lastHbFail.toString())

        ssb.append("    ")
        val ls = NetWaveStore.lastHbSentMs
        if (ls > 0) {
            ssb.append("发 ").append(agoStr(ls))
        } else {
            ssb.append("(等首次心跳)")
        }
        return ssb
    }

    private fun agoStr(thenMs: Long): String {
        val sec = ((System.currentTimeMillis() - thenMs) / 1000).coerceAtLeast(0L)
        return when {
            sec < 60 -> "${sec}s 前"
            sec < 3600 -> "${sec / 60}m${sec % 60}s 前"
            else -> "${sec / 3600}h${(sec % 3600) / 60}m 前"
        }
    }

    /**
     * 图例 + 说明,三段:
     *   状态条颜色 / 图表元素 / 使用
     * 段间空一行,段头加粗,段内用 · 间隔,避免一行塞太多。
     */
    private fun buildLegend(): CharSequence {
        val ssb = SpannableStringBuilder()

        appendBold(ssb, "状态条颜色")
        ssb.append("\n  WS  ")
        appendDot(ssb, NetWaveView.wsColor(NetWaveStore.WsState.OPEN))
        ssb.append(" OPEN   ")
        appendDot(ssb, NetWaveView.wsColor(NetWaveStore.WsState.CONNECTING))
        ssb.append(" CONNECTING   ")
        appendDot(ssb, NetWaveView.wsColor(NetWaveStore.WsState.CLOSED))
        ssb.append(" CLOSED   ")
        appendDot(ssb, NetWaveView.wsColor(NetWaveStore.WsState.LOST))
        ssb.append(" LOST")
        ssb.append("\n  NET ")
        appendDot(ssb, NetWaveView.verdictColor(NetVerdict.OK))
        ssb.append(" 正常   ")
        appendDot(ssb, NetWaveView.verdictColor(NetVerdict.CONGESTED))
        ssb.append(" 警告 (拥塞/挂起/认证)   ")
        appendDot(ssb, NetWaveView.verdictColor(NetVerdict.OFFLINE))
        ssb.append(" 离线 / 拦截")

        ssb.append("\n\n")
        appendBold(ssb, "图表元素")
        ssb.append("\n  HB 泳道: ")
        appendDot(ssb, NetWaveView.HB_SENT)
        ssb.append(" 心跳发送  ")
        appendDot(ssb, NetWaveView.HB_FAIL)
        ssb.append(" 心跳/连接失败")
        ssb.append("\n  蓝色折线 = RTT (burst probe 平均往返延迟,0~历史最大)")
        ssb.append("\n  灰色虚线 = 每分钟刻度   ·   蓝竖线 = NOW   ·   粉竖线 = 选中时刻")

        ssb.append("\n\n")
        appendBold(ssb, "使用")
        ssb.append("\n  横向滑动:回看 30 min 历史   ·   单击波浪:锁定该时刻看详情")
        ssb.append("\n  1Hz 采样 · 数据源 imc-core NetSurveillance (L1+L2+L3 三层探测融合)")

        return ssb
    }

    private fun appendDot(ssb: SpannableStringBuilder, color: Int) {
        val start = ssb.length
        ssb.append("●")
        ssb.setSpan(ForegroundColorSpan(color), start, ssb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun appendDotChar(ssb: SpannableStringBuilder, ch: String, color: Int) {
        val start = ssb.length
        ssb.append(ch)
        ssb.setSpan(ForegroundColorSpan(color), start, ssb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun appendBold(ssb: SpannableStringBuilder, text: String) {
        val start = ssb.length
        ssb.append(text)
        ssb.setSpan(StyleSpan(android.graphics.Typeface.BOLD), start, ssb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    /**
     * 紧凑两段(选中时刻):
     *   ●OPEN ●OK   -1m23s · 14:23:55   RTT 380ms · 抖 45ms · 丢 20%
     *   ♥FIXED 60s   ●发  (或 ●失 / —)
     */
    private fun renderSampleCompact(s: NetWaveStore.Sample?): CharSequence {
        if (s == null) return "↑ 点击波浪查看历史时刻详情"
        val ssb = SpannableStringBuilder()
        appendDot(ssb, NetWaveView.wsColor(s.wsState))
        ssb.append(s.wsState.name)
        ssb.append(" ")
        appendDot(ssb, NetWaveView.verdictColor(s.netVerdict))
        ssb.append(s.netVerdict?.name ?: "—")

        val agoSec = ((System.currentTimeMillis() - s.timestampMs) / 1000).coerceAtLeast(0L)
        val ago = if (agoSec >= 60) "-${agoSec / 60}m${agoSec % 60}s" else "-${agoSec}s"
        ssb.append("   ").append(ago).append(" · ").append(tsFmt.format(Date(s.timestampMs)))

        ssb.append("   RTT ").append(s.rttMs?.let { "${it}ms" } ?: "—")
        ssb.append(" · 抖 ").append(s.jitterMs?.let { "%.0fms".format(it) } ?: "—")
        ssb.append(" · 丢 ").append(s.lossRate?.let { "%.0f%%".format(it * 100) } ?: "—")

        // 第二行:心跳。当 hbMode 为空且该秒无事件,整段省略保持紧凑。
        val hasHbInfo = s.hbMode != null || s.hbSentInTick || s.hbFailedInTick
        if (hasHbInfo) {
            ssb.append("\n")
            val heartColor = if (s.hbFailedInTick) Color.parseColor("#E53935") else Color.parseColor("#1E88E5")
            appendDotChar(ssb, "♥", heartColor)
            ssb.append(s.hbMode ?: "—")
            if (s.hbIntervalSec > 0) ssb.append(" ").append(s.hbIntervalSec.toString()).append("s")
            ssb.append("   ")
            when {
                s.hbFailedInTick -> {
                    appendDotChar(ssb, "●", NetWaveView.HB_FAIL)
                    ssb.append("失败")
                }
                s.hbSentInTick -> {
                    appendDotChar(ssb, "●", NetWaveView.HB_SENT)
                    ssb.append("发送")
                }
                else -> ssb.append("—")
            }
        }

        ssb.setSpan(
            ForegroundColorSpan(Color.parseColor("#212121")),
            0, ssb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        return ssb
    }
}
