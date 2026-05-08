package com.jiuguan.sillytavern

import android.annotation.SuppressLint
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager
import kotlinx.coroutines.*

/**
 * Startup Activity. Supports two modes:
 *
 * V2 (Built-in):  Extract assets → Start NodeService → Wait → Open WebView
 * V1 (Termux):    Check Termux server → Open WebView (fallback)
 */
class LauncherActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "LauncherActivity"
        private const val MAX_SERVER_CHECKS = 120 // 120 × 1s = 2min timeout (first run copies presets)
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var nodeManager: NodeManager
    private lateinit var statusText: TextView
    private lateinit var subtitleText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var btnStart: Button
    private lateinit var btnTermux: Button
    private lateinit var btnSettings: Button
    private lateinit var btnRetry: Button

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val handler = Handler(Looper.getMainLooper())

    private var serverUrl: String = "http://127.0.0.1:8000"
    private var checkAttempts = 0

    /** True if V2 mode: node binary in jniLibs + sillytavern.zip in assets */
    private val isV2Mode: Boolean
        get() = nodeManager.isNodeInstalled() && hasAsset("sillytavern.zip")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_launcher)

        prefs = PreferenceManager.getDefaultSharedPreferences(this)
        nodeManager = NodeManager(applicationContext)

        statusText = findViewById(R.id.text_status)
        subtitleText = findViewById(R.id.text_subtitle)
        progressBar = findViewById(R.id.progress_bar)
        btnStart = findViewById(R.id.btn_start)
        btnTermux = findViewById(R.id.btn_open_termux)
        btnSettings = findViewById(R.id.btn_settings)
        btnRetry = findViewById(R.id.btn_retry)

        btnStart.setOnClickListener {
            if (isV2Mode) startV2() else attemptStartTermux()
        }
        btnTermux.setOnClickListener { openTermux() }
        btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        btnRetry.setOnClickListener {
            checkAttempts = 0
            if (isV2Mode) startV2() else startServerCheck()
        }

        // Decide mode and go
        if (isV2Mode) {
            Log.i(TAG, "V2 mode: built-in Node.js")
            serverUrl = nodeManager.serverUrl
            startV2()
        } else {
            Log.i(TAG, "V1 mode: Termux fallback")
            serverUrl = prefs.getString("server_url", "http://127.0.0.1:8000")
                ?: "http://127.0.0.1:8000"
            startServerCheck()
        }
    }

    override fun onResume() {
        super.onResume()
        if (!isV2Mode) {
            serverUrl = prefs.getString("server_url", "http://127.0.0.1:8000")
                ?: "http://127.0.0.1:8000"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    // ========================= V2: Built-in Mode =========================

    private fun startV2() {
        scope.launch {
            try {
                // Step 1: Check if already running
                if (NodeService.isRunning && nodeManager.isServerReachable()) {
                    showState(State.CONNECTED)
                    delay(300)
                    openMain()
                    return@launch
                }

                // Step 2: Extract node libs + SillyTavern if needed
                val needsExtract = !nodeManager.areNodeLibsInstalled() ||
                    !nodeManager.isSillyTavernInstalled() || !nodeManager.isUpToDate()

                if (needsExtract) {
                    showState(State.EXTRACTING)

                    if (!nodeManager.areNodeLibsInstalled()) {
                        nodeManager.extractNodeLibs { msg ->
                            runOnUiThread { subtitleText.text = msg }
                        }
                    }

                    if (!nodeManager.isSillyTavernInstalled() || !nodeManager.isUpToDate()) {
                        nodeManager.extractSillyTavern { msg ->
                            runOnUiThread { subtitleText.text = msg }
                        }
                    }
                }

                // Step 3: Test node binary
                showState(State.STARTING)
                val testResult = withContext(Dispatchers.IO) {
                    nodeManager.testNodeBinary()
                }
                if (testResult.startsWith("EXIT") || testResult.startsWith("ERROR")) {
                    showState(State.ERROR)
                    runOnUiThread {
                        subtitleText.text = "Node.js 无法运行:\n$testResult"
                    }
                    return@launch
                }
                runOnUiThread {
                    subtitleText.text = "Node.js $testResult 就绪，正在启动酒馆..."
                }

                // Step 4: Prepare config + fix data entries + start server
                withContext(Dispatchers.IO) {
                    nodeManager.ensureConfig()
                    nodeManager.fixCorruptedDataDir()
                }
                val startError = withContext(Dispatchers.IO) {
                    nodeManager.startServer()
                }
                if (startError != null) {
                    showState(State.ERROR)
                    runOnUiThread {
                        subtitleText.text = "启动失败:\n$startError"
                    }
                    return@launch
                }

                // Step 5: Wait for server to be ready
                showState(State.CHECKING)
                checkAttempts = 0
                waitForServer()

            } catch (e: Exception) {
                Log.e(TAG, "V2 startup failed", e)
                showState(State.ERROR)
                runOnUiThread {
                    subtitleText.text = "启动失败: ${e.message}"
                }
            }
        }
    }

    private suspend fun waitForServer() {
        while (checkAttempts < MAX_SERVER_CHECKS) {
            if (nodeManager.isServerReachable()) {
                showState(State.CONNECTED)
                delay(300)
                openMain()
                return
            }
            // Show live output while waiting
            val liveOutput = nodeManager.getProcessOutput()
            if (liveOutput.isNotBlank()) {
                val lastLine = liveOutput.lines().lastOrNull { it.isNotBlank() } ?: ""
                runOnUiThread { subtitleText.text = "等待服务启动...\n$lastLine" }
            }
            // Check if process died
            if (!nodeManager.isProcessAlive()) {
                break
            }
            checkAttempts++
            delay(1000)
        }
        showState(State.NOT_RUNNING)
        val fullOutput = nodeManager.getProcessOutput()
        val alive = nodeManager.isProcessAlive()
        // Show last 30 lines which contain the actual error
        val output = fullOutput.lines().takeLast(30).joinToString("\n")
        val reason = if (output.isNotBlank()) {
            "进程${if (alive) "运行中但未响应" else "已退出"}:\n$output"
        } else {
            "进程${if (alive) "运行中但无输出" else "未启动"}"
        }
        runOnUiThread { subtitleText.text = reason }
    }

    // ========================= V1: Termux Mode =========================

    private fun startServerCheck() {
        showState(State.CHECKING)
        checkAttempts = 0
        checkServerV1()
    }

    private fun checkServerV1() {
        scope.launch {
            val reachable = withContext(Dispatchers.IO) { isServerReachableV1() }
            if (reachable) {
                showState(State.CONNECTED)
                delay(400)
                openMain()
            } else {
                checkAttempts++
                if (checkAttempts < 15) {
                    delay(2000)
                    checkServerV1()
                } else {
                    showState(State.NOT_RUNNING)
                }
            }
        }
    }

    private fun isServerReachableV1(): Boolean {
        return try {
            val url = java.net.URL(serverUrl)
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 2000
            conn.readTimeout = 2000
            conn.requestMethod = "GET"
            val code = conn.responseCode
            conn.disconnect()
            code in 200..399
        } catch (e: Exception) {
            false
        }
    }

    @SuppressLint("QueryPermissionsNeeded")
    private fun attemptStartTermux() {
        showState(State.STARTING)
        try {
            val intent = Intent("com.termux.RUN_COMMAND")
            intent.setClassName("com.termux", "com.termux.app.RunCommandService")
            intent.putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
            intent.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", "cd ~/SillyTavern && bash start.sh"))
            intent.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
            startService(intent)
        } catch (e: Exception) {
            openTermux()
        }
        checkAttempts = 0
        handler.postDelayed({ startServerCheck() }, 3000)
    }

    private fun openTermux() {
        try {
            val intent = packageManager.getLaunchIntentForPackage("com.termux")
            if (intent != null) {
                startActivity(intent)
            } else {
                statusText.text = "未找到 Termux"
                subtitleText.text = "请先安装 Termux 并在其中运行 SillyTavern"
                subtitleText.visibility = View.VISIBLE
            }
        } catch (e: Exception) {
            statusText.text = "无法打开 Termux"
            subtitleText.text = e.message
            subtitleText.visibility = View.VISIBLE
        }
    }

    // ========================= Common =========================

    private fun openMain() {
        val intent = Intent(this, MainActivity::class.java)
        intent.putExtra("server_url", serverUrl)
        startActivity(intent)
        finish()
    }

    private fun hasAsset(name: String): Boolean {
        return try {
            assets.open(name).use { true }
        } catch (e: Exception) {
            false
        }
    }

    private enum class State {
        EXTRACTING, CHECKING, STARTING, CONNECTED, NOT_RUNNING, ERROR
    }

    private fun showState(state: State) {
        runOnUiThread {
            when (state) {
                State.EXTRACTING -> {
                    statusText.text = "首次启动，正在准备..."
                    subtitleText.text = "解压 SillyTavern 运行环境"
                    subtitleText.visibility = View.VISIBLE
                    progressBar.visibility = View.VISIBLE
                    btnStart.visibility = View.GONE
                    btnTermux.visibility = View.GONE
                    btnRetry.visibility = View.GONE
                    btnSettings.visibility = View.GONE
                }
                State.CHECKING -> {
                    statusText.text = "正在连接酒馆..."
                    subtitleText.text = "等待服务就绪"
                    subtitleText.visibility = View.VISIBLE
                    progressBar.visibility = View.VISIBLE
                    btnStart.visibility = View.GONE
                    btnTermux.visibility = View.GONE
                    btnRetry.visibility = View.GONE
                    btnSettings.visibility = View.GONE
                }
                State.STARTING -> {
                    statusText.text = "正在启动酒馆..."
                    subtitleText.text = "请稍等"
                    subtitleText.visibility = View.VISIBLE
                    progressBar.visibility = View.VISIBLE
                    btnStart.visibility = View.GONE
                    btnTermux.visibility = View.GONE
                    btnRetry.visibility = View.GONE
                    btnSettings.visibility = View.GONE
                }
                State.CONNECTED -> {
                    statusText.text = "已连接！"
                    subtitleText.text = "正在打开酒馆..."
                    subtitleText.visibility = View.VISIBLE
                    progressBar.visibility = View.GONE
                    btnStart.visibility = View.GONE
                    btnTermux.visibility = View.GONE
                    btnRetry.visibility = View.GONE
                    btnSettings.visibility = View.GONE
                }
                State.NOT_RUNNING -> {
                    statusText.text = "酒馆未启动"
                    subtitleText.visibility = View.VISIBLE
                    progressBar.visibility = View.GONE
                    btnRetry.visibility = View.VISIBLE
                    btnSettings.visibility = View.VISIBLE
                    if (isV2Mode) {
                        subtitleText.text = "内置服务启动失败，请重试"
                        btnStart.text = "重新启动"
                        btnStart.visibility = View.VISIBLE
                        btnTermux.visibility = View.GONE
                    } else {
                        subtitleText.text = "请先在 Termux 中启动 SillyTavern\n输入: cd ~/SillyTavern && bash start.sh"
                        btnStart.text = "启动酒馆"
                        btnStart.visibility = View.VISIBLE
                        btnTermux.visibility = View.VISIBLE
                    }
                }
                State.ERROR -> {
                    statusText.text = "出错了"
                    subtitleText.visibility = View.VISIBLE
                    progressBar.visibility = View.GONE
                    btnStart.text = "重试"
                    btnStart.visibility = View.VISIBLE
                    btnRetry.visibility = View.GONE
                    btnTermux.visibility = View.GONE
                    btnSettings.visibility = View.VISIBLE
                }
            }
        }
    }
}
