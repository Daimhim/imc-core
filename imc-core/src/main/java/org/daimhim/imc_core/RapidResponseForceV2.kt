package org.daimhim.imc_core

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
        private val timeoutCallbackMap = mutableMapOf<String,Comparable<Pair<String,Any?>>>()
        // 取消回调队列
        private val cancelCallbackMap = mutableMapOf<String,Comparable<Pair<String,Any?>>>()
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

        private fun toAction(action: Int): String {
            return when (action) {
                RRF_INCREASE -> "插入"
                RRF_DELETE -> "删除等待列表"
                RRF_MODIFY -> "修改"
                RRF_DELETE_QUERY -> "查询并删除操作队列和等待列表"
                else -> "未知"
            }
        }
    }
    fun register(id: String, t: Any? = null, timeOut: Long = MAX_TIMEOUT_TIME){
        synchronized(syncRRF){
            IMCLog.i("注册:groupId:${groupId} id:${id}")
            operationTasksQueue.add(RRF_INCREASE to WrapOrderState(groupId = groupId,id,t,timeOut))
            startProcessor()
        }
    }
    fun unRegister(id: String) {
        synchronized(syncRRF) {
            IMCLog.i("注销:groupId:${groupId} id:${id}")
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
            timeoutCallbackMap.put(groupId, call)
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
            cancelCallbackMap.put(groupId, call)
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
                IMCLog.i("↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓当前待操作项目↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓")
                operationTasksQueue.forEach {
                    IMCLog.i("${toAction(it.first)} ${it.second.childId}")
                }
                IMCLog.i("↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑当前待操作项目 ${localWaitingTime} ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑")
                // 取操作
                statePair = operationTasksQueue.poll(localWaitingTime,TimeUnit.MILLISECONDS)
                if (statePair == null && taskToBeExecuted.isEmpty()){
                    break
                }

                IMCLog.i("↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓当前正在进行的操作↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓")
                IMCLog.i("${toAction(statePair?.first?:-1)} ${statePair?.second?.childId}")
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
                                cancelCallbackMap[cancelWOS.groupId]
                                    ?.compareTo(cancelWOS.childId to cancelWOS.any)
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                        val comparable = statePair.second.any as Comparable<Pair<String, Any?>>?
                        comparable?.compareTo(statePair.second.childId to target?.any)
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
                                cancelCallbackMap[cancelWOS.groupId]
                                    ?.compareTo(cancelWOS.childId to cancelWOS.any)
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                        val comparable = statePair.second.any as Comparable<Pair<String, Any?>>?
                        comparable?.compareTo(statePair.second.childId to target?.any)
                    }
                }
                IMCLog.i("↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑当前正在进行的操作↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑")
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
                IMCLog.i("↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓当前等待队列时间到要删除的↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓")
                for (deleteWOS in deleteWOSs) {
                    try {
                        timeoutCallbackMap[deleteWOS.groupId]
                            .also {
                                IMCLog.i("groupId:${deleteWOS.groupId} childId:${deleteWOS.childId} get:${it == null}")
                            }
                            ?.compareTo(deleteWOS.childId to deleteWOS.any)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                IMCLog.i("↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑当前等待队列时间到要删除的↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑")
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