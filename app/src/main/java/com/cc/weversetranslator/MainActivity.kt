package com.cc.weversetranslator

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var status: TextView
    private lateinit var loginButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.textStatus)
        loginButton = findViewById(R.id.buttonLogin)

        loginButton.setOnClickListener { startChatGptLogin() }

        findViewById<Button>(R.id.buttonTest).setOnClickListener { testTranslation() }
        findViewById<Button>(R.id.buttonDisconnect).setOnClickListener {
            AppPrefs.clearCredentials(this)
            updateStatus("已清除本机登录凭据")
        }
        findViewById<Button>(R.id.buttonAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.buttonOpenWeverse).setOnClickListener {
            val launch = packageManager.getLaunchIntentForPackage("co.benx.weverse")
            if (launch != null) startActivity(launch) else updateStatus("设备上未找到 Weverse")
        }

        refreshUi()
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun startChatGptLogin() {
        updateStatus("正在准备 ChatGPT 登录…")
        executor.execute {
            val result = runCatching {
                val flow = OpenAiAuth(this).prepareBrowserFlow()
                runOnUiThread {
                    updateStatus("请在浏览器完成 ChatGPT 登录和套餐授权")
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(flow.url)))
                }
                flow.waitAndFinish()
            }
            runOnUiThread {
                result.onSuccess { updateStatus(it) }
                    .onFailure { updateStatus("登录失败：${it.message ?: it.javaClass.simpleName}") }
            }
        }
    }

    private fun testTranslation() {
        if (!AppPrefs.hasPlanAccess(this)) {
            updateStatus("请先使用 ChatGPT 登录")
            return
        }
        updateStatus("正在使用 ChatGPT 套餐测试翻译…")
        executor.execute {
            val result = runCatching {
                TranslationClient(this).translate(
                    recentContext = listOf("오늘 팬들이랑 얘기 많이 했어 ㅋㅋ"),
                    newMessages = listOf("이제 밥 먹으려고")
                )
            }
            runOnUiThread {
                result.onSuccess { updateStatus("测试结果：\n$it") }
                    .onFailure { updateStatus("测试失败：${it.message ?: it.javaClass.simpleName}") }
            }
        }
    }

    private fun refreshUi() {
        val connected = AppPrefs.hasPlanAccess(this)
        loginButton.text = if (connected) "重新授权 ChatGPT" else "Continue with ChatGPT"
        val email = AppPrefs.email(this)
        val modelName = AppPrefs.modelName(this)
        status.text = if (connected) {
            buildString {
                append("状态：已连接 ChatGPT 套餐")
                if (email.isNotBlank()) append("\n账户：").append(email)
                if (modelName.isNotBlank()) append("\n模型：").append(modelName)
            }
        } else {
            "状态：等待 ChatGPT 登录"
        }
    }

    private fun updateStatus(message: String) {
        status.text = "状态：$message"
    }
}
