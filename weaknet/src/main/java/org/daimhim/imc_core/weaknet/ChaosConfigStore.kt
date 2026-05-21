package org.daimhim.imc_core.weaknet

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * 多预设 ChaosConfig 持久化
 *
 * 存储格式 (SharedPreferences):
 * - key = "presets", value = JSON 数组,每个元素:
 *   { "name": "高延迟", "cfg": { "baseLatencyMs": 500, ... } }
 *
 * 名字唯一(同名覆盖)。顺序 = 用户最近一次保存在最前。
 */
class ChaosConfigStore(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun listNames(): List<String> {
        return loadAll().map { it.first }
    }

    fun load(name: String): ChaosConfig? {
        return loadAll().firstOrNull { it.first == name }?.second
    }

    /** 保存 / 覆盖同名预设,把它顶到列表最前 */
    fun save(name: String, cfg: ChaosConfig) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        val current = loadAll().filterNot { it.first == trimmed }.toMutableList()
        current.add(0, trimmed to cfg)
        writeAll(current)
    }

    fun delete(name: String): Boolean {
        val current = loadAll()
        val next = current.filterNot { it.first == name }
        if (next.size == current.size) return false
        writeAll(next)
        return true
    }

    fun clear() {
        sp.edit().remove(KEY_PRESETS).apply()
    }

    private fun loadAll(): List<Pair<String, ChaosConfig>> {
        val raw = sp.getString(KEY_PRESETS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val out = ArrayList<Pair<String, ChaosConfig>>(arr.length())
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val name = item.optString("name", "").trim()
                if (name.isEmpty()) continue
                val cfgObj = item.optJSONObject("cfg") ?: continue
                out.add(name to cfgObj.toChaosConfig())
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun writeAll(list: List<Pair<String, ChaosConfig>>) {
        val arr = JSONArray()
        list.forEach { (name, cfg) ->
            val item = JSONObject()
            item.put("name", name)
            item.put("cfg", cfg.toJson())
            arr.put(item)
        }
        sp.edit().putString(KEY_PRESETS, arr.toString()).apply()
    }

    companion object {
        private const val PREF_NAME = "weaknet_chaos_presets"
        private const val KEY_PRESETS = "presets"

        fun ChaosConfig.toJson(): JSONObject = JSONObject().apply {
            put("baseLatencyMs", baseLatencyMs)
            put("jitterMs", jitterMs)
            put("dropChunkPercent", dropChunkPercent)
            put("maxBytesPerSecond", maxBytesPerSecond)
            put("disconnectAfterBytes", disconnectAfterBytes)
            put("disconnectAfterMs", disconnectAfterMs)
            put("rejectNewConnections", rejectNewConnections)
        }

        fun JSONObject.toChaosConfig(): ChaosConfig = ChaosConfig(
            baseLatencyMs = optLong("baseLatencyMs", 0L),
            jitterMs = optLong("jitterMs", 0L),
            dropChunkPercent = optInt("dropChunkPercent", 0),
            maxBytesPerSecond = optLong("maxBytesPerSecond", 0L),
            disconnectAfterBytes = optLong("disconnectAfterBytes", 0L),
            disconnectAfterMs = optLong("disconnectAfterMs", 0L),
            rejectNewConnections = optBoolean("rejectNewConnections", false),
        )

        fun chaosConfigFromJsonString(json: String?): ChaosConfig? =
            if (json.isNullOrEmpty()) null
            else try { JSONObject(json).toChaosConfig() } catch (_: Exception) { null }
    }
}
