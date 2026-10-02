package com.cc.weversetranslator

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class TranslationCache(context: Context) {
    companion object {
        // v2 intentionally invalidates translations produced by the older prompt.
        private const val PREFS = "translation_cache_v2"
        private const val KEY_ENTRIES = "entries"
        private const val MAX_ENTRIES = 240
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun load(): LinkedHashMap<String, String> {
        val map = LinkedHashMap<String, String>()
        val raw = prefs.getString(KEY_ENTRIES, null).orEmpty()
        if (raw.isBlank()) return map

        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return map
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val source = item.optString("source").trim()
            val translation = item.optString("translation").trim()
            if (source.isNotBlank() && translation.isNotBlank()) {
                map[source] = translation
            }
        }
        return map
    }

    @Synchronized
    fun put(source: String, translation: String) {
        val normalizedSource = source.trim()
        val normalizedTranslation = translation.trim()

        // Short Korean chat lines are highly context-sensitive. Keep them in the in-memory
        // session cache only; do not reuse them across app/service restarts.
        if (normalizedSource.length < 8 || normalizedTranslation.isBlank()) return

        val current = load()
        current.remove(normalizedSource)
        current[normalizedSource] = normalizedTranslation

        while (current.size > MAX_ENTRIES) {
            current.remove(current.entries.first().key)
        }

        val array = JSONArray()
        current.forEach { (src, dst) ->
            array.put(JSONObject().put("source", src).put("translation", dst))
        }
        prefs.edit().putString(KEY_ENTRIES, array.toString()).apply()
    }

    @Synchronized
    fun clear() {
        prefs.edit().clear().apply()
    }
}
