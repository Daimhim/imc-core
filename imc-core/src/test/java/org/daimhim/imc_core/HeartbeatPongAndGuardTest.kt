package org.daimhim.imc_core

import org.java_websocket.WebSocket
import org.java_websocket.framing.Framedata
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 1.1.13 心跳代际修复 + WS ping/pong 判活的回归测试。
 *
 * 单元部分(无网络,直接驱动 WebSocketClientImpl 的挂载/停止):
 *  - RC2 切档位必须停掉旧档位计时器(历史:僵尸计时器双跑,线上 30s/45s 交替发)
 *  - RC3 旧 client 迟到的 stop 被代际守卫拦下,新 client 的 stop 正常生效
 *  - RC1 同一心跳实例换代挂载(spawn 重连)后,计时器被强制重排,不被陈旧 isRunning 拦
 *
 * 集成部分(本地 WebSocketServer):
 *  - WS_PONG_ONLY 判活:server 正常回 pong → cause=pong 持续出现、无 HeartbeatFailed
 *  - 负路径:server 吞 ping 不回 pong → ENTERED_TOLERANCE / HeartbeatFailed 事件可见
 *    (这正是真机验证「服务器是否接收 WS ping」失败时的日志形态)
 */
class HeartbeatPongAndGuardTest {

    // ── 公共基建 ──────────────────────────────────────────────────────────

    private class RecSched : ITimeoutScheduler {
        val starts = CopyOnWriteArrayList<Long>()
        val stops = AtomicInteger(0)
        private var cb: Callable<Void>? = null
        override fun start(time: Long) { starts.add(time) }
        override fun stop() { stops.incrementAndGet() }
        override fun setCallback(call: Callable<Void>) { cb = call }
    }

    private val events = CopyOnWriteArrayList<ImcEvent>()
    private val sink = ImcEventSink { events.add(it) }

    @Before
    fun setUp() { ImcEvents.subscribe(sink) }

    @After
    fun tearDown() { ImcEvents.unsubscribe(sink) }

    private fun newClient(): V2JavaWebEngine.WebSocketClientImpl =
        V2JavaWebEngine.WebSocketClientImpl(URI("ws://localhost:1"), timeout = 0)

    // ── 单元:RC2 切档位停旧计时器 ────────────────────────────────────────

    @Test
    fun modeSwitch_stops_previous_heartbeat_timer() {
        val schedFg = RecSched()
        val schedBg = RecSched()
        val fg = V2FixedHeartbeat.Builder().setCurHeartbeat(30).setTimeoutScheduler(schedFg).build()
        val bg = V2FixedHeartbeat.Builder().setCurHeartbeat(45).setTimeoutScheduler(schedBg).build()
        val client = newClient()

        client.onChangeMode(fg)
        assertEquals(listOf(30_000L), schedFg.starts)

        client.onChangeMode(bg)
        // 1.1.12 及之前:先换引用再 stop,停的是新档位 → fg 计时器成僵尸
        assertEquals("切档后旧档位计时器必须被停(RC2)", 1, schedFg.stops.get())
        assertEquals(listOf(45_000L), schedBg.starts)
    }

    // ── 单元:RC3 迟到 stop 的代际守卫 ────────────────────────────────────

    @Test
    fun lateStopFromOldClient_guardedBy_generation() {
        val sched = RecSched()
        val hb = V2FixedHeartbeat.Builder().setCurHeartbeat(30).setTimeoutScheduler(sched).build()
        val clientA = newClient()
        val clientB = newClient()

        clientA.onChangeMode(hb)
        assertEquals(listOf(30_000L), sched.starts)

        // 新 client 接管(spawn 重连场景:同一个心跳实例挂到新 client)
        clientB.onChangeMode(hb)
        assertEquals("换代后必须强制重排计时器(RC1),不被陈旧 isRunning 拦", 2, sched.starts.size)

        val stopsBefore = sched.stops.get()
        // 旧 client A 迟到的 onClose/onError → 不许停掉 B 的计时器
        clientA.stopConnectionLostTimerEx(true)
        assertEquals("迟到 stop 应被代际守卫拦下(RC3)", stopsBefore, sched.stops.get())

        // 新 client B 自己的 stop 正常生效
        clientB.stopConnectionLostTimerEx(true)
        assertEquals(stopsBefore + 1, sched.stops.get())
    }

    // ── 集成基建:本地 server ─────────────────────────────────────────────

    private open class LocalWsServer(port: Int) : WebSocketServer(InetSocketAddress("127.0.0.1", port)) {
        val startLatch = CountDownLatch(1)
        override fun onStart() { startLatch.countDown() }
        override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {}
        override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {}
        override fun onMessage(conn: WebSocket, message: String) {}
        override fun onError(conn: WebSocket?, ex: Exception) {}
    }

    /** 吞掉 ping 不回 pong —— 模拟「服务器不支持/不响应 WS ping」 */
    private class PingSwallowServer(port: Int) : LocalWsServer(port) {
        override fun onWebsocketPing(conn: WebSocket?, f: Framedata?) {
            // 故意不调 super:不回 pong
        }
    }

