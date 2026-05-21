package org.daimhim.imc_core

/**
 * 已弃用,仅与 [OkhttpIEngine] 配套使用。
 *
 * V2 引擎 [V2JavaWebEngine] 的连接结果通过 [IMCStatusListener] 上报
 * (`connectionSucceeded` / `connectionLost` / `connectionClosed`),不再用本接口。
 */
@Deprecated(
    message = "仅与已弃用的 OkhttpIEngine 配套使用。V2JavaWebEngine 用 IMCStatusListener 替代。",
    replaceWith = ReplaceWith("IMCStatusListener"),
    level = DeprecationLevel.WARNING
)
interface IEngineActionListener {
    fun onSuccess(iEngine: IEngine)
    fun onFailure(iEngine: IEngine,t: Throwable)
}