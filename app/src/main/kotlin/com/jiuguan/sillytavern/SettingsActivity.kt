package com.jiuguan.sillytavern

import android.content.SharedPreferences
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager
import kotlinx.coroutines.*
import java.net.HttpURLConnection
import java.net.URL

/**
 * Settings screen for V1 (Termux) mode — configure server URL and test connection.
 * In V2 mode this is mostly informational since the server runs built-in.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var editServerUrl: EditText
    private lateinit var btnSave: Button
    private lateinit var btnTest: Button
    private lateinit var btnBack: Button
    private lateinit var textTestResult: TextView
    private lateinit var textHelp: TextView

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        prefs = PreferenceManager.getDefaultSharedPreferences(this)

        editServerUrl = findViewById(R.id.edit_server_url)
        btnSave = findViewById(R.id.btn_save)
        btnTest = findViewById(R.id.btn_test)
        btnBack = findViewById(R.id.btn_back)
        textTestResult = findViewById(R.id.text_test_result)
        textHelp = findViewById(R.id.text_help)

        // Load saved settings
        editServerUrl.setText(prefs.getString("server_url", "http://127.0.0.1:8000"))

        btnSave.setOnClickListener { saveSettings() }
        btnTest.setOnClickListener { testConnection() }
        btnBack.setOnClickListener { finish() }

        textHelp.text = buildString {
            appendLine("使用说明：")
            appendLine()
            appendLine("1. 打开 Termux 应用")
            appendLine("2. 输入以下命令启动酒馆：")
            appendLine("   cd ~/SillyTavern && bash start.sh")
            appendLine("3. 看到 \"listening on port 8000\" 后")
            appendLine("   切换回本应用即可")
            appendLine()
            appendLine("快捷方式（如果你设置了别名）：")
            appendLine("   输入 st 回车即可启动")
            appendLine()
            appendLine("默认地址：http://127.0.0.1:8000")
            appendLine("通常不需要修改，除非你改过端口。")
        }
    }

    private fun saveSettings() {
        val url = editServerUrl.text.toString().trim()
        if (url.isEmpty()) {
            Toast.makeText(this, "请输入服务器地址", Toast.LENGTH_SHORT).show()
            return
        }
        prefs.edit().putString("server_url", url).apply()
        Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun testConnection() {
        val url = editServerUrl.text.toString().trim()
        if (url.isEmpty()) {
            textTestResult.text = "请先输入地址"
            return
        }
        textTestResult.text = "正在测试..."
        textTestResult.setTextColor(getColor(R.color.text_secondary))

        scope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val conn = URL(url).openConnection() as HttpURLConnection
                    conn.connectTimeout = 3000
                    conn.readTimeout = 3000
                    val code = conn.responseCode
                    conn.disconnect()
                    if (code in 200..399) "connected" else "error:$code"
                } catch (e: Exception) {
                    "error:${e.message}"
                }
            }

            if (result == "connected") {
                textTestResult.text = "连接成功！酒馆服务正在运行。"
                textTestResult.setTextColor(getColor(R.color.success))
            } else {
                textTestResult.text = "连接失败：$result\n请确认 Termux 中 SillyTavern 是否已启动。"
                textTestResult.setTextColor(getColor(R.color.error))
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
