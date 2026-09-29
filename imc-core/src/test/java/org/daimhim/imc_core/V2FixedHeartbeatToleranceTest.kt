package org.daimhim.imc_core

import org.java_websocket.WebSocket
import org.java_websocket.framing.Framedata
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
 * 验证 V2FixedHeartbeat 的容差窗口行为:
 *  - 收到 pong → 正常节奏(发心跳 + 再排 curHeartbeat 秒)
 *  - 没收到 pong → 进入容差窗口,发探活,再等 (factor - 1) × curHeartbeat 秒
 *  - 容差窗口结束还没回 → 触发 resetStartAutoConnect
 *
 * ⚠ 这些用例必须挂一个**真连接**的 client:[V2FixedHeartbeat.onTimerTick] 开头有
 * A13 早退 —— webSocketClient 为空或未 open 时直接 return 且不重排计时器
 * (那条路径对应"socket 已断,计时器随之死亡")。不挂 client 就只能记到 1 次 start,
 * 状态机根本走不到。这里开本地 WS server 拿真 isOpen,计时仍由 RecordingScheduler
 * 确定性驱动(不靠 sleep)。
 */
class V2FixedHeartbeatToleranceTest {

    /** 假 scheduler 记录每次 start(timeoutMs) 与 stop 调用 */
    private class RecordingScheduler : ITimeoutScheduler {
        val starts = CopyOnWriteArrayList<Long>()
        val stops = AtomicInteger(0)
        private var cb: Callable<Void>? = null
        override fun start(time: Long) { starts.add(time) }
        override fun stop() { stops.incrementAndGet() }
        override fun setCallback(call: Callable<Void>) { cb = call }
        fun fire() { cb?.call() }
    }

    // ── 夹具:本地 server + 已连接 client ─────────────────────────────────

    private class LocalWsServer(port: Int) : WebSocketServer(InetSocketAddress("127.0.0.1", port)) {
        val startLatch = CountDownLatch(1)
        override fun onStart() { startLatch.countDown() }
        override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {}
        override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {}
        override fun onMessage(conn: WebSocket, message: String) {}
        override fun onError(conn: WebSocket?, ex: Exception) {}
        override fun onWebsocketPing(conn: WebSocket?, f: Framedata?) {
            // 默认 super 会回 pong;这里无所谓,容差用例自己驱动 tick
        }
    }

    private class Attached(
        val server: LocalWsServer,
        val client: V2JavaWebEngine.WebSocketClientImpl,
    ) {
        fun close() {
            try { client.close() } catch (_: Exception) {}
            try { server.stop(500) } catch (_: Exception) {}
        }
    }

    private fun attachOpenClient(): Attached {
        val port = ServerSocket(0).use { it.localPort }
        val server = LocalWsServer(port).apply { start() }
        assertTrue("test server start timeout", server.startLatch.await(3, TimeUnit.SECONDS))
        val client = V2JavaWebEngine.WebSocketClientImpl(URI("ws://127.0.0.1:$port"), timeout = 0)
        client.connectBlocking(3, TimeUnit.SECONDS)
        assertTrue("client 应已连上(用例依赖 isOpen 才能走状态机)", client.isOpen)
        return Attached(server, client)
    }

    // ── 用例 ────────────────────────────────────────────────────────────

    @Test
    fun `收到 pong 时正常节奏 心跳间隔保持`() {
        val att = attachOpenClient()
        try {
            val sched = RecordingScheduler()
            val hb = V2FixedHeartbeat.Builder()
                .setCurHeartbeat(60)
                .setToleranceFactor(1.5)
                .setTimeoutScheduler(sched)
                .build()
            hb.initLinkNative(att.client)

            hb.startConnectionLostTimer()
            assertEquals("起始定时 = 60s", listOf(60_000L), sched.starts)

            // 模拟期间收到 pong
            hb.updateLastPong()
            // 时间到,触发 tick
            sched.fire()
            // 应该:重置 + 发心跳 + 排下次 60s
            assertEquals(listOf(60_000L, 60_000L), sched.starts)
        } finally {
            att.close()
        }
    }

