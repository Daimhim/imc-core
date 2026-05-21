package org.daimhim.imc_core

import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * 默认探测层实现 (纯 JVM,Android 22+ 可用)
 *
 * 探测顺序:DNS → TCP → TLS → HTTP/health。任一阶段失败立刻短路,
 * 然后尝试公网参考点 (`publicReference`) 区分根因:
 *
 *  - DNS 失败 + 公网通 → DNS_FAILURE
 *  - DNS 失败 + 公网也不通 → NO_INTERNET
 *  - TCP 失败 + 公网通 → SERVER_DOWN  (我家接入挂了)
 *  - TCP 失败 + 公网也不通 → NO_INTERNET
 *  - TLS / HTTP 失败:返回对应 verdict,公网参考用于做断言数据
 *
 * 整体执行在后台线程,调用方只会在 callback 里得到结果
 */
/**
 * @param executor 用于跑 socket / 解析的后台线程池
 * @param perStageTimeoutMs 每个探测阶段(DNS / TCP / TLS / HTTP)单独的超时上限。
 *   总预算还是由 [probe] 的 `timeoutMs` 控制,但每段都会用
 *   `min(perStageTimeoutMs, 剩余总预算)`,避免某一段(典型 DNS)吃光整个预算
 *   后续段连 attempt 都没机会做。默认 2s。
 */
