package org.daimhim.imc_core

import java.util.concurrent.Callable
import java.util.concurrent.TimeUnit

interface IAutoConnect {
    fun initAutoConnect(webSocketClient: V2JavaWebEngine.WebSocketClientImpl)
    /**
     * 异常断开 并启动自动重连
     * onClose
     * onError
     */
    fun abnormalDisconnectionAndAutomaticReconnection()
    /**
     * 重置自动连接，用于更新配置
     */
    fun resetStartAutoConnect()

    /**
     * 启动抢救
     */
    fun startAutoConnect()

    /**
     * 停止启动
     */
    fun stopAutoConnect()

    /**
     * 当前是否正在抢救中(已被调度 / 在重连 / 在等下次)。
     * 用于 V2JavaWebEngine 区分:
     *  - true  → 自动重连已经管,handleAfterClose 不要再开一条独立重连路径
     *  - false → 没人管,handleAfterClose 可以直接重启(URL 切换路径)
     */
    fun isActive(): Boolean = false

    /**
     * 注入「重连入口」。autoConnect 决定**什么时候连**(退避调度),
     * Reconnector 实现**用什么连**(查 KeyProvider 拿最新 url, 走 engine.engineOn 重建 client)。
     *
     * 没注入 Reconnector 时退化到老行为:直接 `webSocketClient.reconnect()`(沿用既有 url)。
     */
    fun setReconnector(reconnector: IReconnector) {}

    /**
     * engine 在 onError / onClose 时把最近一次错的类别(exception simpleName)告诉 autoConnect,
     * 让 autoConnect 可以基于错的"形态"做更智能的决策 —— 例如连续 SSL 类异常 N 次时主动触发探测,
     * 区分"真服务端 TLS 烂"和"链路 SSL 拦截"。
     *
     * 默认 no-op。具体实现见 [ProgressiveAutoConnect.onErrorSignal]。
     *
     * @param errorClass exception.javaClass.simpleName,null 表示是 onClose 非异常路径
     */
    fun onErrorSignal(errorClass: String?) {}

    /**
     * 释放本实例持有的外部资源(典型:[NetSurveillance] 的 listener、内部线程等)。
     *
     * **由 [V2JavaWebEngine.engineOff] 自动调用一次**;如果业务想复用同一个 engine 反复
     * engineOn/engineOff,需要在每次 engineOff 后用新的 Builder + 新的 autoConnect 实例
     * 重新构造 engine,**不能**重用已 dispose 过的 autoConnect(否则 surveillance 不再驱动它)。
     *
     * 默认 no-op,允许实现选择性实现。dispose 应当幂等。
     */
    fun dispose() {}
}

/**
 * autoConnect ↔ engine 的重连握手抽象。
 *
 * autoConnect 通过 [IAutoConnect.setReconnector] 拿到本接口的实现,
 * 在定时器触发时调用 [restart],把「具体怎么连」的细节(查 KeyProvider, 销毁旧 client,
 * 调 engineOn 走 spawnNewClient)交给 engine 处理。
 *
 * 修了原来 `webSocketClient.reconnect()` 在 client=null 时 no-op 的死循环 bug —
 * engineOn 走 spawnNewClient 不依赖现有 client 存活。
 */
fun interface IReconnector {
    /** 触发一次重连尝试。实现负责拿最新 url + 起新 client + 状态机推进。 */
    fun restart()
}

/**
 * 切备用接入点回调
 *
 * 当 [NetSurveillance] 判定 [ReconnectAction.FAILOVER] 时被调用;
 * App 在实现里典型动作:挑下一个备用 URL, 调 `engine.engineOn(backupUrl)`.
 *
 * @return true = App 已接管切换流程,SDK 暂停当前 URL 的抢救调度;
 *         false = App 不接管,SDK 继续按 IMMEDIATE 退避在原 URL 上重试
 */
fun interface OnFailoverHandler {
    fun onFailover(): Boolean
}

/**
 * [ProgressiveAutoConnect] 当前状态快照,给调试 UI / 监控用。
 *
 * @param initReconnectDelayMs 配置的初始退避(Builder.initReconnectDelay)
 * @param maxReconnectDelayMs 配置的最大退避(Builder.maxReconnectDelay)
 * @param currentReconnectDelayMs 当前(下一次或正在等待的)退避值
 * @param retryCount 自上次 [ProgressiveAutoConnect.stopAutoConnect] 以来已排过的重连次数
 * @param lastAction 最近一次决策用的 [ReconnectAction];无 surveillance 时为 null
 * @param isAutoConnectActive 抢救流程是否激活(对应 [IAutoConnect.isActive] 的最高位)
 * @param isWaitingNextRetry 是否在等下一次重连的定时器上
 * @param isConnecting 当前 `webSocketClient.reconnect()` 是否在进行中
 */
data class ReconnectStatus(
    val initReconnectDelayMs: Long,
    val maxReconnectDelayMs: Long,
    val currentReconnectDelayMs: Long,
    val retryCount: Int,
    val lastAction: ReconnectAction?,
    val isAutoConnectActive: Boolean,
    val isWaitingNextRetry: Boolean,
    val isConnecting: Boolean,
)

