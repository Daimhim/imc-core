package org.daimhim.imc_core.demo

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import android.widget.ToggleButton
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/**
 * 接收消息列表面板。
 *
 *  - 搜索 / 暂停实时刷新
 *  - 类型筛选(全部 / TEXT / BINARY,单选互斥)
 *  - 点击条目看完整内容:文本直接显示,字节同时给 hex dump + UTF-8 解码
 *  - 详情对话框上有「复制内容」「复制 hex」按钮
 */
class MessageInboxActivity : AppCompatActivity() {

    private lateinit var adapter: InboxAdapter
    private lateinit var tvStats: TextView
    private lateinit var etSearch: EditText
    private lateinit var tbPause: ToggleButton
    private lateinit var tbAll: ToggleButton
    private lateinit var tbText: ToggleButton
    private lateinit var tbBinary: ToggleButton

    private val listener: () -> Unit = {
        if (!tbPause.isChecked) runOnUiThread { refresh() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_message_inbox)

        adapter = InboxAdapter(this)
        findViewById<ListView>(R.id.lv_inbox).apply {
            setAdapter(adapter)
            setOnItemClickListener { _, _, position, _ ->
                (adapter.getItem(position) as? MessageInbox.Entry)?.let { showDetail(it) }
            }
        }

        tvStats = findViewById(R.id.tv_inbox_stats)
        etSearch = findViewById(R.id.et_inbox_search)
        tbPause = findViewById(R.id.tb_inbox_pause)
        tbAll = findViewById(R.id.tb_kind_all)
        tbText = findViewById(R.id.tb_kind_text)
        tbBinary = findViewById(R.id.tb_kind_binary)

        // 类型筛选互斥:点 ALL 取消其它,点 TEXT/BINARY 取消 ALL 并互斥
        tbAll.setOnClickListener {
            tbAll.isChecked = true
            tbText.isChecked = false
            tbBinary.isChecked = false
            refresh()
        }
        tbText.setOnClickListener {
            if (tbText.isChecked) {
                tbAll.isChecked = false
                tbBinary.isChecked = false
            } else if (!tbBinary.isChecked) {
                // 用户取消了 TEXT,且没选 BINARY → 回到全部
                tbAll.isChecked = true
            }
            refresh()
        }
        tbBinary.setOnClickListener {
            if (tbBinary.isChecked) {
                tbAll.isChecked = false
                tbText.isChecked = false
            } else if (!tbText.isChecked) {
                tbAll.isChecked = true
            }
            refresh()
        }

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = refresh()
        })
        tbPause.setOnCheckedChangeListener { _, _ -> refresh() }

        findViewById<Button>(R.id.bt_inbox_clear).setOnClickListener {
            MessageInbox.clear()
            Toast.makeText(this, "已清空收件箱", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.bt_inbox_close).setOnClickListener { finish() }
    }

    override fun onStart() {
        super.onStart()
        MessageInbox.addListener(listener)
        refresh()
    }

    override fun onStop() {
        MessageInbox.removeListener(listener)
        super.onStop()
    }

    private fun selectedKind(): MessageInbox.Kind? = when {
        tbAll.isChecked -> null
        tbText.isChecked -> MessageInbox.Kind.TEXT
        tbBinary.isChecked -> MessageInbox.Kind.BINARY
        else -> null
    }

    private fun refresh() {
        val total = MessageInbox.size()
        val filtered = MessageInbox.snapshot(
            kind = selectedKind(),
            keyword = etSearch.text?.toString(),
        )
        adapter.setEntries(filtered)
        tvStats.text = "${filtered.size} / $total" + (if (tbPause.isChecked) "  ⏸" else "")
    }

    // ── 详情对话框 ────────────────────────────────────────────────────────

    private fun showDetail(entry: MessageInbox.Entry) {
        val sb = StringBuilder()
        sb.append("seq: #").append(entry.seq).append("\n")
        sb.append("type: ").append(entry.kind.name).append("\n")
        sb.append("time: ").append(MessageInbox.formatTime(entry.ts)).append("\n")
        sb.append("size: ").append(entry.size).append(" bytes\n\n")
        when (entry.kind) {
            MessageInbox.Kind.TEXT -> {
                sb.append("--- TEXT ---\n").append(entry.text ?: "")
            }
            MessageInbox.Kind.BINARY -> {
                val data = entry.data ?: ByteArray(0)
                sb.append("--- HEX ---\n").append(MessageInbox.renderHexDump(data))
                sb.append("\n--- UTF-8 (best effort) ---\n")
                sb.append(try { String(data, Charsets.UTF_8) } catch (e: Exception) { "(decode failed: ${e.message})" })
            }
        }
        val body = sb.toString()

        val builder = AlertDialog.Builder(this)
            .setTitle("消息 #${entry.seq}")
            .setMessage(body)
            .setNegativeButton("关闭", null)
            .setPositiveButton("复制") { _, _ -> copyToClipboard(body) }
        if (entry.kind == MessageInbox.Kind.BINARY && entry.data != null) {
            builder.setNeutralButton("复制 hex") { _, _ ->
                val hex = entry.data.joinToString("") { String.format("%02x", it.toInt() and 0xFF) }
                copyToClipboard(hex)
            }
        }
        builder.show()
    }

    private fun copyToClipboard(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("inbox", text))
        Toast.makeText(this, "已复制 ${text.length} 字符", Toast.LENGTH_SHORT).show()
    }

    // ── Adapter ─────────────────────────────────────────────────────────

    private class InboxAdapter(private val ctx: MessageInboxActivity) : BaseAdapter() {
        private var entries: List<MessageInbox.Entry> = emptyList()

        fun setEntries(list: List<MessageInbox.Entry>) {
            entries = list
            notifyDataSetChanged()
        }

        override fun getCount(): Int = entries.size
        override fun getItem(position: Int): Any = entries[position]
        override fun getItemId(position: Int): Long = entries[position].seq

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = convertView ?: ctx.layoutInflater.inflate(R.layout.item_inbox, parent, false)
            val entry = entries[position]
            val meta = view.findViewById<TextView>(R.id.tv_inbox_meta)
            val size = view.findViewById<TextView>(R.id.tv_inbox_size)
            val preview = view.findViewById<TextView>(R.id.tv_inbox_preview)

            meta.text = "#${entry.seq}  ${MessageInbox.formatTime(entry.ts)}  ${entry.kind.name}"
            meta.setTextColor(
                if (entry.kind == MessageInbox.Kind.TEXT) Color.parseColor("#1565C0")
                else Color.parseColor("#EF6C00")
            )
            size.text = "${entry.size}B"
            preview.text = entry.previewUtf8
            return view
        }
    }
}
