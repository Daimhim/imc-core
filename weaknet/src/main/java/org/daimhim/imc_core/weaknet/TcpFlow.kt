package org.daimhim.imc_core.weaknet

import android.util.Log
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import kotlin.random.Random

/**
 * 一条 TCP 流的简化状态机 + 双向 chaos 注入。
 *
 * 线程模型(per flow,共 2-3 个线程):
 *  - VPN reader 线程:调 [onSyn] / [onSegment],只入 queue,不阻塞
 *  - **writer 线程**(c→r):从 [outboundQueue] take chunk,过 chaos,写真 Socket
 *  - **reader 线程**(r→c):read 真 Socket,过 chaos,构 TCP 段写回 tun
 *
 * 关键简化(不变):
 *  - 不处理乱序、不处理重传(client kernel TCP 自己重传,我们幂等吸收)
 *  - 始终公告 65535 window
 *  - 不实现 TIME_WAIT
 *  - SYN-ACK 在收 SYN 时立刻回,真 connect 在后台跑;connect 失败回 RST
 *
 * Chaos 处理:
 *  - c→r 方向:[onSegment] 把 chunk 塞 outboundQueue;writer 线程 take 出来,
 *    apply(chaosC2R),决定 sleep / drop / disconnect,再写 Socket
 *  - r→c 方向:reader 线程 read 后,apply(chaosR2C),决定 sleep / drop,再写 tun
 *
 * Backpressure:outboundQueue 容量 [OUT_QUEUE_CAP]。enqueue 失败时**不更新 ourAck**,
 * client kernel 会重传(因为 ack 没动),自然降速。
 */
