package org.daimhim.imc_core.weaknet

import java.util.concurrent.atomic.AtomicInteger

/**
 * 构造 IPv4 + TCP/UDP 回程包(供 VPN 写回 tun)
 *
 * 所有字段按网络字节序(big-endian)。
 * 校验和:RFC 1071 标准 internet checksum,16-bit 累加 + end-around carry + 取反。
 * TCP/UDP 校验和包含**伪头**(src ip / dst ip / 0 / protocol / l4 长度)。
 */
object PacketBuilder {
    private const val IPV4 = 0x45            // version=4, IHL=5
    private const val DEFAULT_TTL = 64
    private val ipId = AtomicInteger(1)

    /** 构造完整 IPv4+TCP 包,返回 byte 数组 */
    fun buildTcp(
        srcIp: ByteArray, dstIp: ByteArray,
        srcPort: Int, dstPort: Int,
        seq: Long, ack: Long,
        flags: Int,
        window: Int,
        payload: ByteArray? = null,
        payloadOff: Int = 0,
        payloadLen: Int = payload?.size ?: 0,
    ): ByteArray {
        val tcpHdrLen = 20
        val l4Len = tcpHdrLen + payloadLen
        val totalLen = 20 + l4Len
        val pkt = ByteArray(totalLen)

        // IPv4 header
        writeIpv4Header(pkt, 0, totalLen, IpPacketParser.IP_PROTO_TCP, srcIp, dstIp)

        // TCP header
        val l4Off = 20
        pkt[l4Off] = (srcPort shr 8).toByte()
        pkt[l4Off + 1] = srcPort.toByte()
        pkt[l4Off + 2] = (dstPort shr 8).toByte()
        pkt[l4Off + 3] = dstPort.toByte()
        write32(pkt, l4Off + 4, seq)
        write32(pkt, l4Off + 8, ack)
        pkt[l4Off + 12] = (5 shl 4).toByte()    // dataOff=5(*4=20),reserved=0
        pkt[l4Off + 13] = flags.toByte()
        pkt[l4Off + 14] = (window shr 8).toByte()
        pkt[l4Off + 15] = window.toByte()
        // checksum 占位 16-17
        pkt[l4Off + 18] = 0; pkt[l4Off + 19] = 0  // urgent

        // copy payload
        if (payloadLen > 0 && payload != null) {
            System.arraycopy(payload, payloadOff, pkt, l4Off + tcpHdrLen, payloadLen)
        }

        // L4 checksum
        val cksum = l4Checksum(srcIp, dstIp, IpPacketParser.IP_PROTO_TCP, pkt, l4Off, l4Len)
        pkt[l4Off + 16] = (cksum shr 8).toByte()
        pkt[l4Off + 17] = cksum.toByte()

        return pkt
    }

    /** 构造完整 IPv4+UDP 包 */
    fun buildUdp(
        srcIp: ByteArray, dstIp: ByteArray,
        srcPort: Int, dstPort: Int,
        payload: ByteArray, payloadOff: Int, payloadLen: Int,
    ): ByteArray {
        val udpHdrLen = 8
        val l4Len = udpHdrLen + payloadLen
        val totalLen = 20 + l4Len
        val pkt = ByteArray(totalLen)

        writeIpv4Header(pkt, 0, totalLen, IpPacketParser.IP_PROTO_UDP, srcIp, dstIp)

        val l4Off = 20
        pkt[l4Off] = (srcPort shr 8).toByte()
        pkt[l4Off + 1] = srcPort.toByte()
        pkt[l4Off + 2] = (dstPort shr 8).toByte()
        pkt[l4Off + 3] = dstPort.toByte()
        pkt[l4Off + 4] = (l4Len shr 8).toByte()
        pkt[l4Off + 5] = l4Len.toByte()
        // checksum 占位 6-7

        System.arraycopy(payload, payloadOff, pkt, l4Off + udpHdrLen, payloadLen)

        val cksum = l4Checksum(srcIp, dstIp, IpPacketParser.IP_PROTO_UDP, pkt, l4Off, l4Len)
        pkt[l4Off + 6] = (cksum shr 8).toByte()
        pkt[l4Off + 7] = cksum.toByte()

        return pkt
    }

    private fun writeIpv4Header(
        pkt: ByteArray, off: Int,
        totalLen: Int, protocol: Int,
        srcIp: ByteArray, dstIp: ByteArray,
    ) {
        pkt[off] = IPV4.toByte()
        pkt[off + 1] = 0                              // TOS
        pkt[off + 2] = (totalLen shr 8).toByte()
        pkt[off + 3] = totalLen.toByte()
        val id = ipId.getAndIncrement() and 0xFFFF
        pkt[off + 4] = (id shr 8).toByte()
        pkt[off + 5] = id.toByte()
        pkt[off + 6] = 0x40                          // DF
        pkt[off + 7] = 0
        pkt[off + 8] = DEFAULT_TTL.toByte()
        pkt[off + 9] = protocol.toByte()
        pkt[off + 10] = 0; pkt[off + 11] = 0          // checksum 占位
        System.arraycopy(srcIp, 0, pkt, off + 12, 4)
        System.arraycopy(dstIp, 0, pkt, off + 16, 4)
        val cksum = checksum(pkt, off, 20)
        pkt[off + 10] = (cksum shr 8).toByte()
        pkt[off + 11] = cksum.toByte()
    }

    private fun write32(buf: ByteArray, off: Int, v: Long) {
        buf[off] = ((v shr 24) and 0xFF).toByte()
        buf[off + 1] = ((v shr 16) and 0xFF).toByte()
        buf[off + 2] = ((v shr 8) and 0xFF).toByte()
        buf[off + 3] = (v and 0xFF).toByte()
    }

    /** 标准 internet checksum,RFC 1071 */
    private fun checksum(data: ByteArray, off: Int, len: Int): Int {
        var sum = 0L
        var i = off
        val end = off + len
        while (i + 1 < end) {
            sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < end) {
            sum += (data[i].toInt() and 0xFF) shl 8
        }
        while (sum shr 16 != 0L) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        return (sum.inv() and 0xFFFF).toInt()
    }

    /** 伪头 + L4 数据 的 internet checksum */
    private fun l4Checksum(
        srcIp: ByteArray, dstIp: ByteArray, protocol: Int,
        pkt: ByteArray, l4Off: Int, l4Len: Int,
    ): Int {
        var sum = 0L
        // pseudo-header
        sum += ((srcIp[0].toInt() and 0xFF) shl 8) or (srcIp[1].toInt() and 0xFF)
        sum += ((srcIp[2].toInt() and 0xFF) shl 8) or (srcIp[3].toInt() and 0xFF)
        sum += ((dstIp[0].toInt() and 0xFF) shl 8) or (dstIp[1].toInt() and 0xFF)
        sum += ((dstIp[2].toInt() and 0xFF) shl 8) or (dstIp[3].toInt() and 0xFF)
        sum += protocol
        sum += l4Len
        // l4 header + payload
        var i = l4Off
        val end = l4Off + l4Len
        while (i + 1 < end) {
            sum += ((pkt[i].toInt() and 0xFF) shl 8) or (pkt[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < end) {
            sum += (pkt[i].toInt() and 0xFF) shl 8
        }
        while (sum shr 16 != 0L) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        return (sum.inv() and 0xFFFF).toInt()
    }
}
