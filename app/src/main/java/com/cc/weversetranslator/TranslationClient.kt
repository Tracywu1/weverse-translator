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

    fun translate(
        recentContext: List<String>,
        newMessages: List<String>,
        artistName: String = ""
    ): String = translateLines(recentContext, newMessages, artistName).joinToString("\n")

    fun translateLines(
        recentContext: List<String>,
        newMessages: List<String>,
        artistName: String = ""
    ): List<String> {
        require(newMessages.isNotEmpty()) { "当前没有待翻译消息" }
        val token = OpenAiAuth(context).validAccessToken()
        var model = AppPrefs.model(context)
        if (model.isBlank()) model = chooseModel(token)

        val raw = try {
            streamTranslation(token, model, recentContext, newMessages, artistName)
        } catch (e: ModelUnavailableException) {
            AppPrefs.clearModel(context)
            model = chooseModel(token)
            streamTranslation(token, model, recentContext, newMessages, artistName)
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
        newMessages: List<String>,
        artistName: String
    ): String {
        val instructions = """
            你是专门翻译韩语偶像私信的简体中文译者。目标是先理解艺人连续发送的一整段韩文，再给出自然、准确、能直接看懂的中文。

            你收到的上下文只包含艺人一侧的韩文消息。不要假设用户回复了什么，也不要根据缺失的用户消息补剧情。

            最重要的结构规则：
            1. 一个聊天气泡不一定是一句完整的话。艺人经常把同一句话拆成两三条连续发送。必须先判断相邻气泡是否共同组成一个句子或语义单元，再整体理解。
            2. 输出仍要严格对应原气泡数量，但允许前一个中文气泡以逗号、称呼、连接词或未完成短语结束，让后一个气泡自然接上。不要为了让每个气泡单独成句而扭曲意思。
            3. 例如 A1“근데 내여자야” + A2“졸릴때 잠깨는법 좀” 在语境合适时，应理解为连续表达，可译成 ["但是，我的女孩呀，", "困的时候有没有什么醒困的方法？"]，而不是把 A1 强行译成“但是她是我的女人”。

            玩词、造词和昵称规则：
            4. 遇到谐音、字形替换、拆字、合成词、昵称变体、拟声词、粉圈梗时，先结合当前艺人名、前后连续消息和刚出现过的关键词推断“这个梗在玩什么”。
            5. 如果一个临时造词明显由艺人名字/昵称的一部分与附近词组合、替换或变形而来，要优先在中文里重现这个关系，而不是机械音译。
            6. 例如艺人名含“명”，附近又反复出现“코알라”，随后出现“띵알라”时，要考虑“명/띵 + 코알라”的字形/造词梗。若结合上下文足够明确，可译成类似“明考拉”这种中文能看懂且保留梗关系的表达。
            7. 如果梗的具体来源无法完全确定，但能确定它是在玩某个词，不要直接把一串韩文原词丢给用户。优先给出最接近语用功能的中文；必要时可用极短的中文括注解释梗，例如“XX（玩‘考拉’的谐音梗）”。括注只在确有必要时使用，保持简短。
            8. 绝对不要为了显得自然而瞎编一个具体人名、人物关系或事件。人名判断要保守；只有艺人名或连续上下文有明确证据时才按专名处理。

            常规翻译规则：
            9. 先分析助词、词尾、连写、空格错误、复合词边界和省略，再翻译。像“수정”这类既可能是普通词又可能像姓名的形式，缺少人物证据时优先按句法和普通词义理解，不能擅自翻成“秀晶”等具体人名。
            10. 对“강아지 / 고양이 / 냥이”等角色梗、动物设定和撒娇称呼，要结合连续艺人消息翻成“小狗、猫猫”等自然表达；“시키다”在角色语境中可表示“让某人当/扮演某个角色”。
            11. 优先自然中文，但准确性高于润色。短句保持短，长句完整保留原意。任何中文信息都必须能在韩文原句、艺人名或艺人侧上下文中找到依据。
            12. 保留原文亲密程度、调侃、撒娇感、ㅋㅋ、ㅎㅎ、ㅠㅠ、emoji、颜文字和称呼；不要自行增强恋爱意味、人物关系或事实。
            13. 如果一句有两种合理解析，选择与艺人前后消息最一致的一种；仍然无法确定时用更中性的中文表达，但必须让中文用户看得懂。
            14. 同一批连续消息里的称呼、专名、玩词解释和语气必须前后一致。

            输出要求：
            - 必须严格输出 JSON 字符串数组。
            - 数组长度必须与待翻译消息数量完全一致。
            - 每个数组元素只放对应气泡最终要显示的中文。
            - 可以让连续数组元素共同组成一句完整中文。
            - 不加编号、标题、长篇解释或 Markdown。
        """.trimIndent()

        val userText = buildString {
            if (artistName.isNotBlank()) {
                append("当前艺人/聊天对象名称：").append(artistName).append("\n\n")
            }
            if (recentContext.isNotEmpty()) {
                append("最近艺人消息上下文（按时间顺序）：\n")
                recentContext.forEachIndexed { index, value ->
                    append("C").append(index + 1).append(". ").append(value).append('\n')
                }
                append('\n')
            }
            append("待翻译艺人消息（按时间顺序；相邻气泡可能属于同一句话）：\n")
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