class ProgressiveAutoConnect(val builder:Builder) : IAutoConnect{
    companion object {
        /**
         * IMMEDIATE / FAILOVER 决策对应的实际调度延时。
         *
         * 历史值是 initReconnectDelay=1000ms,语义跟 IMMEDIATE 自相矛盾,但小值不可靠 ——
         * 实测 HRY-AL00a 上 100ms / 500ms 的 setExact 都会被 Huawei 系统的 alarm 批处理吞掉,
         * 直到 1000ms 才稳定 fire。回到 1000ms 保稳定;短延时优化需要换调度器(例如 Handler /
         * Choreographer)而非 AlarmManager 才能做到,留作后续。
         *
         * 公开是因为 [ReconnectAction.IMMEDIATE] 的 KDoc 需要引用此常量,
         * 说清"立即"背后的实际调度延迟。业务侧不应当依赖此常量做调度,只用于阅读。
         */
        const val IMMEDIATE_DELAY_MS = 1000L

        /**
         * A7: 链路降级状态下的 attempt-timeout(默认 10s)。正常是 30s。
         * 缩短理由:R2 13h 死期那种 TLS 黑洞场景里,每次 attempt 30s 全部 timeout 是浪费;
         * 10s 内拿不到 TLS 响应基本就是真的黑洞了,早点切下一轮节电更划算。
         */
        const val DEGRADED_ATTEMPT_TIMEOUT_MS = 10_000L
    }

    private val syncConnectionLost = Any()
    private var webSocketClient: V2JavaWebEngine.WebSocketClientImpl? = null

    /** 由 engine 注入。null 时回退到 `webSocketClient.reconnect()`(老路径,client=null 会卡死) */
    @Volatile private var reconnector: IReconnector? = null

    override fun setReconnector(reconnector: IReconnector) {
        this.reconnector = reconnector
    }

    /**
     * 正在抢救中
     */
    private var isAutomaticallyConnecting = false

    /**
     * 连接中状态 连接结果，open/close
     */
    private var isConnectingAutomatically = false

    /**
     * 进入等待下一次连接
     */
    private var isWaitingNextAutomaticConnection = false


    /**
     * 心跳调度器
     */
    private val timeoutScheduler : ITimeoutScheduler = builder.timeoutScheduler

    /**
     * 网络编排层(可选);若提供,会按 [NetReport.recommend] 调整退避策略
     */
    private val surveillance: NetSurveillance? = builder.surveillance

    /**
     * FAILOVER 钩子(可选);编排层判定要切备用接入点时调用,App 实现切换
     */
    private val onFailover: OnFailoverHandler? = builder.onFailover


    // 初始值
    private val initReconnectDelay = builder.initReconnectDelay.toLong()
    private val maxReconnectDelay = builder.maxReconnectDelay.toLong()
    private var reconnectDelay = initReconnectDelay  // Reconnect delay, starts at 1

    /** 自上次 stopAutoConnect 以来重连尝试次数(供调试面板观察用) */
    @Volatile private var retryCount: Int = 0

    /** 最近一次决策时用到的 surveillance action;无 surveillance 时一直是 null */
    @Volatile private var lastAction: ReconnectAction? = null

    /**
     * 最近一次 [onSurveillanceUpdate] 实际 arm 的 effective action(throttle 后)。
     * 用于挡掉网络恢复瞬间的连续相同 IMMEDIATE 重排 race。在 [stopAutoConnect] 清零。
     */
    @Volatile private var lastEffectiveAction: ReconnectAction? = null

    /** 最近一次实际调度的 delay(可能是 init / max / 指数退避值)— 给调试面板看 */
    @Volatile private var lastScheduledDelay: Long = initReconnectDelay

    /**
     * 连续按 IMMEDIATE 快速重连(initReconnectDelay 间隔)却失败的次数。
     * 到 [maxImmediateRetries] 后,即便 surveillance 仍推 IMMEDIATE 也强制走指数退避 —
     * 防止 surveillance 误判"网络 OK"时无限 1s 重连耗电(隧道/无信号场景)。
     * 只在 [stopAutoConnect](连接成功 / 手动停)时清零。
     */
    @Volatile private var consecutiveImmediateRetries: Int = 0
    private val maxImmediateRetries: Int = builder.maxImmediateRetries

    /**
     * A7: 连续 attempt-timeout 计数 + 最近一次 probe 是否 TLS_FAILURE/PROBE_TIMEOUT。
     * 同时满足 N 次 timeout + TLS 类失败时,SDK 进入"链路降级"状态:
     *  - attemptTimeoutMs 临时缩短到 10s(避免每次 30s 等死)
     *  - reconnectDelay 上限拉到 maxReconnectDelay × 2(节电)
     * emit [ImcEvent.LinkDegraded] 一次,业务侧拿到后可以 UI 提示用户切网络。
     * 任意成功 onOpen / stopAutoConnect 时清除降级状态。
     */
    @Volatile private var consecutiveAttemptTimeouts: Int = 0
    @Volatile private var lastProbeWasTlsLikeFailure: Boolean = false
    @Volatile private var inLinkDegradedState: Boolean = false
    private val degradeOnConsecutiveTimeouts: Int = 3

