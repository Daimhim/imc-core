package org.daimhim.imc_core

interface IMCStatusListener {
    fun connectionClosed(code: Int, reason: String?)
    fun connectionLost(throwable: Throwable)
    fun connectionSucceeded()
}