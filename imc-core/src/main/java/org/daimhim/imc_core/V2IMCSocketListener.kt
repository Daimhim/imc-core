package org.daimhim.imc_core


interface V2IMCSocketListener {
    fun onMessage(iEngine: IEngine, text: String):Boolean = false
    fun onMessage(iEngine: IEngine, bytes: ByteArray):Boolean = false
}