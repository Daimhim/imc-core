package org.daimhim.imc_core.demo

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 临时调试用登录:复刻 sgb-management-android 的密码登录,只为拿到 WS 连接需要的 token + name(imAccount)。
 *
 * 流程(全部同步,需在后台线程调用):
 *   1. GET  {JYB}/authentication/oauth/getKey      → 取 RSA 公钥 + indexKey
 *   2. password 用公钥 RSA/PKCS1 加密 → Base64 → 再 Base64(双重,与 sgb realPwdLogin 完全一致)
 *   3. POST {APP}/v2/index/login (form)            → 拿 LoginEntity { token, imAccount }
 *
 * 响应解密:复刻 sgb DecryptInterceptor —— 若 data 为空且存在 payload,则 AES/CBC 解密(key/iv 硬编码自 AESUtils)。
 * 大多数情况下 v2/index/login 的 data 直接是对象,不走解密。
 *
 * 注:仅 debug 环境地址。密码加密用的是服务端公钥,密文每次不同(PKCS1 随机填充),正常。
 */
object QgbLoginHelper {

    private const val JYB_BASE = "https://api.jybtech.cn/"
    private const val APP_BASE = "https://api.qgbtech.cn/sgb-app-manager/"

    // 来自 sgb AESUtils:AES/CBC/PKCS7,响应 payload 解密用
    private const val AES_KEY = "8985b2c4fb1900cf"
    private const val AES_IV = "bc3f07fbe54c4b90"

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    data class Result(
        val ok: Boolean,
        val token: String?,
        val name: String?,
        val phone: String?,
        val msg: String,
    )

    fun login(username: String, password: String): Result {
        return try {
            // 1. getKey
            val keyRespStr = httpGet(JYB_BASE + "authentication/oauth/getKey")
            LogStore.append("[login] getKey 响应: ${keyRespStr.take(200)}")
            val keyData = resolveData(keyRespStr)
                ?: return Result(false, null, null, null, "getKey 无 data")
            val keyObj = when (keyData) {
                is JSONObject -> keyData
                is String -> JSONObject(keyData)
                else -> return Result(false, null, null, null, "getKey data 非预期: $keyData")
            }
            val publicKey = keyObj.optString("publicKey")
            val indexKey = keyObj.optString("indexKey")
            if (publicKey.isEmpty()) return Result(false, null, null, null, "getKey 无 publicKey")

            // 2. RSA 加密密码(双 Base64)
            val encPwd = rsaEncryptDoubleB64(password, publicKey)

            // 3. login
            val form = FormBody.Builder()
                .addEncoded("username", username)
                .addEncoded("password", encPwd)
                .addEncoded("indexKey", indexKey)
                .addEncoded("platform", "android")
                .build()
            val loginRespStr = httpPost(APP_BASE + "v2/index/login", form)
            LogStore.append("[login] login 响应: ${loginRespStr.take(300)}")

            val root = JSONObject(loginRespStr)
            val code = root.optString("code", root.optString("errCode", "-"))
            val msg = root.optString("msg", "")
            val data = resolveData(loginRespStr)
            val dataObj = data as? JSONObject
                ?: return Result(false, null, null, null, "登录无 data(code=$code msg=$msg)")
            val token = dataObj.optString("token")
            val imAccount = dataObj.optString("imAccount")
            val phone = dataObj.optString("phone")
            if (token.isNotEmpty()) {
                Result(true, token, imAccount, phone, "登录成功")
            } else {
                Result(false, null, null, null, "登录无 token(code=$code msg=$msg)")
            }
        } catch (e: Exception) {
            LogStore.append("[login] 异常: ${e.javaClass.simpleName}: ${e.message}")
            Result(false, null, null, null, "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    // ── HTTP ────────────────────────────────────────────────
    private fun httpGet(url: String): String {
        val req = Request.Builder().url(url).get().build()
        client.newCall(req).execute().use { resp ->
            return resp.body?.string() ?: ""
        }
    }

    private fun httpPost(url: String, body: FormBody): String {
        val req = Request.Builder().url(url).post(body).build()
        client.newCall(req).execute().use { resp ->
            return resp.body?.string() ?: ""
        }
    }

    // ── 解析 / 解密 ──────────────────────────────────────────
    /**
     * 取 BaseEntity 的业务数据:优先 data / result / records(对应 Gson 的 @SerializedName alternate),
     * 三者都没有时才回退 payload(hex 编码的 AES 密文,复刻 DecryptInterceptor)。
     * 返回 JSONObject / JSONArray / String / null。
     */
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
        // data/result/records 都缺 → 尝试 payload(hex AES);失败不致命
        val payload = root.optString("payload", "")
        if (payload.isNotEmpty() && payload != "null") {
            return try {
                val dec = aesDecodeHex(payload)
                when {
                    dec.startsWith("{") -> JSONObject(dec)
                    dec.startsWith("[") -> JSONArray(dec)
                    else -> dec
                }
            } catch (e: Exception) {
                LogStore.append("[login] payload 解密失败(忽略): ${e.message}")
                null
            }
        }
        return null
    }

    /** payload 是 hex 编码的 AES/CBC/PKCS7 密文 */
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

    /** RSA/PKCS1 加密 → Base64 → 再 Base64(与 sgb rsaEncrypt + 外层 Base64.encode 双重一致) */
    private fun rsaEncryptDoubleB64(plain: String, publicKeyB64: String): String {
        val keyBytes = Base64.decode(publicKeyB64, Base64.NO_WRAP)
        val pubKey = KeyFactory.getInstance("RSA")
            .generatePublic(X509EncodedKeySpec(keyBytes))
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, pubKey)
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val first = Base64.encodeToString(encrypted, Base64.NO_WRAP)
        return Base64.encodeToString(first.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    }
}
