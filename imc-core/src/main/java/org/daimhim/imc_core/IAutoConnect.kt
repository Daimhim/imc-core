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
         */
        private const val IMMEDIATE_DELAY_MS = 1000L
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

    private val timeoutCall = object : Callable<Void> {
        override fun call(): Void? {
            // 自动连接
            autoReconnect()
            return null
        }

    }

    /** 订阅 surveillance:在等下次重连期间,recommend 一变就重排定时器 */
    private val surveillanceListener = NetReportListener { onSurveillanceUpdate(it) }

    init {
        surveillance?.register(surveillanceListener)
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
    private fun onSurveillanceUpdate(report: NetReport) {
        // 不在等待 → 当前没有定时器要重排,直接忽略
        val waiting = synchronized(syncConnectionLost) { isWaitingNextAutomaticConnection }
        if (!waiting) return

        val action = report.recommend
        lastAction = action
        IMCLog.i("[autoConnect] surveillance 推新建议: $action, 重新决策")

        when (action) {
            ReconnectAction.WAIT_USER -> {
                IMCLog.i("[autoConnect] WAIT_USER → 暂停自动抢救")
                stopAutoConnect()
                return
            }
            ReconnectAction.FAILOVER -> {
                val handled = onFailover?.let {
                    try { it.onFailover() } catch (e: Exception) {
                        e.printStackTrace(); false
                    }
                } ?: false
                if (handled) {
                    IMCLog.i("[autoConnect] FAILOVER 已被 App 接管 → 停止抢救")
                    stopAutoConnect()
                    return
                }
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
                IMCLog.i("[autoConnect] 同 action=$effective 已在排队中, 跳过重排")
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
            IMCLog.i("[autoConnect] 按新建议重排 → ${newDelay}ms (effective=$effective reconnectDelay基数=$reconnectDelay immRetries=$consecutiveImmediateRetries)")
        }
    }

    override fun abnormalDisconnectionAndAutomaticReconnection() {
        val action = consultRecommend()
        // 编排层若判定要等用户介入(门户认证/被阻塞), 直接放弃自动抢救;
        // App 需要在用户解决问题后主动调 makeConnection() / resetStartAutoConnect()
        if (action == ReconnectAction.WAIT_USER) {
            IMCLog.i("ReconnectAction.WAIT_USER 暂停自动抢救")
            return
        }
        // 编排层判定切备用接入点: 先让 App 接管, 若已处理则 SDK 不再调度本 URL 的重连
        if (action == ReconnectAction.FAILOVER && onFailover != null) {
            val handled = try { onFailover.onFailover() } catch (e: Exception) {
                e.printStackTrace(); false
            }
            if (handled) {
                IMCLog.i("FAILOVER 已由 App 接管, SDK 暂停当前 URL 的抢救")
                return
            }
        }
        // 连接结果初始化
        synchronized(syncConnectionLost) {
            isConnectingAutomatically = false
        }
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
        IMCLog.i("开始抢救 isConnecting_Automatically:${isConnectingAutomatically} ${isAutomaticallyConnecting} isOpen:${isOpen()}")
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
        IMCLog.i("停止抢救")
        synchronized(syncConnectionLost) {
            isAutomaticallyConnecting = false
            isConnectingAutomatically = false
            isWaitingNextAutomaticConnection = false
            reconnectDelay = initReconnectDelay
            retryCount = 0
            consecutiveImmediateRetries = 0
            lastAction = null
            lastEffectiveAction = null
            lastScheduledDelay = initReconnectDelay
            timeoutScheduler.stop()
        }
    }

    override fun isActive(): Boolean = synchronized(syncConnectionLost) {
        isAutomaticallyConnecting || isConnectingAutomatically || isWaitingNextAutomaticConnection
    }
    /**
     * 等待下一次 自动连接
     */
    private fun waitingNextAutomaticConnection(delay: Long) {
        IMCLog.i("等待下一次 ${delay} isWaitingNextAutomaticConnection:${isWaitingNextAutomaticConnection} reconnectDelay:$reconnectDelay")
        val rawAction = consultRecommend()
        synchronized(syncConnectionLost) {
            if (isWaitingNextAutomaticConnection) {
                return
            }
            // 套上"连续快试上限"硬地板:额度用尽后即便 surveillance 仍推 IMMEDIATE 也强制退避
            val action = throttleActionLocked(rawAction)
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
            IMCLog.i("等待下一次 action=$action delay=$nextDelay retry=$retryCount immRetries=$consecutiveImmediateRetries")
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
        IMCLog.i("[autoConnect] 连续 $consecutiveImmediateRetries 次快速重连失败,强制退避(忽略 IMMEDIATE)")
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
        IMCLog.i("startConnect 111 ${isConnectingAutomatically}")
        synchronized(syncConnectionLost) {
            if (isConnectingAutomatically) {
                return
            }
            isConnectingAutomatically = true
        }
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
        IMCLog.i("startConnect 222")
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