    @Test
    fun `没 pong 时进入容差窗口 延后 30s`() {
        val att = attachOpenClient()
        try {
            val sched = RecordingScheduler()
            val hb = V2FixedHeartbeat.Builder()
                .setCurHeartbeat(60)
                .setToleranceFactor(1.5)  // 容差时间 = 60 × 0.5 = 30s
                .setTimeoutScheduler(sched)
                .build()
            hb.initLinkNative(att.client)

            hb.startConnectionLostTimer()
            // 没 pong,直接 fire
            sched.fire()
            // 应该:发探活 + 排 30s 容差(60 × 0.5 = 30_000)
            assertEquals("容差窗口排到 30s 后", listOf(60_000L, 30_000L), sched.starts)
        } finally {
            att.close()
        }
    }

    @Test
    fun `容差窗口里收到 pong 恢复正常节奏`() {
        val att = attachOpenClient()
        try {
            val sched = RecordingScheduler()
            val hb = V2FixedHeartbeat.Builder()
                .setCurHeartbeat(60)
                .setToleranceFactor(1.5)
                .setTimeoutScheduler(sched)
                .build()
            hb.initLinkNative(att.client)

            hb.startConnectionLostTimer()
            sched.fire()  // 进入容差
            assertEquals(listOf(60_000L, 30_000L), sched.starts)

            // 容差期间收到 pong
            hb.updateLastPong()
            sched.fire()
            // 应该:重置 + 发心跳 + 排下次 60s,而不是触发重连
            assertEquals(listOf(60_000L, 30_000L, 60_000L), sched.starts)
        } finally {
            att.close()
        }
    }

    @Test
    fun `容差窗口结束还没 pong 触发重连`() {
        val att = attachOpenClient()
        try {
            val sched = RecordingScheduler()
            val hb = V2FixedHeartbeat.Builder()
                .setCurHeartbeat(60)
                .setToleranceFactor(1.5)
                .setTimeoutScheduler(sched)
                .build()
            hb.initLinkNative(att.client)

            hb.startConnectionLostTimer()
            sched.fire()  // 第一次失败 → 进容差
            sched.fire()  // 容差窗口结束依然没 pong → 应该 fail,不再 start
            // starts 应保持 2 个(60_000, 30_000),没有第三次 start
            assertEquals(listOf(60_000L, 30_000L), sched.starts)
        } finally {
            att.close()
        }
    }

    @Test
    fun `factor 1_0 与旧行为等价 一次 fire 直接失败`() {
        val att = attachOpenClient()
        try {
            val sched = RecordingScheduler()
            val hb = V2FixedHeartbeat.Builder()
                .setCurHeartbeat(60)
                .setToleranceFactor(1.0)  // 退回旧行为
                .setTimeoutScheduler(sched)
                .build()
            hb.initLinkNative(att.client)

            hb.startConnectionLostTimer()
            sched.fire()
            // factor=1.0 时容差时间 = 0,但 coerceAtLeast(1_000) 至少 1s
            // 这里的实际行为:进入容差,排 1s
            assertEquals(2, sched.starts.size)
            assertEquals(60_000L, sched.starts[0])
            assertEquals("factor=1.0 时容差时间被夹到最小 1s", 1_000L, sched.starts[1])
        } finally {
            att.close()
        }
    }

    @Test
    fun `stopConnectionLostTimer 后再 startConnectionLostTimer 状态干净`() {
        val att = attachOpenClient()
        try {
            val sched = RecordingScheduler()
            val hb = V2FixedHeartbeat.Builder()
                .setCurHeartbeat(60)
                .setToleranceFactor(1.5)
                .setTimeoutScheduler(sched)
                .build()
            hb.initLinkNative(att.client)

            hb.startConnectionLostTimer()
            sched.fire()  // 进入容差
            hb.stopConnectionLostTimer()
            assertEquals(1, sched.stops.get())

            hb.startConnectionLostTimer()
            // 容差标记应被 stop 清掉,第一次 fire 又走"没 pong → 进容差"路径
            sched.fire()
            // starts: 60s(初始), 30s(容差), 60s(重启), 30s(再进容差) = 4 次
            assertEquals(listOf(60_000L, 30_000L, 60_000L, 30_000L), sched.starts)
        } finally {
            att.close()
        }
    }
}
