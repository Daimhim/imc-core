package org.daimhim.imc_core

import okio.ByteString

interface V2IMCSocketListener<T> {
    fun onMessage(iEngine: T, text: String):Boolean = false
    fun onMessage(iEngine: T, bytes: ByteString):Boolean = false
}