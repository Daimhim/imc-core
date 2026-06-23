package org.daimhim.imc_core.demo

import org.daimhim.imc_core.CustomHeartbeat
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * QGB 协议心跳 — 与 `内部 IM 参考工程` 的 `IMCHeartbeatV2` 协议对齐。
 *
 * R5d(2026-06-12)单元测试 + 翻内部参考工程源码确认:
 *   - **客户端 → 服务端**:**binary 帧**,内容 = `gzip("心跳内容".toByteArray(UTF-8))`(33 字节)
 *   - **服务端 → 客户端**:**binary 帧**,GZIP 压缩的 JSON,内含 `cmdType:"HEART_BEAT"` + `msg:"操作成功"`
 *   - RTT 6–7ms,严格 1:1 请求/响应,**不是广播**
 *
 * R4 错误版(已废弃):发 text 帧 `{"cmdType":"HEART_BEAT"}`,服务端直接丢,日志看起来像
 * "服务端不回",其实是协议都没对上。
 *
 * 计数从 imc-core 内部拿不到(私有字段),所以全部在这层手动累。
 *   - sentCount    : byteHeartbeat() 被调用 → 一次出包
 *   - recvCount    : isHeartbeat(bytes) 命中 → 一次收到心跳回包(已含 gzip 解码)
 *   - failCount    : 外部(QgbWsTestActivity)在 connectionLost 时手动 markFailure
 */
class QGBHeartbeat : CustomHeartbeat {

    val sentCount = AtomicInteger(0)
    val recvCount = AtomicInteger(0)
    val failCount = AtomicInteger(0)

    /**
     * P3/P4: 当前连接上"已发未应答"的心跳数。区别于 sentCount/recvCount(累计统计量):
     * inFlight 在每次(重)连成功 / 断开时清零,只反映本条连接的在途心跳。
     * 旧实现用 sentCount-recvCount 当未应答数 —— 断线时在途的几条永远不会被应答,
     * 累计差值变成永久泄漏(06-16 起恒定卡 12),误导排查。
     */
    val inFlight = AtomicInteger(0)
    val lastSentMs = AtomicLong(0)
    val lastRecvMs = AtomicLong(0)
    val lastFailureMs = AtomicLong(0)
    @Volatile var lastFailureReason: String = ""

    /** byteOrString=true → V2Fixed 走 byteHeartbeat() → ws.send(ByteArray) → binary 帧 */
    override fun byteOrString(): Boolean = true

    override fun byteHeartbeat(): ByteArray {
        val n = sentCount.incrementAndGet()
        lastSentMs.set(System.currentTimeMillis())
        val pending = inFlight.incrementAndGet()
        LogStore.append("[hb] → 发送 #$n gzip(\"心跳内容\")" + if (pending > 1) " (未应答 $pending)" else "")
        return HEARTBEAT_PAYLOAD_GZIP
    }

    /** stringHeartbeat 路径不再走 — byteOrString=true 让 V2Fixed 永远调 byteHeartbeat() */
    override fun stringHeartbeat(): String = ""

    /**
     * 服务端应答路径 — onMessage(ByteArray) 路径上的入参:
     * 入参就是 WS binary 帧原始字节,**带 GZIP 头**(`1f8b08…`)。
     * 必须解压后再判关键字,不然永远查不到(R4 的核心 bug)。
     */
    override fun isHeartbeat(bytes: ByteArray): Boolean {
        val text = decodeMaybeGzip(bytes) ?: return false
        return checkAndAccount(text)
    }

    /**
     * 服务端不会发 text 帧(R5b/c/d 实测确认),这里只是接口要求兜底,
     * 实际命中可能性 = 0。如果真有 text 进来,直接比对关键字。
     */
    override fun isHeartbeat(text: String): Boolean = checkAndAccount(text)

    private fun checkAndAccount(text: String): Boolean {
        // 服务端应答必有 cmdType:HEART_BEAT 字样;为兼容也兜 msg:操作成功
        val match = text.contains("\"cmdType\":\"HEART_BEAT\"") ||
            text.contains("HEART_BEAT") && text.contains("操作成功")
        if (match) {
            val n = recvCount.incrementAndGet()
            inFlight.updateAndGet { (it - 1).coerceAtLeast(0) }
            val now = System.currentTimeMillis()
            val rttMs = lastSentMs.get().let { if (it > 0) now - it else -1 }
            lastRecvMs.set(now)
            LogStore.append("[hb] ← 应答 #$n" + if (rttMs in 0..600000) " (距发送 ${rttMs}ms)" else "")
        }
        return match
    }

    fun markFailure(reason: String) {
        val n = failCount.incrementAndGet()
        lastFailureMs.set(System.currentTimeMillis())
        lastFailureReason = reason
        // P3/P4: 连接已断,在途心跳全部作废 —— 清零,避免跨连接累积成永久"未应答"。
        inFlight.set(0)
        LogStore.append("[hb] ✗ 失败 #$n: $reason")
    }

    /** P3/P4:(重)连成功时调,清零在途计数,确保每条新连接从 0 在途开始。 */
    fun onConnected() {
        inFlight.set(0)
    }

    fun reset() {
        sentCount.set(0); recvCount.set(0); failCount.set(0)
        lastSentMs.set(0); lastRecvMs.set(0); lastFailureMs.set(0)
        inFlight.set(0)
        lastFailureReason = ""
    }

    companion object {
        private const val HEARTBEAT_TEXT = "心跳内容"

        /** 预先 gzip 好,避免每次发送时重复压缩 */
        private val HEARTBEAT_PAYLOAD_GZIP: ByteArray = run {
            val bos = ByteArrayOutputStream()
            GZIPOutputStream(bos).use { it.write(HEARTBEAT_TEXT.toByteArray(Charsets.UTF_8)) }
            bos.toByteArray()
        }

        /**
         * binary 帧原始字节解码:
         *  - GZIP 头(`1f8b08`):解压后按 UTF-8 解码
         *  - 否则当 UTF-8 文本读
         *
         * 失败返回 null(避免错误传播,不命中即可)。
         */
        private fun decodeMaybeGzip(bytes: ByteArray): String? = try {
            val isGzip = bytes.size >= 3 &&
                bytes[0] == 0x1f.toByte() &&
                bytes[1] == 0x8b.toByte() &&
                bytes[2] == 0x08.toByte()
            if (isGzip) {
                GZIPInputStream(ByteArrayInputStream(bytes)).readBytes().toString(Charsets.UTF_8)
            } else {
                bytes.toString(Charsets.UTF_8)
            }
        } catch (_: Exception) { null }
    }
}
