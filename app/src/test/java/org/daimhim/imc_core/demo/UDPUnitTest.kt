package org.daimhim.imc_core.demo

import org.daimhim.imc_core.UDPEngine
import org.daimhim.imc_core.V2IMCListener
import org.junit.Test

class UDPUnitTest {
    @Test
    fun addition_isCorrect() {
        println("UDPUnitTest.addition_isCorrect start")
        val iEngine = UDPEngine()

//        iEngine.engineOn("http://192.168.2.22:8888")
//        iEngine.engineOn("http://127.0.0.1:8888")
//        iEngine.engineOn("http://60.204.170.16:3000")
        iEngine.engineOn("http://175.178.54.43:3000")
        iEngine.addIMCListener(object :V2IMCListener{
            override fun onMessage(text: String) {
                println("UDPUnitTest.onMessage text:${text}")
            }

            override fun onMessage(byteArray: ByteArray) {
                println("UDPUnitTest.onMessage byteArray:${String(byteArray)}")
            }
        })
        println("UDPUnitTest.addition_isCorrect send ${iEngine.getLocalPort()}")
        println("UDPUnitTest.addition_isCorrect ${iEngine.send("Hi! UDP.")}")
        println("UDPUnitTest.addition_isCorrect end")
        Thread.sleep(30000)
    }
}