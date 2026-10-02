package com.cc.weversetranslator

import android.app.Activity
import android.content.ComponentName
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
    private lateinit var translationToggleButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.textStatus)
        loginButton = findViewById(R.id.buttonLogin)
        translationToggleButton = findViewById(R.id.buttonToggleTranslation)

        loginButton.setOnClickListener { startChatGptLogin() }
        translationToggleButton.setOnClickListener {
            AppPrefs.setTranslationEnabled(this, !AppPrefs.translationEnabled(this))
            refreshUi()
        }

        findViewById<Button>(R.id.buttonTest).setOnClickListener { testTranslation() }
        findViewById<Button>(R.id.buttonDisconnect).setOnClickListener {
            AppPrefs.clearCredentials(this)
            updateStatus("已清除本机登录凭据")
            refreshUi()
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
                result.onSuccess {
                    AppPrefs.clearModel(this)
                    refreshUi()
                }.onFailure { updateStatus("登录失败：${it.message ?: it.javaClass.simpleName}") }
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
                    recentContext = listOf(
                        "애들이 난 강아지밖에 못하게 해",
                        "나도 다양한거 하고 싶어",
                        "아니 어울리는걸 떠나서"
                    ),
                    newMessages = listOf("이한이도 고양이를 시켜주는 마음도 있어")
                )
            }
            runOnUiThread {
                result.onSuccess {
                    updateStatus("测试结果：\n$it")
                    refreshUi(keepMessage = true)
                }.onFailure { updateStatus("测试失败：${it.message ?: it.javaClass.simpleName}") }
            }
        }
    }

    private fun refreshUi(keepMessage: Boolean = false) {
        val connected = AppPrefs.hasPlanAccess(this)
        val accessibilityEnabled = isAccessibilityEnabled()
        val weverseInstalled = isWeverseInstalled()
        val translationEnabled = AppPrefs.translationEnabled(this)

        loginButton.text = if (connected) "重新授权 ChatGPT" else "Continue with ChatGPT"
        translationToggleButton.text = if (translationEnabled) "实时翻译：已开启" else "实时翻译：已暂停"

        if (keepMessage) return

        val email = AppPrefs.email(this)
        val modelName = AppPrefs.modelName(this)
        status.text = buildString {
            append("ChatGPT：").append(if (connected) "✓ 已连接" else "○ 待登录")
            if (email.isNotBlank()) append("\n账户：").append(email)
            if (modelName.isNotBlank()) append("\n模型：").append(modelName)
            append("\n无障碍服务：").append(if (accessibilityEnabled) "✓ 已开启" else "○ 待开启")
            append("\nWeverse：").append(if (weverseInstalled) "✓ 已检测" else "○ 未检测")
            append("\n实时翻译：").append(if (translationEnabled) "✓ 开启" else "暂停")
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        val component = ComponentName(this, WeverseAccessibilityService::class.java)
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        return enabledServices.split(':').any { raw ->
            ComponentName.unflattenFromString(raw) == component
        }
    }

    private fun isWeverseInstalled(): Boolean = runCatching {
        packageManager.getApplicationInfo("co.benx.weverse", 0)
    }.isSuccess

    private fun updateStatus(message: String) {
        status.text = "状态：$message"
    }
}
