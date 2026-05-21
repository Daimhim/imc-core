package org.daimhim.imc_core.weaknet

import java.io.FileOutputStream
import java.io.OutputStream

/**
 * 同步写 tun fd。
 * 多个 flow 的 outbound reader 线程会并发往 tun 写包,write() 必须串行。
 */
class TunWriter(out: FileOutputStream) {
    private val out: OutputStream = out
    private val lock = Any()

    fun write(pkt: ByteArray) {
        synchronized(lock) {
            try {
                out.write(pkt)
            } catch (e: Exception) {
                // tun 已关
            }
        }
    }
}

/** 5-tuple flow 标识(转发只走 v4,所以 IP 是 4 字节)。 */
data class FlowKey(
    val protocol: Int,
    val srcIp: ByteArray,
    val srcPort: Int,
    val dstIp: ByteArray,
    val dstPort: Int,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FlowKey) return false
        return protocol == other.protocol &&
                srcPort == other.srcPort &&
                dstPort == other.dstPort &&
                srcIp.contentEquals(other.srcIp) &&
                dstIp.contentEquals(other.dstIp)
    }

    override fun hashCode(): Int {
        var h = protocol
        h = 31 * h + srcIp.contentHashCode()
        h = 31 * h + srcPort
        h = 31 * h + dstIp.contentHashCode()
        h = 31 * h + dstPort
        return h
    }

    override fun toString(): String =
        "${if (protocol == 6) "TCP" else if (protocol == 17) "UDP" else "P$protocol"} " +
        "${ipv4ToString(srcIp)}:$srcPort→${ipv4ToString(dstIp)}:$dstPort"
}
