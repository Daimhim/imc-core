package org.daimhim.imc_core.demo

import android.app.Activity
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import org.daimhim.imc_core.NetProbeProfile
import org.daimhim.imc_core.NetSurveillance
import org.daimhim.imc_core.V2JavaWebEngine

/**
 * 场景模拟弹窗:把"前后台 / 弱网 / 心跳挂起 / 主动重连 / 强制探测"等运行时操作集中到一处,
 * 业务在 QgbWsTestActivity 长跑时直接戳就能复现状态变化,事件流走 [ImcEvents] → [LogStore]
 * → [FullLogActivity] 看实时效果。
 *
 * 用法:
 * ```
 * findViewById<Button>(R.id.bt_scenario).setOnClickListener {
 *     ScenarioDialog.show(this, engine, surveillance,
 *         heartbeatModes = mapOf("Foreground" to 0, "Background" to 1, "Disabled" to 2),
 *         onLog = { LogStore.appendApp(it) })
 * }
 * ```
 *
 * **行为**:每个动作都同步在 [LogStore] 记录一行("[scenario] xxx"),方便区分手动触发 vs 系统自动事件。
 */
object ScenarioDialog {

    fun show(
        activity: Activity,
        engine: V2JavaWebEngine,
        surveillance: NetSurveillance,
        heartbeatModes: Map<String, Int>,
        onLog: (String) -> Unit = { LogStore.appendApp("[scenario] $it") },
    ) {
        val items = arrayOf(
            "切档: AGGRESSIVE (前台积极探测 5s 限流)",
            "切档: BALANCED (默认前台档)",
            "切档: BACKGROUND (60s 限流 + 关 burst)",
            "切档: LOW_POWER (5min 限流)",
            "Burst 探测: 开",
            "Burst 探测: 关",
            "强制单次探测 (forceProbe)",
            "主动触发重连 (makeConnection)",
            "通知网络变化: WIFI 连上",
            "通知网络变化: 断网",
            "心跳模式切换…",
            "查看当前 profile / 引擎状态",
        )

        AlertDialog.Builder(activity)
            .setTitle("场景模拟")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> setProfile(surveillance, NetProbeProfile.AGGRESSIVE, "AGGRESSIVE", onLog)
                    1 -> setProfile(surveillance, NetProbeProfile.BALANCED, "BALANCED", onLog)
                    2 -> setProfile(surveillance, NetProbeProfile.BACKGROUND, "BACKGROUND", onLog)
                    3 -> setProfile(surveillance, NetProbeProfile.LOW_POWER, "LOW_POWER", onLog)
                    4 -> {
                        surveillance.setBurstEnabled(true)
                        onLog("burst → ON")
                    }
                    5 -> {
                        surveillance.setBurstEnabled(false)
                        onLog("burst → OFF")
                    }
                    6 -> {
                        onLog("forceProbe 发起")
                        surveillance.forceProbe { r ->
                            onLog("forceProbe 完成 overall=${r.overall} recommend=${r.recommend}")
                        }
                    }
                    7 -> {
                        onLog("makeConnection 触发")
                        try { engine.makeConnection() } catch (e: Exception) {
                            onLog("makeConnection 异常: ${e.message}")
                        }
                    }
                    8 -> {
                        onLog("onNetworkChange(0) — 模拟 WIFI 上")
                        engine.onNetworkChange(0)
                    }
                    9 -> {
                        onLog("onNetworkChange(-1) — 模拟断网")
                        engine.onNetworkChange(-1)
                    }
                    10 -> showHeartbeatModePicker(activity, engine, heartbeatModes, onLog)
                    11 -> showStatusDialog(activity, surveillance)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun setProfile(
        surveillance: NetSurveillance,
        profile: NetProbeProfile,
        label: String,
        onLog: (String) -> Unit,
    ) {
        surveillance.setProfile(profile)
        onLog("profile → $label (minInt=${profile.minProbeIntervalMs}ms burst=${profile.burstEnabled})")
    }

    private fun showHeartbeatModePicker(
        activity: Activity,
        engine: V2JavaWebEngine,
        heartbeatModes: Map<String, Int>,
        onLog: (String) -> Unit,
    ) {
        if (heartbeatModes.isEmpty()) {
            Toast.makeText(activity, "未注册心跳模式", Toast.LENGTH_SHORT).show()
            return
        }
        val names = heartbeatModes.keys.toTypedArray()
        val values = heartbeatModes.values.toIntArray()
        AlertDialog.Builder(activity)
            .setTitle("切心跳模式")
            .setItems(names) { _, which ->
                val mode = values[which]
                engine.onChangeMode(mode)
                onLog("heartbeat mode → ${names[which]} (key=$mode)")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showStatusDialog(activity: Activity, surveillance: NetSurveillance) {
        val report = surveillance.current()
        val profile = surveillance.currentProfile()
        val sb = StringBuilder()
        sb.append("=== NetReport ===\n")
        sb.append("verdict: ").append(report.overall).append("\n")
        sb.append("recommend: ").append(report.recommend).append("\n")
        sb.append("snapshot: ").append(report.snapshot.verdict).append("\n")
        sb.append("transports: ").append(report.snapshot.transports).append("\n")
        sb.append("\n=== Profile ===\n")
        sb.append("minProbeInt: ").append(profile.minProbeIntervalMs).append("ms\n")
        sb.append("debounce: ").append(profile.debounceMs).append("ms\n")
        sb.append("probeTimeout: ").append(profile.probeTimeoutMs).append("ms\n")
        sb.append("burstEnabled: ").append(profile.burstEnabled).append("\n")
        if (profile.burstEnabled) {
            sb.append("burstInt: ").append(profile.burstIntervalMs).append("ms\n")
            sb.append("burstAttempts: ").append(profile.burstAttempts).append("\n")
        }

        AlertDialog.Builder(activity)
            .setTitle("当前状态")
            .setMessage(sb.toString())
            .setPositiveButton("确定", null)
            .show()
    }
}