class DefaultNetProber(
    private val executor: ExecutorService = defaultExecutor(),
    private val perStageTimeoutMs: Long = 2_000L,
) : NetProber {

    /** 当前阶段实际可用的超时 = min(每段 cap, 剩余总预算) */
    private fun stageBudget(deadline: Long): Long =
        minOf(perStageTimeoutMs, remaining(deadline))

    @Volatile
    private var inflight: Future<*>? = null

    override fun probe(
        target: ProbeTarget,
        timeoutMs: Long,
        callback: (ProbeReport) -> Unit
    ) {
        inflight?.cancel(true)
        inflight = executor.submit {
            val report = try {
                doProbe(target, timeoutMs)
            } catch (e: InterruptedException) {
                makeTimeoutReport(target)
            } catch (e: Exception) {
                makeTimeoutReport(target, e.message)
            }
            try {
                callback(report)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    @Volatile
    private var inflightBurst: Future<*>? = null

    override fun probeBurst(
        target: ProbeTarget,
        attempts: Int,
        intervalMs: Long,
        perAttemptTimeoutMs: Int,
        callback: (BurstProbeReport) -> Unit,
    ) {
        if (attempts <= 0) {
            callback(BurstProbeReport(target, 0, 0, emptyList(), System.currentTimeMillis()))
            return
        }
        inflightBurst?.cancel(true)
        inflightBurst = executor.submit {
            // DNS 解析一次,后续 N 次都用同一个 IP — 排除 DNS 抖动对延迟测量的污染
            val addr: InetAddress? = try {
                InetAddress.getByName(target.host)
            } catch (_: Exception) { null }
            if (addr == null) {
                val report = BurstProbeReport(target, attempts, 0, emptyList(), System.currentTimeMillis())
                try { callback(report) } catch (e: Exception) { e.printStackTrace() }
                return@submit
            }
            val rtts = ArrayList<Long>(attempts)
            var successes = 0
            for (i in 0 until attempts) {
                if (Thread.currentThread().isInterrupted) break
                if (i > 0 && intervalMs > 0) {
                    try { Thread.sleep(intervalMs) } catch (_: InterruptedException) { break }
                }
                val rtt = tcpConnectRtt(addr, target.port, perAttemptTimeoutMs)
                if (rtt >= 0) {
                    rtts.add(rtt)
                    successes++
                }
            }
            val report = BurstProbeReport(target, attempts, successes, rtts, System.currentTimeMillis())
            try { callback(report) } catch (e: Exception) { e.printStackTrace() }
        }
    }

    /** 测一次 TCP connect 的 RTT;失败返 -1 */
    private fun tcpConnectRtt(addr: InetAddress, port: Int, timeoutMs: Int): Long {
        val sock = Socket()
        return try {
            val start = System.currentTimeMillis()
            sock.connect(InetSocketAddress(addr, port), timeoutMs)
            System.currentTimeMillis() - start
        } catch (_: Exception) {
            -1L
        } finally {
            try { sock.close() } catch (_: Exception) {}
        }
    }

    override fun cancel() {
        inflight?.cancel(true)
        inflight = null
        inflightBurst?.cancel(true)
        inflightBurst = null
    }

    // ── 主流程 ───────────────────────────────────────────────────────────────

    private fun doProbe(target: ProbeTarget, timeoutMs: Long): ProbeReport {
        val deadline = System.currentTimeMillis() + timeoutMs

        // 1. DNS — 每段都用 stageBudget(),避免单段把整个预算吃掉
        val (dns, address) = stageDns(target.host, stageBudget(deadline))
        if (!dns.success || address == null) {
            val publicRef = stagePublicRef(target.publicReference, deadline)
            return ProbeReport(
                target = target, dns = dns,
                tcp = ProbeStage(false, 0L, "skipped: dns failed"),
                tls = null, httpHealth = null, publicRef = publicRef,
                verdict = verdictOnDnsFailure(publicRef),
                timestamp = System.currentTimeMillis()
            )
        }

        // 2. TCP
        val (tcp, socket) = stageTcp(address, target.port, stageBudget(deadline))
        if (!tcp.success || socket == null) {
            val publicRef = stagePublicRef(target.publicReference, deadline)
            return ProbeReport(
                target = target, dns = dns, tcp = tcp,
                tls = null, httpHealth = null, publicRef = publicRef,
                verdict = verdictOnTcpFailure(publicRef),
                timestamp = System.currentTimeMillis()
            )
        }

        try {
            // 3. TLS (optional)
            var tls: ProbeStage? = null
            if (target.useTls) {
                val (tlsStage, tlsSocket) = stageTls(socket, target.host, target.port, stageBudget(deadline))
                tls = tlsStage
                if (!tlsStage.success || tlsSocket == null) {
                    val publicRef = stagePublicRef(target.publicReference, deadline)
                    return ProbeReport(
                        target = target, dns = dns, tcp = tcp, tls = tls,
                        httpHealth = null, publicRef = publicRef,
                        verdict = ProbeVerdict.TLS_FAILURE,
                        timestamp = System.currentTimeMillis()
                    )
                }
                try { tlsSocket.close() } catch (_: Exception) {}
            }

            // 4. HTTP health (optional)
            var http: ProbeStage? = null
            if (target.healthPath != null) {
                http = stageHttp(target, stageBudget(deadline))
                if (!http.success) {
                    val publicRef = stagePublicRef(target.publicReference, deadline)
                    return ProbeReport(
                        target = target, dns = dns, tcp = tcp, tls = tls,
                        httpHealth = http, publicRef = publicRef,
                        verdict = ProbeVerdict.HTTP_DEGRADED,
                        timestamp = System.currentTimeMillis()
                    )
                }
            }

            // 全段通过,不浪费一次公网探测
            return ProbeReport(
                target = target, dns = dns, tcp = tcp, tls = tls,
                httpHealth = http, publicRef = null,
                verdict = ProbeVerdict.SERVER_REACHABLE,
                timestamp = System.currentTimeMillis()
            )
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    // ── 各阶段 ───────────────────────────────────────────────────────────────

    private fun stageDns(host: String, timeoutMs: Long): Pair<ProbeStage, InetAddress?> {
        val start = System.currentTimeMillis()
        if (timeoutMs <= 0) return ProbeStage(false, 0L, "dns: no time left") to null
        val future = executor.submit<InetAddress> { InetAddress.getByName(host) }
        return try {
            val addr = future.get(timeoutMs, TimeUnit.MILLISECONDS)
            ProbeStage(true, System.currentTimeMillis() - start) to addr
        } catch (e: TimeoutException) {
            future.cancel(true)
            ProbeStage(false, System.currentTimeMillis() - start, "dns: timeout") to null
        } catch (e: Exception) {
            ProbeStage(
                false, System.currentTimeMillis() - start,
                "dns: ${e.cause?.javaClass?.simpleName ?: e.javaClass.simpleName}"
            ) to null
        }
    }

    private fun stageTcp(
        address: InetAddress,
        port: Int,
        timeoutMs: Long
    ): Pair<ProbeStage, Socket?> {
        val start = System.currentTimeMillis()
        if (timeoutMs <= 0) return ProbeStage(false, 0L, "tcp: no time left") to null
        val socket = Socket()
        return try {
            socket.connect(InetSocketAddress(address, port), timeoutMs.toInt())
            ProbeStage(true, System.currentTimeMillis() - start) to socket
        } catch (e: Exception) {
            try { socket.close() } catch (_: Exception) {}
            ProbeStage(
                false, System.currentTimeMillis() - start,
                "tcp: ${e.javaClass.simpleName}"
            ) to null
        }
    }

    private fun stageTls(
        plainSocket: Socket,
        host: String,
        port: Int,
        timeoutMs: Long
    ): Pair<ProbeStage, SSLSocket?> {
        val start = System.currentTimeMillis()
        if (timeoutMs <= 0) return ProbeStage(false, 0L, "tls: no time left") to null
        return try {
            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val sslSocket = factory.createSocket(plainSocket, host, port, true) as SSLSocket
            sslSocket.soTimeout = timeoutMs.toInt()
            sslSocket.startHandshake()
            ProbeStage(true, System.currentTimeMillis() - start) to sslSocket
        } catch (e: Exception) {
            ProbeStage(
                false, System.currentTimeMillis() - start,
                "tls: ${e.javaClass.simpleName}"
            ) to null
        }
    }

    private fun stageHttp(target: ProbeTarget, timeoutMs: Long): ProbeStage {
        val path = target.healthPath ?: return ProbeStage(true, 0L)
        if (timeoutMs <= 0) return ProbeStage(false, 0L, "http: no time left")
        val scheme = if (target.useTls) "https" else "http"
        val url = "$scheme://${target.host}:${target.port}$path"
        val start = System.currentTimeMillis()
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = timeoutMs.toInt()
            conn.readTimeout = timeoutMs.toInt()
            conn.requestMethod = "GET"
            conn.instanceFollowRedirects = false
            val code = conn.responseCode
            if (code in 200..399) {
                ProbeStage(true, System.currentTimeMillis() - start)
            } else {
                ProbeStage(false, System.currentTimeMillis() - start, "http: $code")
            }
        } catch (e: Exception) {
            ProbeStage(
                false, System.currentTimeMillis() - start,
                "http: ${e.javaClass.simpleName}"
            )
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    /**
     * 公网参考点只测 DNS + TCP,不递归引入它自己的 publicReference
     */
    private fun stagePublicRef(target: ProbeTarget?, deadline: Long): ProbeStage? {
        target ?: return null
        val budget = stageBudget(deadline)
        if (budget < 100) return ProbeStage(false, 0L, "publicRef: no time left")
        val start = System.currentTimeMillis()
        return try {
            val addr = InetAddress.getByName(target.host)
            val sock = Socket()
            try {
                sock.connect(InetSocketAddress(addr, target.port), budget.toInt())
                ProbeStage(true, System.currentTimeMillis() - start)
            } finally {
                try { sock.close() } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            ProbeStage(
                false, System.currentTimeMillis() - start,
                "publicRef: ${e.javaClass.simpleName}"
            )
        }
    }

    // ── 判定规则 ─────────────────────────────────────────────────────────────

    private fun verdictOnDnsFailure(publicRef: ProbeStage?): ProbeVerdict = when {
        publicRef == null -> ProbeVerdict.DNS_FAILURE
        publicRef.success -> ProbeVerdict.DNS_FAILURE   // 公网通,自家 DNS 配置 / 劫持
        else -> ProbeVerdict.NO_INTERNET
    }

    private fun verdictOnTcpFailure(publicRef: ProbeStage?): ProbeVerdict = when {
        publicRef == null -> ProbeVerdict.SERVER_DOWN
        publicRef.success -> ProbeVerdict.SERVER_DOWN   // 公网通,我家接入挂了
        else -> ProbeVerdict.NO_INTERNET
    }

    // ── 工具 ────────────────────────────────────────────────────────────────

    private fun remaining(deadline: Long): Long =
        (deadline - System.currentTimeMillis()).coerceAtLeast(0L)

    private fun makeTimeoutReport(target: ProbeTarget, msg: String? = null): ProbeReport {
        val empty = ProbeStage(false, 0L, msg ?: "timeout")
        return ProbeReport(
            target = target, dns = empty, tcp = empty,
            tls = null, httpHealth = null, publicRef = null,
            verdict = ProbeVerdict.PROBE_TIMEOUT,
            timestamp = System.currentTimeMillis()
        )
    }

    companion object {
        private val THREAD_INDEX = AtomicInteger(0)

        private fun defaultExecutor(): ExecutorService =
            Executors.newCachedThreadPool(ThreadFactory { r ->
                Thread(r).apply {
                    isDaemon = true
                    name = "NetProber-${THREAD_INDEX.incrementAndGet()}"
                }
            })
    }
}