class TcpFlow(
    private val key: FlowKey,
    private val tun: TunWriter,
    private val protect: (Socket) -> Boolean,
    private val onClose: (FlowKey) -> Unit,
    private val configRef: () -> ChaosConfig,
) {
    enum class State { SYN_RCVD, CONNECTING, ESTABLISHED, FIN_WAIT, CLOSED }

    @Volatile var state: State = State.SYN_RCVD
        private set
    @Volatile var lastActiveMs: Long = System.currentTimeMillis()
        private set

    private var ourSeq: Long = Random.nextLong(0, 0x80000000L)
    private var ourAck: Long = 0
    private var connectThread: Thread? = null
    private var readerThread: Thread? = null
    private var writerThread: Thread? = null
    private var outbound: Socket? = null
    @Volatile private var closed = false
    private val ioLock = Any()

    private val outboundQueue = ArrayBlockingQueue<ByteArray>(OUT_QUEUE_CAP)
    private val chaosC2R = ChaosBytePipe(configRef) { reason ->
        triggerDisconnect("c→r $reason")
    }
    private val chaosR2C = ChaosBytePipe(configRef) { reason ->
        triggerDisconnect("r→c $reason")
    }

    fun onSyn(clientSeq: Long) {
        ourAck = u32add(clientSeq, 1)
        sendFlagged(IpPacketParser.TCP_SYN or IpPacketParser.TCP_ACK)
        ourSeq = u32add(ourSeq, 1)
        state = State.CONNECTING
        connectThread = Thread({ doConnect() }, "WeakNet-TCP-conn-${key.dstPort}").apply {
            isDaemon = true
            start()
        }
    }

    private fun doConnect() {
        try {
            // Socket() 默认不创建 FD → protect() 拿不到 fd 直接 false。
            // 先 bind(InetSocketAddress(0)) 强制底层 SocketImpl 创建 FD,再 protect,再 connect。
            // protect 必须在 connect 之前调,否则 outbound 自己被 VPN 截走死循环。
            val s = Socket()
            s.bind(InetSocketAddress(0))
            if (!protect(s)) {
                Log.w(TAG, "protect() failed: $key")
                try { s.close() } catch (_: Exception) {}
                sendFlagged(IpPacketParser.TCP_RST or IpPacketParser.TCP_ACK)
                close()
                return
            }
            s.connect(InetSocketAddress(InetAddress.getByAddress(key.dstIp), key.dstPort), 8_000)
            s.tcpNoDelay = true
            synchronized(ioLock) {
                if (closed) { s.close(); return }
                outbound = s
                state = State.ESTABLISHED
            }
            writerThread = Thread({ outboundWriterLoop(s) }, "WeakNet-TCP-w-${key.dstPort}").apply {
                isDaemon = true
                start()
            }
            readerThread = Thread({ remoteReadLoop(s) }, "WeakNet-TCP-r-${key.dstPort}").apply {
                isDaemon = true
                start()
            }
        } catch (e: Exception) {
            Log.w(TAG, "TCP connect failed: $key (${e.message})")
            sendFlagged(IpPacketParser.TCP_RST or IpPacketParser.TCP_ACK)
            close()
        }
    }

    fun onSegment(
        clientSeq: Long, clientAck: Long, flags: Int,
        payload: ByteArray, payloadOff: Int, payloadLen: Int,
    ) {
        lastActiveMs = System.currentTimeMillis()

        // 1. 把 payload 入 c→r queue。
        //    CONNECTING 期(SYN-ACK 已回,真 outbound 还在 connect)依然要收,
        //    因为 client kernel 已发 ClientHello 之类的 piggyback 数据 — 丢了它就 SSL 失败。
        //    writer 线程在真 outbound 连上后才启,先入 queue 等它消费即可。
        if (payloadLen > 0 && (state == State.ESTABLISHED || state == State.CONNECTING)) {
            val chunk = ByteArray(payloadLen)
            System.arraycopy(payload, payloadOff, chunk, 0, payloadLen)
            val accepted = outboundQueue.offer(chunk)
            if (accepted) {
                ourAck = u32add(ourAck, payloadLen.toLong())
            }
            // 不论 enqueue 成不成都发一次 ACK 把 ourAck 告诉 client
            // (失败时 ourAck 没动 → client kernel 会自动重传,实现 backpressure)
            sendFlagged(IpPacketParser.TCP_ACK)
        }

        // 2. FIN — client 单边关闭
        if (flags and IpPacketParser.TCP_FIN != 0) {
            ourAck = u32add(ourAck, 1)
            sendFlagged(IpPacketParser.TCP_ACK)
            try { outbound?.shutdownOutput() } catch (_: Exception) {}
            if (state == State.FIN_WAIT) {
                close()
            } else {
                state = State.FIN_WAIT
            }
        }

        // 3. RST — 立刻干掉
        if (flags and IpPacketParser.TCP_RST != 0) {
            close()
        }
    }

    private fun outboundWriterLoop(s: Socket) {
        val out = try { s.getOutputStream() } catch (_: Exception) { close(); return }
        while (!closed) {
            val chunk = try { outboundQueue.take() } catch (_: InterruptedException) { break }
            if (!chaosC2R.apply(chunk.size)) {
                if (closed) break else continue
            }
            try {
                out.write(chunk)
                out.flush()
            } catch (e: Exception) {
                Log.w(TAG, "TCP outbound write failed: $key", e)
                sendFlagged(IpPacketParser.TCP_RST or IpPacketParser.TCP_ACK)
                close()
                break
            }
        }
    }

    private fun remoteReadLoop(s: Socket) {
        val buf = ByteArray(1400)
        val input = try { s.getInputStream() } catch (_: Exception) { close(); return }
        while (!closed) {
            val n = try {
                input.read(buf)
            } catch (e: Exception) {
                -1
            }
            if (n < 0) break
            lastActiveMs = System.currentTimeMillis()
            if (!chaosR2C.apply(n)) {
                if (closed) break else continue
            }
            val pkt = PacketBuilder.buildTcp(
                srcIp = key.dstIp, dstIp = key.srcIp,
                srcPort = key.dstPort, dstPort = key.srcPort,
                seq = ourSeq, ack = ourAck,
                flags = IpPacketParser.TCP_ACK or IpPacketParser.TCP_PSH,
                window = WINDOW,
                payload = buf, payloadOff = 0, payloadLen = n,
            )
            tun.write(pkt)
            ourSeq = u32add(ourSeq, n.toLong())
        }
        // remote 关 → 发 FIN 给 client
        if (!closed) {
            sendFlagged(IpPacketParser.TCP_FIN or IpPacketParser.TCP_ACK)
            ourSeq = u32add(ourSeq, 1)
            if (state == State.FIN_WAIT) {
                close()
            } else {
                state = State.FIN_WAIT
            }
        }
    }

    private fun sendFlagged(flags: Int) {
        val pkt = PacketBuilder.buildTcp(
            srcIp = key.dstIp, dstIp = key.srcIp,
            srcPort = key.dstPort, dstPort = key.srcPort,
            seq = ourSeq, ack = ourAck,
            flags = flags,
            window = WINDOW,
        )
        tun.write(pkt)
    }

    private fun triggerDisconnect(reason: String) {
        if (closed) return
        Log.i(TAG, "[chaos] disconnect $key: $reason")
        sendFlagged(IpPacketParser.TCP_RST or IpPacketParser.TCP_ACK)
        close()
    }

    fun close() {
        if (closed) return
        closed = true
        state = State.CLOSED
        synchronized(ioLock) {
            try { outbound?.close() } catch (_: Exception) {}
            outbound = null
        }
        writerThread?.interrupt()
        readerThread?.interrupt()
        onClose(key)
    }

    companion object {
        private const val TAG = "WeakNetTcpFlow"
        private const val WINDOW = 65535
        private const val OUT_QUEUE_CAP = 128
        private fun u32add(a: Long, b: Long): Long = (a + b) and 0xFFFFFFFFL
    }
}
