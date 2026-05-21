package org.daimhim.imc_core

import java.util.concurrent.TimeUnit

/**
 * 把 [duration]+[unit] 转成毫秒整数并做合法性校验
 *
 * 等价于 OkHttp 的 `okhttp3.internal.checkDuration`,自带在这里是为了让
 * `ProgressiveAutoConnect` / `JavaWebEngine` 等不本质依赖 OkHttp 的组件
 * 不再被 OkHttp 的内部 API 绑架。
 *
 * 校验项:
 *  - duration ≥ 0
 *  - 折算到 ms 不能超过 Int.MAX_VALUE
 *  - duration > 0 但折算到 ms = 0 时认为过小,拒绝
 */
internal fun checkDurationMs(name: String, duration: Long, unit: TimeUnit): Int {
    require(duration >= 0L) { "$name < 0" }
    val millis = unit.toMillis(duration)
    require(millis <= Int.MAX_VALUE.toLong()) { "$name too large" }
    require(!(millis == 0L && duration > 0L)) { "$name too small" }
    return millis.toInt()
}
