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
import kotlin.math.abs

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
    private lateinit var persistentCache: TranslationCache
    private lateinit var ocrFallback: OcrFallback

    private val recentContext = ArrayDeque<String>()
    private val translationCache = object : LinkedHashMap<String, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > 300
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
            if (pkg != WEVERSE_PACKAGE) {
                overlay.hideAll()
            } else {
                overlay.showQuickToggle(AppPrefs.translationEnabled(this@WeverseAccessibilityService))
            }
            mainHandler.postDelayed(this, 700)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        persistentCache = TranslationCache(this)
        translationCache.putAll(persistentCache.load())
        ocrFallback = OcrFallback(this)
        overlay = OverlayController(this) { enabled ->
            AppPrefs.setTranslationEnabled(this, enabled)
            if (enabled) {
                mainHandler.removeCallbacks(scanRunnable)
                mainHandler.post(scanRunnable)
            } else {
                overlay.renderTranslations(emptyList(), false)
                overlay.hideStatus()
            }
        }
        mainHandler.post(visibilityWatchdog)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.packageName?.toString() != WEVERSE_PACKAGE) return
        mainHandler.removeCallbacks(scanRunnable)
        val delay = if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) 70L else 140L
        mainHandler.postDelayed(scanRunnable, delay)
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        executor.shutdownNow()
        if (::ocrFallback.isInitialized) ocrFallback.close()
        if (::overlay.isInitialized) overlay.destroy()
        super.onDestroy()
    }

    private fun scanAndTranslate() {
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != WEVERSE_PACKAGE) {
            overlay.hideAll()
            return
        }

        val enabled = AppPrefs.translationEnabled(this)
        overlay.showQuickToggle(enabled)
        if (!enabled) {
            overlay.renderTranslations(emptyList(), false)
            overlay.hideStatus()
            return
        }

        val collected = mutableListOf<ScreenMessage>()
        collectTexts(root, collected)
        val visible = filterMessages(collected)

        if (visible.isNotEmpty()) {
            handleVisible(visible)
        } else {
            overlay.renderTranslations(emptyList(), true)
        }

        // Accessibility nodes are usually better than OCR. OCR is only used when the screen
        // exposes almost no Korean text, which keeps screenshot work rare and avoids duplicates.
        if (AppPrefs.ocrEnabled(this) && visible.size <= 1) {
            overlay.showStatus("OCR 识别中…")
            ocrFallback.requestScan(
                onResult = { ocrMessages ->
                    if (rootInActiveWindow?.packageName?.toString() != WEVERSE_PACKAGE) return@requestScan
                    val combined = (visible + ocrMessages.map { ScreenMessage(it.text, Rect(it.bounds)) })
                    val ocrVisible = filterMessages(combined)
                    if (ocrVisible.isNotEmpty()) {
                        handleVisible(ocrVisible)
                    } else if (!requestInFlight) {
                        overlay.hideStatus()
                    }
                },
                onFailure = {
                    if (!requestInFlight) overlay.hideStatus()
                }
            )
        }
    }

    private fun handleVisible(visible: List<ScreenMessage>) {
        latestVisible = visible
        renderCached(visible)

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
        val contextForRequest = (recentContext.toList() + beforeNew).takeLast(12)

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
                        persistentCache.put(source, translated)
                        rememberContext(source)
                    }
                    renderCached(latestVisible)

                    if (pendingTexts.isEmpty()) {
                        overlay.hideStatus()
                    } else {
                        startNextBatchIfIdle()
                    }

                    mainHandler.removeCallbacks(scanRunnable)
                    mainHandler.postDelayed(scanRunnable, 100)
                }.onFailure { error ->
                    batchTexts.forEach { pendingTexts.add(it) }
                    overlay.showStatus("翻译失败：${error.message ?: error.javaClass.simpleName}")
                }
            }
        }
    }

    private fun renderCached(visible: List<ScreenMessage>) {
        val occurrences = mutableMapOf<String, Int>()
        val items = visible.mapNotNull { message ->
            val translated = translationCache[message.text] ?: return@mapNotNull null
            val occurrence = (occurrences[message.text] ?: 0) + 1
            occurrences[message.text] = occurrence
            OverlayController.BubbleTranslation(
                key = "${message.text}#$occurrence",
                sourceText = message.text,
                sourceBounds = Rect(message.bounds),
                translatedText = translated
            )
        }
        overlay.renderTranslations(items, AppPrefs.translationEnabled(this))
    }

    private fun collectTexts(node: AccessibilityNodeInfo, out: MutableList<ScreenMessage>) {
        val text = node.text?.toString()?.let(::normalize).orEmpty()
        if (text.isNotBlank() && HANGUL.containsMatchIn(text)) {
            val textRect = Rect()
            node.getBoundsInScreen(textRect)
            if (!textRect.isEmpty) {
                out += ScreenMessage(text, resolveBubbleBounds(node, textRect))
            }
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

    private fun resolveBubbleBounds(node: AccessibilityNodeInfo, textRect: Rect): Rect {
        val density = resources.displayMetrics.density
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels
        val maxLeftDelta = (28 * density).toInt()
        val maxRightDelta = (48 * density).toInt()
        var best = Rect(textRect)
        var parent: AccessibilityNodeInfo? = node.parent

        repeat(3) {
            val current = parent ?: return@repeat
            val rect = Rect()
            current.getBoundsInScreen(rect)
            val next = current.parent

            val looksLikeBubble = !rect.isEmpty &&
                rect.contains(textRect) &&
                rect.width() <= (screenWidth * 0.76f).toInt() &&
                rect.height() <= (screenHeight * 0.30f).toInt() &&
                abs(rect.left - textRect.left) <= maxLeftDelta &&
                rect.right - textRect.right <= maxRightDelta

            if (looksLikeBubble && rect.width() >= best.width() && rect.height() >= best.height()) {
                best = Rect(rect)
            }
            current.recycle()
            parent = next
        }
        parent?.recycle()
        return best
    }

    private fun filterMessages(raw: List<ScreenMessage>): List<ScreenMessage> {
        if (raw.isEmpty()) return emptyList()

        val screenHeight = resources.displayMetrics.heightPixels
        val screenWidth = resources.displayMetrics.widthPixels
        val headerCutoff = (screenHeight * 0.13f).toInt()

        val deduped = raw
            .filter {
                it.text.isNotBlank() &&
                    HANGUL.containsMatchIn(it.text) &&
                    it.bounds.left < (screenWidth * 0.82f).toInt()
            }
            .distinctBy {
                val r = it.bounds
                "${it.text}|${r.left / 6}|${r.top / 6}|${r.right / 6}|${r.bottom / 6}"
            }
            .sortedWith(compareBy<ScreenMessage> { it.bounds.top }.thenBy { it.bounds.left })

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
        recentContext.addLast(text)
        while (recentContext.size > 20) recentContext.removeFirst()
    }
}
