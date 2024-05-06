package org.daimhim.imc_core

import java.lang.ref.WeakReference
import java.util.*
import java.util.concurrent.*
import kotlin.Comparator

class RapidResponseForceV2(
    private val MAX_TIMEOUT_TIME: Long = 5 * 1000,
    private val groupId: String = makeOnlyId(),
    private val executors: ExecutorService = Executors.newCachedThreadPool()
) {
    companion object{
        private val operationTasksQueue = LinkedBlockingQueue<Pair<Int,WrapOrderState>>()
        private val RRF_COMPARATOR = Comparator<WrapOrderState>{
                o1,o2->  (o1.timeOut - o1.accumulatedTime).compareTo((o2.timeOut - o2.accumulatedTime))
        }
        private var lastOnlyId  = System.currentTimeMillis()
        @Synchronized
        fun makeOnlyId():String{
            var nowLastOnlyId = System.currentTimeMillis()
            if (lastOnlyId <= nowLastOnlyId) {
                nowLastOnlyId = lastOnlyId + 1
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
        private val RRF_DELETE_QUERY = 3
        //超时回调队列
        private val timeoutCallbackMap = mutableMapOf<String,WeakReference<Comparable<Pair<String,Any?>>>>()
        // 取消回调队列
        private val cancelCallbackMap = mutableMapOf<String,WeakReference<Comparable<Pair<String,Any?>>>>()
        private val syncRRF = Any()

        /**
         * 核心线程是否正在运行
         */
        private var isRun = false
        /**
         * 核心线程，主要用来遍历执行队列
         */
        private val powerTrainRunnable = PowerTrainRunnable()
        @Synchronized
        private fun startProcessor(){
            if (isRun){
                return
            }
            isRun = true
            Thread(powerTrainRunnable).start()
        }
    }
    fun register(id: String, t: Any? = null, timeOut: Long = MAX_TIMEOUT_TIME){
        synchronized(syncRRF){
            IMCLog.i("register id:${id}")
            operationTasksQueue.add(RRF_INCREASE to WrapOrderState(groupId = groupId,id,t,timeOut))
            startProcessor()
        }
    }
    fun unRegister(id: String) {
        synchronized(syncRRF) {
            IMCLog.i("unRegister id:${id}")
            operationTasksQueue.add(RRF_DELETE to WrapOrderState(groupId = groupId, childId = id))
            startProcessor()
        }
    }

    /**
     * 解除并获取
     */
    fun unRegisterAndGet(id: String,call: Comparable<Pair<String,Any?>>) {
        synchronized(syncRRF) {
            operationTasksQueue.add(RRF_DELETE_QUERY to WrapOrderState(
                groupId = groupId,
                childId = id,
                any = object : Comparable<Pair<String,Any?>>{
                override fun compareTo(other: Pair<String, Any?>): Int {
                    return executors.submit(object : Callable<Int>{
                        override fun call(): Int {
                            return call.compareTo(other)
                        }
                    }).get()
                }
            }))
        }
    }

    /**
     * @param call 超时的返回
     *  Pair<String,Any> == id: String, t: Any? = null
     */
    fun timeoutCallback(call: Comparable<Pair<String,Any?>>?) {
        synchronized(syncRRF) {
            if (call == null){
                timeoutCallbackMap.remove(groupId)
                return@synchronized
            }
            val wrCall = WeakReference(call)
            timeoutCallbackMap.put(groupId, WeakReference(object : Comparable<Pair<String,Any?>>{
                override fun compareTo(other: Pair<String, Any?>): Int {
                    // get() 非必须
                    return executors.submit(object : Callable<Int>{
                        override fun call(): Int {
                            return wrCall.get()?.compareTo(other)?:0
                        }
                    }).get()
                }
            }))
        }
    }

    /**
     * @param call 取消的返回
     *  Pair<String,Any> == id: String, t: Any? = null
     */
    fun cancelCallbackMap(call: Comparable<Pair<String,Any?>>?) {
        synchronized(syncRRF) {
            if (call == null){
                timeoutCallbackMap.remove(groupId)
                return@synchronized
            }
            val wrCall = WeakReference(call)
            cancelCallbackMap.put(groupId, WeakReference(object : Comparable<Pair<String,Any?>>{
                override fun compareTo(other: Pair<String, Any?>): Int {
                    // get() 非必须
                    return executors.submit(object : Callable<Int>{
                        override fun call(): Int {
                            return wrCall.get()?.compareTo(other)?:0
                        }
                    }).get()
                }
            }))
        }
    }

    fun testIsRun() = synchronized(syncRRF){ isRun }

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
                operationTasksQueue.forEach {
                    IMCLog.i("当前项目：${it.first}")
                }
                // 没有操作 没有任务 使用全局休眠时间
//                if (operationTasksQueue.isEmpty() && taskToBeExecuted.isEmpty()){
//                    localWaitingTime = MAXIMUM_IDLE_TIME
//                }
                IMCLog.i("当前操作队列：${operationTasksQueue.size} 当前待执行任务：${taskToBeExecuted.size} 当前休眠时间:${localWaitingTime}")
                // 取操作
                statePair = operationTasksQueue.poll(localWaitingTime,TimeUnit.MILLISECONDS)
                IMCLog.i("取操作执行结果 first:${if (statePair?.first == RRF_INCREASE) "add" else "del"} childId:${statePair?.second?.childId} statePair&&taskToBeExecuted taskToBeExecuted.size：${taskToBeExecuted.size} ${statePair == null && taskToBeExecuted.isEmpty()}")
                if (statePair == null && taskToBeExecuted.isEmpty()){
                    break
                }
                when(statePair?.first){
                    RRF_INCREASE->{ // 增加
                        taskToBeExecuted.add(statePair.second)
                    }
                    RRF_DELETE->{
                        //删除
                        var target : WrapOrderState? = null
                        // 移除回调
                        val filterIndexed = taskToBeExecuted.filter { wrapOrderState ->
                            wrapOrderState.groupId == statePair.second.groupId
                                    && wrapOrderState.childId == statePair.second.childId
                        }
                        taskToBeExecuted.removeAll(filterIndexed)
                        for (cancelWOS in filterIndexed) {
                            target = cancelWOS
                            try {
                                val get = cancelCallbackMap[cancelWOS.groupId]?.get()
                                if (get == null){
                                    cancelCallbackMap.remove(cancelWOS.groupId)
                                    continue
                                }
                                IMCLog.i("deleteWOS ${cancelWOS.childId}")
                                get.compareTo(cancelWOS.childId to cancelWOS.any)
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                        val comparable = statePair.second.any as Comparable<Pair<String, Any?>>?
                        comparable?.compareTo(statePair.second.childId to target)
                    }
                    RRF_DELETE_QUERY->{ // 查询并删除
                        var target : WrapOrderState? = null
                        val iterator = operationTasksQueue.iterator()
                        var item : Pair<Int,WrapOrderState>
                        while (iterator.hasNext()){
                            item = iterator.next()
                            if (statePair.second.groupId != item.second.groupId
                                || statePair.second.childId != item.second.childId){
                                continue
                            }
                            iterator.remove()
                            if (item.first == RRF_INCREASE){
                                target = item.second
                            }
                        }
                        // 移除回调
                        val filterIndexed = taskToBeExecuted.filter { wrapOrderState ->
                            wrapOrderState.groupId == statePair.second.groupId
                                    && wrapOrderState.childId == statePair.second.childId
                        }
                        taskToBeExecuted.removeAll(filterIndexed)
                        for (cancelWOS in filterIndexed) {
                            target = cancelWOS
                            try {
                                val get = cancelCallbackMap[cancelWOS.groupId]?.get()
                                if (get == null){
                                    cancelCallbackMap.remove(cancelWOS.groupId)
                                    continue
                                }
                                IMCLog.i("deleteWOS ${cancelWOS.childId}")
                                get.compareTo(cancelWOS.childId to cancelWOS.any)
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                        val comparable = statePair.second.any as Comparable<Pair<String, Any?>>?
                        comparable?.compareTo(statePair.second.childId to target)
                    }
                }
                // 操作未完成
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
                localWaitingTime = MAXIMUM_IDLE_TIME
                // 过滤待删除
                val deleteWOSs = taskToBeExecuted.filter { orderState ->
                    IMCLog.i("当前任务队列：${orderState}")
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
                IMCLog.i("删除超时任务${Arrays.toString(deleteWOSs.toTypedArray())} 当前休眠时间：${localWaitingTime}")
                for (deleteWOS in deleteWOSs) {
                    try {
                        val get = timeoutCallbackMap[deleteWOS.groupId]?.get()
                        if (get == null){
                            timeoutCallbackMap.remove(deleteWOS.groupId)
                            continue
                        }
                        IMCLog.i("deleteWOS ${deleteWOS.childId}")

                        get.compareTo(deleteWOS.childId to deleteWOS.any)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                //从来源删除
                taskToBeExecuted.removeAll(deleteWOSs)
                // 记录本次时间
                recently = System.currentTimeMillis()
            }
            synchronized(syncRRF){
                isRun = false
            }
        }
    }
}