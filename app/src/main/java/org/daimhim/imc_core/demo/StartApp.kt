package org.daimhim.imc_core.demo

import android.app.Application
import com.kongqw.network.monitor.NetworkMonitorManager
import timber.multiplatform.log.DebugTree
import timber.multiplatform.log.Timber

class StartApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Timber.plant(DebugTree())
        NetworkMonitorManager.getInstance().init(this)
        registerActivityLifecycleCallbacks(FullLifecycleHandler())
    }

}