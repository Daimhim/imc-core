package org.daimhim.imc_core.demo

import org.daimhim.imc_core.RapidResponseForceV2
import org.junit.Test
import java.sql.Date
import java.time.LocalDateTime

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
}