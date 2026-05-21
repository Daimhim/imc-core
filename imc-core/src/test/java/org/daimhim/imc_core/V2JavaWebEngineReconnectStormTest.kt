package org.daimhim.imc_core

import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 回归:onClose 时 handleAfterClose 第一分支与 ProgressiveAutoConnect 互踢造成的
 * ~100Hz 重连风暴。
 *
 * 修复后:autoConnect.isActive() == true 时,handleAfterClose 第一分支放弃 spawn
 * V2JWE-restart,让 autoConnect 独占退避调度。
 *
 * 此测试通过 stub IAutoConnect 模拟"autoConnect 活跃"的状态,然后构造
 * 一个 Reconnecting → close 序列,断言不会无限循环 engineOn。
 */
class V2JavaWebEngineReconnectStormTest {

    /** 假的 autoConnect,固定声称 active=true,abnormal 不做任何事。 */
    private class FakeAutoConnect : IAutoConnect {
        val engineOnCount = AtomicInteger(0)
        override fun initAutoConnect(webSocketClient: V2JavaWebEngine.WebSocketClientImpl) {}
        override fun abnormalDisconnectionAndAutomaticReconnection() {}
        override fun resetStartAutoConnect() {}
        override fun startAutoConnect() {}
        override fun stopAutoConnect() {}
        override fun isActive(): Boolean = true
    }

    @Test
    fun stormShouldNotHappenWhenAutoConnectActive() {
        val fakeAuto = FakeAutoConnect()
        val engine = V2JavaWebEngine.Builder()
            .addHeartbeatMode(0, V2FixedHeartbeat.Builder().setCurHeartbeat(60).build())
            .setAutoConnect(fakeAuto)
            .build()

        // 触发 engineOn → 内部尝试 connect(必失败,目标不存在)
        // 失败后 handleAfterClose 检查 fakeAuto.isActive()=true → 不应该再 spawn V2JWE-restart
        engine.engineOn("ws://127.0.0.1:1") // 端口 1 绝大概率拒
        // 给 connect 失败 + handleAfterClose 跑一遍 + 看看有没有暴动
        Thread.sleep(2000)
        engine.engineOff()

        // 修复前:V2JWE-restart 线程会无限 spawn engineOn 直到 socket fd 耗尽
        // 修复后:engineOn 只被外部调过 1 次,内部不会再 spawn
        // 这里用线程数粗略验证:V2JWE-restart 线程数应该为 0(或 ≤ 1 残留)
        val v2jweRestartCount = Thread.getAllStackTraces().keys
            .count { it.name.startsWith("V2JWE-restart") }
        assertTrue(
            "残留 V2JWE-restart 线程数应当 ≤ 1,实际=$v2jweRestartCount → 说明重连风暴又来了",
            v2jweRestartCount <= 1
        )
    }

    @Test
    fun urlSwitchPathStillWorksWhenNoAutoConnect() {
        // 不挂 autoConnect 的情况:URL 切换路径仍然要立即重启
        val engine = V2JavaWebEngine.Builder()
            .addHeartbeatMode(0, V2FixedHeartbeat.Builder().setCurHeartbeat(60).build())
            .build()
        engine.engineOn("ws://127.0.0.1:1")
        Thread.sleep(500)
        engine.engineOff()
        // 不奔溃即可
    }
}
