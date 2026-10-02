package com.cc.weversetranslator

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

class TranslationClient(private val context: Context) {
    companion object {
        private const val API_ROOT = "https://api.openai.com/v1"
    }

    fun translate(recentContext: List<String>, newMessages: List<String>): String =
        translateLines(recentContext, newMessages).joinToString("\n")

    fun translateLines(recentContext: List<String>, newMessages: List<String>): List<String> {
        require(newMessages.isNotEmpty()) { "当前没有待翻译消息" }
        val token = OpenAiAuth(context).validAccessToken()
        var model = AppPrefs.model(context)
        if (model.isBlank()) model = chooseModel(token)

        val raw = try {
            streamTranslation(token, model, recentContext, newMessages)
        } catch (e: ModelUnavailableException) {
            AppPrefs.clearModel(context)
            model = chooseModel(token)
            streamTranslation(token, model, recentContext, newMessages)
        }
        return parseTranslationLines(raw, newMessages.size)
    }

    private fun chooseModel(token: String): String {
        val conn = (URL("$API_ROOT/models").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 20_000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/json")
        }
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val raw = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
            if (code !in 200..299) error(formatHttpError(code, raw))
            val root = JSONObject(raw)
            val models = root.optJSONArray("models") ?: root.optJSONArray("data") ?: JSONArray()
            val candidates = mutableListOf<Pair<String, String>>()
            for (i in 0 until models.length()) {
                val item = models.optJSONObject(i) ?: continue
                val visible = item.optString("visibility")
                if (visible.isNotBlank() && visible != "list") continue
                val slug = item.optString("slug").ifBlank { item.optString("id") }
                if (slug.isBlank()) continue
                val name = item.optString("display_name").ifBlank { slug }
                candidates += slug to name
            }
            if (candidates.isEmpty()) error("ChatGPT 套餐当前未返回可用模型")

            // Plus/Pro优先质量更高的 Sol；不可用时再回退到 Luna。
            val preferred = listOf("gpt-5.6-sol", "gpt-6.1-sol", "gpt-5.6-luna", "gpt-6.1-luna")
            val selected = preferred.firstNotNullOfOrNull { p -> candidates.firstOrNull { it.first == p } }
                ?: candidates.first()
            AppPrefs.saveModel(context, selected.first, selected.second)
            return selected.first
        } finally {
            conn.disconnect()
        }
    }

    private fun streamTranslation(
        token: String,
        model: String,
        recentContext: List<String>,
        newMessages: List<String>
    ): String {
        val instructions = """
            你是专门翻译韩语偶像私信的简体中文译者。目标是“准确理解上下文后，用中国年轻人真实聊天时会说的话表达”，而不是逐字翻译。

            翻译原则：
            1. 先把最近上下文和本批消息当作一段连续对话理解，再逐条输出对应译文。必须保留消息之间的承接关系、省略主语、指代和语气。
            2. 优先自然口语。避免“名女子呀”“做点各种各样的事情”“给予温暖的话语”这类生硬直译。
            3. 人名、成员名、昵称和粉圈专名绝对不要按普通词义乱翻。像“이한”这类疑似人名，要按人名理解；不确定官方中文名时可保留韩文或采用稳妥音译，不能臆造无关名词。
            4. 对“강아지 / 고양이 / 냥이”等角色梗、动物设定和撒娇称呼，要结合上下文翻成“小狗、猫猫、XX猫猫”等自然表达；“시키다”在这种语境常表示“让某人当/扮演某个角色”。
            5. 对韩语口语、省略、连写、错别字、造词和网络梗，先推断最可能的真实意图。若仍有歧义，选最贴合当前对话的一种，不要随意扩写。
            6. 对“어울리다”一类词要按语境处理成“合适/适合/搭”，不要机械翻译；对“떠나서”常按“先不说/抛开……不谈”理解。
            7. 保留原文亲密程度、撒娇感、ㅋㅋ、ㅎㅎ、ㅠㅠ、emoji、颜文字和称呼。中文可以自然转成“哈哈/嘿嘿/呜呜”等，但不要自行增加更强的暧昧或恋爱意味。
            8. 短句尽量短，长句保持原意完整。不要为了“好听”改写成新的信息。
            9. 连续几条明显在讲同一件事时，译文风格和名词必须前后一致。

            输出要求：
            - 必须严格输出 JSON 字符串数组。
            - 数组长度必须与待翻译消息数量完全一致。
            - 每个数组元素只放对应消息的最终中文译文。
            - 不加编号、标题、解释、括号点评或 Markdown。
        """.trimIndent()

        val userText = buildString {
            if (recentContext.isNotEmpty()) {
                append("最近上下文（按时间顺序）：\n")
                recentContext.forEachIndexed { index, value ->
                    append("C").append(index + 1).append(". ").append(value).append('\n')
                }
                append('\n')
            }
            append("待翻译消息（按时间顺序）：\n")
            newMessages.forEachIndexed { index, value ->
                append(index + 1).append(". ").append(value).append('\n')
            }
        }

        val input = JSONArray().put(
            JSONObject()
                .put("role", "user")
                .put("content", userText)
        )
        val body = JSONObject()
            .put("model", model)
            .put("instructions", instructions)
            .put("input", input)
            .put("store", false)
            .put("stream", true)

        val conn = (URL("$API_ROOT/responses").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 60_000
            doOutput = true
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "text/event-stream")
        }

        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val raw = BufferedReader(InputStreamReader(conn.errorStream, Charsets.UTF_8)).use { it.readText() }
                val parsedCode = errorCode(raw)
                if (code == 400 && parsedCode.contains("model", ignoreCase = true)) {
                    throw ModelUnavailableException()
                }
                error(formatHttpError(code, raw))
            }

            val output = StringBuilder()
            var completed = false
            var failure: String? = null
            BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).useLines { lines ->
                lines.forEach { line ->
                    if (!line.startsWith("data:")) return@forEach
                    val data = line.removePrefix("data:").trim()
                    if (data.isBlank() || data == "[DONE]") return@forEach
                    val event = runCatching { JSONObject(data) }.getOrNull() ?: return@forEach
                    when (event.optString("type")) {
                        "response.output_text.delta" -> output.append(event.optString("delta"))
                        "response.completed" -> completed = true
                        "response.failed" -> {
                            val err = event.optJSONObject("response")?.optJSONObject("error")
                            failure = friendlyError(err?.optString("code").orEmpty(), err?.optString("message").orEmpty())
                        }
                        "error" -> {
                            val err = event.optJSONObject("error")
                            failure = friendlyError(err?.optString("code").orEmpty(), err?.optString("message").orEmpty())
                        }
                    }
                }
            }
            failure?.let { error(it) }
            if (!completed) error("翻译连接提前结束，请重试")
            return output.toString().trim().ifBlank { error("模型未返回翻译文字") }
        } finally {
            conn.disconnect()
        }
    }

    private fun parseTranslationLines(raw: String, expected: Int): List<String> {
        val cleaned = raw.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()

        val array = runCatching { JSONArray(cleaned) }.getOrNull()
        if (array != null && array.length() == expected) {
            return List(expected) { index -> array.optString(index).trim() }
        }

        val fallback = cleaned.lines()
            .map { it.trim().removePrefix("-").trim() }
            .filter { it.isNotBlank() }
        if (fallback.size == expected) return fallback
        error("翻译结果条数与原消息不一致，请重试")
    }

    private fun formatHttpError(code: Int, raw: String): String {
        val json = runCatching { JSONObject(raw) }.getOrNull()
        val err = json?.optJSONObject("error")
        val errorCode = err?.optString("code").orEmpty()
        val message = err?.optString("message").orEmpty().ifBlank { json?.optString("detail").orEmpty() }
        return "OpenAI $code：${friendlyError(errorCode, message).ifBlank { raw.take(260) }}"
    }

    private fun friendlyError(code: String, message: String): String = when (code) {
        "subscription_sharing_usage_limit_exceeded" -> "ChatGPT Plus 当前五小时共享额度已达到上限，额度恢复后会继续可用"
        "subscription_sharing_usage_unavailable" -> "ChatGPT 套餐额度暂时不可用，请稍后重试"
        "chatpass_v2_scope_not_authorized", "chatpass_v2_invalid_authorization_context" -> "ChatGPT 套餐授权已失效，请回翻译器重新登录"
        "subscription_sharing_invalid_user" -> "ChatGPT 登录状态已失效，请重新登录"
        else -> message.ifBlank { code }
    }

    private fun errorCode(raw: String): String = runCatching {
        JSONObject(raw).optJSONObject("error")?.optString("code").orEmpty()
    }.getOrDefault("")

    private class ModelUnavailableException : RuntimeException()
}
