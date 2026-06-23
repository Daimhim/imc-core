package org.daimhim.imc_core.demo

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import org.daimhim.imc_core.IEngine
import org.daimhim.imc_core.NetProbeProfile
import org.daimhim.imc_core.NetSurveillance

/**
 * 把 [IEngine] 的心跳模式跟 App 进程的前后台状态绑起来,带**非对称防抖**:
 *
 *  - **回前台:立即切 `foregroundMode`** —— 用户期望即时响应
 *  - **进后台:延迟 [backgroundDelayMs] 后才切 `backgroundMode`** —— 给"分享/选图/接电话"
 *    这类"短暂离开后回来"的场景一个窗口,避免无用切换抖动
 *
 * 行为示例(`backgroundDelayMs = 30s`):
 *
 * ```
 * Activity onStop  ─→ 排一个 30s 后才生效的 BG 任务
 *      │
 *      └── 5s 后 Activity onStart (用户分享完回来了)
 *           → 取消 BG 任务, 立即切 foregroundMode (其实没变, no-op)
 *
 * Activity onStop  ─→ 排一个 30s 后才生效的 BG 任务
 *      │
 *      └── 30s 用户没回来
 *           → BG 任务触发, engine.onChangeMode(backgroundMode)
 * ```
 *
 * @param engine 要绑定的引擎实例
 * @param foregroundMode `engine.onChangeMode(...)` 用的前台心跳模式 key
 * @param backgroundMode `engine.onChangeMode(...)` 用的后台心跳模式 key
 * @param backgroundDelayMs 进入后台后的防抖延迟。默认 30s,跟 WhatsApp / Signal / 微信下限对齐。
 *                          短暂离开(<30s)的场景不会触发 BG 切换
 */
/**
 * 可选地把 [surveillance] 也绑上 —— 前台用 [foregroundProfile],进后台后切到 [backgroundProfile]。
 * 留空表示不调 surveillance,业务自己决定是否切探测档。
 */
class EngineForegroundBinder @JvmOverloads constructor(
    private val engine: IEngine,
    private val foregroundMode: Int,
    private val backgroundMode: Int,
    private val backgroundDelayMs: Long = DEFAULT_BACKGROUND_DELAY_MS,
    private val surveillance: NetSurveillance? = null,
    private val foregroundProfile: NetProbeProfile = NetProbeProfile.BALANCED,
    private val backgroundProfile: NetProbeProfile = NetProbeProfile.BACKGROUND,
) {

    companion object {
        /** WhatsApp / Signal / 微信下限风格,适合一般 IM */
        const val DEFAULT_BACKGROUND_DELAY_MS = 30_000L

        /** Telegram / 微信宽容档,适合高频短暂离开的场景 */
        const val TOLERANT_BACKGROUND_DELAY_MS = 60_000L

        private const val TAG = "EngineFgBinder"
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 当前实际生效的心跳 mode key,-1 表示还没切过(attach 前) */
    @Volatile var currentMode: Int = -1
        private set

    private val applyBackgroundModeTask = Runnable {
        Log.i(TAG,"EngineForegroundBinder: ${backgroundDelayMs}ms 防抖到期, 切到 backgroundMode($backgroundMode)")
        engine.onChangeMode(backgroundMode)
        currentMode = backgroundMode
        // 顺便把 surveillance 切到后台档:降低探测频率 + 关 burst,节电
        surveillance?.setProfile(backgroundProfile)
    }

    private val observer = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            // 进前台: 取消可能 pending 的 BG 切换, 立即切 FG
            mainHandler.removeCallbacks(applyBackgroundModeTask)
            Log.i(TAG,"EngineForegroundBinder: → foregroundMode($foregroundMode)")
            engine.onChangeMode(foregroundMode)
            currentMode = foregroundMode
            // 立即恢复前台 surveillance 档
            surveillance?.setProfile(foregroundProfile)
            // 1.5s 内快速识别"挂起期间是否被系统/对端关掉了":
            //  - onChangeMode 已经立即发了一次心跳(看 V2JavaWebEngine.onChangeMode 路径)
            //  - 这里再调 makeConnection() 让 ProgressiveAutoConnect 立即拉起重连(若已断)
            //  - 真活着的连接两路都是 no-op,所以无副作用
            try {
                engine.makeConnection()
            } catch (e: Exception) {
                Log.e(TAG, "EngineForegroundBinder.onStart makeConnection 异常", e)
            }
        }

        override fun onStop(owner: LifecycleOwner) {
            // 进后台: 不立即切, 排个防抖任务
            mainHandler.removeCallbacks(applyBackgroundModeTask)
            Log.i(TAG,"EngineForegroundBinder: 进后台, 排 ${backgroundDelayMs}ms 防抖任务")
            mainHandler.postDelayed(applyBackgroundModeTask, backgroundDelayMs)
        }
    }

    /**
     * 开始监听。`addObserver` 会自动让 observer "追上"当前 lifecycle 状态,
     * 因此前台时 attach 会立即触发 [observer] 的 onStart → engine 同步到 foregroundMode
     */
    fun attach() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(observer)
    }

    fun detach() {
        ProcessLifecycleOwner.get().lifecycle.removeObserver(observer)
        // 必须取消 pending 任务, 否则 Activity 销毁后 30s 仍可能切 BG 模式触及空 engine
        mainHandler.removeCallbacks(applyBackgroundModeTask)
    }
}
