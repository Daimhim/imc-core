package org.daimhim.imc_core.demo

import org.daimhim.imc_core.IMCLog
import org.daimhim.imc_core.RapidResponseForceV2
import org.daimhim.imc_core.RapidResponseForceV2.WrapOrderState
import org.daimhim.imc_core.TimberIMCLog
import org.junit.Test
import java.sql.Date
import java.time.LocalDateTime
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.LinkedBlockingQueue

class RRFUnitTest {
    @Test
    fun addition_isCorrect() {
        var count = 0
        println("${LocalDateTime.now()} RRFUnitTest.addition_isCorrect")
        val rapidResponseForce = RapidResponseForceV2()

        rapidResponseForce.timeoutCallback(object : Comparable<Pair<String,Any?>>{
            override fun compareTo(other: Pair<String, Any?>): Int {
                println("${LocalDateTime.now()} RRFUnitTest.compareTo ${other.first}")
                count++
                if (count < 2) {
                    rapidResponseForce.register(id = "123")
                }else if (count < 5){
                    rapidResponseForce.register(id = "456", timeOut = 8000)
                }else if (count < 8){
                    rapidResponseForce.register(id = "789", timeOut = 10000)
                }else{
                    rapidResponseForce.register(id = "101112", timeOut = 12000)
                }
                return 0
            }
        })

        rapidResponseForce.register("123")

        Thread.sleep(300000)

    }


    @Test
    fun loopV2() {
        val arrayBlockingQueue = LinkedBlockingQueue<Int>(32)
        arrayBlockingQueue.add(3)
        arrayBlockingQueue.add(1)
        arrayBlockingQueue.add(2)
        while (arrayBlockingQueue.isNotEmpty()){
            println("poll:${arrayBlockingQueue.poll()}")
        }
    }
    @Test
    fun loop() {
        println("RRFUnitTest.loop start")
        IMCLog.testSetIIMCLogFactory(TimberIMCLog())
        var count = 0
        val TEST_ID = "123"
        val timeOut =  5000L
        val rapidResponseForce = RapidResponseForceV2()
        rapidResponseForce.timeoutCallback(object : Comparable<Pair<String,Any?>>{
            override fun compareTo(other: Pair<String, Any?>): Int {
                rapidResponseForce.register(id = TEST_ID, timeOut = timeOut)
                count++
                println("RRFUnitTest.loop count:$count")
                return 0
            }
        })
        rapidResponseForce.cancelCallbackMap(object : Comparable<Pair<String,Any?>>{
            override fun compareTo(other: Pair<String, Any?>): Int {

                return 0
            }
        })
        rapidResponseForce.unRegister(id = TEST_ID)
        rapidResponseForce.register(id = TEST_ID, timeOut = timeOut)
        println("RRFUnitTest.loop count:$count")
        while (rapidResponseForce.testIsRun()){

        }
        println("RRFUnitTest.loop end")
    }
}