    /**
     * A6: 自上次 [stopAutoConnect] 以来,连续 SSL 类异常计数。
     * 达到 [sslBurstThreshold] 时触发一次 forceProbe(reason="ssl-burst"),
     * 让 surveillance 跑公网参考探测 + 接入点探测,区分"服务端 TLS 烂"vs"链路 SSL 拦截"。
     * 阈值上只触发一次,直到连接成功 / 显式停 / 收到非 SSL 异常时清零。
     */
    @Volatile private var consecutiveSslErrors: Int = 0
    @Volatile private var sslBurstProbeRequested: Boolean = false
    private val sslBurstThreshold: Int = 3

    /**
     * A14: 自上次 [stopAutoConnect](连接成功 / 显式停)以来,FAILOVER 钩子返回 handled=false 的累计次数。
     * 累计到 [failoverUnhandledThreshold] 时 emit 一条 [ImcEvent.FailoverUnhandled],提示业务漏配 backup URL。
     * 阈值上仅 emit 一次,避免噪音(`failoverUnhandledNotified`)。
     */
    @Volatile private var failoverUnhandledCount: Int = 0
    @Volatile private var failoverUnhandledNotified: Boolean = false
    private val failoverUnhandledThreshold: Int = 10

    /**
     * 是否已经在当前这一轮"硬地板降级"里请求过 surveillance 主动探测。
     *
     * 触发场景:连续 [maxImmediateRetries] 次 IMMEDIATE 都没建链,WS 显示一直断、但
     * L1 NetStateMonitor 全程没回调过(典型于运营商 NAT 抖动 / DNS 软故障)—— 此时主动让
     * NetSurveillance 跑一次 probe 来真实测一次,补足 L1/L3 与 WS 状态脱节的盲区。
     *
     * 只在每一轮"开始抢救 → 抢救结束"里 forceProbe 一次,防止持续重连风暴打爆探测。
     * 在 [stopAutoConnect](连接成功 / 显式停)清零,下一轮抢救才会再发一次。
     */
    @Volatile private var probedForHardFloor: Boolean = false

    /**
     * 单次 attempt 超时兜底(从 [startConnect] fire 到 onOpen/onError/onClose 回归之间的最大允许时间)。
     *
     * 设计目的:防止"reconnector.restart 后下游静默卡死"导致 [isConnectingAutomatically] 永远 true,
     * 此后任何 [startAutoConnect] 入口都被早返回挡掉、引擎再也连不上。
     *
     * 主防线是 V2JavaWebEngine 的 handshake watchdog (15s);本字段是次防线 —— 如果
     * watchdog 因 RRFv4 worker race / java-websocket connect() 内部沉默 / 其他原因没 fire,
     * 这条 30s 后强制清掉 [isConnectingAutomatically] 并重排 [waitingNextAutomaticConnection]。
     * 30s 故意大于 handshake 15s 一倍以上:正常 handshake 失败 → onClose/onError → 走标准
     * abnormalDisconnectionAndAutomaticReconnection 的链路应该在 15s 内回归,30s 还没回就一定异常。
     */
    private val attemptTimeoutMs: Long = builder.attemptTimeoutMs.toLong()
    private val attemptWatchdog = RapidResponseForceV4()
    private val attemptWatchdogId = "auto-attempt-watchdog"

    private val timeoutCall = object : Callable<Void> {
        override fun call(): Void? {
            // 自动连接
            autoReconnect()
            return null
        }

    }

    /** 订阅 surveillance:在等下次重连期间,recommend 一变就重排定时器 */
    private val surveillanceListener = NetReportListener { onSurveillanceUpdate(it) }

    @Volatile private var disposed = false

    init {
        surveillance?.register(surveillanceListener)
    }

    /**
     * 释放 surveillance 的 listener 引用,防止 engine 重建场景的内存泄漏。
     *
     * 幂等;dispose 后再调 startAutoConnect 等接口仍可工作,但 surveillance 不再驱动它
     * (因为没人 register 了)。所以正常用法是 dispose = 终态。
     */
    override fun dispose() {
        if (disposed) return
        disposed = true
        surveillance?.unregister(surveillanceListener)
        // 显式 clear 一次:stopAutoConnect 也会 unregister,这里兜底确保 RRFv4 worker 完全
        // 释放(防止 engineOff 后 attempt watchdog 还在持有外部回调引用)
        attemptWatchdog.clear()
        stopAutoConnect()
    }

    override fun initAutoConnect(webSocketClient: V2JavaWebEngine.WebSocketClientImpl) {
        this.webSocketClient = webSocketClient
        timeoutScheduler.setCallback(timeoutCall)
    }

    /**
     * Surveillance 推新 NetReport 时的实时响应。
     * 只在"正在等下次重连"的状态下生效 — 其它状态(没在抢救 / 正在 connect)不打扰。
     *
     *  - WAIT_USER → 立即停止抢救(门户认证 / 阻塞,等用户解决)
     *  - FAILOVER  → 调 onFailover 钩子;App 接管就停,不接管按 IMMEDIATE 走
     *  - 其它      → 按新 action 重算 delay,取消当前定时,立即重排
     */
    /**
     * A8: 上一次见到的 transport 集合,用于检测 transports 变化(典型 WIFI → VPN-only)。
     * 旧的 socket 在传输层切换后通常已死,但 TCP/TLS 层不一定立即察觉,主动 kick 重连可大幅
     * 加快恢复速度(R2 13h 死期里就是 Wi-Fi 掉到 VPN 那一瞬间服务端突然能响应)。
     */
    @Volatile private var lastSeenTransports: Set<NetTransport>? = null

