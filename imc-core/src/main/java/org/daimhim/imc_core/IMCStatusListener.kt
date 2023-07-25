package org.daimhim.imc_core

interface IMCStatusListener {
    fun connectionClosed()
    fun connectionLost(throwable: Throwable)
    fun connectionSucceeded()
}