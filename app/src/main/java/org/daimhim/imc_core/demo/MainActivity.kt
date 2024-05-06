package org.daimhim.imc_core.demo

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.widget.Toast
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import com.kongqw.network.monitor.NetworkMonitorManager
import com.kongqw.network.monitor.enums.NetworkState
import com.kongqw.network.monitor.interfaces.NetworkMonitor
import kotlinx.coroutines.launch
import org.daimhim.imc_core.IEngineState
import timber.multiplatform.log.Timber
import org.daimhim.imc_core.demo.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private val _viewModel : MainViewModel  by viewModels()
    private val _mainAdapter = MainAdapter()
    private lateinit var _binding: ActivityMainBinding
    private var foregroundCallback = object : Comparable<Boolean>{
        override fun compareTo(other: Boolean): Int {
            _viewModel.setForeground(other)
            return 0
        }

    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        _binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(_binding.root)
        NetworkMonitorManager.getInstance().register(this)
        initView()
        initListener()
        initSubscribe()
    }
    private fun initSubscribe() {
        lifecycleScope
            .launch {
                _viewModel
                    .imcStatus
                    .collect {
                        Timber.i("imcStatus:${it}")
                        when(it){
                            IEngineState.ENGINE_OPEN->{
                                _binding.buttonFirst.setText("退出")
                            }
                            IEngineState.ENGINE_FAILED->{
                                _binding.buttonFirst.setText("重试中...")
                            }
                            IEngineState.ENGINE_CLOSED->{
                                _binding.buttonFirst.setText("登录")
                            }
                        }
                    }
            }
            .start()
        lifecycleScope
            .launch {
                _viewModel
                    .onMessage
                    .collect {
                        _mainAdapter.addItem(it)
                        _binding.rvList.scrollToPosition(0)
                    }
            }
            .start()
        FullLifecycleHandler
            .registerForegroundCallback(foregroundCallback)
//        SdtLogic.setCallBack(object : SdtLogic.ICallBack{
//            override fun reportSignalDetectResults(p0: String?) {
//                println("SDTUnitTest.reportSignalDetectResults:${p0}")
//            }
//        })
//        SdtLogic.setHttpNetcheckCGI("https://58a4ad34.r15.cpolar.top")
//        Thread.sleep(9000)
    }

    private fun initListener() {
        _binding.buttonFirst.setOnClickListener {
            val value = _viewModel.imcStatus.value
            when (value) {
                IEngineState.ENGINE_OPEN,IEngineState.ENGINE_FAILED -> {
                    _binding.buttonFirst.setText("操作中...")
                    _viewModel.loginOut()
                }

                IEngineState.ENGINE_CLOSED -> {
                    val toString = _binding.textviewFirst.text?.toString()
                    if (toString.isNullOrEmpty()) {
                        Toast.makeText(this, "用户名不能为空", Toast.LENGTH_LONG).show()
                        return@setOnClickListener
                    }
                    _viewModel.login(toString)
                    _binding.buttonFirst.setText("操作中...")
                }
            }
        }
        _binding.button.setOnClickListener {
            val toString = _binding.editTextTextPersonName2.text?.toString()
            if (toString.isNullOrEmpty()){
                Toast.makeText(requireContext(),"用户输入不能为空",Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val name = _binding.editTextTextPersonName.text?.toString()
            if (name.isNullOrEmpty()){
                Toast.makeText(requireContext(),"接受用户不能为空",Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            _viewModel.send(name,toString)
            _mainAdapter.addItem(MainItem("Me",0,toString))
            _binding.rvList.scrollToPosition(0)
            _binding.editTextTextPersonName2.setText("")
        }
        _binding.btChangeMode.setOnClickListener {
            _viewModel.setForeground(!_binding.btChangeMode.isChecked)
        }
    }

    private fun initView() {
        _binding.rvList.adapter = _mainAdapter
    }

    override fun onDestroy() {
        super.onDestroy()
        NetworkMonitorManager.getInstance().unregister(this)
        FullLifecycleHandler
            .unregisterForegroundCallback(foregroundCallback)
    }

    @NetworkMonitor
    fun onNetworkChange(networkState: NetworkState){
        _viewModel.onNetworkChange(0)
    }
}