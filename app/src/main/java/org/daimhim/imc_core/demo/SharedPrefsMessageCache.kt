package org.daimhim.imc_core.demo

import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import org.daimhim.imc_core.CachedMessage
import org.daimhim.imc_core.IMessageCache
import org.daimhim.imc_core.decodeMessageCacheBlob
import org.daimhim.imc_core.encodeMessageCacheBlob

/**
 * Android SharedPreferences 实现的发送缓存。
 *
 * 整个队列序列化成二进制 blob → base64 文本,写在 SP 的一个 key 里。
 * SP 设计上就不适合塞 MB 级别的内容,所以默认 [maxBytes] 卡到 32KB,够 IM 短消息场景用。
 *
 * 进程重启时 [init] 块会自动从 SP 还原;解析失败 / owner 不匹配会清掉 key 自己恢复。
 *
 * 用法:
 * ```
 * val sp = getSharedPreferences("imc_cache", Context.MODE_PRIVATE)
 * val cache = SharedPrefsMessageCache(sp, owner = currentAccountId)
 * engine = V2JavaWebEngine.Builder()
 *     .setMessageCache(cache)
 *     .build()
 * ```
 *
 * @param sp 持久化目标 SharedPreferences
 * @param owner 持有者标识(典型:当前账号 ID)。null = 不做隔离;非 null 时加载发现
 *              SP 里的 owner 跟当前不一致 → 自动清掉旧账号残留
 * @param key SP 里使用的 key,默认 `imc_core_send_cache`
 * @param maxBytes 字节上限,超出按 FIFO 淘汰,默认 32KB
 */
class SharedPrefsMessageCache @JvmOverloads constructor(
    private val sp: SharedPreferences,
    private val owner: String? = null,
    private val key: String = DEFAULT_KEY,
    private val maxBytes: Int = DEFAULT_MAX_BYTES,
) : IMessageCache {

    companion object {
        const val DEFAULT_KEY = "imc_core_send_cache"
        const val DEFAULT_MAX_BYTES = 32 * 1024
        private const val TAG = "SpMsgCache"
    }

    private val lock = Any()
    private val queue = ArrayDeque<CachedMessage>()
    private var occupied: Int = 0

    init {
        loadFromSp()
    }

    private fun loadFromSp() {
        val raw = sp.getString(key, null) ?: return
        try {
            val bytes = Base64.decode(raw, Base64.NO_WRAP)
            val decoded = decodeMessageCacheBlob(bytes)
            if (decoded == null) {
                Log.i(TAG,"SharedPrefsMessageCache blob 解析失败, 清空 key=$key")
                sp.edit().remove(key).apply()
                return
            }
            // owner 隔离:当前 owner 非空且跟 SP 里的不一致 → 旧账号残留,直接清掉
            if (owner != null && decoded.owner != owner) {
                Log.i(TAG,"SharedPrefsMessageCache owner 不匹配 stored='${decoded.owner}' current='$owner', 清空 key=$key")
                sp.edit().remove(key).apply()
                return
            }
            queue.addAll(decoded.messages)
            occupied = queue.sumOf { it.sizeBytes }
            Log.i(TAG,"SharedPrefsMessageCache 加载 ${queue.size} 条 ${occupied}B")
        } catch (e: Exception) {
            Log.e(TAG, "SharedPrefsMessageCache base64 解码失败, 清空 key=$key", e)
            sp.edit().remove(key).apply()
            queue.clear(); occupied = 0
        }
    }

    private fun flushToSp() {
        if (queue.isEmpty()) {
            sp.edit().remove(key).apply(); return
        }
        try {
            val bytes = encodeMessageCacheBlob(queue.toList(), owner)
            val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
            sp.edit().putString(key, encoded).apply()
        } catch (e: Exception) {
            Log.e(TAG, "SharedPrefsMessageCache 写入失败 key=$key", e)
        }
    }

    override fun put(message: CachedMessage) {
        synchronized(lock) {
            var newOccupied = occupied + message.sizeBytes
            while (newOccupied > maxBytes && queue.isNotEmpty()) {
                val evicted = queue.removeFirst()
                newOccupied -= evicted.sizeBytes
            }
            queue.addLast(message)
            occupied = newOccupied
            flushToSp()
        }
    }

    override fun pollFirst(): CachedMessage? = synchronized(lock) {
        val m = queue.removeFirstOrNull() ?: return@synchronized null
        occupied -= m.sizeBytes
        flushToSp()
        m
    }

    override fun pushFirst(message: CachedMessage) {
        synchronized(lock) {
            queue.addFirst(message)
            occupied += message.sizeBytes
            flushToSp()
        }
    }

    override fun remove(id: String): Boolean = synchronized(lock) {
        val idx = queue.indexOfFirst { it.id == id }
        if (idx < 0) return@synchronized false
        val m = queue.removeAt(idx)
        occupied -= m.sizeBytes
        flushToSp()
        true
    }

    override fun isEmpty(): Boolean = synchronized(lock) { queue.isEmpty() }

    override fun clear() {
        synchronized(lock) {
            queue.clear()
            occupied = 0
            sp.edit().remove(key).apply()
        }
    }

    override fun size(): Int = synchronized(lock) { queue.size }
}
