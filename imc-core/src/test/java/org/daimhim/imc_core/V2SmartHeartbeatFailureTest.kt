package org.daimhim.imc_core

import org.java_websocket.WebSocket
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
 * V2SmartHeartbeat 的失败判定回归。此前该实现零测试覆盖,四项缺陷都在这里钉住:
 *
 *  - #1 迟到 tick 不得复活已停的计时器(调度器侧另有代际守卫,这里是实现侧兜底)
 *  - #2 状态机收进 sync,emit / 发帧 / 重排一律锁外(行为不变,靠结构保证)
 *  - #3 连接错误(onClose/onError → stopConnectionLostTimer(true))不记「漏心跳」,
 *        否则 close() → onClose → stopTimer(true) 又加一,一次抖动计 2 次
 *  - N2 首次丢心跳不 close,连续 miss 超 MAX_HEARTBEAT_FAILURE 才放弃本连接
 */
class V2SmartHeartbeatFailureTest {

    // ── 基建 ───────────────────────────────────────────────────────────

    private class RecordingScheduler : ITimeoutScheduler {
        val starts = CopyOnWriteArrayList<Long>()
        val stops = AtomicInteger(0)
        private var cb: Callable<Void>? = null
        override fun start(time: Long) { starts.add(time) }
        override fun stop() { stops.incrementAndGet() }
        override fun setCallback(call: Callable<Void>) { cb = call }
        fun fire() { cb?.call() }
    }

    /** server 侧观察 client 是否真的 close —— 比 client.isClosed 同步断言可靠 */
    private class LocalWsServer(port: Int) : WebSocketServer(InetSocketAddress("127.0.0.1", port)) {
        val startLatch = CountDownLatch(1)
        val closeLatch = CountDownLatch(1)
        override fun onStart() { startLatch.countDown() }
        override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {}
        override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
            closeLatch.countDown()
        }
        override fun onMessage(conn: WebSocket, message: String) {}
        override fun onError(conn: WebSocket?, ex: Exception) {}
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

    private val events = CopyOnWriteArrayList<ImcEvent>()
    private val sink = ImcEventSink { events.add(it) }

    @Before
    fun setUp() { ImcEvents.subscribe(sink) }

    @After
    fun tearDown() { ImcEvents.unsubscribe(sink) }

    private fun attachOpenClient(): Attached {
        val port = ServerSocket(0).use { it.localPort }
        val server = LocalWsServer(port).apply { start() }
        assertTrue("test server start timeout", server.startLatch.await(3, TimeUnit.SECONDS))
        val client = V2JavaWebEngine.WebSocketClientImpl(URI("ws://127.0.0.1:$port"), timeout = 0)
        client.connectBlocking(3, TimeUnit.SECONDS)
        assertTrue("client 应已连上", client.isOpen)
        return Attached(server, client)
    }

    private fun newSmart(sched: RecordingScheduler, maxFailure: Int = 2): V2SmartHeartbeat =
        V2SmartHeartbeat.Builder()
            .setInitialHeartbeat(5)
            .setMinHeartbeat(1)
            .setHeartbeatStep(1)
            .setMaxHeartbeatFailure(maxFailure)
            .setTimeoutScheduler(sched)
            .build()

    // ── #1:迟到 tick 不得复活已停的计时器 ────────────────────────────────

    @Test
    fun `已停的计时器不被迟到 tick 复活`() {
        val att = attachOpenClient()
        try {
            val sched = RecordingScheduler()
            val hb = newSmart(sched)
            hb.initLinkNative(att.client)

            hb.startConnectionLostTimer()
            assertEquals(1, sched.starts.size)

            hb.stopConnectionLostTimer()
            assertEquals(1, sched.stops.get())

            // 迟到 tick:调度器已停,不得重排
            sched.fire()
            assertEquals("迟到 tick 不得重新排程", 1, sched.starts.size)
        } finally {
            att.close()
        }
    }

    // ── N2:首次丢心跳不 close ──────────────────────────────────────────

    @Test
    fun `首次丢心跳不 close`() {
        val att = attachOpenClient()
        try {
            val sched = RecordingScheduler()
            val hb = newSmart(sched, maxFailure = 2)
            hb.initLinkNative(att.client)

            hb.startConnectionLostTimer()
            sched.fire()   // 第 1 次 miss

            assertTrue(
                "首次 miss 不应 close(阈值 maxFailure=2)",
                !att.server.closeLatch.await(300, TimeUnit.MILLISECONDS)
            )
            assertEquals(1, events.filterIsInstance<ImcEvent.HeartbeatFailed>().size)
            assertEquals("未到阈值应继续排程", 2, sched.starts.size)
        } finally {
            att.close()
        }
    }

    // ── N2:连续 miss 超阈值才 close ────────────────────────────────────

    @Test
    fun `连续丢心跳超过阈值才 close`() {
        val att = attachOpenClient()
        try {
            val sched = RecordingScheduler()
            // maxFailure=2 → 第 3 次 miss 才超阈值
            val hb = newSmart(sched, maxFailure = 2)
            hb.initLinkNative(att.client)

            hb.startConnectionLostTimer()
            sched.fire()   // miss 1
            sched.fire()   // miss 2
            assertTrue("未到阈值不应 close", !att.server.closeLatch.await(200, TimeUnit.MILLISECONDS))

            sched.fire()   // miss 3 → 超阈值
            assertTrue(
                "连续 miss 超阈值应 close 以触发 onClose 抢救",
                att.server.closeLatch.await(3, TimeUnit.SECONDS)
            )
        } finally {
            att.close()
        }
    }

    // ── #3:连接错误不记 miss(否则一次抖动计 2 次) ─────────────────────

    @Test
    fun `连接错误不计为漏心跳`() {
        val att = attachOpenClient()
        try {
            val sched = RecordingScheduler()
            val hb = newSmart(sched, maxFailure = 2)
            hb.initLinkNative(att.client)

            hb.startConnectionLostTimer()
            // 模拟连接错误路径:onClose/onError → stopConnectionLostTimer(isError=true)。
            // 旧实现这里会 onHeartbeatFailure(true) 再记一次 miss,和 close()→onClose 链
            // 叠加成双计数。现在只做 NAT 生命周期自适应,不记 miss。
            // 每轮 start 是因为 stopConnectionLostTimer 只在 isRunning 时生效。
            repeat(3) {
                hb.startConnectionLostTimer()
                hb.stopConnectionLostTimer(true)
            }

            assertEquals(
                "连接错误不应产生 HeartbeatFailed(漏心跳记账)",
                0, events.filterIsInstance<ImcEvent.HeartbeatFailed>().size
            )
        } finally {
            att.close()
        }
    }
}
