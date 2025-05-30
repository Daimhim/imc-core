package org.daimhim.imc_core.demo

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.contextaware.ContextAwareHelper
import com.kongqw.network.monitor.NetworkMonitorManager
import org.daimhim.container.ContextHelper
import org.daimhim.imc_core.IMCLog
import timber.multiplatform.log.DebugTree
import timber.multiplatform.log.Timber

class StartApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Timber.plant(DebugTree())
        ContextHelper.init(this)
//        SdtLogic.setCallBack(object : SdtLogic.ICallBack{
//            override fun reportSignalDetectResults(p0: String?) {
//                Timber.i("SDTUnitTest.reportSignalDetectResults:${p0}")
//            }
//        })
//
//        Mars.init(applicationContext, Handler(Looper.getMainLooper()))
//
//        SdtLogic.setHttpNetcheckCGI("https://58a4ad34.r15.cpolar.top")

        NetworkMonitorManager.getInstance().init(this)
        registerActivityLifecycleCallbacks(FullLifecycleHandler())
//        ContextHelper
    }

}