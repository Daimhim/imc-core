package org.daimhim.imc_core

import okio.ByteString
import java.util.TreeMap

/**
 * 0~10 底层保留
 */
class IMCListenerManager<T>  {
    companion object {
        val DEFAULT_LEVEL = 15
    }

    private val imcSocketListeners = TreeMap<Int, MutableList<V2IMCSocketListener<T>>>()
    // 监听分发和定向监听分发
    private val v2RealRecipient = V2RealRecipient<T>()

    init {
        // 加入分发器
        addIMCSocketListener(DEFAULT_LEVEL,v2RealRecipient)
    }

    fun addIMCSocketListener(level: Int, listener: V2IMCSocketListener<T>) {
        synchronized(imcSocketListeners) {
            val v2IMCSocketListeners = imcSocketListeners[level] ?: mutableListOf()
            v2IMCSocketListeners.add(listener)
            imcSocketListeners[level] = v2IMCSocketListeners
        }
    }

    fun removeIMCSocketListener(listener: V2IMCSocketListener<T>) {
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

    fun onMessage(iEngine: T, text: String) {
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
    fun onMessage(iEngine: T, bytes: ByteString) {
        synchronized(imcSocketListeners) {
            imcSocketListeners
                .forEach { (t, u) ->
                    u.forEach {
                        try {
                            val onMessage = it.onMessage(iEngine, bytes)
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