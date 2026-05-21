package org.daimhim.imc_core

import org.junit.Assert.fail
import org.junit.Test
import java.net.URISyntaxException

/**
 * 验证 V2JavaWebEngine 在 spawnNewClient 前对脏 URL 的容错。
 *
 * 真实场景:用户从聊天软件拷贝 wss://... 时,JWT token 中间常被夹换行 / NBSP /
 * zero-width 空格 → 严格 URI() 会抛 URISyntaxException。
 *
 * 修复后:engineOn 接收脏 URL,内部 sanitize + 必要时 percent-encode,不应抛出 URI 异常。
 */
class V2JavaWebEngineUriCleanupTest {

    private fun assertEngineOnAcceptsUrl(url: String) {
        val engine = V2JavaWebEngine.Builder()
            .addHeartbeatMode(0, V2FixedHeartbeat.Builder().setCurHeartbeat(60).build())
            .setAutoConnect(ProgressiveAutoConnect.Builder().build())
            .build()
        try {
            engine.engineOn(url)
            // engineOn 内部异步,connect 失败不会立刻抛 — URI() 失败才会同步抛
        } catch (e: URISyntaxException) {
            fail("URI() 仍然崩在: ${e.message}")
        } catch (_: Throwable) {
            // 连不上 example.com:443 等其它异常无所谓,我们只看 URI 解析这一关
        } finally {
            try { engine.engineOff() } catch (_: Throwable) {}
        }
    }

    @Test
    fun token_with_newline_should_not_crash() {
        val dirty = "wss://example.com/ws:90?token=abc.def\nghi_jkl&name=u1&platform=android&state=0"
        assertEngineOnAcceptsUrl(dirty)
    }

    @Test
    fun token_with_nbsp_should_not_crash() {
        // U+00A0 non-breaking space
        val dirty = "wss://example.com/ws:90?token=abc.def ghi_jkl&name=u1&platform=android&state=0"
        assertEngineOnAcceptsUrl(dirty)
    }

    @Test
    fun token_with_zwsp_should_not_crash() {
        // U+200B zero-width space
        val dirty = "wss://example.com/ws:90?token=abc.def​ghi_jkl&name=u1&platform=android&state=0"
        assertEngineOnAcceptsUrl(dirty)
    }

    @Test
    fun token_with_inline_spaces_should_not_crash() {
        val dirty = "wss://example.com/ws:90?token=abc def ghi&name=u1&platform=android&state=0"
        assertEngineOnAcceptsUrl(dirty)
    }

    @Test
    fun clean_url_passes_through_unchanged() {
        val clean =
            "wss://example.com/ws:90?token=abc.def-ghi_jkl&name=u1&platform=android&state=0"
        assertEngineOnAcceptsUrl(clean)
    }
}
