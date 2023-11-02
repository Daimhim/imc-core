package org.daimhim.imc_core

import okio.ByteString
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

    fun onMessage(iEngine: IEngine, text: String) {
        synchronized(imcSocketListeners) {
            imcSocketListeners
                .forEach { (t, u) ->
                    u.forEach {
                        try {
                            val onMessage = it.onMessage(iEngine, text)
                            if (onMessage) {
                                return@synchronized
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
        }
    }
    fun onMessage(iEngine: IEngine, bytes: ByteString) {
        synchronized(imcSocketListeners) {
            imcSocketListeners
                .forEach { (t, u) ->
                    u.forEach {
                        try {
                            val onMessage = it.onMessage(iEngine, bytes.toByteArray())
                            if (onMessage) {
                                return@synchronized
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
        }
    }

    fun addIMCListener(v2IMCListener: V2IMCListener){
        v2RealRecipient.addIMCListener(v2IMCListener)
    }
    fun removeIMCListener(v2IMCListener: V2IMCListener){
        v2RealRecipient.removeIMCListener(v2IMCListener)
    }

}