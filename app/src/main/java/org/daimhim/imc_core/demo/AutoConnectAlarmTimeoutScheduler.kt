package org.daimhim.imc_core.demo

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import org.daimhim.container.ContextHelper
import org.daimhim.imc_core.ITimeoutScheduler
import timber.multiplatform.log.Timber
import java.util.concurrent.Callable

class AutoConnectAlarmTimeoutScheduler(private val name:String) : ITimeoutScheduler {
    companion object {
        const val AUTO_CONNECT_ALARM_TIMEOUT_ACTION = "com.zjkj.im_core.action.AUTO_CONNECT_ALARM_TIMEOUT_ACTION"
    }
    private var pendingIntent:PendingIntent
    init {
        Timber.i("AlarmTimeoutScheduler.init $name")
        val intent = Intent(AUTO_CONNECT_ALARM_TIMEOUT_ACTION).apply {
            setPackage(ContextHelper.getApplication().packageName)
        }
        pendingIntent = PendingIntent.getBroadcast(
            ContextHelper.getApplication(),
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE
        )
    }

    private val sync = Any()
    private var isStop = false
    override fun start(time: Long) {
        Timber.i("AlarmTimeoutScheduler.start $name $time")
        synchronized(sync){
            isStop = false
            val triggerTime = SystemClock.elapsedRealtime() + time
            val alarmManager = ContextHelper
                .getApplication()
                .getSystemService(Context.ALARM_SERVICE) as AlarmManager
            AlarmTimeoutBroadcastReceiver.setCall(call,name)
            // setExact 在 Doze 下会被推迟到 maintenance window(深 Doze 可数小时一次),
            // 导致熄屏静止几小时后 WS 断了也不重连。setExactAndAllowWhileIdle 在 Doze 下仍允许
            // 触发(每 app 约 9 分钟限一次,对重连退避足够)。API 22 没 Doze,走 setExact。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerTime,
                    pendingIntent
                )
            } else {
                alarmManager.setExact(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerTime,
                    pendingIntent
                )
            }
        }
    }

    override fun stop() {
        Timber.i("AlarmTimeoutScheduler.stop $name")
        synchronized(sync){
            if (isStop) return
            isStop = true
            (ContextHelper
                .getApplication()
                .getSystemService(Context.ALARM_SERVICE) as AlarmManager)
                .cancel(pendingIntent)
            AlarmTimeoutBroadcastReceiver.setCall(null,name)
        }
    }
    private var call : Callable<Void>? = null
    override fun setCallback(call: Callable<Void>) {
        this.call = call
        AlarmTimeoutBroadcastReceiver.setCall(call,name)
    }


    class AlarmTimeoutBroadcastReceiver : BroadcastReceiver() {
        companion object{
            private var callable : Callable<Void>? = null
            private var bindName: String  = ""
            fun setCall(call:Callable<Void>?,name: String){
                this.callable = call
                this.bindName = name
            }
        }
        override fun onReceive(context: Context?, intent: Intent?) {
            Timber.i("AlarmTimeoutScheduler.onReceive $bindName")
            try {
                callable?.call()
            }catch (e: Exception){
                Timber.e(e)
            }
        }
    }
}