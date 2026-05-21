package org.daimhim.imc_core.weaknet

import android.content.Context
import org.daimhim.imc_core.weaknet.ChaosConfigStore.Companion.chaosConfigFromJsonString
import org.daimhim.imc_core.weaknet.ChaosConfigStore.Companion.toJson

/**
 * 主页最后一次"应用"过的状态(目标 app / 预设名 / chaos 配置),
 * 给悬浮窗 / 自动化场景做快捷启动用。
 *
 * 主页保存,悬浮窗或别处读取。
 */
class LastConfigStore(context: Context) {
    private val sp = context.applicationContext
        .getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun save(targetPackage: String?, presetName: String?, cfg: ChaosConfig) {
        sp.edit()
            .putString(KEY_TARGET, targetPackage)
            .putString(KEY_PRESET, presetName)
            .putString(KEY_CFG_JSON, cfg.toJson().toString())
            .apply()
    }

    fun targetPackage(): String? = sp.getString(KEY_TARGET, null)
    fun presetName(): String? = sp.getString(KEY_PRESET, null)
    fun config(): ChaosConfig = chaosConfigFromJsonString(sp.getString(KEY_CFG_JSON, null))
        ?: ChaosConfig()

    companion object {
        private const val PREF = "weaknet_last_state"
        private const val KEY_TARGET = "target_package"
        private const val KEY_PRESET = "preset_name"
        private const val KEY_CFG_JSON = "chaos_cfg_json"
    }
}
