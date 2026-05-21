package org.daimhim.imc_core.weaknet

/**
 * 极简 IPv4 包头解析(只解析头,不动 payload)
 * Phase 2: 暴露 L4 偏移,转发器要直接读 TCP/UDP 头 + payload。
 * 当前不解析 IPv6 — VPN tun 配置里也只走 v4。
 */
data class IpFlow(
    val version: Int,
    val protocol: Int,           // 6 = TCP, 17 = UDP, 1 = ICMP, ...
    val srcIp: ByteArray,        // 4 字节
    val dstIp: ByteArray,        // 4 字节
    val srcPort: Int,            // -1 表示无端口(ICMP 等)
    val dstPort: Int,
    val l3HeaderLen: Int,        // IPv4 IHL*4
    val l4HeaderLen: Int,        // TCP data offset *4 或 UDP 固定 8
    val totalLen: Int,           // IP 总长(含头)
    val payloadOff: Int,         // payload 在原 buf 内的偏移
    val payloadLen: Int,
    val tcpSeq: Long = 0,
    val tcpAck: Long = 0,
    val tcpFlags: Int = 0,
    val tcpWindow: Int = 0,
) {
    val srcIpStr: String get() = ipv4ToString(srcIp)
    val dstIpStr: String get() = ipv4ToString(dstIp)

    val protocolName: String get() = when (protocol) {
        IpPacketParser.IP_PROTO_TCP -> "TCP"
        IpPacketParser.IP_PROTO_UDP -> "UDP"
        IpPacketParser.IP_PROTO_ICMP -> "ICMP"
        else -> "P$protocol"
    }

    val tcpFlagsStr: String get() {
        if (protocol != IpPacketParser.IP_PROTO_TCP) return ""
        val sb = StringBuilder()
        if (tcpFlags and IpPacketParser.TCP_FIN != 0) sb.append('F')
        if (tcpFlags and IpPacketParser.TCP_SYN != 0) sb.append('S')
        if (tcpFlags and IpPacketParser.TCP_RST != 0) sb.append('R')
        if (tcpFlags and IpPacketParser.TCP_PSH != 0) sb.append('P')
        if (tcpFlags and IpPacketParser.TCP_ACK != 0) sb.append('.')
        if (tcpFlags and IpPacketParser.TCP_URG != 0) sb.append('U')
        return sb.toString()
    }

    override fun toString(): String {
        val flags = if (tcpFlagsStr.isNotEmpty()) " [$tcpFlagsStr]" else ""
        return "$protocolName $srcIpStr:$srcPort → $dstIpStr:$dstPort ${payloadLen}B$flags"
    }
}

object IpPacketParser {
    const val IP_PROTO_ICMP = 1
    const val IP_PROTO_TCP = 6
    const val IP_PROTO_UDP = 17

    const val TCP_FIN = 0x01
    const val TCP_SYN = 0x02
    const val TCP_RST = 0x04
    const val TCP_PSH = 0x08
    const val TCP_ACK = 0x10
    const val TCP_URG = 0x20

    /** 解析一个原始 IPv4 包。len 是有效字节数。失败返回 null。 */
    fun parse(buf: ByteArray, len: Int): IpFlow? {
        if (len < 20) return null
        val version = (buf[0].toInt() shr 4) and 0xF
        if (version != 4) return null  // Phase 2: 暂不支持 IPv6
        val ihl = (buf[0].toInt() and 0xF) * 4
        if (ihl < 20 || ihl > len) return null
        val totalLen = u16(buf, 2)
        if (totalLen > len) return null
        val protocol = buf[9].toInt() and 0xFF
        val src = buf.copyOfRange(12, 16)
        val dst = buf.copyOfRange(16, 20)
        return when (protocol) {
            IP_PROTO_TCP -> parseTcp(buf, ihl, totalLen, protocol, src, dst)
            IP_PROTO_UDP -> parseUdp(buf, ihl, totalLen, protocol, src, dst)
            else -> IpFlow(
                version = 4, protocol = protocol,
                srcIp = src, dstIp = dst,
                srcPort = -1, dstPort = -1,
                l3HeaderLen = ihl, l4HeaderLen = 0,
                totalLen = totalLen,
                payloadOff = ihl, payloadLen = totalLen - ihl,
            )
        }
    }

    private fun parseTcp(
        buf: ByteArray, ihl: Int, totalLen: Int,
        protocol: Int, src: ByteArray, dst: ByteArray
    ): IpFlow? {
        if (totalLen < ihl + 20) return null
        val l4 = ihl
        val sp = u16(buf, l4)
        val dp = u16(buf, l4 + 2)
        val seq = u32(buf, l4 + 4)
        val ack = u32(buf, l4 + 8)
        val dataOff = ((buf[l4 + 12].toInt() shr 4) and 0xF) * 4
        if (dataOff < 20 || ihl + dataOff > totalLen) return null
        val flags = buf[l4 + 13].toInt() and 0xFF
        val window = u16(buf, l4 + 14)
        val payloadOff = l4 + dataOff
        val payloadLen = totalLen - payloadOff
        return IpFlow(
            version = 4, protocol = protocol,
            srcIp = src, dstIp = dst,
            srcPort = sp, dstPort = dp,
            l3HeaderLen = ihl, l4HeaderLen = dataOff,
            totalLen = totalLen,
            payloadOff = payloadOff,
            payloadLen = payloadLen.coerceAtLeast(0),
            tcpSeq = seq, tcpAck = ack, tcpFlags = flags, tcpWindow = window,
        )
    }

    private fun parseUdp(
        buf: ByteArray, ihl: Int, totalLen: Int,
        protocol: Int, src: ByteArray, dst: ByteArray
    ): IpFlow? {
        if (totalLen < ihl + 8) return null
        val l4 = ihl
        val sp = u16(buf, l4)
        val dp = u16(buf, l4 + 2)
        val udpLen = u16(buf, l4 + 4)
        val payloadOff = l4 + 8
        val payloadLen = (udpLen - 8).coerceAtLeast(0)
        return IpFlow(
            version = 4, protocol = protocol,
            srcIp = src, dstIp = dst,
            srcPort = sp, dstPort = dp,
            l3HeaderLen = ihl, l4HeaderLen = 8,
            totalLen = totalLen,
            payloadOff = payloadOff,
            payloadLen = payloadLen,
        )
    }

    private fun u16(buf: ByteArray, off: Int): Int =
        ((buf[off].toInt() and 0xFF) shl 8) or (buf[off + 1].toInt() and 0xFF)

    private fun u32(buf: ByteArray, off: Int): Long =
        (((buf[off].toInt() and 0xFF).toLong()) shl 24) or
        (((buf[off + 1].toInt() and 0xFF).toLong()) shl 16) or
        (((buf[off + 2].toInt() and 0xFF).toLong()) shl 8) or
        ((buf[off + 3].toInt() and 0xFF).toLong())
}

internal fun ipv4ToString(ip: ByteArray): String =
    "${ip[0].toInt() and 0xFF}.${ip[1].toInt() and 0xFF}." +
    "${ip[2].toInt() and 0xFF}.${ip[3].toInt() and 0xFF}"