    private fun onSurveillanceUpdate(report: NetReport) {
        // A8: transports 变化检测 —— 即便 verdict 没变也要触发重连
        val newTransports = report.snapshot.transports
        val transportsChanged = synchronized(syncConnectionLost) {
            val prev = lastSeenTransports
            lastSeenTransports = newTransports
            prev != null && prev != newTransports
        }
        if (transportsChanged) {
            ImcEvents.emit(
                ImcEvent.AutoConnectAborted("transports-changed:${newTransports}")
            )
            // 当前 connected → 戳一下 reconnector 让它重建 client(走 engineOn → spawnNewClient)
            // 当前 waiting → 重排到 IMMEDIATE
            val wsOpen = isOpen()
            if (wsOpen) {
                reconnector?.restart()
                return
            }
            synchronized(syncConnectionLost) {
                if (isWaitingNextAutomaticConnection) {
                    reconnectDelay = initReconnectDelay
                    timeoutScheduler.stop()
                    timeoutScheduler.start(IMMEDIATE_DELAY_MS)
                    lastScheduledDelay = IMMEDIATE_DELAY_MS
                    lastEffectiveAction = ReconnectAction.IMMEDIATE
                }
            }
            // 继续往下走正常 recommend 决策,不直接 return —— 万一 surveillance 推 BACKOFF_LONG/WAIT_USER 还要尊重
        }

        // 不在等待 → 当前没有定时器要重排,直接忽略
        val waiting = synchronized(syncConnectionLost) { isWaitingNextAutomaticConnection }
        if (!waiting) return

        val action = report.recommend
        lastAction = action

        when (action) {
            ReconnectAction.WAIT_USER -> {
                ImcEvents.emit(ImcEvent.AutoConnectAborted("surveillance:WAIT_USER"))
                stopAutoConnect()
                return
            }
            ReconnectAction.FAILOVER -> {
                val handled = onFailover?.let {
                    try { it.onFailover() } catch (e: Exception) {
                        e.printStackTrace(); false
                    }
                } ?: false
                ImcEvents.emit(ImcEvent.FailoverInvoked(handledByApp = handled))
                if (handled) {
                    ImcEvents.emit(ImcEvent.AutoConnectAborted("surveillance:FAILOVER_HANDLED"))
                    stopAutoConnect()
                    return
                }
                trackFailoverUnhandled()
                // 未接管 → 走 IMMEDIATE 路径
            }
            else -> {}
        }

        synchronized(syncConnectionLost) {
            if (!isWaitingNextAutomaticConnection) return  // 期间被别处停了
            // 这里只是重排 pending 的那次重试,不算新的一次 attempt,所以 throttle 只读不 +1
            val effective = throttleActionLocked(action)
            // 网络恢复瞬间 surveillance 会在 ~200ms 内连推 3-4 次 IMMEDIATE(onCapabilitiesChanged
            // / onAvailable / probe 结果各一次)。每次都 stop+start 100ms 会让 alarm 还没 fire 就
            // 被 cancel,导致 attempt 永远不触发。这里挡掉同 effective action 的重复重排。
            // 走到这里 isWaitingNextAutomaticConnection==true,意味着上一次 start 的 alarm 还没 fire。
            // 同 effective action 就别再 cancel+arm,直接让原 alarm 兜底吧。
            if (effective == lastEffectiveAction) {
                return
            }
            val newDelay = when (effective) {
                ReconnectAction.IMMEDIATE,
                ReconnectAction.FAILOVER -> {
                    // IMMEDIATE/FAILOVER 代表编排层判定网络恢复了 —— 把指数退避基数也压回
                    // initReconnectDelay,否则后面再失败会从上一次的大 delay 继续翻倍。
                    reconnectDelay = initReconnectDelay
                    IMMEDIATE_DELAY_MS
                }
                ReconnectAction.BACKOFF_LONG -> {
                    // 确认离线 → 清空快试额度,等真恢复时重新发 3 次快试
                    consecutiveImmediateRetries = 0
                    maxReconnectDelay
                }
                else -> reconnectDelay  // 含 IMMEDIATE 被 throttle 降级成 BACKOFF_NORMAL 的情况
            }
            timeoutScheduler.stop()
            timeoutScheduler.start(newDelay)
            lastScheduledDelay = newDelay
            lastEffectiveAction = effective
        }
    }

