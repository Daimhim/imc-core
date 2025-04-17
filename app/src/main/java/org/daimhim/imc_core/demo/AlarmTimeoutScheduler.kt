package org.daimhim.imc_core.demo

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import org.daimhim.container.ContextHelper
import org.daimhim.imc_core.ITimeoutScheduler
import timber.multiplatform.log.Timber
import java.util.concurrent.Callable
import kotlin.concurrent.thread

class AlarmTimeoutScheduler : ITimeoutScheduler {
    private var pendingIntent: PendingIntent
    companion object {
        const val ALARM_TIMEOUT_ACTION = "org.daimhim.imc.action.ALARM_TIMEOUT_ACTION"
    }
    init {
        // 创建 PendingIntent
        val intent = Intent(ALARM_TIMEOUT_ACTION).apply {
            `package` = ContextHelper.getApplication().packageName
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
        Timber.i("AlarmTimeoutScheduler.start")
        synchronized(sync){
            isStop = false
            val triggerTime = SystemClock.elapsedRealtime() + time
            val alarmManager = ContextHelper
                .getApplication()
                .getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.setExact(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerTime,
                pendingIntent
            )
            AlarmTimeoutBroadcastReceiver.registerSub(ALARM_TIMEOUT_ACTION,call)
        }
    }

    override fun stop() {
        Timber.i("AlarmTimeoutScheduler.stop")
        synchronized(sync){
            if (isStop) return
            isStop = true
            (ContextHelper
                .getApplication()
                .getSystemService(Context.ALARM_SERVICE) as AlarmManager)
                .cancel(pendingIntent)
            AlarmTimeoutBroadcastReceiver.unregisterSub(ALARM_TIMEOUT_ACTION)
        }
    }
    private var call : Callable<Void>? = null
    override fun setCallback(call: Callable<Void>) {
        this.call = call
    }


    class AlarmTimeoutBroadcastReceiver : BroadcastReceiver() {
        companion object{
            private val subMap = mutableMapOf<String, Callable<Void>>()
            fun registerSub(key:String, call: Callable<Void>?){
                subMap[key] = call?:return
            }
            fun unregisterSub(key:String){
                subMap.remove(key)
            }
        }
        override fun onReceive(context: Context?, intent: Intent?) {
            Timber.i("AlarmTimeoutScheduler.onReceive ${intent?.action}")
            try {
                subMap[intent?.action]?.call()
            }catch (e: Exception){
                Timber.e(e)
            }
        }
    }
}