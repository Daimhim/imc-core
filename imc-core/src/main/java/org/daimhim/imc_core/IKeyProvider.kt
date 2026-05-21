package org.daimhim.imc_core

/**
 * 引擎需要发起 (重)连接时,通过这个 Provider 拿当前 URL。
 *
 * 这是「pull 模式」的 URL 配置:App 实现一个 lambda,引擎每次准备 connect 之前
 * 调用它,拿到最新的 url(包含最新 token / 首连 vs 重连标识 / 备用 host 切换等)。
 *
 * 与 [IEngine.engineOn] 的「push 模式」并存:
 *  - 没设 Provider → 沿用历史行为,engineOn 传入的 url 固化在引擎里,所有重试都用它
 *  - 设了 Provider → autoConnect 触发的重连每次都现拿 url;`engineOn` 仍可被 App 显式调用
 *    立即触发一次连接(传入的 url 优先,但下一次 autoConnect 重试会回到 Provider)
 *
 * 典型场景:
 *  - JWT token 过期前刷新,新 url 带新 token
 *  - 首连 vs 重连标识(例如 QGB 的 `state=0` 首连 / `state=1` 重连)
 *  - 主/备 endpoint 切换
 *
 * 实现注意:
 *  - **不要在 provide() 里做阻塞 IO**,引擎可能在重连热路径上同步调用
 *  - 返回 null 或抛异常会导致这次重连尝试被跳过,后续按 autoConnect 退避继续
 */
fun interface IKeyProvider {
    /** 返回当前应使用的 URL。null = 这次不连,等下次。 */
    fun provide(): String?
}
