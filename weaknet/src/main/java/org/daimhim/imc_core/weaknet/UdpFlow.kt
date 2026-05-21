package org.daimhim.imc_core.weaknet

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ArrayBlockingQueue

/**
 * 一条 UDP 流的 NAT 项 + 双向 chaos 注入。
 *
 * 线程模型(per flow,2 个线程):
 *  - writer 线程(c→r):take chunk,apply chaosC2R,send DatagramSocket
 *  - reader 线程(r→c):receive,apply chaosR2C,构 UDP 包写回 tun
 */
class UdpFlow(
    private val key: FlowKey,
    private val tun: TunWriter,
    private val protect: (DatagramSocket) -> Boolean,
    private val onClose: (FlowKey) -> Unit,
    private val configRef: () -> ChaosConfig,
) {
    @Volatile var lastActiveMs: Long = System.currentTimeMillis()
        private set

    private var sock: DatagramSocket? = null
    private var readerThread: Thread? = null
    private var writerThread: Thread? = null
    @Volatile private var closed = false

    private val outQueue = ArrayBlockingQueue<ByteArray>(OUT_QUEUE_CAP)
    private val chaosC2R = ChaosBytePipe(configRef) { close() }
    private val chaosR2C = ChaosBytePipe(configRef) { close() }

    fun init(): Boolean {
        return try {
            val s = DatagramSocket()
            if (!protect(s)) {
                Log.w(TAG, "protect() failed: $key")
                s.close()
                return false
            }
            s.connect(InetSocketAddress(InetAddress.getByAddress(key.dstIp), key.dstPort))
            s.soTimeout = 0
            sock = s
            writerThread = Thread({ writerLoop(s) }, "WeakNet-UDP-w-${key.dstPort}").apply {
                isDaemon = true
                start()
            }
            readerThread = Thread({ readerLoop(s) }, "WeakNet-UDP-r-${key.dstPort}").apply {
                isDaemon = true
                start()
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "UDP init failed: $key", e)
            false
        }
    }

    fun sendOutbound(data: ByteArray, off: Int, len: Int) {
        lastActiveMs = System.currentTimeMillis()
        val chunk = ByteArray(len)
        System.arraycopy(data, off, chunk, 0, len)
        // queue 满直接丢(UDP 本来就允许丢)
        outQueue.offer(chunk)
    }

    private fun writerLoop(s: DatagramSocket) {
        while (!closed) {
            val chunk = try { outQueue.take() } catch (_: InterruptedException) { break }
            if (!chaosC2R.apply(chunk.size)) {
                if (closed) break else continue
            }
            try {
                s.send(DatagramPacket(chunk, chunk.size))
            } catch (e: Exception) {
                Log.w(TAG, "UDP send failed: $key", e)
                close()
                break
            }
        }
    }

    private fun readerLoop(s: DatagramSocket) {
        val buf = ByteArray(2048)
        val pkt = DatagramPacket(buf, buf.size)
        while (!closed) {
            try {
                s.receive(pkt)
            } catch (e: Exception) {
                if (!closed) Log.d(TAG, "UDP read closed: $key (${e.message})")
                break
            }
            lastActiveMs = System.currentTimeMillis()
            if (!chaosR2C.apply(pkt.length)) {
                if (closed) break else continue
            }
            val reply = PacketBuilder.buildUdp(
                srcIp = key.dstIp, dstIp = key.srcIp,
                srcPort = key.dstPort, dstPort = key.srcPort,
                payload = buf, payloadOff = 0, payloadLen = pkt.length,
            )
            tun.write(reply)
        }
        close()
    }

    fun close() {
        if (closed) return
        closed = true
        try { sock?.close() } catch (_: Exception) {}
        sock = null
        writerThread?.interrupt()
        readerThread?.interrupt()
        onClose(key)
    }

    companion object {
        private const val TAG = "WeakNetUdpFlow"
        private const val OUT_QUEUE_CAP = 64
    }
}
