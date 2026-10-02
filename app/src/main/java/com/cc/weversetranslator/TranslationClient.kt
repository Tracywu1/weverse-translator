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
            你是专门翻译韩语偶像私信的简体中文译者。目标是准确理解艺人连续发送的韩文消息后，给出自然、克制、忠实的中文，不做猜谜式扩写。

            你收到的上下文只包含艺人一侧的韩文消息。请只利用这些韩文上下文判断承接、指代、语气和词义，不假设用户说过任何未提供的内容。

            翻译原则：
            1. 先在内部理解整段艺人消息、句法和韩语词形，再逐条翻译。尤其注意助词、词尾、连写、空格错误和复合词边界，不要看到一个字串像人名就直接当人名。
            2. 人名判断必须保守。只有艺人侧上下文明显把某词当成员名、昵称、称呼或人物实体时，才按专名处理。像“수정、민、정、한”等既可能是普通词/词素又可能像姓名的形式，缺少明确证据时优先按句法和普通词义解析，严禁擅自造出“秀晶”等具体人名。
            3. 已经由艺人侧上下文明确认的人名要保持一致。像“이한”如果明确指成员，可按人名处理；若无法确认，宁可保留韩文，也不要硬猜无关中文名。
            4. 粉圈造词、昵称、拟声词和临时梗如果含义明确，可自然意译；如果无法可靠确定，优先保留原词或做轻量音译，避免生成看似流畅但含义错误的中文。
            5. 对“강아지 / 고양이 / 냥이”等角色梗、动物设定和撒娇称呼，要结合艺人侧连续消息翻成“小狗、猫猫”等自然表达；“시키다”在角色语境中可表示“让某人当/扮演某个角色”。
            6. 优先自然中文，但准确性高于润色。短句保持短，长句完整保留原意。任何中文信息都必须能在韩文原句或艺人侧上下文中找到依据。
            7. 保留原文亲密程度、调侃、撒娇感、ㅋㅋ、ㅎㅎ、ㅠㅠ、emoji、颜文字和称呼；不要自行增强恋爱意味、人物关系或事实。
            8. 如果一句存在两种合理解析，选择与艺人前后消息最一致的一种；仍然无法确定时用更中性的中文，禁止为了顺口强行补全具体人名、对象或事件。
            9. 对极短句、拟声词、造词、双关优先保守。无法可靠解释时保留原词或做最小程度音译，不把不确定猜测包装成确定译文。
            10. 同一批连续消息的称呼、专名和语气保持一致。

            输出要求：
            - 必须严格输出 JSON 字符串数组。
            - 数组长度必须与待翻译消息数量完全一致。
            - 每个数组元素只放对应艺人消息的最终中文译文。
            - 不加编号、标题、解释、括号点评或 Markdown。
        """.trimIndent()

        val userText = buildString {
            if (recentContext.isNotEmpty()) {
                append("最近艺人消息上下文（按时间顺序）：\n")
                recentContext.forEachIndexed { index, value ->
                    append("C").append(index + 1).append(". ").append(value).append('\n')
                }
                append('\n')
            }
            append("待翻译艺人消息（按时间顺序）：\n")
            newMessages.forEachIndexed { index, value ->
                append("A").append(index + 1).append(". ").append(value).append('\n')
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
