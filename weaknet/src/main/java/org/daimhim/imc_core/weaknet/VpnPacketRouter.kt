package org.daimhim.imc_core.weaknet

import java.net.DatagramSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * VPN 包路由器 — 顶层 dispatcher。
 *
 * 持有当前 [ChaosConfig](热更新,所有 flow 通过 configRef 实时读)。
 *
 * 每个客户端 IP 包来时:
 *  1. 解析头
 *  2. 查 flow 表
 *  3. TCP: 看 SYN 决定新建(若 rejectNewConnections 立刻回 RST)
 *  4. UDP: 不存在就建
 *  5. payload 喂 flow,flow 自己写回 tun
 */
class VpnPacketRouter(
    private val tun: TunWriter,
    private val protectSocket: (Socket) -> Boolean,
    private val protectDgram: (DatagramSocket) -> Boolean,
    private val onEvent: (String) -> Unit = {},
) {
    @Volatile private var config: ChaosConfig = ChaosConfig()
    private val configRef: () -> ChaosConfig = { config }

    private val tcpFlows = ConcurrentHashMap<FlowKey, TcpFlow>()
    private val udpFlows = ConcurrentHashMap<FlowKey, UdpFlow>()

    fun updateConfig(c: ChaosConfig) {
        config = c
        onEvent("[chaos] 应用: latency=${c.baseLatencyMs}+${c.jitterMs}ms drop=${c.dropChunkPercent}% bw=${c.maxBytesPerSecond}B/s reject=${c.rejectNewConnections}")
    }

    fun currentConfig(): ChaosConfig = config

    fun onPacket(buf: ByteArray, len: Int) {
        val flow = IpPacketParser.parse(buf, len) ?: return
        when (flow.protocol) {
            IpPacketParser.IP_PROTO_TCP -> handleTcp(buf, flow)
            IpPacketParser.IP_PROTO_UDP -> handleUdp(buf, flow)
        }
    }

    private fun handleTcp(buf: ByteArray, ip: IpFlow) {
        val key = FlowKey(
            protocol = IpPacketParser.IP_PROTO_TCP,
            srcIp = ip.srcIp, srcPort = ip.srcPort,
            dstIp = ip.dstIp, dstPort = ip.dstPort,
        )
        val flags = ip.tcpFlags
        val isSyn = flags and IpPacketParser.TCP_SYN != 0
        val isRst = flags and IpPacketParser.TCP_RST != 0
        val existing = tcpFlows[key]

        if (isRst) {
            existing?.close()
            return
        }

        if (isSyn && existing == null) {
            if (config.rejectNewConnections) {
                onEvent("[tcp] 拒绝新连接 $key")
                // 回一个 RST 让 client 立刻失败,而不是傻等 SYN-ACK
                val rst = PacketBuilder.buildTcp(
                    srcIp = ip.dstIp, dstIp = ip.srcIp,
                    srcPort = ip.dstPort, dstPort = ip.srcPort,
                    seq = 0, ack = (ip.tcpSeq + 1) and 0xFFFFFFFFL,
                    flags = IpPacketParser.TCP_RST or IpPacketParser.TCP_ACK,
                    window = 0,
                )
                tun.write(rst)
                return
            }
            val newFlow = TcpFlow(
                key = key,
                tun = tun,
                protect = protectSocket,
                onClose = { k -> tcpFlows.remove(k); onEvent("[tcp] 关闭 $k") },
                configRef = configRef,
            )
            tcpFlows[key] = newFlow
            onEvent("[tcp] 新建 $key")
            newFlow.onSyn(ip.tcpSeq)
            return
        }
        if (isSyn && existing != null) {
            // 重传 SYN,忽略(我们已经回过 SYN-ACK)
            return
        }

        existing?.onSegment(
            clientSeq = ip.tcpSeq,
            clientAck = ip.tcpAck,
            flags = ip.tcpFlags,
            payload = buf,
            payloadOff = ip.payloadOff,
            payloadLen = ip.payloadLen,
        )
    }

    private fun handleUdp(buf: ByteArray, ip: IpFlow) {
        val key = FlowKey(
            protocol = IpPacketParser.IP_PROTO_UDP,
            srcIp = ip.srcIp, srcPort = ip.srcPort,
            dstIp = ip.dstIp, dstPort = ip.dstPort,
        )
        var flow = udpFlows[key]
        if (flow == null) {
            if (config.rejectNewConnections) {
                onEvent("[udp] 拒绝新流 $key")
                return
            }
            val newFlow = UdpFlow(
                key = key,
                tun = tun,
                protect = protectDgram,
                onClose = { k -> udpFlows.remove(k); onEvent("[udp] 关闭 $k") },
                configRef = configRef,
            )
            if (!newFlow.init()) return
            udpFlows[key] = newFlow
            onEvent("[udp] 新建 $key")
            flow = newFlow
        }
        flow.sendOutbound(buf, ip.payloadOff, ip.payloadLen)
    }

    fun shutdown() {
        tcpFlows.values.toList().forEach { it.close() }
        tcpFlows.clear()
        udpFlows.values.toList().forEach { it.close() }
        udpFlows.clear()
    }

    fun stats(): String = "tcp=${tcpFlows.size} udp=${udpFlows.size}"
}
