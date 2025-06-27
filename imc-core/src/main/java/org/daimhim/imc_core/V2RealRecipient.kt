package org.daimhim.imc_core

import java.nio.ByteBuffer

class V2RealRecipient : V2IMCSocketListener {
    private val imcListeners = mutableListOf<V2IMCListener>()

    override fun onMessage(iEngine: IEngine, text: String):Boolean {
        imcListeners.forEach {
            it.onMessage(text)
        }
        return super.onMessage(iEngine, text)
    }

    override fun onMessage(iEngine: IEngine, bytes: ByteBuffer): Boolean {
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