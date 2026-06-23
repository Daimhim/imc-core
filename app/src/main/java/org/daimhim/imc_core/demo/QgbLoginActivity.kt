package org.daimhim.imc_core.demo

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * 临时调试登录页:输入手机号 + 密码,走 [QgbLoginHelper] 拿 token + imAccount,
 * 写入 QgbWsTestActivity 共用的 SharedPreferences(KEY_LAST_TOKEN / KEY_LAST_NAME),
 * setResult(OK) 返回,连接页据此回填。账号密码本地持久化,免重复输入。
 */
class QgbLoginActivity : AppCompatActivity() {

    companion object {
        // 复用连接页的 prefs,登录结果直接进 KEY_LAST_TOKEN / KEY_LAST_NAME
        private const val WS_PREFS = "qgb_ws_test"
        private const val KEY_LAST_TOKEN = "last_token"
        private const val KEY_LAST_NAME = "last_name"
        // 登录页自己的账号密码缓存
        private const val LOGIN_PREFS = "qgb_login"
        private const val KEY_USER = "login_user"
        private const val KEY_PWD = "login_pwd"
        private const val KEY_REMEMBER = "login_remember"
    }

    private val wsPrefs by lazy { getSharedPreferences(WS_PREFS, MODE_PRIVATE) }
    private val loginPrefs by lazy { getSharedPreferences(LOGIN_PREFS, MODE_PRIVATE) }
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var lastToken: String? = null
    @Volatile private var lastName: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_qgb_login)
        title = "登录获取 WS 参数"

        val etUser = findViewById<EditText>(R.id.et_login_user)
        val etPwd = findViewById<EditText>(R.id.et_login_pwd)
        val cbRemember = findViewById<CheckBox>(R.id.cb_remember)
        val tvResult = findViewById<TextView>(R.id.tv_login_result)
        val btLogin = findViewById<Button>(R.id.bt_do_login)
        val btBack = findViewById<Button>(R.id.bt_use_and_back)

        // 恢复缓存的账号密码
        cbRemember.isChecked = loginPrefs.getBoolean(KEY_REMEMBER, true)
        if (cbRemember.isChecked) {
            etUser.setText(loginPrefs.getString(KEY_USER, ""))
            etPwd.setText(loginPrefs.getString(KEY_PWD, ""))
        }

        btLogin.setOnClickListener {
            val user = etUser.text.toString().trim()
            val pwd = etPwd.text.toString()
            if (user.isEmpty() || pwd.isEmpty()) {
                Toast.makeText(this, "手机号 / 密码不能为空", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // 持久化账号密码
            if (cbRemember.isChecked) {
                loginPrefs.edit()
                    .putString(KEY_USER, user)
                    .putString(KEY_PWD, pwd)
                    .putBoolean(KEY_REMEMBER, true)
                    .apply()
            } else {
                loginPrefs.edit().remove(KEY_USER).remove(KEY_PWD).putBoolean(KEY_REMEMBER, false).apply()
            }

            btLogin.isEnabled = false
            tvResult.text = "登录中…"
            LogStore.append("[login] 发起登录 user=$user")
            Thread {
                val r = QgbLoginHelper.login(user, pwd)
                mainHandler.post {
                    btLogin.isEnabled = true
                    if (r.ok) {
                        lastToken = r.token
                        lastName = r.name
                        // 写入连接页共用 prefs
                        wsPrefs.edit()
                            .putString(KEY_LAST_TOKEN, r.token)
                            .putString(KEY_LAST_NAME, r.name)
                            .apply()
                        tvResult.text = buildString {
                            append("✓ 登录成功\n")
                            append("name(imAccount): ").append(r.name).append('\n')
                            append("phone: ").append(r.phone ?: "-").append('\n')
                            append("token: ").append(r.token?.take(32)).append("…(${r.token?.length}字符)")
                        }
                        btBack.isEnabled = true
                        Toast.makeText(this, "登录成功", Toast.LENGTH_SHORT).show()
                    } else {
                        tvResult.text = "✗ 登录失败\n${r.msg}"
                        Toast.makeText(this, "登录失败: ${r.msg}", Toast.LENGTH_LONG).show()
                    }
                }
            }.start()
        }

        btBack.setOnClickListener {
            // token/name 已写 prefs;返回 OK 让连接页回填
            setResult(Activity.RESULT_OK)
            finish()
        }
    }
}
