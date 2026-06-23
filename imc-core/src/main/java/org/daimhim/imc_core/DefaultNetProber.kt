package org.daimhim.imc_core

import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
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
 * **IPv6 / IPv4 fallback**:DNS 阶段用 [InetAddress.getAllByName] 拿全部 A/AAAA 记录,
 * TCP 阶段顺序遍历直到成功 —— 避免 v6 路由不通时直接判 SERVER_DOWN。
 *
 * **回调线程**:所有 callback **都在 [executor] 提供的后台线程**触发,**不会**在调用方栈上同步回调。
 *
 * **DNS 平台限制**:[InetAddress.getByName] / [InetAddress.getAllByName] 是 JVM 内置阻塞调用,
 * `Thread.interrupt` 无法唤醒。这意味着 [stageBudget] 超时只让主流程不再等待,DNS 后台线程
 * 会一直卡到系统级 DNS 超时(典型 15-30s)。所以 [executor] 必须是 **multi-thread 池**
 * (默认是有界 FixedThreadPool + daemon),不能传 SingleThreadExecutor —— 主任务 + DNS 子任务
 * 共争同一线程会死锁。
 */
class DefaultNetProber(
    private val executor: ExecutorService = defaultExecutor(),
    private val perStageTimeoutMs: Long = 2_000L,
    /** 端侧 DNS 缓存。同一进程共享一份。 */
    private val dnsCache: DnsCache = DnsCache(),
) : NetProber {

    /** 当前阶段实际可用的超时 = min(每段 cap, 剩余总预算) */
    private fun stageBudget(deadline: Long): Long =
        minOf(perStageTimeoutMs, remaining(deadline))

    // AtomicReference.getAndSet 把 "取旧+置新" 做成原子操作,
    // 防御:并发 probe 时旧 future 漏 cancel 留在线程池。
    private val inflight = AtomicReference<Future<*>?>(null)

    override fun probe(
        target: ProbeTarget,
        timeoutMs: Long,
        callback: (ProbeReport) -> Unit
    ) {
        // ProbeStarted 在调用栈上 emit:跨线程也无所谓,sink 拿到时序与代码 emit 时序一致
        ImcEvents.emit(ImcEvent.ProbeStarted(target.host, target.port))
        val future = try {
            executor.submit {
                val report = try {
                    doProbe(target, timeoutMs)
                } catch (e: InterruptedException) {
                    makeTimeoutReport(target)
                } catch (e: Exception) {
                    makeTimeoutReport(target, e.message)
                }
                // 探测完成事件:在 callback 之前 emit,给 sink 一个跟业务 callback 同步的信号
                ImcEvents.emit(
                    ImcEvent.ProbeFinished(
                        host = report.target.host,
                        port = report.target.port,
                        verdict = report.verdict,
                        dnsElapsedMs = report.dns.elapsedMs,
                        tcpElapsedMs = report.tcp.elapsedMs,
                        tlsElapsedMs = report.tls?.elapsedMs,
                        httpElapsedMs = report.httpHealth?.elapsedMs,
                        publicRefSuccess = report.publicRef?.success,
                    )
                )
                try {
                    callback(report)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        } catch (rej: java.util.concurrent.RejectedExecutionException) {
            ImcEvents.emit(ImcEvent.InternalError(
                site = "DefaultNetProber.probe.submit",
                errorClass = rej.javaClass.simpleName,
                message = rej.message,
            ))
            // 兜底:直接报 PROBE_TIMEOUT,callback 在调用线程跑(此时 executor 已废,
            // 同步回调是唯一选择;调用方应当避免在此场景下进入递归)
            val rep = makeTimeoutReport(target, "executor rejected")
            ImcEvents.emit(
                ImcEvent.ProbeFinished(
                    host = rep.target.host, port = rep.target.port, verdict = rep.verdict,
                    dnsElapsedMs = 0L, tcpElapsedMs = 0L,
                    tlsElapsedMs = null, httpElapsedMs = null, publicRefSuccess = null,
                )
            )
            try { callback(rep) }
            catch (e: Exception) { e.printStackTrace() }
            return
        }
        inflight.getAndSet(future)?.cancel(true)
    }

    private val inflightBurst = AtomicReference<Future<*>?>(null)

    override fun probeBurst(
        target: ProbeTarget,
        attempts: Int,
        intervalMs: Long,
        perAttemptTimeoutMs: Int,
        callback: (BurstProbeReport) -> Unit,
    ) {
        if (attempts <= 0) {
            // 异步触发:扔到 executor 上跑,避免在调用栈上同步回调 ——
            // 跟接口契约一致,也防御调用方在 callback 里递归 register 时的栈深问题。
            try {
                executor.submit {
                    val report = BurstProbeReport(target, 0, 0, emptyList(), System.currentTimeMillis())
                    emitBurstFinished(report)
                    try { callback(report) } catch (e: Exception) { e.printStackTrace() }
                }
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                // 退化为同步,跟 probe 的退路一致
                val report = BurstProbeReport(target, 0, 0, emptyList(), System.currentTimeMillis())
                emitBurstFinished(report)
                try { callback(report) } catch (e: Exception) { e.printStackTrace() }
            }
            return
        }
        val future = try {
            executor.submit {
                // DNS 解析一次,后续 N 次都用同一个 IP — 排除 DNS 抖动对延迟测量的污染。
                // 也走 IPv6/v4 fallback:挑第一个能 TCP 上的地址作为 burst 全程的目标。
                val addrs: List<InetAddress> = try {
                    dnsCache.resolve(target.host)
                } catch (_: Exception) { emptyList() }
                if (addrs.isEmpty()) {
                    val report = BurstProbeReport(target, attempts, 0, emptyList(), System.currentTimeMillis())
                    emitBurstFinished(report)
                    try { callback(report) } catch (e: Exception) { e.printStackTrace() }
                    return@submit
                }
                // 找一个能连的地址。如果都连不通,burst 全失败,successes=0。
                val chosen: InetAddress? = addrs.firstOrNull { addr ->
                    tcpConnectRtt(addr, target.port, perAttemptTimeoutMs) >= 0
                }
                if (chosen == null) {
                    val report = BurstProbeReport(target, attempts, 0, emptyList(), System.currentTimeMillis())
                    emitBurstFinished(report)
                    try { callback(report) } catch (e: Exception) { e.printStackTrace() }
                    return@submit
                }
                val rtts = ArrayList<Long>(attempts)
                // 第一次的 RTT 已经测过了,但样本数还得跑满 attempts 次以保留统计意义。
                var successes = 0
                for (i in 0 until attempts) {
                    if (Thread.currentThread().isInterrupted) break
                    if (i > 0 && intervalMs > 0) {
                        try { Thread.sleep(intervalMs) } catch (_: InterruptedException) { break }
                    }
                    val rtt = tcpConnectRtt(chosen, target.port, perAttemptTimeoutMs)
                    if (rtt >= 0) {
                        rtts.add(rtt)
                        successes++
                    }
                }
                val report = BurstProbeReport(target, attempts, successes, rtts, System.currentTimeMillis())
                emitBurstFinished(report)
                try { callback(report) } catch (e: Exception) { e.printStackTrace() }
            }
        } catch (rej: java.util.concurrent.RejectedExecutionException) {
            ImcEvents.emit(ImcEvent.InternalError(
                site = "DefaultNetProber.probeBurst.submit",
                errorClass = rej.javaClass.simpleName,
                message = rej.message,
            ))
            val rep = BurstProbeReport(target, attempts, 0, emptyList(), System.currentTimeMillis())
            emitBurstFinished(rep)
            try { callback(rep) }
            catch (e: Exception) { e.printStackTrace() }
            return
        }
        inflightBurst.getAndSet(future)?.cancel(true)
    }

    /** 把 [BurstProbeReport] 转成 [ImcEvent.BurstProbeFinished] 并 emit。集中一处避免各早退路径漏埋 */
    private fun emitBurstFinished(report: BurstProbeReport) {
        ImcEvents.emit(
            ImcEvent.BurstProbeFinished(
                host = report.target.host, port = report.target.port,
                attempts = report.attempts, successes = report.successes,
                p50Ms = report.p50Ms, p95Ms = report.p95Ms,
                jitterMs = report.jitterMs, lossRate = report.lossRate,
            )
        )
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
        inflight.getAndSet(null)?.cancel(true)
        inflightBurst.getAndSet(null)?.cancel(true)
    }

    /** 仅取消 burst 路径,baseline probe 不受影响 */
    override fun cancelBurst() {
        inflightBurst.getAndSet(null)?.cancel(true)
    }

    // ── 主流程 ───────────────────────────────────────────────────────────────

    private fun doProbe(target: ProbeTarget, timeoutMs: Long): ProbeReport {
        val deadline = System.currentTimeMillis() + timeoutMs

        // 1. DNS — 取全部 A/AAAA 记录,留给 TCP 阶段做 v6/v4 fallback
        val (dns, addresses) = stageDns(target.host, stageBudget(deadline))
        if (!dns.success || addresses.isEmpty()) {
            val publicRef = stagePublicRef(target.publicReference, deadline)
            return ProbeReport(
                target = target, dns = dns,
                tcp = ProbeStage(false, 0L, "skipped: dns failed"),
                tls = null, httpHealth = null, publicRef = publicRef,
                verdict = verdictOnDnsFailure(publicRef),
                timestamp = System.currentTimeMillis()
            )
        }

        // 2. TCP — 顺序尝试每个地址,第一个通的胜出
        val (tcp, socket) = stageTcpMulti(addresses, target.port, deadline)
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

    /**
     * DNS 解析返回主机的全部 InetAddress(可能含 IPv4 + IPv6 混合)。
     *
     * **平台限制**:`getAllByName` 不可中断,future.cancel(true) 无法唤醒它。
     * 这里用 future.get(timeout) 让主流程超时退出,后台线程会一直卡到系统级 DNS 超时 —
     * 所以 executor 必须是 multi-thread 池,否则下次 probe 进来排队等死。
     */
    private fun stageDns(host: String, timeoutMs: Long): Pair<ProbeStage, List<InetAddress>> {
        val start = System.currentTimeMillis()
        if (timeoutMs <= 0) return ProbeStage(false, 0L, "dns: no time left") to emptyList()
        val future = try {
            executor.submit<List<InetAddress>> {
                dnsCache.resolve(host)
            }
        } catch (rej: java.util.concurrent.RejectedExecutionException) {
            return ProbeStage(false, 0L, "dns: executor rejected") to emptyList()
        }
        return try {
            val addrs = future.get(timeoutMs, TimeUnit.MILLISECONDS)
            if (addrs.isEmpty()) {
                ProbeStage(false, System.currentTimeMillis() - start, "dns: empty") to emptyList()
            } else {
                ProbeStage(true, System.currentTimeMillis() - start) to addrs
            }
        } catch (e: TimeoutException) {
            future.cancel(true)
            ProbeStage(false, System.currentTimeMillis() - start, "dns: timeout") to emptyList()
        } catch (e: Exception) {
            ProbeStage(
                false, System.currentTimeMillis() - start,
                "dns: ${e.cause?.javaClass?.simpleName ?: e.javaClass.simpleName}"
            ) to emptyList()
        }
    }

    /**
     * 顺序尝试每个地址,第一个连上的胜出。
     *
     * 用顺序 (而不是 happy-eyeballs 并发) 的原因:
     *  - 简单,JVM 实现成本低
     *  - perStageTimeoutMs ≈ 2s,即便 v6 全部超时再 fallback 到 v4,最坏也就 2s×N,可接受
     *  - 想要更激进可换 happy-eyeballs(IPv6 优先发起,250ms 后 IPv4 并发)
     */
    private fun stageTcpMulti(
        addresses: List<InetAddress>,
        port: Int,
        deadline: Long
    ): Pair<ProbeStage, Socket?> {
        var lastError: String? = null
        var totalElapsed = 0L
        for ((idx, addr) in addresses.withIndex()) {
            val budget = stageBudget(deadline)
            if (budget <= 0) {
                lastError = "tcp: no time left after ${idx} attempts"
                break
            }
            val (stage, socket) = stageTcp(addr, port, budget)
            totalElapsed += stage.elapsedMs
            if (stage.success && socket != null) {
                // 返回成功的耗时累计 = 已失败的 + 成功的本次,便于日志判断 fallback 代价
                return ProbeStage(true, totalElapsed) to socket
            }
            lastError = "${stage.error} via ${addr.hostAddress}"
        }
        return ProbeStage(false, totalElapsed, lastError ?: "tcp: no addresses") to null
    }

    private fun stageTcp(
        address: InetAddress,
        port: Int,
        timeoutMs: Long
    ): Pair<ProbeStage, Socket?> {
        val start = System.currentTimeMillis()
        if (timeoutMs <= 0) return ProbeStage(false, 0L, "tcp: no time left") to null
        val socket = Socket()
        // timeoutMs 来自 stageBudget,上限是 perStageTimeoutMs(默认 2_000ms),不会溢出 Int。
        // 即便上游传 Long.MAX_VALUE 也兜底夹一下。
        val timeoutInt = timeoutMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        return try {
            socket.connect(InetSocketAddress(address, port), timeoutInt)
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
            sslSocket.soTimeout = timeoutMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
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
        val timeoutInt = timeoutMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        return try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = timeoutInt
            conn.readTimeout = timeoutInt
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
     * 公网参考点只测 DNS + TCP,不递归引入它自己的 publicReference。
     * 一并享受 v6/v4 fallback。
     */
    private fun stagePublicRef(target: ProbeTarget?, deadline: Long): ProbeStage? {
        target ?: return null
        val budget = stageBudget(deadline)
        if (budget < 100) return ProbeStage(false, 0L, "publicRef: no time left")
        val start = System.currentTimeMillis()
        return try {
            val addrs = dnsCache.resolve(target.host)
            if (addrs.isEmpty()) return ProbeStage(false, System.currentTimeMillis() - start, "publicRef: dns empty")
            // 顺序尝试各地址
            for (addr in addrs) {
                val remainBudget = stageBudget(deadline)
                if (remainBudget <= 0) break
                val sock = Socket()
                try {
                    sock.connect(InetSocketAddress(addr, target.port), remainBudget.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                    return ProbeStage(true, System.currentTimeMillis() - start)
                } catch (_: Exception) {
                    // 继续下一个地址
                } finally {
                    try { sock.close() } catch (_: Exception) {}
                }
            }
            ProbeStage(false, System.currentTimeMillis() - start, "publicRef: all addrs failed")
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

        /**
         * 默认 executor:**有界** FixedThreadPool,大小 = 4,daemon 线程。
         *
         * - **有上限**:防御高频探测时线程暴涨。一次完整 probe 主任务 + DNS 子任务最多占 2 个线程,
         *   池开 4 足够同时承载主探测 + burst + 一次公网参考 + 一次额外余量。
         * - **daemon**:进程退出不挡。
         * - **队列**:无界 LinkedBlockingQueue,但拒绝策略上谁也不抛 —— Caller 路径有
         *   RejectedExecutionException 兜底走同步退化。
         * - **DNS 不可中断的副作用**:被 cancel 的 DNS 任务会一直卡住占线程,直到系统级超时
         *   (15~30s)。**这是平台限制**,JVM 层无法解决;只能靠 daemon 池等线程被回收。
         *   极差网长时间运行下,可能出现 4 个线程全卡 DNS、新 probe 进队列等的现象,
         *   此时业务层应当通过 `setBurstEnabled(false)` 或抬高 `minProbeIntervalMs` 减压。
         */
        private fun defaultExecutor(): ExecutorService {
            val factory = ThreadFactory { r ->
                Thread(r).apply {
                    isDaemon = true
                    name = "NetProber-${THREAD_INDEX.incrementAndGet()}"
                }
            }
            return ThreadPoolExecutor(
                4, 4,
                60L, TimeUnit.SECONDS,
                LinkedBlockingQueue(),
                factory
            ).apply { allowCoreThreadTimeOut(true) }
        }
    }
}
