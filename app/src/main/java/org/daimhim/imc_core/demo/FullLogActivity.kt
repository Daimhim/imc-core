package org.daimhim.imc_core.demo

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import android.widget.ToggleButton
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import org.daimhim.imc_core.ImcEvent
import java.io.File

/**
 * 重写后的完整日志视图。
 *
 * 功能:
 *  - 关键字搜索(忽略大小写)
 *  - Category 多选 chip(默认全部),APP 来的条目用单独 "APP" chip 控制
 *  - "暂停"按钮 — 暂停期间 LogStore 仍写入,UI 不刷新
 *  - "仅错误"开关 — 只显示 WARN/ERROR 级
 *  - 级别色:ERROR 红 / WARN 橙 / INFO 灰 / VERBOSE 浅灰
 *  - SDK 条目带 category 前缀色块,APP 蓝色
 *  - 点击条目展开看完整字段(SDK 事件用 toString)
 *  - "日志文件"对话框:adb pull 路径 / 分享最新
 */
class FullLogActivity : AppCompatActivity() {

    private lateinit var adapter: EntryAdapter
    private lateinit var tvStats: TextView
    private lateinit var etSearch: EditText
    private lateinit var tbPause: ToggleButton
    private lateinit var tbErrorsOnly: ToggleButton

    // 选中的 category:全 selected = null(不过滤)
    private var selectedCategories: MutableSet<ImcEvent.Category>? = null
    private var includeApp: Boolean = true

