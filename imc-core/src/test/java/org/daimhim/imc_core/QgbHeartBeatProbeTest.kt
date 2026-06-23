package org.daimhim.imc_core

import org.junit.Assume.assumeTrue
import org.junit.Test
import org.java_websocket.WebSocket
import org.java_websocket.client.WebSocketClient
import org.java_websocket.framing.Framedata
import org.java_websocket.handshake.ServerHandshake
import java.net.URI
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * R5 隔离探测:用 **全新账号** 登录(防止 R4 / R5-v1 同账号污染),验证两件事:
 *
 *  1. 服务端的 `HEART_BEAT` 5s 推送是否真的是 per-session 的服务端主动行为
 *     (而不是另一端 session 转发过来的污染)
 *  2. 客户端发 `HEART_BEAT` 文本帧服务端是否有任何额外响应
 *
 * A/B 对照:
 *  - **阶段 A**(state=1,首次登录):**只连不发**,40s 看推送基线
 *  - **阶段 B**(state=0,模拟重连):连上,**期间发 5 次 `HEART_BEAT`**,40s 看是否有额外回包
 *  - 比较两阶段收到的 HEART_BEAT 广播数;若 B 不显著多于 A,则客户端发不影响
 *
 * 运行(账号需要现成 token,登录走 [QgbLoginJvm]):
 *   $env:QGB_USERNAME='18109241424'; $env:QGB_PASSWORD='000qwer'; ./gradlew.bat :imc-core:test --tests "*QgbHeartBeatProbeTest*" --rerun-tasks
 *
 * 不设凭据时跳过。
 */
class QgbHeartBeatProbeTest {

    private fun credsOrSkip(): Pair<String, String> {
        val u = System.getProperty("qgb.username") ?: System.getenv("QGB_USERNAME")
        val p = System.getProperty("qgb.password") ?: System.getenv("QGB_PASSWORD")
        assumeTrue("未提供凭据(qgb.username / QGB_USERNAME 等),跳过", !u.isNullOrBlank() && !p.isNullOrBlank())
        return u!! to p!!
    }

    @Test
    fun isolated_probe_with_fresh_account() {
        val (username, password) = credsOrSkip()
        println("=== 登录 ===")
        val r = QgbLoginJvm.login(username, password)
        if (!r.ok) {
            org.junit.Assert.fail("登录失败: ${r.msg}")
            return
        }
        val token = r.token!!
        val name = r.name ?: error("登录返回无 imAccount")
        println("=== 登录成功 imAccount=$name phone=${r.phone} ===")
        // 不打 token,免得敏感数据落仓

        val urlBase = "wss://client.qgbtech.cn/ws?token=${URLEncoder.encode(token, "UTF-8")}&name=${URLEncoder.encode(name, "UTF-8")}&platform=android"

        // ── 阶段 A:state=1, 沉默 40s ────────────────────────────
        println("\n========================================")
        println("=== 阶段 A: state=1 沉默 40s 看推送基线 ===")
        println("========================================")
        val statA = runProbe(
            url = "$urlBase&state=1",
            durationMs = 40_000,
            sends = emptyList<Pair<Long, ByteArray>>(),
            tag = "A/silent",
        )

        // ── 阶段 B:state=0, 期间发 5 次 gzip("心跳内容") binary 帧 ─────
        println("\n========================================")
        println("=== 阶段 B: state=0 期间发 5 次 gzip(\"心跳内容\") binary 帧 ===")
        println("========================================")
        // 协议来自 sgb-management-android imc-core IMCHeartbeatV2:
        //   `"心跳内容".gzip().toByteString()` → binary 帧;服务端应答 binary 含 "HEART_BEAT" + "请求成功"
        // 发送时刻:T+5, T+10, T+15, T+20, T+25(都在 40s 窗口内)
        val heartbeatPayload = gzipBytes("心跳内容".toByteArray(Charsets.UTF_8))
        println("=== 心跳 payload gzip 后 ${heartbeatPayload.size}B hex(0..16)=${heartbeatPayload.take(16).joinToString("") { "%02x".format(it) }} ===")
        val sendsB = listOf(5_000L, 10_000L, 15_000L, 20_000L, 25_000L)
            .map { it to heartbeatPayload }
        val statB = runProbe(
            url = "$urlBase&state=0",
            durationMs = 40_000,
            sends = sendsB,
            tag = "B/send5",
        )

        // ── 总结对比 ─────────────────────────────────────────
        println("\n==============================================")
        println("=== 对比结论 ===")
        println("阶段 A (silent):  HEART_BEAT 收 ${statA.heartBeatCount} 次  全部 binary ${statA.binaryCount} 帧 text ${statA.textCount} 帧")
        println("阶段 B (send 5):  HEART_BEAT 收 ${statB.heartBeatCount} 次  全部 binary ${statB.binaryCount} 帧 text ${statB.textCount} 帧")
        println("差值:              HEART_BEAT  +${statB.heartBeatCount - statA.heartBeatCount}")
        val expected = 40_000 / 5_000  // 8 次,如果纯服务端 5s 广播
        println("理论纯广播预期:    $expected 次/40s")
        when {
            statB.heartBeatCount > statA.heartBeatCount + 2 -> println("=> 阶段 B 显著多于 A:客户端发可能有应答")
            statB.heartBeatCount <= statA.heartBeatCount + 1 -> println("=> 两阶段相近:客户端发无应答,5s 广播是服务端自发的")
            else -> println("=> 噪声范围内,无明显差异")
        }
        println("阶段 A 平台状态: ${statA.platformLoginState ?: "(未收到)"}")
        println("阶段 B 平台状态: ${statB.platformLoginState ?: "(未收到)"}")
        println("==============================================")
    }

