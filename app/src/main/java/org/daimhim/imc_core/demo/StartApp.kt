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
        NetworkMonitorManager.getInstance().init(this)
        // 前后台监听改用 androidx.lifecycle.ProcessLifecycleOwner, 不再需要手搓 FullLifecycleHandler
    }
}