    override fun abnormalDisconnectionAndAutomaticReconnection() {
        val action = consultRecommend()
        // 编排层若判定要等用户介入(门户认证/被阻塞), 直接放弃自动抢救;
        // App 需要在用户解决问题后主动调 makeConnection() / resetStartAutoConnect()
        if (action == ReconnectAction.WAIT_USER) {
            ImcEvents.emit(ImcEvent.AutoConnectAborted("WAIT_USER"))
            return
        }
        // 编排层判定切备用接入点: 先让 App 接管, 若已处理则 SDK 不再调度本 URL 的重连
        if (action == ReconnectAction.FAILOVER && onFailover != null) {
            val handled = try { onFailover.onFailover() } catch (e: Exception) {
                e.printStackTrace(); false
            }
            ImcEvents.emit(ImcEvent.FailoverInvoked(handledByApp = handled))
            if (handled) {
                ImcEvents.emit(ImcEvent.AutoConnectAborted("FAILOVER_HANDLED"))
                return
            }
            trackFailoverUnhandled()
        }
        // 连接结果初始化
        synchronized(syncConnectionLost) {
            isConnectingAutomatically = false
        }
        // 上一次 attempt 已经回归(收到了 onError/onClose),attempt watchdog 完成了使命,撤掉
        // 否则 30s 兜底会在新一轮 attempt 没起来时误开火
        attemptWatchdog.unregister(attemptWatchdogId)
        // 初次还是多次
        if (isAutomaticallyConnecting) {
            // 连接已经过了
            waitingNextAutomaticConnection(reconnectDelay)
            return
        }
        // 初次
        startAutoConnect()
    }

    override fun resetStartAutoConnect() {
        stopAutoConnect()
        startAutoConnect()
    }

    override fun startAutoConnect(){
        synchronized(syncConnectionLost) {
            if (isOpen()) {
                // 不需要抢救
                return
            }
            if (isAutomaticallyConnecting) {
                //多次启动 直接返回
                return
            }
            if (isConnectingAutomatically) {
                // 正在连接 直接返回
                return
            }
            // 初次 记录一下初次
            isAutomaticallyConnecting = true
            waitingNextAutomaticConnection(reconnectDelay)
        }
    }

    override fun stopAutoConnect(){
        val wasActive = synchronized(syncConnectionLost) {
            val active = isAutomaticallyConnecting || isConnectingAutomatically || isWaitingNextAutomaticConnection
            isAutomaticallyConnecting = false
            isConnectingAutomatically = false
            isWaitingNextAutomaticConnection = false
            reconnectDelay = initReconnectDelay
            retryCount = 0
            consecutiveImmediateRetries = 0
            probedForHardFloor = false
            consecutiveSslErrors = 0
            sslBurstProbeRequested = false
            consecutiveAttemptTimeouts = 0
            lastProbeWasTlsLikeFailure = false
            inLinkDegradedState = false
            lastSeenTransports = null
            failoverUnhandledCount = 0
            failoverUnhandledNotified = false
            lastAction = null
            lastEffectiveAction = null
            lastScheduledDelay = initReconnectDelay
            timeoutScheduler.stop()
            active
        }
        // attempt watchdog 跟着 isConnectingAutomatically 走;stopAutoConnect 既清后者也清前者。
        // 锁外做,避免 unregister 的内部 lock 跟 syncConnectionLost 嵌套。
        attemptWatchdog.unregister(attemptWatchdogId)
        // 仅当确实在抢救中才 emit aborted,避免 stopAutoConnect 被反复幂等调用时噪声爆炸
        if (wasActive) ImcEvents.emit(ImcEvent.AutoConnectAborted("stopAutoConnect"))
    }

