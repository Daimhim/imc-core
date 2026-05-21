package org.daimhim.imc_core.demo

import android.os.Handler
import android.os.Looper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import org.daimhim.imc_core.IEngine
import org.daimhim.imc_core.IMCLog

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
class EngineForegroundBinder @JvmOverloads constructor(
    private val engine: IEngine,
    private val foregroundMode: Int,
    private val backgroundMode: Int,
    private val backgroundDelayMs: Long = DEFAULT_BACKGROUND_DELAY_MS,
) {

    companion object {
        /** WhatsApp / Signal / 微信下限风格,适合一般 IM */
        const val DEFAULT_BACKGROUND_DELAY_MS = 30_000L

        /** Telegram / 微信宽容档,适合高频短暂离开的场景 */
        const val TOLERANT_BACKGROUND_DELAY_MS = 60_000L
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private val applyBackgroundModeTask = Runnable {
        IMCLog.i("EngineForegroundBinder: ${backgroundDelayMs}ms 防抖到期, 切到 backgroundMode($backgroundMode)")
        engine.onChangeMode(backgroundMode)
    }

    private val observer = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            // 进前台: 取消可能 pending 的 BG 切换, 立即切 FG
            mainHandler.removeCallbacks(applyBackgroundModeTask)
            IMCLog.i("EngineForegroundBinder: → foregroundMode($foregroundMode)")
            engine.onChangeMode(foregroundMode)
            // 1.5s 内快速识别"挂起期间是否被系统/对端关掉了":
            //  - onChangeMode 已经立即发了一次心跳(看 V2JavaWebEngine.onChangeMode 路径)
            //  - 这里再调 makeConnection() 让 ProgressiveAutoConnect 立即拉起重连(若已断)
            //  - 真活着的连接两路都是 no-op,所以无副作用
            try {
                engine.makeConnection()
            } catch (e: Exception) {
                IMCLog.e(e, "EngineForegroundBinder.onStart makeConnection 异常")
            }
        }

        override fun onStop(owner: LifecycleOwner) {
            // 进后台: 不立即切, 排个防抖任务
            mainHandler.removeCallbacks(applyBackgroundModeTask)
            IMCLog.i("EngineForegroundBinder: 进后台, 排 ${backgroundDelayMs}ms 防抖任务")
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
