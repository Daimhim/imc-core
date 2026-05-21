package org.daimhim.imc_core.demo

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import androidx.appcompat.app.AppCompatActivity

/**
 * 完整事件日志全屏视图(demo app 共用)。
 * 从 [LogStore] 拉所有条目;支持实时刷新;清空按钮清掉缓冲。
 */
class FullLogActivity : AppCompatActivity() {

    private lateinit var adapter: ArrayAdapter<String>
    private val listener: () -> Unit = { runOnUiThread { refresh() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_full_log)
        adapter = ArrayAdapter(this, R.layout.item_log, LogStore.snapshot())
        findViewById<ListView>(R.id.lv_log).adapter = adapter

        findViewById<Button>(R.id.bt_clear).setOnClickListener { LogStore.clear() }
        findViewById<Button>(R.id.bt_close).setOnClickListener { finish() }
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

    private fun refresh() {
        adapter.clear()
        adapter.addAll(LogStore.snapshot())
        adapter.notifyDataSetChanged()
    }
}
