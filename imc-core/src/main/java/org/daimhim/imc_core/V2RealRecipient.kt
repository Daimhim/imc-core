package org.daimhim.imc_core

import java.util.concurrent.CopyOnWriteArrayList


internal class V2RealRecipient : V2IMCSocketListener {
    // CopyOnWriteArrayList:IO 线程 forEach 派发,业务线程 add/remove,
    // 不能用普通 mutableListOf —— 会抛 ConcurrentModificationException。
    private val imcListeners = CopyOnWriteArrayList<V2IMCListener>()

    override fun onMessage(iEngine: IEngine, text: String):Boolean {
        imcListeners.forEach {
            it.onMessage(text)
        }
        return super.onMessage(iEngine, text)
    }

    override fun onMessage(iEngine: IEngine, bytes: ByteArray): Boolean {
        imcListeners.forEach {
            it.onMessage(bytes)
        }
        return super.onMessage(iEngine, bytes)
    }
    fun addIMCListener(imcListener: V2IMCListener) {
        imcListeners.add(imcListener)
    }

    fun removeIMCListener(imcListener: V2IMCListener) {
        imcListeners.remove(imcListener)
    }
}
