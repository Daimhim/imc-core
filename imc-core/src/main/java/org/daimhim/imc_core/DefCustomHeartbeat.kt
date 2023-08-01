package org.daimhim.imc_core

import okio.ByteString
import timber.multiplatform.log.Timber

class DefCustomHeartbeat : CustomHeartbeat {
    override fun isHeartbeat(iEngine: IEngine, bytes: ByteString): Boolean {
        Timber.i("isHeartbeat ByteString")
        return ByteString.EMPTY == bytes
    }

    override fun isHeartbeat(iEngine: IEngine, text: String): Boolean {
        return false
    }

    override fun byteHeartbeat(): ByteString {
        Timber.i("byteHeartbeat ByteString")
        return ByteString.EMPTY
    }

    override fun stringHeartbeat(): String {
        return ""
    }

    override fun byteOrString(): Boolean {
        Timber.i("byteOrString true")
        return true
    }
}