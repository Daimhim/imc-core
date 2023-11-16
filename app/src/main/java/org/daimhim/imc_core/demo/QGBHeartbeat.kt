package org.daimhim.imc_core.demo

import org.daimhim.imc_core.CustomHeartbeat
import org.daimhim.imc_core.IEngine

class QGBHeartbeat : CustomHeartbeat {
    override fun byteHeartbeat(): ByteArray {
        return byteArrayOf()
    }

    override fun byteOrString(): Boolean {
        return false
    }

    override fun isHeartbeat(iEngine: IEngine, bytes: ByteArray): Boolean {
        return isHeartbeat(iEngine, String(bytes))
    }

    override fun isHeartbeat(iEngine: IEngine, text: String): Boolean {
        return text.contains("HEART_BEAT")
    }

    override fun stringHeartbeat(): String {
        return "{\"fromAccount\":{\"accountId\":\"202012221018295\"},\"toAccount\":{\"accountId\":\"202012221018295\"},\"cmdType\":\"HEART_BEAT\"}"
    }
}