    private val listener: () -> Unit = {
        if (!tbPause.isChecked) runOnUiThread { refresh() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_full_log)

        adapter = EntryAdapter(this)
        findViewById<ListView>(R.id.lv_log).apply {
            setAdapter(adapter)
            setOnItemClickListener { _, _, position, _ ->
                (adapter.getItem(position) as? LogStore.LogEntry)?.let { showDetail(it) }
            }
        }

        tvStats = findViewById(R.id.tv_stats)
        etSearch = findViewById(R.id.et_search)
        tbPause = findViewById(R.id.tb_pause)
        tbErrorsOnly = findViewById(R.id.tb_errors_only)

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = refresh()
        })
        tbPause.setOnCheckedChangeListener { _, _ -> refresh() }
        tbErrorsOnly.setOnCheckedChangeListener { _, _ -> refresh() }

        buildCategoryChips()

        findViewById<Button>(R.id.bt_clear).setOnClickListener {
            LogStore.clear()
            Toast.makeText(this, "已清空内存缓冲", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.bt_close).setOnClickListener { finish() }
        findViewById<Button>(R.id.bt_logfile).setOnClickListener { showLogFileDialog() }
    }

    override fun onStart() {
        super.onStart()
        LogStore.addListener(listener)
        refresh()
    }

    override fun onStop() {
        LogStore.removeListener(listener)
        super.onStop()
    }

    // ── 分类 chip ─────────────────────────────────────────────────────────

    private fun buildCategoryChips() {
        val container = findViewById<LinearLayout>(R.id.ll_categories)
        container.removeAllViews()

        // "全部" chip:点了就清空筛选
        container.addView(makeChip("全部", checked = true) { chip ->
            selectedCategories = null
            includeApp = true
            // 取消其它 chip 的选中
            for (i in 1 until container.childCount) {
                (container.getChildAt(i) as ToggleButton).isChecked = false
            }
            chip.isChecked = true
            refresh()
        })

        // APP chip
        container.addView(makeChip("APP", checked = false) { chip ->
            includeApp = chip.isChecked
            // 任何一个分类 chip 变化后,"全部"自动跟随
            (container.getChildAt(0) as ToggleButton).isChecked = false
            ensureCategoriesNonNullIfAnyChecked()
            refresh()
        })

        // 6 个 SDK 分类(去掉 INTERNAL — 它单独由 minLevel 控制时太特殊;干脆也展示一项)
        for (cat in ImcEvent.Category.values()) {
            container.addView(makeChip(cat.name, checked = false) { chip ->
                val set = (selectedCategories ?: mutableSetOf())
                if (chip.isChecked) set.add(cat) else set.remove(cat)
                selectedCategories = if (set.isEmpty()) null else set
                (container.getChildAt(0) as ToggleButton).isChecked = false
                refresh()
            })
        }
    }

    private fun ensureCategoriesNonNullIfAnyChecked() {
        // selectedCategories=null 表示"全部 category",但 includeApp=false 时
        // 应当仍能渲染 SDK 全部 category。这里不做特殊处理,refresh 时按显式集合过滤
    }

    private fun makeChip(
        label: String,
        checked: Boolean,
        onToggle: (ToggleButton) -> Unit,
    ): ToggleButton {
        val tb = ToggleButton(this).apply {
            text = label
            textOn = label
            textOff = label
            isChecked = checked
            textSize = 10f
            setPadding(16, 4, 16, 4)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.setMargins(4, 0, 4, 0)
            layoutParams = lp
        }
        tb.setOnClickListener { onToggle(tb) }
        return tb
    }

    // ── 渲染 ─────────────────────────────────────────────────────────────

    private fun refresh() {
        val keyword = etSearch.text?.toString()
        val minLevel = if (tbErrorsOnly.isChecked) LogStore.Level.WARN else null
        val total = LogStore.snapshot().size
        // 显式 categories=null 表示不过滤;但当 includeApp=false 又没选 SDK 类时,展示空集
        val filtered = LogStore.snapshot(
            categories = selectedCategories,
            keyword = keyword,
            minLevel = minLevel,
        ).filter { entry ->
            // APP 条目不带 category;按 includeApp 决定是否纳入
            if (entry.category == null) includeApp
            else (selectedCategories == null || entry.category in selectedCategories!!)
        }
        adapter.setEntries(filtered)
        tvStats.text = "${filtered.size} / $total" + (if (tbPause.isChecked) "  ⏸" else "")
    }

    private fun showDetail(entry: LogStore.LogEntry) {
        val sb = StringBuilder()
        sb.append("时间: ").append(LogStore.formatTime(entry.ts)).append("\n")
        sb.append("来源: ").append(entry.source.name).append(" / ").append(entry.tag).append("\n")
        sb.append("级别: ").append(entry.level.name).append("\n")
        entry.category?.let { sb.append("分类: ").append(it.name).append("\n") }
        sb.append("\n").append(entry.message)
        entry.event?.let {
            sb.append("\n\n--- 原始事件 ---\n").append(it.toString())
        }
        AlertDialog.Builder(this)
            .setTitle("日志条目")
            .setMessage(sb.toString())
            .setPositiveButton("确定", null)
            .show()
    }

    // ── 日志文件对话框 ────────────────────────────────────────────────────

    private fun showLogFileDialog() {
        val dir = FileLogger.logDir()
        val files = FileLogger.listFiles()
        if (dir == null) {
            Toast.makeText(this, "日志文件未初始化", Toast.LENGTH_SHORT).show()
            return
        }
        val sb = StringBuilder()
        sb.append("目录:\n").append(dir.absolutePath).append("\n\n")
        if (files.isEmpty()) {
            sb.append("(暂无日志文件)")
        } else {
            sb.append("文件 (新→旧):\n")
            for (f in files) {
                sb.append("· ").append(f.name)
                    .append("  ").append(f.length() / 1024).append("KB\n")
            }
            sb.append("\nadb pull:\nadb pull ").append(dir.absolutePath)
        }

        val builder = AlertDialog.Builder(this)
            .setTitle("本地日志文件")
            .setMessage(sb.toString())
            .setNegativeButton("关闭", null)
        val current = files.firstOrNull()
        if (current != null) {
            builder.setPositiveButton("分享最新") { _, _ -> shareFile(current) }
        }
        builder.show()
    }

    private fun shareFile(file: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, file.name)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "分享日志 ${file.name}"))
        } catch (e: Exception) {
            Toast.makeText(this, "分享失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // ── ListView Adapter:渲染富文本 ──────────────────────────────────────

    private class EntryAdapter(private val ctx: FullLogActivity) : BaseAdapter() {

        private var entries: List<LogStore.LogEntry> = emptyList()

        fun setEntries(list: List<LogStore.LogEntry>) {
            entries = list
            notifyDataSetChanged()
        }

        override fun getCount(): Int = entries.size
        override fun getItem(position: Int): Any = entries[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val tv = (convertView as? TextView)
                ?: ctx.layoutInflater.inflate(R.layout.item_log, parent, false) as TextView
            val entry = entries[position]
            tv.text = format(entry)
            return tv
        }

        private fun format(entry: LogStore.LogEntry): CharSequence {
            val ssb = SpannableStringBuilder()
            // [HH:mm:ss.SSS] (灰)
            val ts = "[${LogStore.formatTime(entry.ts)}] "
            ssb.append(ts)
            ssb.setSpan(
                ForegroundColorSpan(Color.parseColor("#888888")),
                0, ts.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )

            // 来源/分类标签
            val tag = "[${entry.category?.name ?: entry.tag}] "
            val tagStart = ssb.length
            ssb.append(tag)
            ssb.setSpan(
                ForegroundColorSpan(colorForCategory(entry)),
                tagStart, tagStart + tag.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            ssb.setSpan(
                StyleSpan(Typeface.BOLD),
                tagStart, tagStart + tag.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )

            // message,按 level 着色
            val msgStart = ssb.length
            ssb.append(entry.message)
            ssb.setSpan(
                ForegroundColorSpan(colorForLevel(entry.level)),
                msgStart, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )

            return ssb
        }

        private fun colorForLevel(level: LogStore.Level): Int = when (level) {
            LogStore.Level.ERROR -> Color.parseColor("#C62828")
            LogStore.Level.WARN -> Color.parseColor("#EF6C00")
            LogStore.Level.INFO -> Color.parseColor("#222222")
            LogStore.Level.VERBOSE -> Color.parseColor("#888888")
        }

        private fun colorForCategory(entry: LogStore.LogEntry): Int {
            val cat = entry.category ?: return Color.parseColor("#1565C0") // APP = 蓝
            return when (cat) {
                ImcEvent.Category.ENGINE -> Color.parseColor("#1565C0")
                ImcEvent.Category.HEARTBEAT -> Color.parseColor("#6A1B9A")
                ImcEvent.Category.CACHE -> Color.parseColor("#00838F")
                ImcEvent.Category.AUTOCONNECT -> Color.parseColor("#2E7D32")
                ImcEvent.Category.NETWORK -> Color.parseColor("#558B2F")
                ImcEvent.Category.PROBE -> Color.parseColor("#EF6C00")
                ImcEvent.Category.INTERNAL -> Color.parseColor("#C62828")
            }
        }
    }
}
