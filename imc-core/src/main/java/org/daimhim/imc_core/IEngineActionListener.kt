package org.daimhim.imc_core

interface IEngineActionListener {
    fun onSuccess(iEngine: IEngine)
    fun onFailure(iEngine: IEngine,t: Throwable)
}