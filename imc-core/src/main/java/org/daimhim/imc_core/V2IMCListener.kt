package org.daimhim.imc_core

import java.nio.ByteBuffer

interface V2IMCListener {
    fun onMessage(text: String){}
    fun onMessage(byteArray: ByteBuffer){}
}