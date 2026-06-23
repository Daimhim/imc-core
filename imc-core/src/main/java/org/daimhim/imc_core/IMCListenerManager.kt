package org.daimhim.imc_core

import java.util.TreeMap

/**
 * 0~10 底层保留
 */
class IMCListenerManager  {
    companion object {
        val DEFAULT_LEVEL = 15
    }

    private val imcSocketListeners = TreeMap<Int, MutableList<V2IMCSocketListener>>()
    // 监听分发和定向监听分发
    private val v2RealRecipient = V2RealRecipient()

    init {
        // 加入分发器
        addIMCSocketListener(DEFAULT_LEVEL,v2RealRecipient)
    }

    fun addIMCSocketListener(level: Int, listener: V2IMCSocketListener) {
        synchronized(imcSocketListeners) {
            val v2IMCSocketListeners = imcSocketListeners[level] ?: mutableListOf()
            v2IMCSocketListeners.add(listener)
            imcSocketListeners[level] = v2IMCSocketListeners
        }
    }

    fun removeIMCSocketListener(listener: V2IMCSocketListener) {
        synchronized(imcSocketListeners) {
            val mutableListOf = mutableListOf<Int>()
            imcSocketListeners
                .forEach { (t, u) ->
                    u.remove(listener)
                    if (u.isEmpty()) {
                        mutableListOf.add(t)
                    }
                }
            mutableListOf.forEach {
                imcSocketListeners.remove(it)
            }
        }
    }

    /**
     * 派发前先 snapshot 列表,避免在持锁状态下回调用户代码。
     *
     * 原因:
     *  - 用户实现 onMessage 里可能做同步 IO / 数据库 / UI 等长操作,持锁回调会把整个派发链卡住;
     *  - 用户实现里跨线程触发 add/remove 会请求同一把锁,产生死锁风险;
     *  - TreeMap 已按 key(level)有序,snapshot 后 flatMap 仍保持优先级顺序。
     */
    fun onMessage(iEngine: IEngine, text: String) {
        val snapshot = snapshotListeners()
        for (listener in snapshot) {
            try {
                if (listener.onMessage(iEngine, text)) return
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
    fun onMessage(iEngine: IEngine, bytes: ByteArray) {
        val snapshot = snapshotListeners()
        for (listener in snapshot) {
            try {
                if (listener.onMessage(iEngine, bytes)) return
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun snapshotListeners(): List<V2IMCSocketListener> = synchronized(imcSocketListeners) {
        // TreeMap.values 按 level 升序 — flatMap 后顺序与原派发顺序一致
        imcSocketListeners.values.flatMap { it.toList() }
    }

    fun addIMCListener(v2IMCListener: V2IMCListener){
        v2RealRecipient.addIMCListener(v2IMCListener)
    }
    fun removeIMCListener(v2IMCListener: V2IMCListener){
        v2RealRecipient.removeIMCListener(v2IMCListener)
    }

}
