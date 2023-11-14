package org.daimhim.imc_core

import timber.multiplatform.log.Timber


/**
 * 快速超时响应队列
 */
class RapidResponseForce<T : Any>(
    private val MAX_TIMEOUT_TIME: Long = 5 * 1000,
    private val groupId: String = System.nanoTime().toString(),
) {
    companion object {
        private var reviewThread: Thread? = null
        private val queuingFence = Object()
        private val reviewRunnable = ReviewRunnable()
    }

    fun register(id: String, t: T) {
        register(id, t,MAX_TIMEOUT_TIME)
    }

    fun register(id: String, t: T, timeOut: Long) {
        Timber.i("register id:$id t:$t timeOut:$timeOut")
        return synchronized(queuingFence) {
            reviewRunnable.register(groupId, id, WrapOrderState(groupId = groupId, t = t, timeOut = timeOut))
            startOrNotify()
        }
    }

    fun unRegister(id: String): T? {
        Timber.i("unRegister id:$id")
        return synchronized(queuingFence) {
            val unRegister = reviewRunnable.unRegister(groupId, id)
            startOrNotify()
            unRegister?.t as T?
        }

    }

    private fun startOrNotify() {
        synchronized(queuingFence) {
            if (reviewThread == null) {
                reviewThread = Thread(reviewRunnable)
                reviewThread?.start()
                return
            }
            queuingFence.notify()
        }
    }

    fun timeoutCallback(call: ((List<T>?) -> Unit)?) {
        if (call == null) {
            reviewRunnable.timeoutCallback(groupId = groupId, call = null)
            return
        }
        reviewRunnable.timeoutCallback(groupId = groupId) {
            call.invoke(it as List<T>)
        }
    }

    class WrapOrderState<T>(
        val groupId: String,
        val t: T,
        val timeOut: Long = 0L,
        var integrationTime: Long = 0L,
    )

    private class ReviewRunnable : Runnable {
        // 等待队列
        private val waitingReaction = mutableMapOf<String, MutableMap<String, WrapOrderState<*>>>()

        //超时回调队列
        private val timeoutCallbackMap = mutableMapOf<String, ((List<*>) -> Unit)>()
        private val pendingQueue = mutableListOf<Runnable>()
        fun register(groupId: String, id: String, wrapOrderState: WrapOrderState<*>) {
            val mutableMap = waitingReaction[groupId] ?: mutableMapOf()
            mutableMap.put(id, wrapOrderState)
            waitingReaction[groupId] = mutableMap
        }

        fun unRegister(groupId: String, id: String): WrapOrderState<*>? {
            val remove = waitingReaction[groupId]?.remove(id)
            if (remove != null && waitingReaction[groupId]?.isEmpty() == true) {
                waitingReaction.remove(groupId)
            }
            return remove

        }


        fun timeoutCallback(groupId: String, call: ((List<*>) -> Unit)?) {
            if (call == null) {
                timeoutCallbackMap.remove(groupId)
                return
            }
            timeoutCallbackMap.put(groupId, call)
        }

        override fun run() {
            synchronized(queuingFence) {
                // 上次时间
                var recently = System.currentTimeMillis()
                // 本次最接近
                var closest = 0L
                // 上次到本次时间
                var lastInterval = 0L
                // 当前项目累计时间
                var currentItemCumulativeTime = 0L
//                Timber.i("----- waitingReaction 开始了")
                while (waitingReaction.isNotEmpty()) {
//                    Timber.i("----- waitingReaction isNotEmpty")
                    // 当前时间
                    val current = System.currentTimeMillis()
                    // 上次与本次间隔
                    lastInterval = current - recently
                    // 最接近超时的
                    closest = 0L
                    //超时队列
                    val timeoutMap = mutableMapOf<String, MutableList<WrapOrderState<*>>>()
                    var wrapOrderStates: MutableList<WrapOrderState<*>>
                    // 遍历组
                    val groupIterator = waitingReaction.iterator()
                    var iterator: MutableIterator<MutableMap.MutableEntry<String, WrapOrderState<*>>>
                    var groupNext: MutableMap.MutableEntry<String, MutableMap<String, WrapOrderState<*>>>
                    var next: MutableMap.MutableEntry<String, WrapOrderState<*>>
                    while (groupIterator.hasNext()) {
                        // 遍历组内容
                        groupNext = groupIterator.next()
                        iterator = groupNext.value.iterator()
                        while (iterator.hasNext()) {
                            next = iterator.next()
                            currentItemCumulativeTime = next.value.integrationTime + lastInterval
//                            Timber.i("-----组ID ${groupNext.key}消息ID：${next.key} 累计时间：${currentItemCumulativeTime} ${lastInterval}")
                            if (currentItemCumulativeTime >= next.value.timeOut) { //超时
                                wrapOrderStates = timeoutMap[next.value.groupId] ?: mutableListOf()
                                wrapOrderStates.add(next.value)
                                timeoutMap[next.value.groupId] = wrapOrderStates
                                iterator.remove()
                                continue
                            } else if (currentItemCumulativeTime >= closest) { // 距离超时最近
                                closest = next.value.timeOut - currentItemCumulativeTime
                            }
                            // 记录本次累加
                            next.value.integrationTime = currentItemCumulativeTime
                        }
                        if (groupNext.value.isEmpty()) {
                            groupIterator.remove()
                        }
                    }
                    //超时回调
                    timeoutMap.forEach { entry ->
//                        Timber.i("----- 超时回调 ${entry.key} ${entry.value.size}")
                        timeoutCallbackMap[entry.key]?.invoke(entry.value.map { it.t })
                    }
                    // 记录本次时间
                    recently = System.currentTimeMillis()
                    //等待最近的那个
                    if (closest <= 0L) {
                        closest = 25 * 1000L
                    }
//                    Timber.i("----- closest ${closest}")
                    queuingFence.wait(closest)
                }
                reviewThread = null
            }
//            Timber.i("----- waitingReaction 结束了")
        }
    }
}