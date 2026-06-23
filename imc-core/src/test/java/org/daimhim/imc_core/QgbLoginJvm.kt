package org.daimhim.imc_core

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * QGB 密码登录(JVM 版,纯 JDK + json + HttpURLConnection,无 Android / OkHttp 依赖)。
 *
 * 与 `app` 模块 `QgbLoginHelper` 行为完全一致:
 *  1. GET  {JYB}/authentication/oauth/getKey      → publicKey + indexKey
 *  2. RSA/PKCS1 + 双 Base64 加密 password
 *  3. POST {APP}/v2/index/login form              → { token, imAccount, phone }
 *
 * 单测用,跑完不缓存。返回 [Result.token] + [Result.name] 直接拼 wss URL。
 */
object QgbLoginJvm {

    private const val JYB_BASE = "https://api.jybtech.cn/"
    private const val APP_BASE = "https://api.qgbtech.cn/sgb-app-manager/"

    private const val AES_KEY = "8985b2c4fb1900cf"
    private const val AES_IV = "bc3f07fbe54c4b90"

    data class Result(
        val ok: Boolean,
        val token: String?,
        val name: String?,
        val phone: String?,
        val msg: String,
    )

    fun login(username: String, password: String): Result {
        return try {
            val keyResp = httpGet("${JYB_BASE}authentication/oauth/getKey")
            val keyData = resolveData(keyResp) ?: return Result(false, null, null, null, "getKey 无 data: ${keyResp.take(200)}")
            val keyObj = when (keyData) {
                is JSONObject -> keyData
                is String -> JSONObject(keyData)
                else -> return Result(false, null, null, null, "getKey data 非预期")
            }
            val publicKey = keyObj.optString("publicKey")
            val indexKey = keyObj.optString("indexKey")
            if (publicKey.isEmpty()) return Result(false, null, null, null, "getKey 无 publicKey")

            val encPwd = rsaEncryptDoubleB64(password, publicKey)

            val body = listOf(
                "username" to username,
                "password" to encPwd,
                "indexKey" to indexKey,
                "platform" to "android",
            ).joinToString("&") { (k, v) ->
                "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
            }
            val loginResp = httpPostForm("${APP_BASE}v2/index/login", body)
            val root = JSONObject(loginResp)
            val code = root.optString("code", root.optString("errCode", "-"))
            val msg = root.optString("msg", "")
            val data = resolveData(loginResp) as? JSONObject
                ?: return Result(false, null, null, null, "登录无 data (code=$code msg=$msg) raw=${loginResp.take(200)}")
            val token = data.optString("token")
            val imAccount = data.optString("imAccount")
            val phone = data.optString("phone")
            if (token.isNotEmpty()) {
                Result(true, token, imAccount, phone, "登录成功")
            } else {
                Result(false, null, null, null, "登录无 token (code=$code msg=$msg)")
            }
        } catch (e: Exception) {
            Result(false, null, null, null, "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun httpGet(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        return conn.readAllAndClose()
    }

    private fun httpPostForm(url: String, urlEncodedBody: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        val bytes = urlEncodedBody.toByteArray(Charsets.UTF_8)
        conn.setRequestProperty("Content-Length", bytes.size.toString())
        val out: OutputStream = conn.outputStream
        out.write(bytes); out.flush(); out.close()
        return conn.readAllAndClose()
    }

    private fun HttpURLConnection.readAllAndClose(): String {
        val stream = try { inputStream } catch (_: Exception) { errorStream }
        stream ?: return ""
        return BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
    }

    private fun resolveData(rootStr: String): Any? {
        val root = JSONObject(rootStr)
        for (key in arrayOf("data", "result", "records")) {
            val v = root.opt(key)
            if (v is JSONObject || v is JSONArray) return v
            if (v != null && v != JSONObject.NULL) {
                val s = v.toString()
                if (s.isNotEmpty() && s != "null") return s
            }
        }
        val payload = root.optString("payload", "")
        if (payload.isNotEmpty() && payload != "null") {
            return try {
                val dec = aesDecodeHex(payload)
                when {
                    dec.startsWith("{") -> JSONObject(dec)
                    dec.startsWith("[") -> JSONArray(dec)
                    else -> dec
                }
            } catch (_: Exception) { null }
        }
        return null
    }

    private fun aesDecodeHex(hex: String): String {
        val cipherBytes = hexToBytes(hex)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(AES_KEY.toByteArray(Charsets.UTF_8), "AES"),
            IvParameterSpec(AES_IV.toByteArray(Charsets.UTF_8)),
        )
        return String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
    }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.trim()
        val out = ByteArray(clean.length / 2)
        var i = 0
        while (i < out.size) {
            out[i] = ((Character.digit(clean[i * 2], 16) shl 4) +
                Character.digit(clean[i * 2 + 1], 16)).toByte()
            i++
        }
        return out
    }

    private fun rsaEncryptDoubleB64(plain: String, publicKeyB64: String): String {
        val keyBytes = Base64.getDecoder().decode(publicKeyB64)
        val pubKey = KeyFactory.getInstance("RSA")
            .generatePublic(X509EncodedKeySpec(keyBytes))
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, pubKey)
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val first = Base64.getEncoder().encodeToString(encrypted)
        return Base64.getEncoder().encodeToString(first.toByteArray(Charsets.UTF_8))
    }
}
