package org.daimhim.imc_core.weaknet

import android.app.AlertDialog
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import java.util.Locale

/**
 * 应用选择器对话框 — 图标 + 名称 + 包名,顶部搜索框过滤。
 */
class AppPickerDialog(
    private val context: Context,
    private val initialPackage: String?,
    private val onPicked: (packageName: String?) -> Unit,
) {
    data class Entry(
        val packageName: String?,         // null = 全局
        val label: String,
        val icon: Drawable?,
    )

    private val all = ArrayList<Entry>()
    private lateinit var adapter: AppAdapter

    fun show() {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_app_picker, null)
        val search = view.findViewById<EditText>(R.id.et_search)
        val listView = view.findViewById<ListView>(R.id.lv_apps)

        adapter = AppAdapter()
        listView.adapter = adapter

        val dialog = AlertDialog.Builder(context)
            .setTitle("选择代理目标")
            .setView(view)
            .setNegativeButton("取消", null)
            .create()

        listView.setOnItemClickListener { _, _, position, _ ->
            val e = adapter.getItem(position)
            onPicked(e.packageName)
            dialog.dismiss()
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                adapter.filter(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        dialog.show()
        // 异步加载列表
        Thread { loadApps(); (context as? android.app.Activity)?.runOnUiThread { adapter.filter("") } }.start()
    }

    private fun loadApps() {
        val pm = context.packageManager
        val installed = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val list = installed
            .filter { it.packageName != context.packageName }    // 排除自身
            .map { app ->
                Entry(
                    packageName = app.packageName,
                    label = pm.getApplicationLabel(app).toString(),
                    icon = try { pm.getApplicationIcon(app) } catch (_: Exception) { null },
                )
            }
            .sortedWith(compareBy(
                // 非系统 app 优先
                { (it.packageName == null) },  // 全局放最前(虽然这里不会出现)
                { isSystemApp(pm, it.packageName) },
                { it.label.lowercase(Locale.getDefault()) },
            ))
        synchronized(all) {
            all.clear()
            all.add(Entry(null, "全局(不限制 app)", null))
            all.addAll(list)
        }
    }

    private fun isSystemApp(pm: PackageManager, pkg: String?): Boolean {
        if (pkg == null) return false
        return try {
            val info = pm.getApplicationInfo(pkg, 0)
            (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        } catch (_: Exception) { true }
    }

    private inner class AppAdapter : BaseAdapter() {
        private val shown = ArrayList<Entry>()

        fun filter(q: String) {
            val ql = q.trim().lowercase(Locale.getDefault())
            shown.clear()
            synchronized(all) {
                if (ql.isEmpty()) shown.addAll(all)
                else for (e in all) {
                    if (e.packageName == null) { shown.add(e); continue }
                    if (e.label.lowercase(Locale.getDefault()).contains(ql) ||
                        (e.packageName.lowercase(Locale.getDefault()).contains(ql))
                    ) shown.add(e)
                }
            }
            notifyDataSetChanged()
        }

        override fun getCount(): Int = shown.size
        override fun getItem(position: Int): Entry = shown[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val v = convertView ?: LayoutInflater.from(context)
                .inflate(R.layout.item_app_entry, parent, false)
            val e = shown[position]
            v.findViewById<ImageView>(R.id.iv_icon).setImageDrawable(e.icon)
            v.findViewById<TextView>(R.id.tv_label).text =
                if (e.packageName == initialPackage) "✓ ${e.label}" else e.label
            v.findViewById<TextView>(R.id.tv_pkg).text = e.packageName ?: "—"
            return v
        }
    }
}
