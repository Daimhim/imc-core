package org.daimhim.imc_core

object IEngineState {
    /**
     * 打开
     */
    @JvmStatic
    val ENGINE_OPEN: Int = 1

    /**
     * 连接中/登录中
     */
    @JvmStatic
    val ENGINE_CONNECTING: Int = 2

    /**
     * 不知原因失败
     */
    @JvmStatic
    val ENGINE_FAILED: Int = 3

    /**
     * 关闭质指令
     */
    @JvmStatic
    val ENGINE_CLOSED: Int = 1000
    /**
     * 关闭质指令
     */
    @JvmStatic
    val ENGINE_CLOSED_FAILED: Int = 1006

}