package com.cc.weversetranslator

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import java.util.concurrent.Executor

class OcrFallback(private val service: AccessibilityService) {
    data class OcrMessage(val text: String, val bounds: Rect)

    private val hangul = Regex("[\\u1100-\\u11FF\\u3130-\\u318F\\uAC00-\\uD7A3]")
    private val recognizer = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
    private val mainHandler = Handler(Looper.getMainLooper())
    private val mainExecutor = Executor { command -> mainHandler.post(command) }

    private var inFlight = false
    private var lastAttemptAt = 0L

    fun requestScan(onResult: (List<OcrMessage>) -> Unit, onFailure: (String) -> Unit = {}) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            onFailure("OCR 截图兜底需要 Android 11 或更高版本")
            return
        }
        val now = System.currentTimeMillis()
        if (inFlight || now - lastAttemptAt < 1800L) return
        inFlight = true
        lastAttemptAt = now

        service.takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                    val hardwareBuffer = screenshot.hardwareBuffer
                    val bitmap = runCatching {
                        Bitmap.wrapHardwareBuffer(hardwareBuffer, screenshot.colorSpace)
                            ?.copy(Bitmap.Config.ARGB_8888, false)
                    }.getOrNull()
                    hardwareBuffer.close()

                    if (bitmap == null) {
                        inFlight = false
                        onFailure("OCR 截图转换失败")
                        return
                    }

                    recognizer.process(InputImage.fromBitmap(bitmap, 0))
                        .addOnSuccessListener { result ->
                            val messages = result.textBlocks.mapNotNull { block ->
                                val text = block.text.replace(Regex("\\s+"), " ").trim()
                                val bounds = block.boundingBox
                                if (text.isBlank() || bounds == null || !hangul.containsMatchIn(text)) {
                                    null
                                } else {
                                    OcrMessage(text, Rect(bounds))
                                }
                            }
                            onResult(messages)
                        }
                        .addOnFailureListener { error ->
                            onFailure(error.message ?: "OCR 识别失败")
                        }
                        .addOnCompleteListener {
                            bitmap.recycle()
                            inFlight = false
                        }
                }

                override fun onFailure(errorCode: Int) {
                    inFlight = false
                    onFailure("OCR 截图失败：$errorCode")
                }
            }
        )
    }

    fun close() {
        recognizer.close()
    }
}