    override fun isActive(): Boolean = synchronized(syncConnectionLost) {
        isAutomaticallyConnecting || isConnectingAutomatically || isWaitingNextAutomaticConnection
    }
    /**
     * 等待下一次 自动连接
     */
    private fun waitingNextAutomaticConnection(delay: Long) {
        val rawAction = consultRecommend()
        // 硬地板被触发(IMMEDIATE → BACKOFF_NORMAL)且本轮还没探测过的标志,锁内置位、锁外触发
        var requestProbe = false
        var probeFailureCount = 0
        synchronized(syncConnectionLost) {
            if (isWaitingNextAutomaticConnection) {
                return
            }
            // 套上"连续快试上限"硬地板:额度用尽后即便 surveillance 仍推 IMMEDIATE 也强制退避
            val action = throttleActionLocked(rawAction)
            if (rawAction == ReconnectAction.IMMEDIATE &&
                action == ReconnectAction.BACKOFF_NORMAL &&
                !probedForHardFloor
            ) {
                probedForHardFloor = true
                requestProbe = true
                probeFailureCount = consecutiveImmediateRetries
            }
            val nextDelay = when (action) {
                // 编排层认为网络一切正常,立即重试 —— 真的立即,0ms
                // (历史是 initReconnectDelay=1000ms,语义跟 IMMEDIATE 自相矛盾,导致 4a 测试
                //  wifi 恢复 → onOpen 平均 5.5s 里有 1s 是这个无谓等待;
                //  throttle 硬地板 [maxImmediateRetries] 仍兜底,IMMEDIATE 连失 3 次会自动降级到
                //  BACKOFF_NORMAL,不会变成 0ms 死循环)
                ReconnectAction.IMMEDIATE,
                ReconnectAction.FAILOVER -> {
                    consecutiveImmediateRetries++  // 用掉一次快试额度
                    IMMEDIATE_DELAY_MS
                }
                // 用户没网/挂起,直接顶满;确认离线 → 清空快试额度,等真恢复重新给
                ReconnectAction.BACKOFF_LONG -> {
                    consecutiveImmediateRetries = 0
                    maxReconnectDelay
                }
                // 其余(BACKOFF_NORMAL / 无 surveillance)走原指数退避
                ReconnectAction.WAIT_USER,
                ReconnectAction.BACKOFF_NORMAL,
                null -> {
                    if (reconnectDelay < maxReconnectDelay) {
                        reconnectDelay = delay * 2
                    }
                    reconnectDelay
                }
            }
            isWaitingNextAutomaticConnection = true
            // 记录给调试面板用,不污染指数退避的内部 reconnectDelay
            retryCount++
            lastAction = action
            lastEffectiveAction = action
            lastScheduledDelay = nextDelay
            timeoutScheduler.start(nextDelay)
        }
        // 锁外 emit:这是公开 API 调用栈上的关键事件,sink 卡了不能反过来卡退避调度
        ImcEvents.emit(
            ImcEvent.AutoConnectScheduled(
                delayMs = lastScheduledDelay,
                action = lastEffectiveAction,
                retryCount = retryCount,
            )
        )
        // 硬地板触发的主动探测:L1 没回调 / surveillance 一直推 IMMEDIATE,但 3 次快试都没建链 ——
        // 让 surveillance 跑一次 forceProbe 真实测一下接入点;探测回包后通过 forceProbe 自带 callback
        // 直接驱动重排,bypass [onSurveillanceUpdate] 的 throttle 检查 —— 这是 A11 修法。
        //
        // 旧版用 no-op callback 依赖 listener 路径,但 [onSurveillanceUpdate] 里有
        //   `if (effective == lastEffectiveAction) return`
        // 的 throttle,当 hard-floor 把 IMMEDIATE → BACKOFF_NORMAL 后,probe 回来的 recommend
        // 即使是 BACKOFF_NORMAL/IMMEDIATE 也都会被 throttle 同值挡掉(R2 hit #1/#2 实测到)。
        // 走 forceProbe 自带 callback 等价于"这次结果保证落地",不被 throttle 拦。
        if (requestProbe) {
            ImcEvents.emit(
                ImcEvent.AutoConnectProbeRequested(
                    reason = "hard-floor",
                    consecutiveFailures = probeFailureCount,
                )
            )
            try {
                surveillance?.forceProbe { newReport -> applyProbeResult(newReport) }
            } catch (e: Exception) {
                ImcEvents.emit(
                    ImcEvent.InternalError(
                        site = "ProgressiveAutoConnect.forceProbe",
                        errorClass = e.javaClass.simpleName,
                        message = e.message,
                    )
                )
            }
        }
    }

    /**
     * A11: hard-floor probe 完成后用 probe 结果重排 timer。
     *
     * 必须**bypass throttle 比较**(`effective == lastEffectiveAction`),否则 hard-floor
     * 已经把当前 action 设为 BACKOFF_NORMAL,probe 回来无论给什么 recommend 都会被同值挡掉。
     *
     * 边界:
     *  - 期间 stopAutoConnect 已清:`isWaitingNextAutomaticConnection == false`,probe 作废,直接退出
     *  - 期间 timer 已 fire(attempt 在跑):同上,probe 来晚一步,作废
     *  - 期间被 [onSurveillanceUpdate] 提前应用了同一份 report:lastEffectiveAction 已变,这里再 arm
     *    一次也是无害的(同 delay 重启 timer)
     */
    private fun applyProbeResult(report: NetReport) {
        val action = report.recommend
        // A7: probe verdict 是 TLS_FAILURE / PROBE_TIMEOUT 这种"TLS 类不通"时,记录起来,
        // 与连续 attempt-timeout 配合判定是否进入链路降级。
        val isTlsLike = report.lastProbe?.verdict?.let {
            it == ProbeVerdict.TLS_FAILURE || it == ProbeVerdict.PROBE_TIMEOUT
        } ?: false
        var degradeNow = false
        var triggerTimeouts = 0
        synchronized(syncConnectionLost) {
            if (!isWaitingNextAutomaticConnection) return
            lastProbeWasTlsLikeFailure = isTlsLike
            if (isTlsLike &&
                consecutiveAttemptTimeouts >= degradeOnConsecutiveTimeouts &&
                !inLinkDegradedState
            ) {
                inLinkDegradedState = true
                degradeNow = true
                triggerTimeouts = consecutiveAttemptTimeouts
            }
            val effective = throttleActionLocked(action)
            val newDelay = when (effective) {
                ReconnectAction.IMMEDIATE,
                ReconnectAction.FAILOVER -> {
                    reconnectDelay = initReconnectDelay
                    IMMEDIATE_DELAY_MS
                }
                ReconnectAction.BACKOFF_LONG -> {
                    consecutiveImmediateRetries = 0
                    maxReconnectDelay
                }
                else -> {
                    // A7: 进入降级后退避上限拉到 maxReconnectDelay × 2,节电(13h 死期那种场景)
                    if (inLinkDegradedState && reconnectDelay >= maxReconnectDelay) {
                        maxReconnectDelay * 2
                    } else reconnectDelay
                }
            }
            timeoutScheduler.stop()
            timeoutScheduler.start(newDelay)
            lastAction = action
            lastEffectiveAction = effective
            lastScheduledDelay = newDelay
        }
        if (degradeNow) {
            ImcEvents.emit(
                ImcEvent.LinkDegraded(
                    reason = ImcEvent.LinkDegraded.Reason.TLS_BLACKHOLE,
                    consecutiveTimeouts = triggerTimeouts,
                )
            )
        }
        ImcEvents.emit(
            ImcEvent.AutoConnectScheduled(
                delayMs = lastScheduledDelay,
                action = lastEffectiveAction,
                retryCount = retryCount,
            )
        )
    }

