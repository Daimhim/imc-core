package org.daimhim.imc_core


/**
 * 计时回调工具
 *
 * 支持多个任务同时计时，内部仅以"距离最近的截止时间"为一次 wait，节省线程与系统资源：
 *  - 整个实例只有一个守护线程，每轮只 wait 当前最早的截止时间
 *  - register / unregister / clear 会唤醒线程重排调度
 *  - 全部任务处理完后线程自动退出，下次 register 时按需重启
 *  - 回调在内部线程触发；不要在回调里做长耗时阻塞操作
 *  - 回调脱离锁触发，可以安全地在回调里 register / unregister 同一实例
 *
 * 与 [RapidResponseForce] / [RapidResponseForceV2] / [RapidResponseForceV3] 的区别：
 *  - 状态全部实例隔离，没有 static 共享 map（多实例同 id 不会互相覆盖）
 *  - 回调改为 per-task lambda，去掉了 groupId + Comparable<Pair<...>> 的 hack
 *  - 不依赖 ExecutorService / PriorityBlockingQueue，单线程裸 wait/notify 实现
 */
class RapidResponseForceV4(
    private val MAX_TIMEOUT_TIME: Long = 5 * 1000,
) {
    companion object{
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
    }
    private val tasks = HashMap<String, Task>()
    private val lock = Object()

    @Volatile
    private var workerThread: Thread? = null

    /**
     * 注册一个计时任务。若同名任务已存在，将覆盖旧的截止时间和回调
     *
     * @param id         任务唯一标识
     * @param timeoutMs  超时时间（毫秒）
     * @param onTimeout  超时回调，参数为本任务 id
     */
    fun register(id: String, timeoutMs: Long = MAX_TIMEOUT_TIME, onTimeout: (id: String) -> Unit) {
        synchronized(lock) {
            tasks[id] = Task(id, System.currentTimeMillis() + timeoutMs, onTimeout)
            ensureWorker()
            lock.notifyAll()
        }
    }

    /**
     * 注销指定任务
     * @return 任务存在并被移除返回 true；已超时或不存在返回 false
     */
    fun unregister(id: String): Boolean {
        synchronized(lock) {
            val removed = tasks.remove(id) != null
            if (removed) lock.notifyAll()
            return removed
        }
    }

    /**
     * 清空所有任务
     */
    fun clear() {
        synchronized(lock) {
            if (tasks.isEmpty()) return
            tasks.clear()
            lock.notifyAll()
        }
    }

    /**
     * 当前待计时任务数
     */
    fun size(): Int = synchronized(lock) { tasks.size }

    /**
     * 工作线程是否在运行（调试用）
     */
    fun isRunning(): Boolean = workerThread?.isAlive == true

    private fun ensureWorker() {
        val current = workerThread
        if (current != null && current.isAlive) return
        workerThread = Thread(::workerLoop, "RapidResponseForceV4-${hashCode()}").apply {
            isDaemon = true
            start()
        }
    }

    private fun workerLoop() {
        while (true) {
            // 1. 收割所有已到期任务
            val expired = ArrayList<Task>()
            synchronized(lock) {
                val now = System.currentTimeMillis()
                val iter = tasks.entries.iterator()
                while (iter.hasNext()) {
                    val task = iter.next().value
                    if (task.deadline <= now) {
                        expired += task
                        iter.remove()
                    }
                }
            }

            // 2. 锁外触发回调，允许回调里 register / unregister 同实例
            for (task in expired) {
                try {
                    task.onTimeout(task.id)
                } catch (e: Exception) {
                    IMCLog.e(e, "RapidResponseForceV4 onTimeout id=${task.id}")
                }
            }

            // 3. 计算下一次最近的截止时间并 wait；任务空了就退出
            synchronized(lock) {
                if (tasks.isEmpty()) {
                    workerThread = null
                    return
                }
                val now = System.currentTimeMillis()
                var nearest = Long.MAX_VALUE
                for (task in tasks.values) {
                    val remaining = task.deadline - now
                    if (remaining < nearest) nearest = remaining
                }
                if (nearest > 0L) {
                    try {
                        lock.wait(nearest)
                    } catch (e: InterruptedException) {
                        workerThread = null
                        return
                    }
                }
                // nearest <= 0：循环顶部立即再收割一次，不必 wait
            }
        }
    }

    private class Task(
        val id: String,
        val deadline: Long,
        val onTimeout: (id: String) -> Unit
    )
}
