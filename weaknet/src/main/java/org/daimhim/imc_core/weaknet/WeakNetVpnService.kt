package org.daimhim.imc_core.weaknet

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * QNET 风格弱网模拟器 — Phase 2
 *
 * 这一阶段做的:
 *  - 起 VpnService,建 tun(10.10.10.1/32, MTU 1500, route 0.0.0.0/0)
 *  - 经 [EXTRA_ALLOWED_APPS] 把目标 app 加进 allowedApplications
 *  - 把 tun 包路由到 [VpnPacketRouter] → TcpFlow / UdpFlow 真转发
 *  - 目标 app 拿到正常网络
 *
 * 还没接的(Phase 3):
 *  - ChaosConfig 注入还没挂上,字节流当前透传无损伤
 */
class WeakNetVpnService : VpnService() {

    @Volatile private var tunFd: ParcelFileDescriptor? = null
    @Volatile private var readerThread: Thread? = null
    @Volatile private var router: VpnPacketRouter? = null
    @Volatile private var running = false

    private val totalPackets = AtomicLong(0)
    private val totalBytes = AtomicLong(0)

    override fun onCreate() {
        super.onCreate()
        startForegroundNotice()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP) {
            stopVpn("ui stop")
            stopSelf()
            return START_NOT_STICKY
        }
        if (action == ACTION_APPLY_CHAOS) {
            val json = intent?.getStringExtra(EXTRA_CHAOS_CONFIG)
            val cfg = ChaosConfigStore.chaosConfigFromJsonString(json)
            if (cfg != null) router?.updateConfig(cfg)
            else emit("[chaos] 解析失败:$json")
            return START_STICKY
        }
        val allowed: Array<String> = intent?.getStringArrayExtra(EXTRA_ALLOWED_APPS)
            ?.filterNotNull()?.toTypedArray() ?: emptyArray()
        val initialChaos = intent?.getStringExtra(EXTRA_CHAOS_CONFIG)
            ?.let { ChaosConfigStore.chaosConfigFromJsonString(it) }
        if (!running) startVpn(allowed, initialChaos)
        return START_STICKY
    }

    override fun onDestroy() {
        stopVpn("onDestroy")
        instance = null
        super.onDestroy()
    }

    private fun startVpn(allowedPackages: Array<String>, initialChaos: ChaosConfig? = null) {
        val builder = Builder()
            .setSession(SESSION_NAME)
            .setMtu(MTU)
            .addAddress(VPN_ADDR_V4, 32)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("8.8.8.8")
            .addDnsServer("114.114.114.114")
            .setBlocking(true)

        if (allowedPackages.isNotEmpty()) {
            for (pkg in allowedPackages) {
                try {
                    builder.addAllowedApplication(pkg)
                } catch (e: Exception) {
                    Log.w(TAG, "addAllowedApplication failed: $pkg", e)
                    emit("[vpn] 添加 $pkg 失败: ${e.message}")
                }
            }
            emit("[vpn] allowed apps: ${allowedPackages.joinToString()}")
        } else {
            emit("[vpn] 未指定 app,全局 VPN(所有 app 流量都会被截获)")
        }

        // weaknet 自己不能再走自己,否则死循环
        try { builder.addDisallowedApplication(packageName) } catch (_: Exception) {}

        val fd = builder.establish() ?: run {
            emit("[vpn] establish() 返回 null,可能用户未授权 VPN")
            stopSelf()
            return
        }
        tunFd = fd
        running = true

        val tun = TunWriter(FileOutputStream(fd.fileDescriptor))
        val r = VpnPacketRouter(
            tun = tun,
            protectSocket = { sock -> protect(sock) },
            protectDgram = { sock -> protect(sock) },
            onEvent = { msg -> emit(msg) },
        )
        if (initialChaos != null) r.updateConfig(initialChaos)
        router = r
        emit("[vpn] tun fd 起来:mtu=$MTU addr=$VPN_ADDR_V4")

        readerThread = Thread({ readLoop(fd) }, "WeakNet-VpnReader").apply {
            isDaemon = true
            start()
        }
    }

    private fun stopVpn(reason: String) {
        if (!running) return
        running = false
        router?.shutdown()
        router = null
        try { tunFd?.close() } catch (_: Exception) {}
        tunFd = null
        readerThread?.interrupt()
        readerThread = null
        emit("[vpn] 停止($reason). 累计包=${totalPackets.get()} 累计字节=${totalBytes.get()}")
        totalPackets.set(0)
        totalBytes.set(0)
    }

    private fun readLoop(fd: ParcelFileDescriptor) {
        val input = FileInputStream(fd.fileDescriptor)
        val buf = ByteArray(MTU + 100)
        while (running) {
            val n = try {
                input.read(buf)
            } catch (e: Exception) {
                if (running) emit("[vpn] read 异常: ${e.message}")
                break
            }
            if (n <= 0) continue
            totalPackets.incrementAndGet()
            totalBytes.addAndGet(n.toLong())
            router?.onPacket(buf, n)
        }
        emit("[vpn] readLoop 退出")
    }

    // ── 前台通知 ───────────────────────────────────────────────────────

    private fun startForegroundNotice() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "WeakNet VPN",
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(ch)
        }
        val stopIntent = Intent(this, WeakNetVpnService::class.java).apply { action = ACTION_STOP }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        else PendingIntent.FLAG_UPDATE_CURRENT
        val stopPi = PendingIntent.getService(this, 1, stopIntent, flags)

        val noti: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("WeakNet 弱网代理")
            .setContentText("正在拦截目标 app 流量")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "停止", stopPi)
            .build()
        startForeground(NOTI_ID, noti)
    }

    // ── 简易事件总线(供 Activity 订阅日志)───────────────────────────

    private fun emit(msg: String) {
        Log.i(TAG, msg)
        for (l in listeners) try { l(msg) } catch (_: Exception) {}
    }

    companion object {
        private const val TAG = "WeakNetVpn"
        private const val CHANNEL_ID = "weaknet_vpn"
        private const val NOTI_ID = 1001
        private const val SESSION_NAME = "WeakNet"
        private const val MTU = 1500
        private const val VPN_ADDR_V4 = "10.10.10.1"

        const val EXTRA_ALLOWED_APPS = "allowed_apps"
        const val EXTRA_CHAOS_CONFIG = "chaos_cfg_json"
        const val ACTION_STOP = "org.daimhim.imc_core.weaknet.VPN_STOP"
        const val ACTION_APPLY_CHAOS = "org.daimhim.imc_core.weaknet.APPLY_CHAOS"

        fun applyChaos(ctx: android.content.Context, cfg: ChaosConfig) {
            val json = with(ChaosConfigStore.Companion) { cfg.toJson() }.toString()
            val intent = Intent(ctx, WeakNetVpnService::class.java).apply {
                action = ACTION_APPLY_CHAOS
                putExtra(EXTRA_CHAOS_CONFIG, json)
            }
            ctx.startService(intent)
        }

        @Volatile private var instance: WeakNetVpnService? = null
        private val listeners = CopyOnWriteArrayList<(String) -> Unit>()

        fun isRunning(): Boolean = instance?.running == true

        fun addListener(l: (String) -> Unit) { listeners.add(l) }
        fun removeListener(l: (String) -> Unit) { listeners.remove(l) }

        fun stats(): Triple<Long, Long, String> {
            val s = instance ?: return Triple(0L, 0L, "")
            return Triple(s.totalPackets.get(), s.totalBytes.get(), s.router?.stats() ?: "")
        }
    }
}
