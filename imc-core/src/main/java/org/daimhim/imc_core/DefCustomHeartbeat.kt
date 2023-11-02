package org.daimhim.imc_core

import timber.multiplatform.log.Timber

class DefCustomHeartbeat : CustomHeartbeat {
    override fun isHeartbeat(iEngine: IEngine, bytes: ByteArray): Boolean {
        Timber.i("isHeartbeat ByteString")
        return bytes.isEmpty()
    }

    override fun isHeartbeat(iEngine: IEngine, text: String): Boolean {
        return false
    }

    override fun byteHeartbeat(): ByteArray {
        Timber.i("byteHeartbeat ByteString")
        return byteArrayOf()
    }

    override fun stringHeartbeat(): String {
        return ""
    }

    override fun byteOrString(): Boolean {
        Timber.i("byteOrString true")
        return true
    }
}