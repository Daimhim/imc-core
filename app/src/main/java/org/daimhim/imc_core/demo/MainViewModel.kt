package org.daimhim.imc_core.demo

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.ByteString
import org.daimhim.imc_core.*
import timber.multiplatform.log.Timber

class MainViewModel : ViewModel() {

    private val _IMCStatus = MutableStateFlow(IEngineState.ENGINE_CLOSED)
    val imcStatus : StateFlow<Int> =_IMCStatus

    private val _onMessage = MutableSharedFlow<MainItem>()
    val onMessage : SharedFlow<MainItem> =_onMessage

//    private val BASE_URL = "ws://419d3270.r9.cpolar.top"
    private val BASE_URL = "http://127.0.0.1:3390/v1"


    private var iEngine : WebSocketEngine = WebSocketEngine
        .Builder()
        .okHttpClient(
            OkHttpClient
                .Builder()
//            .connectTimeout(5L,TimeUnit.SECONDS)
                .build())
        .build()
    init {
        iEngine.setIMCStatusListener(object : IMCStatusListener {
            override fun connectionClosed() {
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

                override fun onMessage(byteArray: ByteString) {
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

    fun login(name:String){
        try {
            iEngine
                .engineOn(Request.Builder().url(BASE_URL).build(),object : IEngineActionListener{
                    override fun onSuccess(iEngine: IEngine) {
                        Timber.i("IEngineActionListener onSuccess")
                    }

                    override fun onFailure(iEngine: IEngine, t: Throwable) {
                        Timber.i(t,"IEngineActionListener onFailure")
                    }

                })
        }catch (e:Exception){
            e.printStackTrace()
        }
    }
    fun send(name:String,text:String){
        iEngine
            .send(text)
    }
    fun loginOut(){
        iEngine
            .engineOff()
    }

    fun setForeground(foreground:Boolean){
        iEngine.onChangeMode(if (foreground) 0 else 1)
    }
    fun onNetworkChange(networkState:Int){
        iEngine.onNetworkChange(networkState)
    }
}