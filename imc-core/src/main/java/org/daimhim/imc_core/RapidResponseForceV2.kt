package org.daimhim.imc_core

import timber.multiplatform.log.Timber
import java.lang.ref.WeakReference
import java.util.*
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.Comparator

class RapidResponseForceV2(
    private val MAX_TIMEOUT_TIME: Long = 5 * 1000,
    private val groupId: String = makeOnlyId(),
) {
    companion object{
        private val operationTasksQueue = LinkedBlockingQueue<Pair<Int,WrapOrderState>>()
        private val RRF_COMPARATOR = Comparator<WrapOrderState>{
                o1,o2->  (o1.timeOut - o1.accumulatedTime).compareTo((o2.timeOut - o2.accumulatedTime))
        }
        private var lastOnlyId  = System.currentTimeMillis()
        @Synchronized
        private fun makeOnlyId():String{
            var nowLastOnlyId = System.currentTimeMillis()
            if (lastOnlyId == nowLastOnlyId) {
                nowLastOnlyId += 1
            }
            lastOnlyId = nowLastOnlyId
            return lastOnlyId.toString()
        }
        private val MAXIMUM_IDLE_TIME = 25 * 1000L

        /**
         * Action 增、删、改、查
         */
        private val RRF_INCREASE = 0
        private val RRF_DELETE = 1
        private val RRF_MODIFY = 2
        private val RRF_QUERY = 3
        //超时回调队列
        private val timeoutCallbackMap = mutableMapOf<String,WeakReference<Comparable<Pair<String,Any?>>>>()
        private val syncRRF = Any()

        /**
         * 核心线程是否正在运行
         */
        private var isRun = false
        /**
         * 核心线程，主要用来遍历执行队列
         */
        private val powerTrainRunnable = PowerTrainRunnable()
        private fun startProcessor(){
            synchronized(syncRRF){
                if (isRun){
                    return
                }
                Thread(powerTrainRunnable).start()
            }
        }
    }
    fun register(id: String, t: Any? = null, timeOut: Long = MAX_TIMEOUT_TIME){
        Timber.i("register id:${id}")
        operationTasksQueue.add(RRF_INCREASE to WrapOrderState(groupId,id,t,timeOut))
        startProcessor()
    }
    fun unRegister(id: String) {
        Timber.i("unRegister id:${id}")
        operationTasksQueue.add(RRF_DELETE to WrapOrderState(groupId = groupId, childId = id))
        startProcessor()
    }

    fun timeoutCallback(call: Comparable<Pair<String,Any?>>) {
        synchronized(syncRRF) {
            timeoutCallbackMap.put(groupId, WeakReference(call))
        }
    }
    class WrapOrderState(
        val groupId: String,
        val childId: String,
        val any: Any? = null,
        val timeOut: Long = 0L,
        var accumulatedTime: Long = 0L,
    ){
        override fun toString(): String {
            return "groupId:${groupId}" +
                    "childId:${childId}" +
                    "any:${any}" +
                    "timeOut:${timeOut}" +
                    "accumulatedTime:${accumulatedTime}"
        }
    }

    private class PowerTrainRunnable : Runnable{

        private val taskToBeExecuted = mutableListOf<WrapOrderState>()

        override fun run() {
            synchronized(syncRRF){
                isRun = true
            }
            var localWaitingTime = MAXIMUM_IDLE_TIME
            var statePair:Pair<Int,WrapOrderState>?
            // 上次时间
            var recently = System.currentTimeMillis()
            // 当前时间
            var current: Long
            // 上次到本次时间
            var lastInterval = 0L
            // 单项剩余时间
            var remainingTime = 0L
            while (true){
                Timber.i("是否使用全局休眠时间 ${operationTasksQueue.isEmpty() && taskToBeExecuted.isEmpty()}")
                // 没有操作 没有任务 使用全局休眠时间
                if (operationTasksQueue.isEmpty() && taskToBeExecuted.isEmpty()){
                    localWaitingTime = MAXIMUM_IDLE_TIME
                }

                Timber.i("执行取操作,等待${localWaitingTime}")
                // 取操作
                statePair = operationTasksQueue.poll(localWaitingTime,TimeUnit.MILLISECONDS)
                Timber.i("取操作执行结果 ${statePair?.first} statePair&&taskToBeExecuted ${statePair == null && taskToBeExecuted.isEmpty()}")
                if (statePair == null && taskToBeExecuted.isEmpty()){
                    break
                }
                when(statePair?.first){
                    RRF_INCREASE->{ // 增加
                        taskToBeExecuted.add(statePair.second)
                    }
                    RRF_DELETE->{ //删除
                        val filterIndexed = taskToBeExecuted.filter { wrapOrderState ->
                            wrapOrderState.groupId == statePair.second.groupId
                                    && wrapOrderState.childId == statePair.second.childId
                        }
                        taskToBeExecuted.removeAll(filterIndexed)
                    }
                }
                // 操作已经完成
                if (operationTasksQueue.isNotEmpty()){
                    continue
                }
                // 没有待处理的数据
                if (taskToBeExecuted.isEmpty()){
                    continue
                }
                taskToBeExecuted.sortWith(RRF_COMPARATOR)

                current = System.currentTimeMillis()
                // 上次与本次间隔
                lastInterval = Math.abs(current - recently)
                lastInterval = if (lastInterval < 0) 0 else lastInterval
                // 过滤待删除
                val deleteWOSs = taskToBeExecuted.filter { orderState ->
                    orderState.accumulatedTime += lastInterval
                    remainingTime = orderState.timeOut - orderState.accumulatedTime
                    if (remainingTime <= 0) { // 已经超时
                        return@filter true
                    }
                    if (remainingTime < localWaitingTime) { // 最接近结束 记录它
                        localWaitingTime = remainingTime
                    }
                    return@filter false
                }
                Timber.i("删除超时任务${Arrays.toString(deleteWOSs.toTypedArray())}")
                //从来源删除
                taskToBeExecuted.removeAll(deleteWOSs)
                synchronized(syncRRF) {
                    for (deleteWOS in deleteWOSs) {
                        try {
                            val get = timeoutCallbackMap[deleteWOS.groupId]?.get()
                            if (get == null){
                                timeoutCallbackMap.remove(deleteWOS.groupId)
                                continue
                            }
                            get.compareTo(deleteWOS.childId to deleteWOS.any)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
                // 记录本次时间
                recently = System.currentTimeMillis()
            }
            synchronized(syncRRF){
                isRun = false
            }
        }
    }
}