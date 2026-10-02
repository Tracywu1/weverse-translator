package com.cc.weversetranslator

import android.content.Context
import java.util.UUID

object AppPrefs {
    private const val PREFS = "translator_settings_v2"
    private const val KEY_HOST_ID = "host_id"
    private const val KEY_CLIENT_ID = "client_id"
    private const val KEY_SUBJECT = "subject"
    private const val KEY_EMAIL = "email"
    private const val KEY_ID_TOKEN = "id_token"
    private const val KEY_ACCESS_TOKEN = "access_token"
    private const val KEY_REFRESH_TOKEN = "refresh_token"
    private const val KEY_SCOPE = "scope"
    private const val KEY_EXPIRES_IN = "expires_in"
    private const val KEY_SAVED_AT = "saved_at"
    private const val KEY_MODEL = "model"
    private const val KEY_MODEL_NAME = "model_name"
    private const val KEY_TRANSLATION_ENABLED = "translation_enabled"
    private const val KEY_OCR_ENABLED = "ocr_enabled"

    fun hostId(context: Context): String {
        val prefs = prefs(context)
        val existing = prefs.getString(KEY_HOST_ID, null)
        if (!existing.isNullOrBlank()) return existing
        val created = "urn:uuid:${UUID.randomUUID()}"
        prefs.edit().putString(KEY_HOST_ID, created).apply()
        return created
    }

    fun clientId(context: Context) = prefs(context).getString(KEY_CLIENT_ID, "").orEmpty()
    fun subject(context: Context) = prefs(context).getString(KEY_SUBJECT, "").orEmpty()
    fun email(context: Context) = prefs(context).getString(KEY_EMAIL, "").orEmpty()
    fun idToken(context: Context) = prefs(context).getString(KEY_ID_TOKEN, "").orEmpty()
    fun accessToken(context: Context) = prefs(context).getString(KEY_ACCESS_TOKEN, "").orEmpty()
    fun refreshToken(context: Context) = prefs(context).getString(KEY_REFRESH_TOKEN, "").orEmpty()
    fun scopes(context: Context) = prefs(context).getString(KEY_SCOPE, "").orEmpty()
    fun expiresIn(context: Context) = prefs(context).getLong(KEY_EXPIRES_IN, 0L)
    fun savedAt(context: Context) = prefs(context).getLong(KEY_SAVED_AT, 0L)
    fun model(context: Context) = prefs(context).getString(KEY_MODEL, "").orEmpty()
    fun modelName(context: Context) = prefs(context).getString(KEY_MODEL_NAME, "").orEmpty()
    fun translationEnabled(context: Context) = prefs(context).getBoolean(KEY_TRANSLATION_ENABLED, true)
    fun ocrEnabled(context: Context) = prefs(context).getBoolean(KEY_OCR_ENABLED, true)

    fun setTranslationEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_TRANSLATION_ENABLED, enabled).apply()
    }

    fun setOcrEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_OCR_ENABLED, enabled).apply()
    }

    fun hasPlanAccess(context: Context): Boolean =
        clientId(context).isNotBlank() &&
            refreshToken(context).isNotBlank() &&
            scopes(context).split(' ').contains("chatgpt.tokens.use.direct")

    fun saveTokens(
        context: Context,
        clientId: String,
        subject: String,
        email: String,
        idToken: String,
        accessToken: String,
        refreshToken: String,
        scope: String,
        expiresIn: Long
    ) {
        prefs(context).edit()
            .putString(KEY_CLIENT_ID, clientId)
            .putString(KEY_SUBJECT, subject)
            .putString(KEY_EMAIL, email)
            .putString(KEY_ID_TOKEN, idToken)
            .putString(KEY_ACCESS_TOKEN, accessToken)
            .putString(KEY_REFRESH_TOKEN, refreshToken)
            .putString(KEY_SCOPE, scope)
            .putLong(KEY_EXPIRES_IN, expiresIn)
            .putLong(KEY_SAVED_AT, System.currentTimeMillis())
            .apply()
    }

    fun updateRefreshedTokens(
        context: Context,
        accessToken: String,
        refreshToken: String,
        scope: String,
        expiresIn: Long,
        idToken: String? = null
    ) {
        prefs(context).edit()
            .putString(KEY_ACCESS_TOKEN, accessToken)
            .putString(KEY_REFRESH_TOKEN, refreshToken)
            .putString(KEY_SCOPE, scope)
            .putLong(KEY_EXPIRES_IN, expiresIn)
            .putLong(KEY_SAVED_AT, System.currentTimeMillis())
            .apply {
                if (!idToken.isNullOrBlank()) putString(KEY_ID_TOKEN, idToken)
            }
            .apply()
    }

    fun saveModel(context: Context, slug: String, displayName: String) {
        prefs(context).edit()
            .putString(KEY_MODEL, slug)
            .putString(KEY_MODEL_NAME, displayName)
            .apply()
    }

    fun clearModel(context: Context) {
        prefs(context).edit().remove(KEY_MODEL).remove(KEY_MODEL_NAME).apply()
    }

    fun clearCredentials(context: Context) {
        prefs(context).edit()
            .remove(KEY_SUBJECT)
            .remove(KEY_EMAIL)
            .remove(KEY_ID_TOKEN)
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_SCOPE)
            .remove(KEY_EXPIRES_IN)
            .remove(KEY_SAVED_AT)
            .remove(KEY_MODEL)
            .remove(KEY_MODEL_NAME)
            .apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
