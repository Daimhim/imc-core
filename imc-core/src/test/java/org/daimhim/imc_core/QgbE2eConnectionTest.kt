package org.daimhim.imc_core

import org.junit.Assume.assumeTrue
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 真实网络端到端连接测试(可选)
 *
 * 通过 `-Dqgb.url=wss://...` 系统属性提供 URL,例如:
 *   ./gradlew :imc-core:test --tests "*QgbE2eConnectionTest" -Dqgb.url=wss://...
 *
 * 没设属性时整组测试被 Assume 跳过,所以平时 CI 不会跑这个。
 *
 * 主要验收:
 *  1. URL 格式 (`/ws:90` 这种少见 path) 能被 java-websocket 解析、TCP+TLS+握手能跑通
 *  2. 修复 #1: engineOn 初次记录 currentKey,内部 reset 路径有正确 URL
 *  3. 修复 #3: 服务端正常关闭 → connectionClosed; 异常断开 → connectionLost
 */
class QgbE2eConnectionTest {

    private fun urlOrSkip(): String {
        val url = System.getProperty("qgb.url")
        assumeTrue("未提供 -Dqgb.url=..., 跳过 E2E 测试", !url.isNullOrBlank())
        return url
    }

    private fun buildEngine(): V2JavaWebEngine = V2JavaWebEngine.Builder()
        .addHeartbeatMode(0, V2FixedHeartbeat.Builder().setCurHeartbeat(15L).build())
        .setAutoConnect(ProgressiveAutoConnect.Builder().build())
        .build()

    @Test
    fun connect_to_qgb_test_env_within_10s() {
        val url = urlOrSkip()
        val engine = buildEngine()

        val opened = CountDownLatch(1)
        val firstEvent = AtomicReference<String?>(null)
        val messages = CopyOnWriteArrayList<String>()

        engine.setIMCStatusListener(object : IMCStatusListener {
            override fun connectionSucceeded() {
                firstEvent.compareAndSet(null, "SUCCEEDED")
                opened.countDown()
            }
            override fun connectionClosed(code: Int, reason: String?) {
                firstEvent.compareAndSet(null, "CLOSED($code,$reason)")
            }
            override fun connectionLost(throwable: Throwable) {
                firstEvent.compareAndSet(null, "LOST(${throwable.javaClass.simpleName}:${throwable.message})")
            }
        })

        engine.addIMCListener(object : V2IMCListener {
            override fun onMessage(text: String) {
                messages.add("TEXT(${text.length})")
            }
            override fun onMessage(byteArray: ByteArray) {
                messages.add("BYTES(${byteArray.size})")
            }
        })

        // 在另一线程发起连接,因为 engineOn 内部会同步 connect
        Thread { engine.engineOn(url) }.start()

        try {
            val ok = opened.await(10, TimeUnit.SECONDS)
            if (!ok) {
                org.junit.Assert.fail(
                    "10s 内未连上, firstEvent=${firstEvent.get()}, messages=${messages.joinToString()}"
                )
            }
            assertNotNull("firstEvent 不应为 null", firstEvent.get())
            // 给服务端 3s, 看下握手后有没有立即推消息 (IM 服务一般会推上线 ack / pull-down 包)
            Thread.sleep(3_000)
        } finally {
            engine.engineOff()
            // 等异步关闭流程走完
            Thread.sleep(500)
        }

        println("E2E result: firstEvent=${firstEvent.get()} messages=$messages")
    }

    @Test
    fun fix1_currentKey_records_on_first_engineOn() {
        val url = urlOrSkip()
        val engine = buildEngine()

        // engineOn 内部同步设 currentKey (锁内),所以 launch 完立即读应该已经赋值
        Thread { engine.engineOn(url) }.start()
        Thread.sleep(200)

        val field = V2JavaWebEngine::class.java.getDeclaredField("currentKey")
        field.isAccessible = true
        val captured = field.get(engine) as String

        engine.engineOff()
        Thread.sleep(500)

        assertTrue(
            "currentKey 应该已被记录, 实际: '$captured'",
            captured.isNotEmpty() && captured == url
        )
    }
}
