package org.daimhim.imc_core.demo

import org.daimhim.imc_core.UDPEngine
import org.daimhim.imc_core.V2IMCListener
import org.junit.Test

class UDPUnitTest {
    @Test
    fun addition_isCorrect() {
        println("UDPUnitTest.addition_isCorrect start")
        val iEngine = UDPEngine
            .Builder()
            .builder()

//        iEngine.engineOn("http://192.168.137.1:3390")
        iEngine.engineOn("http://123.60.90.82:63000")
//        iEngine.engineOn("http://127.0.0.1:8888")
        iEngine.addIMCListener(object :V2IMCListener{
            override fun onMessage(text: String) {
                println("UDPUnitTest.onMessage text:${text}")
            }

            override fun onMessage(byteArray: ByteArray) {
                println("UDPUnitTest.onMessage byteArray:${String(byteArray)}")
            }
        })
        Thread.sleep(3000)

        println("UDPUnitTest.addition_isCorrect send ${iEngine.getLocalPort()}")
        println("UDPUnitTest.addition_isCorrect ${iEngine.send("{\"pushId\":\"1805795793597247488\",\"cmdType\":\"MSG_IS_SEND\",\"payLoad\":{\"appSeqId\":\"1805795793597247488\",\"sequenceId\":\"1805795793609830400\",\"timelineId\":\"1805168106142588928\"}}")}")
        println("UDPUnitTest.addition_isCorrect end")
        Thread.sleep(30000)
    }
}