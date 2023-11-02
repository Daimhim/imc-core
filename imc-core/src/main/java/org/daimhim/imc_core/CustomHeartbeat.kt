package org.daimhim.imc_core



interface CustomHeartbeat {
    fun isHeartbeat(iEngine: IEngine, bytes: ByteArray):Boolean
    fun isHeartbeat(iEngine: IEngine, text: String): Boolean

    fun byteHeartbeat():ByteArray
    fun stringHeartbeat():String

    fun byteOrString():Boolean
}