package org.daimhim.imc_core


interface V2IMCListener {
    fun onMessage(text: String){}
    fun onMessage(byteArray: ByteArray){}
}