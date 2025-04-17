package org.daimhim.imc_core.demo

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import org.daimhim.imc_core.ITimeoutScheduler
import timber.multiplatform.log.Timber
import java.util.concurrent.Callable

class AlarmTimeoutSchedulerTestActivity : AppCompatActivity() {
    private val alarmTimeoutScheduler = AlarmTimeoutScheduler()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_alarm_timeout_scheduler_test)

        alarmTimeoutScheduler.setCallback(object : Callable<Void> {
            override fun call(): Void? {
                // 执行回调
                Timber.i("AlarmTimeoutScheduler.setCallback")
                alarmTimeoutScheduler.start(15000)
                return null
            }

        })

        findViewById<View>(R.id.bt_start).setOnClickListener {
            alarmTimeoutScheduler.start(15000)
        }

    }
}