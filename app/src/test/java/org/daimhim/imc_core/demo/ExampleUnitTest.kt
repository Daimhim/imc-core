package org.daimhim.imc_core.demo

import okhttp3.OkHttpClient
import org.daimhim.imc_core.IMCStatusListener
import org.daimhim.imc_core.OkhttpIEngine
import org.junit.Test

import timber.multiplatform.log.DebugTree
import timber.multiplatform.log.Timber

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
class ExampleUnitTest {
    private lateinit var okhttpIEngine : OkhttpIEngine
    private val cmd_send = "send:"
    private val cmd_close = "close()"
    private val cmd_connect = "connect()"
    private val cmd_state = "state()"
    private val cmd_exit = "exit()"
    @Test
    fun addition_isCorrect() {
        Timber.plant(DebugTree())
        okhttpIEngine = OkhttpIEngine
            .Builder()
            .okHttpClient(
                OkHttpClient
                .Builder()
//            .connectTimeout(5L,TimeUnit.SECONDS)
                .build())
            .build()
        var preprocessedCharacters: String
        okhttpIEngine.setIMCStatusListener(object : IMCStatusListener {

            override fun connectionClosed(code: Int, reason: String?) {
                Timber.i("connectionClosed code:${code} reason:${reason}")
            }

            override fun connectionLost(throwable: Throwable) {
                Timber.i("connectionLost")
            }

            override fun connectionSucceeded() {
                Timber.i("connectionSucceeded")
            }

        })
        while (true) {
            Timber.i("Please enter the command：")
            val input = readlnOrNull()
            when {
                input == cmd_connect -> {
                    okhttpIEngine.engineOn("http://127.0.0.1:3390/v1")
                    Timber.i("engineOn()")
                }
                input == cmd_close -> {
                    okhttpIEngine.engineOff()
                    Timber.i("engineOff()")
                }
                input == cmd_state -> {
                    Timber.i("engineState:${okhttpIEngine.engineState}")
                }
                input == cmd_exit -> {
                    Timber.i("exit()")
                    return
                }
                input.isNullOrEmpty() -> {
                    Timber.i("unrecognized command!")
                }
                input.startsWith(cmd_send) -> {
                    preprocessedCharacters = input.substring(cmd_send.length)
                    okhttpIEngine.send(preprocessedCharacters)
                    Timber.i("$preprocessedCharacters")
                }
                else -> {
                    Timber.i("unrecognized command!")
                }
            }
        }
    }
}