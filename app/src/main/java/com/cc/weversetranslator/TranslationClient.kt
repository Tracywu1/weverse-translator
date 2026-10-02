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

            val preferred = listOf("gpt-5.6-luna", "gpt-6.1-luna", "gpt-5.6-sol", "gpt-6.1-sol")
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
            你是韩语到简体中文的聊天翻译器，场景是艺人与粉丝的即时私信。
            结合上下文处理省略主语、口语、ㅋㅋ、ㅎㅎ、ㅠㅠ、昵称、网络用语和连续短句。
            中文要像中国年轻人在微信里自然聊天，避免书面翻译腔。
            忠实保留原文的亲密程度、撒娇感、语气词、称呼、emoji 和颜文字，不自行增加暧昧含义。
            遇到歧义时采用最符合上下文的解释。
            必须严格输出 JSON 字符串数组，数组长度必须与待翻译消息数量完全一致。
            每个数组元素只放对应消息的中文翻译，不加编号、标题、解释或 Markdown。
        """.trimIndent()

        val userText = buildString {
            if (recentContext.isNotEmpty()) {
                append("最近上下文：\n")
                recentContext.forEach { append("- ").append(it).append('\n') }
                append('\n')
            }
            append("待翻译消息：\n")
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
