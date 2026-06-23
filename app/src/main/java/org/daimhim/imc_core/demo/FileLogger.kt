package org.daimhim.imc_core.demo

import android.content.Context
import android.os.Process
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue

/**
 * 本地持久化日志文件写入器(进程级单例)。
 *
 * 目标:把 demo 埋点(经 [LogStore])+ SDK 结构化事件(经 ImcEventLogBridge 订阅)落地到本地文件,
 * 后续连接出问题时可 `adb pull` 出来离线排查。
 *
 * 设计:
 *  - 写入路径:`getExternalFilesDir(null)/imclog/imc-yyyy-MM-dd[.N].log`
 *    用 app-private 外部目录,免存储权限,且 `adb pull` 方便。
 *  - 异步:调用方只入队([LinkedBlockingQueue]),单后台守护线程批量 drain → 落盘,绝不阻塞业务线程。
 *  - 按天切分 + 单文件超 [MAX_FILE_BYTES] 滚动加序号,保留最近 [RETAIN_DAYS] 天,旧的自动删。
 *  - 未 init 时 [log] 直接丢弃(no-op),不会崩。
 */
object FileLogger {

    private const val DIR = "imclog"
    private const val MAX_FILE_BYTES = 4L * 1024 * 1024   // 单文件 4MB 滚动
    private const val RETAIN_DAYS = 7
    private const val FILE_PREFIX = "imc-"
    private const val FILE_SUFFIX = ".log"

    @Volatile private var baseDir: File? = null
    @Volatile private var initialized = false

    private val queue = LinkedBlockingQueue<String>()
    private val tsFmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, DIR)
            dir.mkdirs()
            baseDir = dir
            initialized = true
            startWriter()
        }
        cleanupOldFiles()
        log("====== FileLogger 启动 pid=${Process.myPid()} dir=${baseDir?.absolutePath} ======", level = 'I', tag = "LOG")
    }

    /** 入队一条日志。level: V/D/I/W/E;tag: APP(埋点) / SDK(引擎内部) / LOG(本写入器自身) */
    fun log(line: String, level: Char = 'I', tag: String = "APP") {
        if (!initialized) return
        val ts = tsFmt.format(Date())
        queue.offer("$ts ${level}/${tag}: $line")
    }

    /** 当前正在写的文件(给 UI 显示 / 分享用);未 init 返回 null */
    fun currentFile(): File? {
        val dir = baseDir ?: return null
        return resolveTodayFile(dir)
    }

    fun logDir(): File? = baseDir

    /** 列出全部日志文件,新→旧 */
    fun listFiles(): List<File> {
        val dir = baseDir ?: return emptyList()
        return dir.listFiles { f -> f.isFile && f.name.startsWith(FILE_PREFIX) && f.name.endsWith(FILE_SUFFIX) }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    private fun startWriter() {
        Thread {
            val batch = ArrayList<String>(64)
            while (true) {
                try {
                    batch.clear()
                    batch.add(queue.take())     // 阻塞等第一条
                    queue.drainTo(batch, 256)   // 顺手把已堆积的一起取出
                    flush(batch)
                } catch (ie: InterruptedException) {
                    break
                } catch (_: Exception) {
                    // 写盘失败不能拖垮线程,丢这批继续
                }
            }
        }.apply {
            name = "FileLogger"
            isDaemon = true
            start()
        }
    }

    private fun flush(lines: List<String>) {
        val dir = baseDir ?: return
        val file = resolveTodayFile(dir)
        file.appendText(lines.joinToString("\n", postfix = "\n"))
    }

    /**
     * 今天该写哪个文件:基础名 imc-yyyy-MM-dd.log;若已超 4MB 则滚动到 .1 / .2 …
     * 每次落盘前都查一次,跨天 / 超限自动换文件。
     */
    private fun resolveTodayFile(dir: File): File {
        val day = dayFmt.format(Date())
        var idx = 0
        var f = File(dir, "$FILE_PREFIX$day$FILE_SUFFIX")
        while (f.exists() && f.length() >= MAX_FILE_BYTES) {
            idx++
            f = File(dir, "$FILE_PREFIX$day.$idx$FILE_SUFFIX")
        }
        return f
    }

    private fun cleanupOldFiles() {
        val dir = baseDir ?: return
        val cutoff = System.currentTimeMillis() - RETAIN_DAYS * 24L * 3600 * 1000
        dir.listFiles { f -> f.isFile && f.name.startsWith(FILE_PREFIX) }
            ?.filter { it.lastModified() < cutoff }
            ?.forEach { runCatching { it.delete() } }
    }
}
