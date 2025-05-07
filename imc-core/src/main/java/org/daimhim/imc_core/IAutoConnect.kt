package org.daimhim.imc_core

import okhttp3.internal.checkDuration
import org.daimhim.imc_core.V2FixedHeartbeat.Builder
import timber.multiplatform.log.Timber
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
}

class ProgressiveAutoConnect(val builder:Builder) : IAutoConnect{
    private val syncConnectionLost = Any()
    private var webSocketClient: V2JavaWebEngine.WebSocketClientImpl? = null

    /**
     * 正在抢救中
     */
    private var isAutomaticallyConnecting = false

    /**
     * 连接中状态 连接结果，open/close
     */
    private var isConnecting_Automatically = false

    /**
     * 进入等待下一次连接
     */
    private var isWaitingNextAutomaticConnection = false


    /**
     * 心跳调度器
     */
    private val timeoutScheduler : ITimeoutScheduler = builder.timeoutScheduler


    // 初始值
    private val initReconnectDelay = 1000L
    internal var reconnectDelay = initReconnectDelay  // Reconnect delay, starts at 1
    var maxReconnectDelay = 128_1000L

    private val timeoutCall = object : Callable<Void> {
        override fun call(): Void? {
            // 自动连接
            autoReconnect()
            return null
        }

    }

    override fun initAutoConnect(webSocketClient: V2JavaWebEngine.WebSocketClientImpl) {
        this.webSocketClient = webSocketClient
        timeoutScheduler.setCallback(timeoutCall)
    }

    override fun abnormalDisconnectionAndAutomaticReconnection() {
        // 连接结果初始化
        synchronized(syncConnectionLost) {
            isConnecting_Automatically = false
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
        Timber.i("开始抢救 isConnecting_Automatically:${isConnecting_Automatically} ${isAutomaticallyConnecting} isOpen:${isOpen()}")
        synchronized(syncConnectionLost) {
            if (isOpen()) {
                // 不需要抢救
                return
            }
            if (isAutomaticallyConnecting) {
                //多次启动 直接返回
                return
            }
            if (isConnecting_Automatically) {
                // 正在连接 直接返回
                return
            }
            // 初次 记录一下初次
            isAutomaticallyConnecting = true
            waitingNextAutomaticConnection(reconnectDelay)
        }
    }

    override fun stopAutoConnect(){
        Timber.i("停止抢救")
        synchronized(syncConnectionLost) {
            isAutomaticallyConnecting = false
            isConnecting_Automatically = false
            isWaitingNextAutomaticConnection = false
            reconnectDelay = initReconnectDelay
            timeoutScheduler.stop()
        }
    }
    /**
     * 等待下一次 自动连接
     */
    private fun waitingNextAutomaticConnection(delay: Long) {
        Timber.i("等待下一次 ${delay} isWaitingNextAutomaticConnection:${isWaitingNextAutomaticConnection} reconnectDelay:$reconnectDelay")
        synchronized(syncConnectionLost) {
            if (isWaitingNextAutomaticConnection) {
                return
            }
            if (reconnectDelay < maxReconnectDelay) {
                reconnectDelay = delay * 2
            }
            isWaitingNextAutomaticConnection = true
            timeoutScheduler.start(reconnectDelay)
            Timber.i("等待下一次 111 ${reconnectDelay}")
        }
    }
    private fun startConnect() {
        Timber.i("startConnect 111 ${isConnecting_Automatically}")
        synchronized(syncConnectionLost) {
            if (isConnecting_Automatically) {
                return
            }
            isConnecting_Automatically = true
        }
        webSocketClient?.reconnect()
        Timber.i("startConnect 222")
    }

    private fun autoReconnect(){
        synchronized(syncConnectionLost) {
            isWaitingNextAutomaticConnection = false
        }
        startConnect()
    }

    fun isOpen():Boolean{
        return webSocketClient?.isOpen == true
    }

    class Builder{
        internal var maxReconnectDelay = 128 * 1000

        fun maxReconnectDelay(delay: Long, unit: TimeUnit) = apply {
            maxReconnectDelay = checkDuration("maxReconnectDelay", delay, unit)
        }
        // 心跳调度器
        internal var timeoutScheduler : ITimeoutScheduler = RRFTimeoutScheduler()

        fun setTimeoutScheduler(timeoutScheduler: ITimeoutScheduler) : Builder {
            this.timeoutScheduler = timeoutScheduler
            return this
        }
        fun build():ProgressiveAutoConnect{
            return ProgressiveAutoConnect(this)
        }
    }
}