package org.daimhim.imc_core

import java.nio.ByteBuffer

interface V2IMCSocketListener {
    fun onMessage(iEngine: IEngine, text: String):Boolean = false
    fun onMessage(iEngine: IEngine, bytes: ByteBuffer):Boolean = false
}