    private data class Stats(
        val heartBeatCount: Int,
        val binaryCount: Int,
        val textCount: Int,
        val pongCount: Int,
        val platformLoginState: String?,
    )

    private fun gzipBytes(input: ByteArray): ByteArray {
        val bos = java.io.ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(bos).use { it.write(input) }
        return bos.toByteArray()
    }

    private fun runProbe(
        url: String,
        durationMs: Long,
        sends: List<Pair<Long, ByteArray>>,
        tag: String,
    ): Stats {
        val opened = CountDownLatch(1)
        val textFrames = CopyOnWriteArrayList<String>()
        val binaryFrames = CopyOnWriteArrayList<ByteArray>()
        val pongCount = AtomicInteger(0)
        val heartBeatCount = AtomicInteger(0)
        var platformLoginState: String? = null

        val client = object : WebSocketClient(URI(url)) {
            override fun onOpen(handshake: ServerHandshake?) {
                println("[$tag] onOpen status=${handshake?.httpStatus}")
                opened.countDown()
            }

            override fun onMessage(message: String?) {
                val m = message ?: return
                textFrames.add(m)
                println("[$tag] [TEXT ${m.length}B] $m")
            }

            override fun onMessage(bytes: ByteBuffer?) {
                bytes ?: return
                val arr = ByteArray(bytes.remaining()).also { bytes.duplicate().get(it) }
                binaryFrames.add(arr)
                val isGzip = arr.size >= 3 && arr[0] == 0x1f.toByte() &&
                    arr[1] == 0x8b.toByte() && arr[2] == 0x08.toByte()
                val decoded: String = if (isGzip) {
                    try {
                        java.util.zip.GZIPInputStream(java.io.ByteArrayInputStream(arr))
                            .readBytes().toString(Charsets.UTF_8)
                    } catch (e: Exception) {
                        "[gzip decode failed: ${e.message}]"
                    }
                } else {
                    try { arr.toString(Charsets.UTF_8) } catch (_: Exception) { "[binary]" }
                }
                // 提取 cmdType
                val cmdType = Regex("\"cmdType\"\\s*:\\s*\"([A-Z_]+)\"")
                    .find(decoded)?.groupValues?.get(1) ?: "?"
                if (cmdType == "HEART_BEAT") heartBeatCount.incrementAndGet()
                if (cmdType == "PLATFORM_LOGIN_STATE") {
                    platformLoginState = decoded.take(400)
                }
                println("[$tag] [BIN ${arr.size}B / cmdType=$cmdType] ${decoded.take(200)}${if (decoded.length > 200) "…" else ""}")
            }

            override fun onClose(code: Int, reason: String?, remote: Boolean) {
                println("[$tag] onClose code=$code remote=$remote reason=$reason")
            }

            override fun onError(ex: Exception?) {
                println("[$tag] onError ${ex?.javaClass?.simpleName}: ${ex?.message}")
            }

            override fun onWebsocketPong(conn: WebSocket?, f: Framedata?) {
                pongCount.incrementAndGet()
                println("[$tag] ws-PONG #${pongCount.get()}")
            }
        }
        client.connectionLostTimeout = 0  // 关 LCD,免得它自杀影响观测
        client.connect()
        if (!opened.await(10, TimeUnit.SECONDS)) {
            org.junit.Assert.fail("[$tag] 10s 未连上")
            return Stats(0, 0, 0, 0, null)
        }

        val t0 = System.nanoTime()
        val sendsIter = sends.toMutableList()
        var sentCount = 0
        while (true) {
            val elapsed = (System.nanoTime() - t0) / 1_000_000
            if (elapsed >= durationMs) break
            // 时间到就发
            while (sendsIter.isNotEmpty() && sendsIter.first().first <= elapsed) {
                val (_, payload) = sendsIter.removeAt(0)
                sentCount++
                println("[$tag] >> send #$sentCount @${elapsed}ms: ${payload.size}B binary frame (gzip)")
                client.send(payload)
            }
            Thread.sleep(50)
        }

        try { client.closeBlocking() } catch (_: Exception) {}

        return Stats(
            heartBeatCount = heartBeatCount.get(),
            binaryCount = binaryFrames.size,
            textCount = textFrames.size,
            pongCount = pongCount.get(),
            platformLoginState = platformLoginState,
        )
    }
}
