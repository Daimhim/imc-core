package org.daimhim.imc_core.demo

import android.app.Application
import com.kongqw.network.monitor.NetworkMonitorManager
import org.daimhim.container.ContextHelper
import timber.multiplatform.log.DebugTree
import timber.multiplatform.log.Timber

class StartApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Timber.plant(DebugTree())
        ContextHelper.init(this)
        // 本地持久化日志:埋点 + SDK 内部日志都落 getExternalFilesDir/imclog,供事后 adb pull 排查
        FileLogger.init(this)
        // ImcEvents → LogStore/FileLogger 桥:订阅 SDK 结构化事件,在 demo 视图与日志文件还原"以前的 SDK 日志"体感
        ImcEventLogBridge.attach()
        NetworkMonitorManager.getInstance().init(this)
        // 前后台监听改用 androidx.lifecycle.ProcessLifecycleOwner, 不再需要手搓 FullLifecycleHandler
    }
}