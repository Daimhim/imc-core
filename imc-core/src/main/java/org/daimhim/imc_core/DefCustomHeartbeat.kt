package org.daimhim.imc_core


class DefCustomHeartbeat : CustomHeartbeat {
    override fun isHeartbeat(iEngine: IEngine, bytes: ByteArray): Boolean {
        IMCLog.i("isHeartbeat ByteString")
        return bytes.isEmpty()
    }

    override fun isHeartbeat(iEngine: IEngine, text: String): Boolean {
        return false
    }

    override fun byteHeartbeat(): ByteArray {
        IMCLog.i("byteHeartbeat ByteString")
        return byteArrayOf()
    }

    override fun stringHeartbeat(): String {
        return ""
    }

    override fun byteOrString(): Boolean {
        IMCLog.i("byteOrString true")
        return true
    }
}