package org.daimhim.imc_core

import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * 跑一次登录,打印 token + imAccount,给 demo 装机前手工填表用。
 * 不消费 token,服务端那边相当于没人登录。
 */
class QgbLoginPrint {
    @Test
    fun print_token() {
        val u = System.getProperty("qgb.username") ?: System.getenv("QGB_USERNAME")
        val p = System.getProperty("qgb.password") ?: System.getenv("QGB_PASSWORD")
        assumeTrue("未提供凭据,跳过", !u.isNullOrBlank() && !p.isNullOrBlank())
        val r = QgbLoginJvm.login(u!!, p!!)
        if (!r.ok) org.junit.Assert.fail("登录失败: ${r.msg}")
        println("=== LOGIN_OK ===")
        println("TOKEN=${r.token}")
        println("NAME=${r.name}")
        println("PHONE=${r.phone}")
        println("=== END ===")
    }
}
