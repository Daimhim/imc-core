package org.daimhim.imc_core

import okio.ByteString

class V2RealRecipient<T> : V2IMCSocketListener<T> {
    private val imcListeners = mutableListOf<V2IMCListener>()

    override fun onMessage(iEngine: T, text: String):Boolean {
        imcListeners.forEach {
            it.onMessage(text)
        }
        return super.onMessage(iEngine, text)
    }

    override fun onMessage(iEngine: T, bytes: ByteString): Boolean {
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