    /**
     * A6: engine.onError 通知 autoConnect 最近一次错的类别。
     * 是 SSL 类(SSLHandshakeException / SSLProtocolException / SSLException 等)就累计,达阈值触发一次 forceProbe。
     * 是其它类(IllegalStateException / WebsocketNotConnectedException 等)清零计数。
     */
    override fun onErrorSignal(errorClass: String?) {
        val isSsl = errorClass != null && errorClass.startsWith("SSL")
        val shouldTriggerProbe = synchronized(syncConnectionLost) {
            // A7: 收到任何"真的"onError(不是 timeout 兜底)→ attempt 是回归了,清 timeout 计数
            consecutiveAttemptTimeouts = 0
            if (!isSsl) {
                consecutiveSslErrors = 0
                return@synchronized false
            }
            consecutiveSslErrors += 1
            val trigger = consecutiveSslErrors >= sslBurstThreshold && !sslBurstProbeRequested
            if (trigger) sslBurstProbeRequested = true
            trigger
        }
        if (!shouldTriggerProbe) return
        ImcEvents.emit(
            ImcEvent.AutoConnectProbeRequested(
                reason = "ssl-burst",
                consecutiveFailures = consecutiveSslErrors,
            )
        )
        try {
            surveillance?.forceProbe { newReport -> applyProbeResult(newReport) }
        } catch (e: Exception) {
            ImcEvents.emit(
                ImcEvent.InternalError(
                    site = "ProgressiveAutoConnect.forceProbe(ssl-burst)",
                    errorClass = e.javaClass.simpleName,
                    message = e.message,
                )
            )
        }
    }

    /**
     * A14: FAILOVER 未被业务接管时累计计数;到阈值 emit 一次提醒,提示业务侧补 backup URL。
     * 必须锁外调用(emit 不允许在锁内)。
     */
    private fun trackFailoverUnhandled() {
        val (count, shouldNotify) = synchronized(syncConnectionLost) {
            failoverUnhandledCount += 1
            val notify = failoverUnhandledCount >= failoverUnhandledThreshold && !failoverUnhandledNotified
            if (notify) failoverUnhandledNotified = true
            failoverUnhandledCount to notify
        }
        if (shouldNotify) {
            ImcEvents.emit(
                ImcEvent.FailoverUnhandled(
                    totalUnhandled = count,
                    threshold = failoverUnhandledThreshold,
                )
            )
        }
    }

    /**
     * 给 surveillance 的原始 action 套上"连续快试上限"硬地板。必须在 syncConnectionLost 锁内调用。
     *
     * IMMEDIATE 连续失败 [maxImmediateRetries] 次后,强制降级成 BACKOFF_NORMAL 走指数退避 —
     * 这是与 surveillance 判断无关的兜底,保证"无信号时不会无限 1s 重连耗电"。
     */
    private fun throttleActionLocked(rawAction: ReconnectAction?): ReconnectAction? {
        if (rawAction != ReconnectAction.IMMEDIATE) return rawAction
        if (consecutiveImmediateRetries < maxImmediateRetries) return rawAction
        return ReconnectAction.BACKOFF_NORMAL
    }

    /** 返回当前抢救状态快照,只读,可安全在任意线程调用(包括 UI 线程) */
    fun status(): ReconnectStatus = synchronized(syncConnectionLost) {
        ReconnectStatus(
            initReconnectDelayMs = initReconnectDelay,
            maxReconnectDelayMs = maxReconnectDelay,
            currentReconnectDelayMs = lastScheduledDelay,
            retryCount = retryCount,
            lastAction = lastAction,
            isAutoConnectActive = isAutomaticallyConnecting,
            isWaitingNextRetry = isWaitingNextAutomaticConnection,
            isConnecting = isConnectingAutomatically,
        )
    }

    private fun consultRecommend(): ReconnectAction? =
        surveillance?.current()?.recommend
    private fun startConnect() {
        synchronized(syncConnectionLost) {
            if (isConnectingAutomatically) {
                return
            }
            isConnectingAutomatically = true
        }
        // attempt 超时兜底:reconnector.restart 调用后下游(engine.engineOn → spawnNewClient
        // → client.connect)出问题时,主防线是 V2JavaWebEngine 的 handshake watchdog (15s)
        // 走 onClose/onError 抢救链;本兜底 30s 是次防线,防止 watchdog 自身失效造成
        // isConnectingAutomatically 永久 true 的引擎卡死(P0)。
        // A7: 链路降级状态下缩短到 10s,避免 13h 死期那种"每次 30s 等死"的浪费。
        // 锁外 register,避免与 reconnector 回调中可能持有的其它锁嵌套。
        val effectiveTimeout = if (inLinkDegradedState) DEGRADED_ATTEMPT_TIMEOUT_MS else attemptTimeoutMs
        attemptWatchdog.register(attemptWatchdogId, effectiveTimeout) { _ ->
            onAttemptTimeout()
        }
        // 当前 attempt 的目标 key,debug 用
        val targetKey: String? = try {
            webSocketClient?.uri?.toString()
        } catch (_: Exception) { null }
        ImcEvents.emit(ImcEvent.AutoConnectFired(targetKey))
        try {
            val r = reconnector
            if (r != null) {
                // 新路径:走 engine.engineOn → spawnNewClient,拿 KeyProvider 提供的最新 url。
                // 不依赖 webSocketClient 存活,修了 client=null 时 reconnect() no-op 的 P0-1 bug。
                r.restart()
            } else {
                // 回退:老 SDK 集成、没注入 reconnector 的场景
                webSocketClient?.reconnect()
            }
        }catch (e: Exception){
            e.printStackTrace()
        }
    }

