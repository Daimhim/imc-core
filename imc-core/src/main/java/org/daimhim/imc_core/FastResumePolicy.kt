package org.daimhim.imc_core

/**
 * 快速恢复策略:回前台([V2JavaWebEngine.Builder.setForegroundModes] 声明的档位被
 * [IEngine.onChangeMode] 切入)时,引擎按当前状态尽快拿回一条可用连接。
 *
 * 各状态的处置:
 *  - **Connected**:距最后一次入站超过 [staleAfterMs] → 直接判死重建(后台被冻结超过服务端
 *    空闲超时,连接基本已被对端关掉,探活只是白等);否则按 [probe] 探活,[probeTimeoutMs]
 *    内没收到任何入站(消息 / pong 都算)→ 重建
 *  - **Connecting**:在途尝试已超过 [staleAttemptMs](多半是后台发起、卡在冻结前的网络上)→ 换掉
 *  - **Reconnecting(等退避)**:清退避、不经调度器立即发起 —— 调度器(如 AlarmManager)
 *    在后台被系统押后的闹钟不必再等
 *
 * 所有时长都按 [V2JavaWebEngine.Builder.setElapsedClock] 的时钟算;Android 必须注入
 * `SystemClock::elapsedRealtime`,否则休眠时长不计入,「静默多久」会被严重低估。
 */
data class FastResumePolicy(
    /**
     * Connected 时距最后一次入站超过该值直接判死重建,不探活。0 = 不按静默判死。
     * 建议取「服务端空闲超时 - 余量」,如服务端 60s 空闲踢人则取 50s。
     */
    val staleAfterMs: Long = 0L,
    /**
     * Connected 且未判死时的探活方式。默认 [Probe.HEARTBEAT]:发的就是当前档位线上在用的心跳,
     * 它能不能拿到应答已经由心跳判活验证过;[Probe.WS_PING] 要求服务端确实回 pong,
     * 不回的话每次回前台都会把健康连接拆掉重建,确认过再用。
     */
    val probe: Probe = Probe.HEARTBEAT,
    /** 探活等待时长 */
    val probeTimeoutMs: Long = 3_000L,
    /** Connecting 在途尝试超过该值视作陈旧,换掉重来 */
    val staleAttemptMs: Long = 3_000L,
    /** 两次快速恢复的最小间隔(Activity 来回切换时去抖) */
    val debounceMs: Long = 1_000L,
) {
    init {
        require(staleAfterMs >= 0) { "staleAfterMs 必须 >= 0" }
        require(probeTimeoutMs > 0) { "probeTimeoutMs 必须 > 0" }
        require(staleAttemptMs >= 0) { "staleAttemptMs 必须 >= 0" }
        require(debounceMs >= 0) { "debounceMs 必须 >= 0" }
    }

    enum class Probe {
        /** 不探活:未超 [staleAfterMs] 即视作健康。适用于不回任何应答的服务端 */
        NONE,

        /** 发 WS ping(RFC 6455 要求服务端回 pong,但并非所有服务端 / 网关都照做) */
        WS_PING,

        /** 走当前心跳档位的 [ILinkNative.sendHeartbeat](自定义心跳 + 按档位配置的 ping) */
        HEARTBEAT,
    }
}
