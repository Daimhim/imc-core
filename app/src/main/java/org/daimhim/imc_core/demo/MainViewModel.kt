package org.daimhim.imc_core.demo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.daimhim.imc_core.*
import timber.multiplatform.log.Timber
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

class MainViewModel : ViewModel() {

    private val _IMCStatus = MutableStateFlow(IEngineState.ENGINE_CLOSED)
    val imcStatus : StateFlow<Int> =_IMCStatus

    private val _onMessage = MutableSharedFlow<MainItem>()
    val onMessage : SharedFlow<MainItem> =_onMessage

//    private val BASE_URL = ""
    val BASE_URL = "https://2400-117-22-144-14.ngrok-free.app"
//    private val BASE_URL = "http://e6ebacd.r15.cpolar.top"
//    private val BASE_URL = ""


//    private var iEngine : OkhttpIEngine = OkhttpIEngine
//        .Builder()
//        .okHttpClient(
//            OkHttpClient
//                .Builder()
////            .connectTimeout(5L,TimeUnit.SECONDS)
//                .build())
//        .setIMCLog(TimberIMCLog("11111111111111111111"))
//        .customHeartbeat(QGBHeartbeat())
//        .build()

    private var iEngine  = V2JavaWebEngine
        .Builder()
        .setIMCLog(TimberIMCLog("11111111111111111111"))
//        .heartbeatEnable(true)
//        .heartbeatInterval(5L,45L,TimeUnit.SECONDS)
        .build()
    init {
        iEngine.setIMCStatusListener(object : IMCStatusListener {

            override fun connectionClosed(code: Int, reason: String?) {
                Timber.i("connectionClosed")
                viewModelScope
                    .launch {
                        _IMCStatus.emit(IEngineState.ENGINE_CLOSED)
                    }.start()
            }


            override fun connectionLost(throwable: Throwable) {
                Timber.i(throwable,"connectionLost")
                viewModelScope
                    .launch {
                        _IMCStatus.emit(IEngineState.ENGINE_FAILED)
                    }.start()
            }

            override fun connectionSucceeded() {
                Timber.i("connectionSucceeded")
                viewModelScope
                    .launch {
                        _IMCStatus.emit(IEngineState.ENGINE_OPEN)
                    }.start()
            }

        })
        iEngine
            .addIMCListener(object : V2IMCListener {
                override fun onMessage(text: String) {
                    Timber.i("onMessage:收到新消息 ${text.length} ${System.currentTimeMillis()}")
                    viewModelScope
                        .launch {
                            _onMessage.emit(MainItem("Ta",1,text))
                        }.start()
                }

                override fun onMessage(byteArray: ByteArray) {
                    Timber.i("onMessage:收到新消息11  ${byteArray.size} ${System.currentTimeMillis()}")
                    viewModelScope
                        .launch {
//                            val parseDelimitedFrom =
//                                ChatMessage.parseFrom(byteArray.toByteArray())
                            Timber.i("onMessage:收到新消息22  ${System.currentTimeMillis()}")
//                            _onMessage.emit(MainItem("Ta",1,parseDelimitedFrom.content))
                        }.start()
                }

            })
    }

    fun login(name:String = BASE_URL){
        try {
            iEngine
                .engineOn(name)
        }catch (e:Exception){
            e.printStackTrace()
        }
//        try {
//            iEngine
//                .engineOn(Request.Builder().url(BASE_URL).build(),object : IEngineActionListener{
//                    override fun onSuccess(iEngine: IEngine) {
//                        Timber.i("IEngineActionListener onSuccess")
//                    }
//
//                    override fun onFailure(iEngine: IEngine, t: Throwable) {
//                        Timber.i(t,"IEngineActionListener onFailure")
//                    }
//
//                })
//        }catch (e:Exception){
//            e.printStackTrace()
//        }
        Timber.i("IEngineActionListener onSuccess")
    }
    fun send(name:String,text:String){
        iEngine
            .send(text)
    }
    fun loginOut(){
        Thread(kotlinx.coroutines.Runnable {
            iEngine
                .engineOff()
            Timber.i("loginOut()")
        }).start()
    }

    fun setForeground(foreground:Boolean){
        iEngine.onChangeMode(if (foreground) 0 else 1)
    }
    fun onNetworkChange(networkState:Int){
        iEngine.onNetworkChange(networkState)
    }
}