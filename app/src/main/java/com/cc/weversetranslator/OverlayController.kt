package com.cc.weversetranslator

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView

class OverlayController(private val service: AccessibilityService) {
    data class BubbleTranslation(
        val key: String,
        val sourceBounds: Rect,
        val translatedText: String
    )

    private val windowManager = service.getSystemService(WindowManager::class.java)
    private val bubbleViews = linkedMapOf<String, TextView>()
    private var statusView: TextView? = null

    fun renderTranslations(items: List<BubbleTranslation>) {
        val visibleItems = items.takeLast(12)
        val desiredKeys = visibleItems.map { it.key }.toSet()

        bubbleViews.keys.filter { it !in desiredKeys }.toList().forEach { key ->
            bubbleViews.remove(key)?.let { view -> runCatching { windowManager.removeView(view) } }
        }

        visibleItems.forEach { item ->
            val view = bubbleViews[item.key] ?: createBubbleView().also {
                bubbleViews[item.key] = it
                runCatching { windowManager.addView(it, layoutParamsFor(item)) }
            }
            view.text = item.translatedText
            runCatching { windowManager.updateViewLayout(view, layoutParamsFor(item)) }
        }
    }

    fun showStatus(text: String) {
        val view = statusView ?: createStatusView().also { statusView = it }
        view.text = text
        view.visibility = View.VISIBLE
    }

    fun hideStatus() {
        statusView?.visibility = View.GONE
    }

    fun hideAll() {
        clearBubbleViews()
        hideStatus()
    }

    fun destroy() {
        clearBubbleViews()
        statusView?.let { runCatching { windowManager.removeView(it) } }
        statusView = null
    }

    private fun createBubbleView(): TextView {
        val density = service.resources.displayMetrics.density
        val horizontalPad = (9 * density).toInt()
        val verticalPad = (5 * density).toInt()
        val background = GradientDrawable().apply {
            setColor(Color.argb(252, 188, 239, 243))
            cornerRadius = 15 * density
        }

        return TextView(service).apply {
            setTextColor(Color.rgb(28, 30, 32))
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            includeFontPadding = false
            setLineSpacing(0f, 1.02f)
            setPadding(horizontalPad, verticalPad, horizontalPad, verticalPad)
            this.background = background
            elevation = 1.2f * density
            isSingleLine = false
            maxLines = Int.MAX_VALUE
            ellipsize = null
            setHorizontallyScrolling(false)
            setAutoSizeTextTypeUniformWithConfiguration(9, 16, 1, TypedValue.COMPLEX_UNIT_SP)
        }
    }

    private fun layoutParamsFor(item: BubbleTranslation): WindowManager.LayoutParams {
        val density = service.resources.displayMetrics.density
        val screenWidth = service.resources.displayMetrics.widthPixels
        val screenHeight = service.resources.displayMetrics.heightPixels
        val margin = (4 * density).toInt()
        val horizontalPad = (9 * density).toInt()
        val verticalPad = (5 * density).toInt()
        val minWidth = (72 * density).toInt()
        val maxWidth = (screenWidth * 0.74f).toInt()
        val minHeight = (34 * density).toInt()
        val maxHeight = (screenHeight * 0.32f).toInt()

        val width = (item.sourceBounds.width() + horizontalPad * 2)
            .coerceIn(minWidth, maxWidth)
        val height = (item.sourceBounds.height() + verticalPad * 2)
            .coerceIn(minHeight, maxHeight)

        val preferredX = item.sourceBounds.left - horizontalPad
        val preferredY = item.sourceBounds.top - verticalPad
        val x = preferredX.coerceIn(margin, (screenWidth - width - margin).coerceAtLeast(margin))
        val y = preferredY.coerceIn(margin, (screenHeight - height - margin).coerceAtLeast(margin))

        return WindowManager.LayoutParams().apply {
            this.width = width
            this.height = height
            type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            format = android.graphics.PixelFormat.TRANSLUCENT
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
        }
    }

    private fun createStatusView(): TextView {
        val density = service.resources.displayMetrics.density
        val background = GradientDrawable().apply {
            setColor(Color.argb(215, 32, 32, 36))
            cornerRadius = 14 * density
        }
        val view = TextView(service).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            setPadding(
                (10 * density).toInt(),
                (6 * density).toInt(),
                (10 * density).toInt(),
                (6 * density).toInt()
            )
            this.background = background
        }
        val params = WindowManager.LayoutParams().apply {
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            format = android.graphics.PixelFormat.TRANSLUCENT
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = (26 * density).toInt()
        }
        windowManager.addView(view, params)
        return view
    }

    private fun clearBubbleViews() {
        bubbleViews.values.forEach { view -> runCatching { windowManager.removeView(view) } }
        bubbleViews.clear()
    }
}