    private fun findFreePort(): Int = ServerSocket(0).use { it.localPort }

    private fun buildEngine(hb: V2FixedHeartbeat): V2JavaWebEngine =
        V2JavaWebEngine.Builder()
            .addHeartbeatMode(0, hb)
            .setAutoConnect(
                ProgressiveAutoConnect.Builder()
                    .initReconnectDelay(200, TimeUnit.MILLISECONDS)
                    .build()
            )
            .build()

    private inline fun <reified T : ImcEvent> collected(): List<T> =
        events.filterIsInstance<T>()

    // ── 集成:NONE 只发不判死(服务器收心跳续命但不回 ack 的形态) ────────

    @Test
    fun noneLiveness_neverFails_keepsSending() {
        val port = findFreePort()
        val server = PingSwallowServer(port).apply { start() }   // 吞 ping 不回,最严苛
        try {
            assertTrue("test server start timeout", server.startLatch.await(3, TimeUnit.SECONDS))
            val engine = buildEngine(
                V2FixedHeartbeat.Builder()
                    .setCurHeartbeat(1)
                    .setToleranceFactor(2.0)
                    .setCustomHeartbeat(StringPingCustomHeartbeat())
                    .setLivenessSource(V2FixedHeartbeat.LivenessSource.NONE)
                    .build()
            )
            Thread { engine.engineOn("ws://localhost:$port") }.start()
            Thread.sleep(4500)
            engine.engineOff()

            val sent = collected<ImcEvent.HeartbeatSent>()
            assertTrue(
                "NONE 模式心跳应持续发送(实际 ${sent.size} 次)",
                sent.size >= 3
            )
            assertTrue(
                "NONE 模式不应进容差窗口",
                collected<ImcEvent.HeartbeatDegraded>().isEmpty()
            )
            assertTrue(
                "NONE 模式不应判死",
                collected<ImcEvent.HeartbeatFailed>().isEmpty()
            )
        } finally {
            try { server.stop(500) } catch (_: Exception) {}
        }
    }

    // ── 集成:server 正常回 pong,WS_PONG_ONLY 判活成立 ───────────────────

    @Test
    fun pongOnlyLiveness_withPongReplyingServer() {
        val port = findFreePort()
        val server = LocalWsServer(port).apply { start() }
        try {
            assertTrue("test server start timeout", server.startLatch.await(3, TimeUnit.SECONDS))
            val engine = buildEngine(
                V2FixedHeartbeat.Builder()
                    .setCurHeartbeat(1)          // 1s 心跳,缩短观察窗口
                    .setToleranceFactor(2.0)     // 2s 判死
                    .setLivenessSource(V2FixedHeartbeat.LivenessSource.WS_PONG_ONLY)
                    .build()
            )
            Thread { engine.engineOn("ws://localhost:$port") }.start()
            Thread.sleep(4000)
            engine.engineOff()

            val pongs = collected<ImcEvent.HeartbeatPongReceived>()
                .filter { it.cause == ImcEvent.HeartbeatPongReceived.CAUSE_PONG }
            assertTrue(
                "pong 回包应持续出现(实际 ${pongs.size} 次),事件: ${
                    collected<ImcEvent.HeartbeatPongReceived>().joinToString { it.cause }
                }",
                pongs.size >= 2
            )
            // 正常回 pong 的 server 不应判死
            assertTrue(
                "不应出现 HeartbeatFailed",
                collected<ImcEvent.HeartbeatFailed>().isEmpty()
            )
        } finally {
            try { server.stop(500) } catch (_: Exception) {}
        }
    }

    // ── 集成:server 吞 ping,失败路径必须可见(真机验证负路径的仿真) ────

    @Test
    fun pongNotAnswered_degradationAndFailureVisible() {
        val port = findFreePort()
        val server = PingSwallowServer(port).apply { start() }
        try {
            assertTrue("test server start timeout", server.startLatch.await(3, TimeUnit.SECONDS))
            val engine = buildEngine(
                V2FixedHeartbeat.Builder()
                    .setCurHeartbeat(1)
                    .setToleranceFactor(2.0)
                    .setLivenessSource(V2FixedHeartbeat.LivenessSource.WS_PONG_ONLY)
                    .build()
            )
            Thread { engine.engineOn("ws://localhost:$port") }.start()
            Thread.sleep(5000)
            engine.engineOff()

            assertTrue(
                "吞 pong 时应看到 ENTERED_TOLERANCE 降级信号",
                collected<ImcEvent.HeartbeatDegraded>().isNotEmpty()
            )
            assertTrue(
                "持续无 pong 应触发 HeartbeatFailed(可见的判死,而非静默无心跳)",
                collected<ImcEvent.HeartbeatFailed>().isNotEmpty()
            )
            assertTrue(
                "不应有任何 cause=pong 的回包",
                collected<ImcEvent.HeartbeatPongReceived>()
                    .none { it.cause == ImcEvent.HeartbeatPongReceived.CAUSE_PONG }
            )
        } finally {
            try { server.stop(500) } catch (_: Exception) {}
        }
    }
}
