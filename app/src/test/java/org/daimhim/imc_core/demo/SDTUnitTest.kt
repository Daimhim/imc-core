package org.daimhim.imc_core.demo

import com.tencent.mars.Mars
import com.tencent.mars.sdt.SdtLogic
import org.java_websocket.drafts.Draft_6455
import org.junit.Test
import java.nio.ByteBuffer

class SDTUnitTest {
    @Test
    fun addition_isCorrect() {
        val s = byteArrayOf(-119, -128, -80, -94, -104, 39)
        val draft = Draft_6455()
        val translateFrame = draft.translateFrame(ByteBuffer.wrap(s))
        val first = translateFrame.first()
        println("first:${first::class.java.name}")
    }
}