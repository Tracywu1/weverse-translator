package com.cc.weversetranslator

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.concurrent.Executors

class WeverseAccessibilityService : AccessibilityService() {
    companion object {
        private const val WEVERSE_PACKAGE = "co.benx.weverse"
        private val HANGUL = Regex("[\\u1100-\\u11FF\\u3130-\\u318F\\uAC00-\\uD7A3]")
    }

    private data class ScreenMessage(
        val text: String,
        val bounds: Rect
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var overlay: OverlayController

    private val recentContext = ArrayDeque<String>()
    private val translationCache = object : LinkedHashMap<String, String>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > 200
    }
    private val pendingTexts = LinkedHashSet<String>()
    private var inFlightTexts: List<String> = emptyList()
    private var latestVisible: List<ScreenMessage> = emptyList()
    private var requestSerial = 0L
    private var requestInFlight = false

    private val scanRunnable = Runnable { scanAndTranslate() }
    private val visibilityWatchdog = object : Runnable {
        override fun run() {
            val pkg = rootInActiveWindow?.packageName?.toString()
            if (pkg != WEVERSE_PACKAGE) overlay.hideAll()
            mainHandler.postDelayed(this, 900)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        overlay = OverlayController(this)
        mainHandler.post(visibilityWatchdog)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.packageName?.toString() != WEVERSE_PACKAGE) return
        mainHandler.removeCallbacks(scanRunnable)
        mainHandler.postDelayed(scanRunnable, 180)
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        executor.shutdownNow()
        if (::overlay.isInitialized) overlay.destroy()
        super.onDestroy()
    }

    private fun scanAndTranslate() {
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != WEVERSE_PACKAGE) {
            overlay.hideAll()
            return
        }

        val collected = mutableListOf<ScreenMessage>()
        collectTexts(root, collected)
        val visible = filterMessages(collected)
        latestVisible = visible

        renderCached(visible)

        // Every untranslated message is queued immediately, even while another request is in flight.
        // This prevents messages from being lost when the user scrolls or Weverse updates rapidly.
        visible.forEach { message ->
            if (message.text !in translationCache && message.text !in inFlightTexts) {
                pendingTexts.add(message.text)
            }
        }

        if (pendingTexts.isEmpty()) {
            if (!requestInFlight) overlay.hideStatus()
            return
        }

        if (!AppPrefs.hasPlanAccess(this)) {
            overlay.showStatus("请先回翻译器使用 ChatGPT 登录")
            return
        }

        startNextBatchIfIdle()
    }

    private fun startNextBatchIfIdle() {
        if (requestInFlight || pendingTexts.isEmpty()) return

        val batchTexts = pendingTexts.take(8)
        batchTexts.forEach { pendingTexts.remove(it) }
        inFlightTexts = batchTexts

        val batchSet = batchTexts.toSet()
        val firstNewIndex = latestVisible.indexOfFirst { it.text in batchSet }
        val beforeNew = if (firstNewIndex > 0) {
            latestVisible.take(firstNewIndex).map { it.text }
        } else {
            emptyList()
        }
        val contextForRequest = (recentContext.toList() + beforeNew)
            .distinct()
            .takeLast(8)

        val serial = ++requestSerial
        requestInFlight = true
        overlay.showStatus("翻译中…")

        executor.execute {
            val result = runCatching {
                TranslationClient(this).translateLines(
                    recentContext = contextForRequest,
                    newMessages = batchTexts
                )
            }

            mainHandler.post {
                requestInFlight = false
                inFlightTexts = emptyList()
                if (serial != requestSerial) return@post

                result.onSuccess { translations ->
                    batchTexts.zip(translations).forEach { (source, translated) ->
                        translationCache[source] = translated
                        rememberContext(source)
                    }
                    renderCached(latestVisible)

                    if (pendingTexts.isEmpty()) {
                        overlay.hideStatus()
                    } else {
                        startNextBatchIfIdle()
                    }

                    // Re-scan once after a successful batch. This catches nodes that appeared
                    // during the network request even when Weverse emitted no further event.
                    mainHandler.removeCallbacks(scanRunnable)
                    mainHandler.postDelayed(scanRunnable, 120)
                }.onFailure { error ->
                    // Only successful translations enter the cache. Failed items remain pending
                    // and can be retried on the next Weverse UI event.
                    batchTexts.forEach { pendingTexts.add(it) }
                    overlay.showStatus("翻译失败：${error.message ?: error.javaClass.simpleName}")
                }
            }
        }
    }

    private fun renderCached(visible: List<ScreenMessage>) {
        val items = visible.mapNotNull { message ->
            val translated = translationCache[message.text] ?: return@mapNotNull null
            OverlayController.BubbleTranslation(
                sourceBounds = Rect(message.bounds),
                text = translated
            )
        }
        overlay.renderTranslations(items)
    }

    private fun collectTexts(node: AccessibilityNodeInfo, out: MutableList<ScreenMessage>) {
        val text = node.text?.toString()?.let(::normalize).orEmpty()
        if (text.isNotBlank() && HANGUL.containsMatchIn(text)) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            if (!rect.isEmpty) out += ScreenMessage(text, rect)
        }

        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                try {
                    collectTexts(child, out)
                } finally {
                    child.recycle()
                }
            }
        }
    }

    private fun filterMessages(raw: List<ScreenMessage>): List<ScreenMessage> {
        if (raw.isEmpty()) return emptyList()

        val screenHeight = resources.displayMetrics.heightPixels
        val headerCutoff = (screenHeight * 0.13f).toInt()

        val deduped = raw
            .filter { it.text.isNotBlank() && HANGUL.containsMatchIn(it.text) }
            .distinctBy {
                val r = it.bounds
                "${it.text}|${r.left / 4}|${r.top / 4}|${r.right / 4}|${r.bottom / 4}"
            }
            .sortedWith(compareBy<ScreenMessage> { it.bounds.top }.thenBy { it.bounds.left })

        // The artist name appears once in the top title and is repeated beside message groups.
        // Filtering by the actual top title is much safer than filtering all short Korean text,
        // because real messages such as "얍", "이거까지" and "씻고왔다" are also short.
        val headerNames = deduped
            .filter { it.bounds.top < headerCutoff && it.text.length <= 24 }
            .map { it.text }
            .toSet()

        return deduped.filter { item ->
            val isHeader = item.bounds.top < headerCutoff
            val isSenderName = !isHeader && item.text in headerNames
            !isHeader && !isSenderName
        }
    }

    private fun normalize(value: String): String =
        value.replace(Regex("\\s+"), " ").trim()

    private fun rememberContext(text: String) {
        if (recentContext.peekLast() == text) return
        recentContext.addLast(text)
        while (recentContext.size > 16) recentContext.removeFirst()
    }
}
