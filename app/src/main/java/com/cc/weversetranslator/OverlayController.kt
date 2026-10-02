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
        val sourceBounds: Rect,
        val text: String
    )

    private val windowManager = service.getSystemService(WindowManager::class.java)
    private val bubbleViews = mutableListOf<View>()
    private var statusView: TextView? = null

    fun renderTranslations(items: List<BubbleTranslation>) {
        clearBubbleViews()
        items.takeLast(10).forEach { item -> addInlineTranslation(item) }
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

    /**
     * Paint the Chinese translation directly over the Korean text area instead of adding a
     * separate white card. This keeps every translation attached to its original DM bubble and
     * prevents long cards from covering neighbouring messages.
     */
    private fun addInlineTranslation(item: BubbleTranslation) {
        val density = service.resources.displayMetrics.density
        val screenWidth = service.resources.displayMetrics.widthPixels
        val screenHeight = service.resources.displayMetrics.heightPixels

        val horizontalPad = (10 * density).toInt()
        val verticalPad = (6 * density).toInt()
        val margin = (4 * density).toInt()
        val minWidth = (96 * density).toInt()
        val maxWidth = (screenWidth * 0.72f).toInt()

        val baseWidth = item.sourceBounds.width() + horizontalPad * 2
        val width = when {
            item.text.length >= 34 -> maxOf(baseWidth, (screenWidth * 0.68f).toInt())
            item.text.length >= 20 -> maxOf(baseWidth, (screenWidth * 0.58f).toInt())
            item.text.length >= 10 -> maxOf(baseWidth, (screenWidth * 0.42f).toInt())
            else -> baseWidth
        }.coerceIn(minWidth, maxWidth)

        val minHeight = (38 * density).toInt()
        val desiredHeight = (item.sourceBounds.height() + verticalPad * 2).coerceAtLeast(minHeight)
        val maxHeight = (screenHeight * 0.30f).toInt()
        val height = desiredHeight.coerceAtMost(maxHeight)

        // Approximate Weverse's artist-message cyan so the translation feels like part of the
        // existing bubble instead of a floating subtitle card.
        val background = GradientDrawable().apply {
            setColor(Color.argb(252, 188, 239, 243))
            cornerRadius = 15 * density
        }

        val label = TextView(service).apply {
            text = item.text
            setTextColor(Color.rgb(28, 30, 32))
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            includeFontPadding = false
            setLineSpacing(0f, 1.04f)
            setPadding(horizontalPad, verticalPad, horizontalPad, verticalPad)
            this.background = background
            elevation = 1.5f * density
            isSingleLine = false
            maxLines = Int.MAX_VALUE
            ellipsize = null
            setHorizontallyScrolling(false)
            // Chinese is usually shorter than Korean, but this lets unusually long translations
            // shrink until they fit inside the source bubble area instead of overflowing.
            setAutoSizeTextTypeUniformWithConfiguration(
                10,
                15,
                1,
                TypedValue.COMPLEX_UNIT_SP
            )
        }

        val preferredX = item.sourceBounds.left - horizontalPad
        val preferredY = item.sourceBounds.top - verticalPad
        val x = preferredX.coerceIn(margin, (screenWidth - width - margin).coerceAtLeast(margin))
        val y = preferredY.coerceIn(margin, (screenHeight - height - margin).coerceAtLeast(margin))

        val params = WindowManager.LayoutParams().apply {
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

        runCatching { windowManager.addView(label, params) }
            .onSuccess { bubbleViews += label }
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
        bubbleViews.forEach { view -> runCatching { windowManager.removeView(view) } }
        bubbleViews.clear()
    }
}
