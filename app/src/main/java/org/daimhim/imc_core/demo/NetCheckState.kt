package com.custom.socket_connect

/**
 * @Description 网络状态检测结果枚举类
 * UNKNOWN：初始状态或者识别不出来状态为UNKNOWN状态；
 * GOOD：dns查询成功并且ping也成功，即标记为GOOD状态；
 * BAD：ping失败一次标记为BAD状态；
 * OFFLINE：dns server错误，ping错误
 */
enum class NetCheckState {
    UNKNOWN,
    GOOD,
    BAD,
    OFFLINE
}