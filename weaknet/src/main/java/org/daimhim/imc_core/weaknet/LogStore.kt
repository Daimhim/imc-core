package org.daimhim.imc_core.weaknet

import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 共享日志缓冲。
 *
 * - 全局单例,主页和全屏页都从这读
 * - 最新在前(index 0),最旧在后(LRU 淘汰)
 * - 上限 [CAP] 条,溢出丢尾
 * - 观察者模式:页面注册 listener 拿增量
 */
object LogStore {
    private const val CAP = 2000
    private val deque = ArrayDeque<String>(CAP)
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    private val lock = Any()

    fun append(line: String) {
        val ts = timeFmt.format(Date())
        val entry = "[$ts] $line"
        synchronized(lock) {
            deque.addFirst(entry)
            while (deque.size > CAP) deque.removeLast()
        }
        for (l in listeners) try { l() } catch (_: Exception) {}
    }

    fun snapshot(): List<String> = synchronized(lock) { ArrayList(deque) }

    fun snapshotFlat(): String {
        val sb = StringBuilder()
        synchronized(lock) {
            for (s in deque) { sb.append(s); sb.append('\n') }
        }
        return sb.toString()
    }

    fun clear() {
        synchronized(lock) { deque.clear() }
        for (l in listeners) try { l() } catch (_: Exception) {}
    }

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
}