    /**
     * attempt 超时兜底回调:[attemptTimeoutMs] 内还没收到 onOpen/onError/onClose(对应路径分别
     * 是 stopAutoConnect / abnormalDisconnectionAndAutomaticReconnection 来清 [isConnectingAutomatically]),
     * 视作本轮 attempt 失败,强制重排下一轮。
     *
     * 故意不清 [isAutomaticallyConnecting](抢救流程仍激活),也不动 [reconnectDelay](沿用当前指数退避),
     * 让 [waitingNextAutomaticConnection] 接着走正常退避序列。
     */
    private fun onAttemptTimeout() {
        val shouldReschedule = synchronized(syncConnectionLost) {
            if (!isConnectingAutomatically) {
                // 这期间 onOpen/onError 已经走过标准路径,attempt 已正常结束,什么都不做
                return@synchronized false
            }
            isConnectingAutomatically = false
            // A7: 累计 attempt-timeout,与 probe 的 TLS_FAILURE 一起判定是否进入降级
            consecutiveAttemptTimeouts += 1
            true
        }
        if (!shouldReschedule) return
        // A7: 真实的有效 timeout 长度(降级状态下缩短到 10s,正常 30s)
        val effectiveTimeout = if (inLinkDegradedState) DEGRADED_ATTEMPT_TIMEOUT_MS else attemptTimeoutMs
        ImcEvents.emit(ImcEvent.AutoConnectAborted("attempt-timeout-${effectiveTimeout}ms"))
        // 直接进入下一轮等待。reconnectDelay 用当前值,不重置 — 卡死的 attempt 应该被视作失败,
        // 指数退避自然增长(但 surveillance IMMEDIATE / FAILOVER 仍会压回 initReconnectDelay)
        waitingNextAutomaticConnection(reconnectDelay)
    }

    private fun autoReconnect(){
        synchronized(syncConnectionLost) {
            isWaitingNextAutomaticConnection = false
        }
        startConnect()
    }

    private fun isOpen():Boolean{
        return webSocketClient?.isOpen == true
    }

    class Builder{
        internal var initReconnectDelay : Int = 1000
        internal var maxReconnectDelay : Int = 128 * 1000

        /**
         * 连续 IMMEDIATE 快速重连的次数上限,默认 3。
         * 超过后即便 surveillance 仍推 IMMEDIATE 也强制走指数退避 —
         * 对齐 spec「网络恢复后 3 次重连内建链」+「无信号时退避省电」。
         */
        internal var maxImmediateRetries : Int = 3

        /**
         * 单次 attempt 超时兜底,默认 30000ms。详见 [ProgressiveAutoConnect.attemptTimeoutMs] KDoc。
         */
        internal var attemptTimeoutMs : Int = 30 * 1000

        fun maxReconnectDelay(delay: Long, unit: TimeUnit) = apply {
            maxReconnectDelay = checkDurationMs("maxReconnectDelay", delay, unit)
        }

        fun initReconnectDelay(delay: Long, unit: TimeUnit) = apply {
            initReconnectDelay = checkDurationMs("initReconnectDelay", delay, unit)
        }

        fun maxImmediateRetries(count: Int) = apply {
            require(count >= 1) { "maxImmediateRetries 必须 >= 1,当前 $count" }
            maxImmediateRetries = count
        }

        fun attemptTimeout(time: Long, unit: TimeUnit) = apply {
            attemptTimeoutMs = checkDurationMs("attemptTimeout", time, unit)
        }

        // 心跳调度器
        internal var timeoutScheduler : ITimeoutScheduler = RRFTimeoutScheduler()

        fun setTimeoutScheduler(timeoutScheduler: ITimeoutScheduler) : Builder {
            this.timeoutScheduler = timeoutScheduler
            return this
        }

        /** 网络编排层(可选)。提供后会按 [NetReport.recommend] 智能调整退避策略 */
        internal var surveillance: NetSurveillance? = null

        fun surveillance(s: NetSurveillance): Builder = apply { this.surveillance = s }

        /** FAILOVER 钩子(可选)。判定切备用接入点时回调 App,App 决定如何换 URL */
        internal var onFailover: OnFailoverHandler? = null

        fun onFailover(handler: OnFailoverHandler): Builder = apply { this.onFailover = handler }

        fun build():ProgressiveAutoConnect{
            return ProgressiveAutoConnect(this)
        }
    }
}