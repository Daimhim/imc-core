package org.daimhim.imc_core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * #8:单个 listener 抛异常不得饿死同链后续 listener。
 *
 * 修前 forEach 里没 try —— 中间那个抛异常会中断整个循环,排在它后面的全部收不到消息,
 * 异常还会往上冒到 IO 线程。
 */
class V2RealRecipientDispatchTest {

    /** 派发过程不碰 IEngine 的方法,用动态代理占位即可,免得为接口全集写桩 */
    private val stubEngine: IEngine = Proxy.newProxyInstance(
        IEngine::class.java.classLoader,
        arrayOf(IEngine::class.java),
    ) { _, _, _ -> null } as IEngine

    private class RecordingListener(val name: String, val throwOn: Boolean = false) : V2IMCListener {
        val texts = ArrayList<String>()
        val binaries = ArrayList<ByteArray>()
        override fun onMessage(text: String) {
            if (throwOn) throw IllegalStateException("boom from $name")
            texts.add(text)
        }

        override fun onMessage(byteArray: ByteArray) {
            if (throwOn) throw IllegalStateException("boom from $name")
            binaries.add(byteArray)
        }
    }

    @Test
    fun `中间 listener 抛异常不影响后续 listener 收消息`() {
        val r = V2RealRecipient()
        val first = RecordingListener("first")
        val bad = RecordingListener("bad", throwOn = true)
        val last = RecordingListener("last")
        r.addIMCListener(first)
        r.addIMCListener(bad)
        r.addIMCListener(last)

        r.onMessage(stubEngine, "hello")

        assertEquals(listOf("hello"), first.texts)
        assertEquals("抛异常的后面那个也必须收到", listOf("hello"), last.texts)
    }

    @Test
    fun `二进制派发同样隔离异常`() {
        val r = V2RealRecipient()
        val bad = RecordingListener("bad", throwOn = true)
        val last = RecordingListener("last")
        r.addIMCListener(bad)
        r.addIMCListener(last)

        r.onMessage(stubEngine, byteArrayOf(1, 2, 3))

        assertEquals(1, last.binaries.size)
        assertEquals(3, last.binaries[0].size)
    }

    @Test
    fun `移除 listener 后不再收到消息`() {
        val r = V2RealRecipient()
        val l = RecordingListener("only")
        r.addIMCListener(l)
        r.onMessage(stubEngine, "a")
        r.removeIMCListener(l)
        r.onMessage(stubEngine, "b")

        assertEquals(listOf("a"), l.texts)
    }
}
