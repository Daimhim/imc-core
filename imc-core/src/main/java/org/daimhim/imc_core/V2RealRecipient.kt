package org.daimhim.imc_core

import java.util.concurrent.CopyOnWriteArrayList


internal class V2RealRecipient : V2IMCSocketListener {
    // CopyOnWriteArrayList:IO 线程 forEach 派发,业务线程 add/remove,
    // 不能用普通 mutableListOf —— 会抛 ConcurrentModificationException。
    private val imcListeners = CopyOnWriteArrayList<V2IMCListener>()

    override fun onMessage(iEngine: IEngine, text: String):Boolean {
        dispatch { it.onMessage(text) }
        return super.onMessage(iEngine, text)
    }

    override fun onMessage(iEngine: IEngine, bytes: ByteArray): Boolean {
        dispatch { it.onMessage(bytes) }
        return super.onMessage(iEngine, bytes)
    }

    /**
     * #8:逐个派发并隔离异常。forEach 里没 try 的话,某个 listener 抛异常会中断整个
     * 循环 —— 排在它后面的 listener 全部收不到这条消息,而且异常会往上冒到 IO 线程。
     */
    private fun dispatch(block: (V2IMCListener) -> Unit) {
        for (l in imcListeners) {
            try {
                block(l)
            } catch (e: Exception) {
                ImcEvents.emit(ImcEvent.InternalError(
                    site = "V2RealRecipient.dispatch",
                    errorClass = e.javaClass.simpleName,
                    message = e.message ?: "listener threw",
                ))
            }
        }
    }
    fun addIMCListener(imcListener: V2IMCListener) {
        imcListeners.add(imcListener)
    }

    fun removeIMCListener(imcListener: V2IMCListener) {
        imcListeners.remove(imcListener)
    }
}
