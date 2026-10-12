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
 * 与历史 V0/V2 实现(已删除)的区别：
 *  - 状态全部实例隔离，没有 static 共享 map（多实例同 id 不会互相覆盖）
 *  - 回调改为 per-task lambda，去掉了 groupId + Comparable<Pair<...>> 的 hack
 *  - 不依赖 ExecutorService / PriorityBlockingQueue，单线程裸 wait/notify 实现
 */
class RapidResponseForceV4(
    private val MAX_TIMEOUT_TIME: Long = 5 * 1000,
    /**
     * 计时时钟(毫秒,只用差值)。默认 [nowMs](nanoTime)。
     *
     * Android 上 nanoTime 是 CLOCK_MONOTONIC,**设备休眠期间不走**:冻结前挂的 15s 看门狗,
     * 解冻后还要再等剩余时长。注入 SystemClock.elapsedRealtime(含休眠)后,到期判定按含休眠的时长算;
     * 但 worker 的 `wait` 本身仍按单调时钟计时,醒来时机要靠 [maxWaitSliceMs] 兜。
     */
    private val clock: () -> Long = { nowMs() },
    /**
     * 单次 wait 的上限(毫秒),0 = 不限(一次 wait 到最近截止)。
     *
     * `Object.wait` 在 Android 上按 CLOCK_MONOTONIC 计时,设备深睡期间不走:即便 [clock] 注入了
     * elapsedRealtime,worker 也要再 wait 完剩余的单调时长才会醒来重新判定。设了上限后,解冻后
     * 至多晚这么久就能按 [clock] 判到期。只给短命的看门狗用 —— 有任务挂着期间 worker 每隔这么久
     * 醒一次,常驻任务(如心跳)不要设。
     */
    private val maxWaitSliceMs: Long = 0L,
) {
    companion object{
        /**
         * 计时用单调时钟(毫秒)。
         *
         * #9:计时只关心「过了多久」,不能用墙钟 —— NTP 校时 / 手动改时间会让墙钟跳变:
         * 往前跳,所有任务瞬间集体到期、批量误触发;往后跳,一段时间内谁都不触发。
         * [System.nanoTime] 由 JVM 保证单调且只有差值有意义,正好匹配这里的用途。
         * (任务的对外 timestamp 字段仍用墙钟,那是给人看的时间点。)
         */
        internal fun nowMs(): Long = System.nanoTime() / 1_000_000L

        /** 看门狗类短命任务的 [maxWaitSliceMs]:设备解冻后至多晚 1s 判到期 */
        internal const val WATCHDOG_WAIT_SLICE_MS = 1_000L

        private var lastOnlyId  = System.currentTimeMillis()
        @Synchronized
        internal fun makeOnlyId():String{
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
     * worker 是否在工作(精确语义,不依赖 [Thread.isAlive])。
     *
     * 之前 [ensureWorker] 用 `workerThread.isAlive` 判存活,JVM 在 worker.run() 已 return
     * 但线程对象尚未标记 dead 的微小窗口里,isAlive 仍为 true → 会让 ensureWorker 误以为
     * "worker 还在,不必重启",而旧 worker 又即将 die,新任务永远无人处理(本次诊断中
     * 握手 watchdog 12 分钟沉默的可疑根因之一)。
     *
     * 改用 volatile flag,且 worker 退出时**在 `lock` synchronized 内**置 false ——
     * 与 [ensureWorker] 在 register 锁内读取配对,完全消除 isAlive race 窗口。
     */
    @Volatile
    private var workerRunning = false

    /**
     * 注册一个计时任务。若同名任务已存在，将覆盖旧的截止时间和回调
     *
     * @param id         任务唯一标识
     * @param timeoutMs  超时时间（毫秒）
     * @param onTimeout  超时回调，参数为本任务 id
     */
    fun register(id: String, timeoutMs: Long = MAX_TIMEOUT_TIME, onTimeout: (id: String) -> Unit) {
        synchronized(lock) {
            tasks[id] = Task(id, clock() + timeoutMs, onTimeout)
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
    internal fun isRunning(): Boolean = workerRunning

    private fun ensureWorker() {
        // 调用方 ([register]) 必持 lock,所以读 workerRunning 跟 worker 退出时锁内置 false 配对,无 race
        if (workerRunning) return
        workerRunning = true
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
                val now = clock()
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
                    ImcEvents.emit(ImcEvent.InternalError(
                        site = "RapidResponseForceV4.onTimeout",
                        errorClass = e.javaClass.simpleName,
                        message = "${e.message} (taskId=${task.id})",
                    ))
                }
            }

            // 3. 计算下一次最近的截止时间并 wait；任务空了就退出。
            //    workerRunning = false 必须在 lock 内置,与 [ensureWorker] 锁内读 workerRunning
            //    配对 —— 若锁外置 false,worker 退出锁后到真置 false 之间会有 race:register
            //    抢锁看 workerRunning=true 不起新线程,而旧 worker 已经走完即将退出。
            synchronized(lock) {
                if (tasks.isEmpty()) {
                    workerRunning = false
                    workerThread = null
                    return
                }
                val now = clock()
                var nearest = Long.MAX_VALUE
                for (task in tasks.values) {
                    val remaining = task.deadline - now
                    if (remaining < nearest) nearest = remaining
                }
                if (nearest > 0L) {
                    try {
                        lock.wait(if (maxWaitSliceMs > 0) minOf(nearest, maxWaitSliceMs) else nearest)
                    } catch (e: InterruptedException) {
                        workerRunning = false
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
