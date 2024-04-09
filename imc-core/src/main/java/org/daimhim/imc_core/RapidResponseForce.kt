package org.daimhim.imc_core

import timber.multiplatform.log.Timber
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.PriorityBlockingQueue

class RapidResponseForce<T : Any>(
    private val MAX_TIMEOUT_TIME: Long = 5 * 1000,
    private val groupId: String = makeOnlyId(),
) {
    companion object {
        private val priorityBlockingQueue = PriorityBlockingQueue<WrapOrderState<Any?>>(5
        ) { o1, o2 -> (o1.timeOut - o1.integrationTime).compareTo((o2.timeOut - o2.integrationTime)) }


        private val MAXIMUM_IDLE_TIME = 25 * 1000L
        private var reviewThread: Thread? = null
        /**
         * 核心线程，主要用来遍历执行队列
         */
        private val powerTrainRunnable = PowerTrainRunnable()
        //超时回调队列
        private val timeoutCallbackMap = ConcurrentHashMap<String, ((List<*>) -> Unit)>()
        private var lastOnlyId : Long ? = null
        @Synchronized
        private fun makeOnlyId():String{
            var nowLastOnlyId = System.currentTimeMillis()
            if (lastOnlyId == nowLastOnlyId) {
                nowLastOnlyId += 1
            }
            lastOnlyId = nowLastOnlyId
            return lastOnlyId.toString()
        }

        /**
         * 锁定 第三方线程调用并发
         */
        private val syncRRF = Object()

        @Synchronized
        private fun startTrainBoiler() {
            if (reviewThread == null) {
                reviewThread = Thread(powerTrainRunnable)
                reviewThread?.start()
                return
            }
            synchronized(syncRRF){
                syncRRF.notify()
            }
        }
    }
    fun register(id: String, t: T?, timeOut: Long = MAX_TIMEOUT_TIME){
//        if (!groupId.startsWith("AutoReconnect") && !groupId.startsWith("Heartbeat")) {
            Timber.i("register ${groupId} id:${id} timeOut:${timeOut}")
//        }
        priorityBlockingQueue.offer(WrapOrderState<Any?>(groupId,id,t,timeOut))
        startTrainBoiler()
    }

    fun unRegister(id: String,call:((T?)->Unit)? = null){
        call?.invoke(unRegister(id))
        startTrainBoiler()
    }

    fun unRegister(id: String):T?{
//        if (!groupId.startsWith("AutoReconnect") && !groupId.startsWith("Heartbeat")) {
            Timber.i("unRegister ${groupId} id:${id}")
//        }
        val register = getRegister(id)
        priorityBlockingQueue.remove(register)
        return (register?.t as T?).apply { startTrainBoiler() }
    }

    fun isRegister(id: String):T?{
        return (getRegister(id)?.t as T?).apply { startTrainBoiler() }
    }

    private fun getRegister(id: String):WrapOrderState<Any?>?{
        return priorityBlockingQueue.find { it.groupId == groupId && it.childId == id }
    }
    fun timeoutCallback(call: ((List<T>?) -> Unit)?){
        if (call == null){
            timeoutCallbackMap.remove(groupId)
        }else{
            timeoutCallbackMap.put(groupId){
                call.invoke(it as List<T>)
            }
        }
        startTrainBoiler()
    }

   private class WrapOrderState<T>(
       val groupId: String,
       val childId: String,
       val t: T?,
       val timeOut: Long = 0L,
       var integrationTime: Long = 0L,
   )

    private class PowerTrainRunnable : Runnable{
        override fun run() {
            var coolingTime = 0L // 用来记录 实际冷却时间
            // 本次最接近
            var closest = 0L
            // 上次到本次时间
            var lastInterval = 0L
            // 当前项目累计时间
            var currentItemCumulativeTime = 0L
            // 上次时间
            var recently = System.currentTimeMillis()
            // 当前时间
            var current: Long
            // 最接近超时的
            closest = 0L
            var calmDown = false
            //超时队列
            val timeoutMap = mutableMapOf<String, MutableSet<WrapOrderState<Any?>>>()
            var wrapOrderStates: MutableSet<WrapOrderState<Any?>>
            Timber.i("PowerTrainRunnable 初次启动")
            while (true){
                calmDown = false
                closest = 0L
                current = System.currentTimeMillis()
                // 上次与本次间隔
                lastInterval = Math.abs(current - recently)
                lastInterval = if (lastInterval < 0) 0 else lastInterval
                Timber.i("PowerTrainRunnable 让气氛热起来 lastInterval:${lastInterval} ${priorityBlockingQueue.size}")
                for (wrapOrderState in priorityBlockingQueue) {
                    // 上次累计时间 + 截至目前的经过时间
                    currentItemCumulativeTime = wrapOrderState.integrationTime + lastInterval
                    if (currentItemCumulativeTime >= wrapOrderState.timeOut) {// 已经超时
                        wrapOrderStates = timeoutMap[wrapOrderState.groupId] ?: mutableSetOf()
                        wrapOrderStates.add(wrapOrderState)
                        timeoutMap[wrapOrderState.groupId] = wrapOrderStates
                        continue
                    } else if (currentItemCumulativeTime >= closest) { // 距离超时最近
                        closest = wrapOrderState.timeOut - currentItemCumulativeTime
                    }
                    // 记录本次累加
                    wrapOrderState.integrationTime = currentItemCumulativeTime
                }
                //超时回调
                timeoutMap.forEach { entry ->
                    try {
                        // 从队列中删除超时的
                        priorityBlockingQueue.removeAll(entry.value)
                        // 回调超时的
                        timeoutCallbackMap[entry.key]?.invoke(entry.value.map {
                            Timber.i("PowerTrainRunnable 宾客退场:${it.groupId} ${it.childId}")
                            it.t
                        })
                    }catch (e:Exception){
                        e.printStackTrace()
                    }
                }
                timeoutMap.clear()
                // 记录本次时间
                recently = System.currentTimeMillis()
                coolingTime = closest
                if (priorityBlockingQueue.isEmpty()){
                    calmDown = true
                    coolingTime = MAXIMUM_IDLE_TIME
                }
                Timber.i("PowerTrainRunnable 冷静：almDown:${calmDown} ${priorityBlockingQueue.size} coolingTime:${coolingTime}")
                synchronized(syncRRF){
                    syncRRF.wait(coolingTime)
                }
                // 在25秒内无任务，跳出
                if (calmDown && priorityBlockingQueue.isEmpty()){
                    break
                }
            }
            Timber.i("PowerTrainRunnable 执行结束")
            reviewThread = null
        }
    }
}