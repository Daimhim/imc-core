package org.daimhim.imc_core

import okio.ByteString

interface CustomHeartbeat {
    fun isHeartbeat(iEngine: IEngine, bytes: ByteString):Boolean
    fun isHeartbeat(iEngine: IEngine, text: String): Boolean

    fun byteHeartbeat():ByteString
    fun stringHeartbeat():String

    fun byteOrString():Boolean
}