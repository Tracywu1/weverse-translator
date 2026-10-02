package com.cc.weversetranslator

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.math.BigInteger
import java.util.Base64

class OpenAiAuth(private val context: Context) {
    companion object {
        private const val AUTHORIZE = "https://auth.openai.com/api/accounts/authorize"
        private const val TOKEN = "https://auth.openai.com/api/accounts/oauth/token"
        private const val JWKS = "https://auth.openai.com/.well-known/jwks.json"
        private const val ISSUER = "https://auth.openai.com"
        private const val RESOURCE = "https://api.openai.com/v1"
        private const val DYNAMIC_CLIENT = "dynamic_agent_client"
        private const val AGENT_NAME = "Weverse Translator"
        private const val SCOPES = "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct"
    }

    data class BrowserFlow(val url: String, val waitAndFinish: () -> String)

    fun prepareBrowserFlow(): BrowserFlow {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).apply {
            soTimeout = 300_000
        }
        val redirectUri = "http://127.0.0.1:${server.localPort}/auth/callback"
        val state = randomBase64Url(32)
        val nonce = randomBase64Url(32)
        val verifier = randomBase64Url(64)
        val challenge = base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))

        val savedClient = AppPrefs.clientId(context)
        val isNew = savedClient.isBlank()
        val clientId = if (isNew) DYNAMIC_CLIENT else savedClient

        val params = linkedMapOf(
            "client_id" to clientId,
            "redirect_uri" to redirectUri,
            "response_type" to "code",
            "scope" to SCOPES,
            "resource" to RESOURCE,
            "state" to state,
            "nonce" to nonce,
            "code_challenge" to challenge,
            "code_challenge_method" to "S256",
            "ext_agent_host_id" to AppPrefs.hostId(context)
        )
        if (isNew) params["agent_name_hint"] = AGENT_NAME
        if (!isNew && AppPrefs.idToken(context).isNotBlank()) params["id_token_hint"] = AppPrefs.idToken(context)
        if (!isNew && AppPrefs.email(context).isNotBlank()) params["login_hint"] = AppPrefs.email(context)

        val authUrl = AUTHORIZE + "?" + params.entries.joinToString("&") {
            enc(it.key) + "=" + enc(it.value)
        }

        return BrowserFlow(authUrl) {
            try {
                val callback = acceptCallback(server)
                if (callback["state"] != state) error("登录状态校验失败，请重新登录")
                callback["error"]?.let { error("ChatGPT 授权失败：$it") }
                val code = callback["code"].orEmpty()
                if (code.isBlank()) error("登录回调缺少授权码")

                val issuedClient = if (isNew) callback["client_id"].orEmpty() else savedClient
                if (issuedClient.isBlank() || issuedClient == DYNAMIC_CLIENT) {
                    error("ChatGPT 客户端注册未完成")
                }
                if (!isNew && callback["client_id"].orEmpty().let { it.isNotBlank() && it != savedClient }) {
                    error("登录返回了不同的客户端标识")
                }

                val tokenJson = postForm(
                    TOKEN,
                    linkedMapOf(
                        "grant_type" to "authorization_code",
                        "code" to code,
                        "redirect_uri" to redirectUri,
                        "client_id" to issuedClient,
                        "code_verifier" to verifier,
                        "resource" to RESOURCE
                    )
                )

                val accessToken = tokenJson.optString("access_token")
                val refreshToken = tokenJson.optString("refresh_token")
                val idToken = tokenJson.optString("id_token")
                val scope = tokenJson.optString("scope")
                val expiresIn = tokenJson.optLong("expires_in", 3600L)
                if (accessToken.isBlank() || refreshToken.isBlank() || idToken.isBlank()) {
                    error("登录成功，但凭据返回不完整")
                }
                if (!scope.split(' ').contains("chatgpt.tokens.use.direct")) {
                    error("你已登录，但未授权使用 ChatGPT 套餐额度")
                }

                val claims = verifyIdToken(idToken, issuedClient, nonce)
                val subject = claims.optString("sub")
                val email = claims.optString("email")
                if (subject.isBlank()) error("ID Token 缺少账户标识")

                AppPrefs.saveTokens(
                    context,
                    issuedClient,
                    subject,
                    email,
                    idToken,
                    accessToken,
                    refreshToken,
                    scope,
                    expiresIn
                )
                AppPrefs.clearModel(context)
                if (email.isBlank()) "已连接 ChatGPT 套餐" else "已连接：$email"
            } finally {
                runCatching { server.close() }
            }
        }
    }

    @Synchronized
    fun validAccessToken(): String {
        if (!AppPrefs.hasPlanAccess(context)) error("请先使用 ChatGPT 登录")
        val token = AppPrefs.accessToken(context)
        val expiresAt = AppPrefs.savedAt(context) + AppPrefs.expiresIn(context) * 1000L
        if (token.isNotBlank() && System.currentTimeMillis() < expiresAt - 120_000L) return token

        val refresh = AppPrefs.refreshToken(context)
        val clientId = AppPrefs.clientId(context)
        if (refresh.isBlank() || clientId.isBlank()) error("登录已失效，请重新登录 ChatGPT")

        val json = postForm(
            TOKEN,
            linkedMapOf(
                "grant_type" to "refresh_token",
                "client_id" to clientId,
                "refresh_token" to refresh,
                "resource" to RESOURCE
            )
        )
        val newAccess = json.optString("access_token")
        val newRefresh = json.optString("refresh_token")
        val newScope = json.optString("scope").ifBlank { AppPrefs.scopes(context) }
        val expiresIn = json.optLong("expires_in", 3600L)
        if (newAccess.isBlank() || newRefresh.isBlank()) error("ChatGPT 登录续期失败，请重新登录")
        if (!newScope.split(' ').contains("chatgpt.tokens.use.direct")) {
            error("ChatGPT 套餐授权已关闭，请重新登录并授权")
        }
        AppPrefs.updateRefreshedTokens(
            context,
            newAccess,
            newRefresh,
            newScope,
            expiresIn,
            json.optString("id_token").takeIf { it.isNotBlank() }
        )
        return newAccess
    }

    private fun acceptCallback(server: ServerSocket): Map<String, String> {
        server.accept().use { socket ->
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            val firstLine = reader.readLine().orEmpty()
            val target = firstLine.split(' ').getOrNull(1).orEmpty()
            val query = Uri.parse("http://127.0.0.1$target").encodedQuery.orEmpty()
            val result = query.split('&').filter { it.isNotBlank() }.associate { part ->
                val pair = part.split('=', limit = 2)
                dec(pair[0]) to dec(pair.getOrElse(1) { "" })
            }
            val html = """
                <!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width"></head>
                <body style="font-family:sans-serif;padding:28px"><h2>ChatGPT 登录已完成</h2><p>现在可以返回 Weverse Translator。</p></body></html>
            """.trimIndent().toByteArray(Charsets.UTF_8)
            val header = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${html.size}\r\nConnection: close\r\n\r\n"
            socket.getOutputStream().apply {
                write(header.toByteArray(Charsets.UTF_8))
                write(html)
                flush()
            }
            return result
        }
    }

    private fun verifyIdToken(token: String, expectedAudience: String, expectedNonce: String): JSONObject {
        val parts = token.split('.')
        if (parts.size != 3) error("ID Token 格式错误")
        val header = JSONObject(String(Base64.getUrlDecoder().decode(pad(parts[0])), Charsets.UTF_8))
        val claims = JSONObject(String(Base64.getUrlDecoder().decode(pad(parts[1])), Charsets.UTF_8))
        if (header.optString("alg") != "RS256") error("ID Token 签名算法异常")
        val kid = header.optString("kid")
        if (kid.isBlank()) error("ID Token 缺少 kid")

        val jwks = getJson(JWKS).optJSONArray("keys") ?: JSONArray()
        var jwk: JSONObject? = null
        for (i in 0 until jwks.length()) {
            val candidate = jwks.optJSONObject(i) ?: continue
            if (candidate.optString("kid") == kid) {
                jwk = candidate
                break
            }
        }
        val key = jwk ?: error("OpenAI JWKS 中找不到匹配密钥")
        val n = BigInteger(1, Base64.getUrlDecoder().decode(pad(key.getString("n"))))
        val e = BigInteger(1, Base64.getUrlDecoder().decode(pad(key.getString("e"))))
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(n, e))
        val verifier = Signature.getInstance("SHA256withRSA").apply {
            initVerify(publicKey)
            update("${parts[0]}.${parts[1]}".toByteArray(Charsets.US_ASCII))
        }
        val signature = Base64.getUrlDecoder().decode(pad(parts[2]))
        if (!verifier.verify(signature)) error("ID Token 签名验证失败")

        if (claims.optString("iss") != ISSUER) error("ID Token issuer 校验失败")
        if (!audienceMatches(claims.opt("aud"), expectedAudience)) error("ID Token audience 校验失败")
        if (claims.optLong("exp", 0L) * 1000L <= System.currentTimeMillis()) error("ID Token 已过期")
        if (claims.optString("nonce") != expectedNonce) error("ID Token nonce 校验失败")
        return claims
    }

    private fun audienceMatches(value: Any?, expected: String): Boolean = when (value) {
        is String -> value == expected
        is JSONArray -> (0 until value.length()).any { value.optString(it) == expected }
        else -> false
    }

    private fun postForm(url: String, values: Map<String, String>): JSONObject {
        val body = values.entries.joinToString("&") { enc(it.key) + "=" + enc(it.value) }
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 30_000
            doOutput = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val raw = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
            if (code !in 200..299) {
                val detail = runCatching {
                    val j = JSONObject(raw)
                    j.optString("error_description").ifBlank {
                        j.optJSONObject("error")?.optString("message").orEmpty()
                    }.ifBlank { j.optString("detail") }
                }.getOrDefault(raw.take(300))
                error("OpenAI 登录 $code：${detail.ifBlank { raw.take(300) }}")
            }
            return JSONObject(raw)
        } finally {
            conn.disconnect()
        }
    }

    private fun getJson(url: String): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 20_000
        try {
            val raw = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() }
            return JSONObject(raw)
        } finally {
            conn.disconnect()
        }
    }

    private fun randomBase64Url(bytes: Int): String {
        val data = ByteArray(bytes)
        SecureRandom().nextBytes(data)
        return base64Url(data)
    }

    private fun base64Url(data: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(data)

    private fun pad(value: String): String = value + "=".repeat((4 - value.length % 4) % 4)
    private fun enc(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
    private fun dec(value: String) = URLDecoder.decode(value, StandardCharsets.UTF_8.name())
}
