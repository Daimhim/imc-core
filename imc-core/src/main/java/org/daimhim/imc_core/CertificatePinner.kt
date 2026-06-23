package org.daimhim.imc_core

import java.security.cert.Certificate

/**
 * 证书指纹校验钩子。
 *
 * 设计目的:Wi-Fi 链路上的 MITM / DPI 拦截会用一张自签 / 被注入 trust store 的证书冒充服务端 ——
 * 系统的 TrustManager 会通过(因为证书"链是有效的"),但证书指纹与真服务端不一致。客户端层
 * 比对真实证书指纹可以拦下这种情形,拿到明确的 [ImcEvent.TlsFailure] (stage=PIN_FAILURE) 而不是
 * 后续 SSL 读写期的随机错误。
 *
 * 用法:
 * ```kotlin
 * V2JavaWebEngine.Builder()
 *     .setCertificatePinner { host, certs ->
 *         val leaf = certs.firstOrNull() ?: return@setCertificatePinner false
 *         val sha256 = hashSha256(leaf.encoded)
 *         host == "your-im-server.example.com" && sha256.equals("AA:BB:CC:...", true)
 *     }
 *     .build()
 * ```
 *
 * 实现注意:
 *  - 钩子在 WS 握手完成、onOpen 触发之前调用;返回 false 立即触发 close()
 *  - host 用 URI.host(不带端口);certs 顺序是 server→intermediate→root
 *  - 实现里**不要**做长耗时操作(完整 OCSP / CRL 校验请走系统 TrustManager,本钩子只比对指纹)
 *  - 抛异常 = 视为不通过(等价 false)
 */
fun interface CertificatePinner {
    fun pin(host: String, certs: List<Certificate>): Boolean
}
