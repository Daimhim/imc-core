package org.daimhim.imc_core.demo

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ToggleButton
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import org.daimhim.imc_core.*
import timber.multiplatform.log.Timber
import java.nio.ByteBuffer
import java.util.concurrent.Callable

class V2JavaWebEngineTestActivity : AppCompatActivity() {
    private lateinit var engine : V2JavaWebEngine
    private val mainAdapter = MainAdapter()

    companion object{
        val FIXED_HEARTBEAT = 0
        val SMART_HEARTBEAT = 1
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_v2_java_web_engine_test)
        initEngine()
        initView()
        initListener()
    }

    private fun initEngine() {

        engine = V2JavaWebEngine
            .Builder()
            .setIMCLog(TimberIMCLog("V2JavaWebEngine"))
            .addHeartbeatMode(
                FIXED_HEARTBEAT,
                V2FixedHeartbeat
                    .Builder()
                    .setCurHeartbeat(5)
                    .build()
            )
            .addHeartbeatMode(
                SMART_HEARTBEAT,
                V2SmartHeartbeat
                    .Builder()
                    .setHeartbeatStep(10)
                    .setMinHeartbeat(35L)
                    .setInitialHeartbeat(65L)
                    .setTimeoutScheduler(HeartbeatAlarmTimeoutScheduler("智能心跳"))
                    .build()
            )
            .setAutoConnect(
                ProgressiveAutoConnect
                    .Builder()
                    .setTimeoutScheduler(AutoConnectAlarmTimeoutScheduler("自动连接"))
                    .build()
            )
            .build()
    }

    private fun initListener() {
        engine.setIMCStatusListener(object : IMCStatusListener{
            override fun connectionLost(throwable: Throwable) {
                runOnUiThread {
                    findViewById<Button>(R.id.bt_link).setText("失去连接")
                }
            }
            override fun connectionClosed(code: Int, reason: String?) {
                runOnUiThread {
                    findViewById<Button>(R.id.bt_link).setText("连接关闭")
                }
            }
            override fun connectionSucceeded() {
                runOnUiThread {
                    findViewById<Button>(R.id.bt_link).setText("连接成功")
                }
            }

        })
        engine.addIMCListener(object : V2IMCListener{
            override fun onMessage(byteArray: ByteArray) {
                super.onMessage(byteArray)
                mainAdapter.addItem(MainItem("Ta",1,"byteArray:${byteArray.size}"))
            }

            override fun onMessage(text: String) {
                super.onMessage(text)
                mainAdapter.addItem(MainItem("Ta",1,text))
            }
        })
        // 重置连接
        findViewById<View>(R.id.bt_rest)
            .setOnClickListener {
                engine.makeConnection()
            }
        findViewById<ToggleButton>(R.id.tb_heartbeat)
            .setOnClickListener {
                val checked = findViewById<ToggleButton>(R.id.tb_heartbeat).isChecked
                if (checked) {
                    engine.onChangeMode(SMART_HEARTBEAT)
                }else{
                    engine.onChangeMode(FIXED_HEARTBEAT)
                }
            }
        findViewById<View>(R.id.bt_link)
            .setOnClickListener {
                val url = findViewById<EditText>(R.id.et_url).text.toString()
                if (url.isEmpty()) {
                    return@setOnClickListener
                }
                if (engine.engineState() == IEngineState.ENGINE_OPEN) {
                    findViewById<Button>(R.id.bt_link).setText("链接")
                    engine.engineOff()
                }else if (engine.engineState() == IEngineState.ENGINE_CLOSED) {
                    findViewById<Button>(R.id.bt_link).setText("连接中...")
                    Thread {
                        engine.engineOn(url)
                    }.start()
                }

            }
        findViewById<View>(R.id.bt_send)
           .setOnClickListener {
//               var count = 0
//               val ts = RRFTimeoutScheduler()
//               ts.setCallback {
//                   count++
//                   println("RRFTimeoutScheduler 回调 $count")
//                   ts.start(15000)
//                   null
//               }
//               ts.start(15000)

               val text = findViewById<EditText>(R.id.et_send).text.toString()
               if (text.isNotEmpty()) {
                   return@setOnClickListener
               }
               engine.send(text)
               mainAdapter.addItem(MainItem("Me",1,text))
           }
        FullLifecycleHandler
            .registerForegroundCallback(object : Comparable<Boolean>{
                override fun compareTo(other: Boolean): Int {
                    Timber.i("IHeartbeat.RRFTimeoutScheduler compareTo $other")
//                    if (other) {
//                        engine.onChangeMode(FIXED_HEARTBEAT)
//                    }else{
//                        engine.onChangeMode(SMART_HEARTBEAT)
//                    }
                    return 0
                }
            })
    }


    private fun initView() {
        findViewById<EditText>(R.id.et_url)
            .setText("https://client.jingyingbang.com/ws?name=202101041012028&token=eyJ0eXAiOiJKV1QiLCJhbGciOiJSUzUxMiJ9.eyJzdWIiOiIxMzMxNTU5OTA2NTk5Mzc0ODQ4Iiwic2NvcGUiOiJkZWZhdWx0IiwiaXNzIjoiMTUwMTUxMTIwMDgiLCJsb2dpbiI6MTc1MDc0Nzc1NH0.RII9dykaeBST54tuy17cdHJBC3UtR8FfQgrtHowxL_Q-fr1K88rRHOzuvzKg1M866hq8cDBQGISkES7niZxKWKC9dHghN80KmR8x35UIzL1tWt0x5Ia6yhwiCkW26D61F9UN9cVuHxfIrLOJAJnsaSNfZBgm0m47-jdmyIvdKqU&platform=android")
        val findViewById = findViewById<RecyclerView>(R.id.rv_list)
        findViewById.adapter = mainAdapter


    }
}