package org.daimhim.imc_core

interface IMCStatusListener {
    @Deprecated("可以使用多参数的")
    fun connectionClosed(){}
    fun connectionClosed(code: Int, reason: String?){
        connectionClosed()
    }
    fun connectionLost(throwable: Throwable)
    fun connectionSucceeded()
}