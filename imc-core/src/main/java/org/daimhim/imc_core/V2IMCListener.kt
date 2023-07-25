package org.daimhim.imc_core

import okio.ByteString

interface V2IMCListener {
    fun onMessage(text: String){}
    fun onMessage(byteArray: ByteString){}
}