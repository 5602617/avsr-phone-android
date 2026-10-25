package io.github.lejw0925.virtualscreen.phone

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.KeyguardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Base64
import android.view.Display
import io.github.lejw0925.virtualscreen.core.OperationEnvelope
import io.github.lejw0925.virtualscreen.core.ScreenAccessibilityService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.concurrent.Executor
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/** A single read-only main-display observation. No screenshots, results, or images are retained. */
class PhoneGlance(context: Context) {
    private val app = context.applicationContext
    private val power = app.getSystemService(PowerManager::class.java)
    private val keyguard = app.getSystemService(KeyguardManager::class.java)
    private val displays = app.getSystemService(DisplayManager::class.java)
    private val imageExecutor = Executor { task -> Dispatchers.Default.dispatch(EmptyCoroutineContext, task) }

    private data class Availability(
        val service: ScreenAccessibilityService?,
        val interactive: Boolean,
        val locked: Boolean,
        val permission: Boolean,
        val status: String,
        val reason: String?,
    )

    private fun availability(): Availability {
        val service = ScreenAccessibilityService.current
        val interactive = power?.isInteractive == true
        // Missing system facts must not be represented as an unlocked phone.
        val locked = keyguard == null || keyguard.isKeyguardLocked || keyguard.isDeviceLocked
        val permission = service?.serviceInfo?.capabilities?.let {
            it and AccessibilityServiceInfo.CAPABILITY_CAN_TAKE_SCREENSHOT != 0
        } == true
        val failure = when {
            Build.VERSION.SDK_INT < 33 -> "unavailable" to "ANDROID_VERSION_UNSUPPORTED"
            service == null -> "denied" to "ACCESSIBILITY_NOT_CONNECTED"
            !permission -> "denied" to "SCREENSHOT_PERMISSION_UNAVAILABLE"
            displays?.getDisplay(Display.DEFAULT_DISPLAY) == null -> "unavailable" to "MAIN_DISPLAY_UNAVAILABLE"
            !interactive -> "unavailable" to "SCREEN_NOT_INTERACTIVE"
            locked -> "denied" to "DEVICE_LOCKED"
            else -> "ok" to null
        }
        return Availability(service, interactive, locked, permission, failure.first, failure.second)
    }

    /** Capability facts only: true is not proof of a person using the phone or a readable window. */
    fun status(): OperationEnvelope {
        val state = availability()
        return OperationEnvelope.success(
            "status" to "ok", "connected" to true,
            "display_id" to Display.DEFAULT_DISPLAY,
            "can_capture" to (state.reason == null),
            "accessibility_connected" to (state.service != null),
            "screenshot_permission" to state.permission,
            "screen_interactive" to state.interactive, "device_locked" to state.locked,
            "user_active" to (state.interactive && !state.locked),
            "user_active_basis" to "screen_interactive_and_unlocked_not_human_activity",
            "reason" to state.reason,
            "capture_availability" to state.status,
            "secure_content_detection" to "system_error_or_blank_pixels_only",
        )
    }

    /** The caller must move image_base64 to an MCP image block and bypass receipt persistence. */
    suspend fun capture(): OperationEnvelope = withContext(Dispatchers.Main.immediate) {
        val before = availability()
        if (before.reason != null) return@withContext failure(before.status, before.reason)
        val service = before.service ?: return@withContext failure("disconnected", "ACCESSIBILITY_DISCONNECTED")
        val bounds = try { service.mainDisplayApplicationBounds() } catch (_: Exception) { emptyList() }
        withTimeoutOrNull(CALLBACK_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                fun complete(value: OperationEnvelope) {
                    if (continuation.isActive) continuation.resume(value)
                }
                try {
                    // This fixed display ID is deliberately not a caller-controlled argument.
                    service.takeScreenshot(Display.DEFAULT_DISPLAY, imageExecutor,
                        object : AccessibilityService.TakeScreenshotCallback {
                            override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                                // Android may still deliver a callback after cancellation/timeout.
                                // Own and close its buffer even when no caller remains.
                                val buffer = screenshot.hardwareBuffer
                                try {
                                    if (!continuation.isActive) return
                                    val now = availability()
                                    if (ScreenAccessibilityService.current !== service) {
                                        complete(failure("disconnected", "ACCESSIBILITY_DISCONNECTED"))
                                    } else if (now.reason != null) {
                                        complete(failure(now.status, now.reason))
                                    } else {
                                        val observedAt = Instant.ofEpochMilli(System.currentTimeMillis() -
                                            (SystemClock.uptimeMillis() - screenshot.timestamp).coerceAtLeast(0L)).toString()
                                        val hardwareBitmap = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                                        if (hardwareBitmap == null) {
                                            complete(failure("unavailable", "SCREENSHOT_BUFFER_UNAVAILABLE"))
                                            return
                                        }
                                        try {
                                            val bitmap = hardwareBitmap.copy(Bitmap.Config.ARGB_8888, false)
                                            if (bitmap == null) {
                                                complete(failure("unavailable", "SCREENSHOT_BUFFER_UNAVAILABLE"))
                                                return
                                            }
                                            try {
                                                val result = encode(bitmap, bounds, observedAt)
                                                val after = availability()
                                                complete(when {
                                                    ScreenAccessibilityService.current !== service ->
                                                        failure("disconnected", "ACCESSIBILITY_DISCONNECTED")
                                                    after.reason != null -> failure(after.status, after.reason)
                                                    else -> result
                                                })
                                            } finally { bitmap.recycle() }
                                        } finally { hardwareBitmap.recycle() }
                                    }
                                } catch (cancelled: CancellationException) {
                                    continuation.cancel(cancelled)
                                } catch (_: SecurityException) {
                                    complete(failure("denied", "SCREENSHOT_ACCESS_DENIED"))
                                } catch (_: Exception) {
                                    complete(failure("unavailable", "SCREENSHOT_PROCESSING_FAILED"))
                                } finally { buffer.close() }
                            }

                            override fun onFailure(errorCode: Int) {
                                val result = when (errorCode) {
                                    AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS ->
                                        failure("denied", "SCREENSHOT_ACCESS_DENIED")
                                    AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW ->
                                        failure("protected", "SECURE_WINDOW")
                                    AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT ->
                                        failure("unavailable", "SYSTEM_SCREENSHOT_INTERVAL")
                                    AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY ->
                                        failure("unavailable", "MAIN_DISPLAY_UNAVAILABLE")
                                    else -> failure("unavailable", "SYSTEM_SCREENSHOT_FAILED")
                                }
                                complete(if (ScreenAccessibilityService.current !== service)
                                    failure("disconnected", "ACCESSIBILITY_DISCONNECTED") else result)
                            }
                        })
                } catch (_: SecurityException) {
                    complete(failure("denied", "SCREENSHOT_ACCESS_DENIED"))
                } catch (_: Exception) {
                    complete(failure("unavailable", "SCREENSHOT_REQUEST_FAILED"))
                }
            }
        } ?: failure("unavailable", "SCREENSHOT_CALLBACK_TIMEOUT")
    }

    private fun encode(bitmap: Bitmap, applicationBounds: List<Rect>, observedAt: String): OperationEnvelope {
        val displayBounds = Rect(0, 0, bitmap.width, bitmap.height)
        val contentBounds = applicationBounds.mapNotNull { candidate ->
            Rect(candidate).takeIf { it.intersect(displayBounds) }
        }
        // Display screenshots can succeed with FLAG_SECURE content replaced by black while
        // status/navigation bars remain visible. Check foreground bounds as well as the center.
        // Black pixels are not proof of FLAG_SECURE: report unavailable, never claim protected.
        if ((contentBounds + displayBounds).any { isBlankContent(bitmap, it) }) {
            return failure("unavailable", "BLANK_OR_REDACTED_SCREEN")
        }
        val ratio = (MAX_IMAGE_EDGE.toDouble() / maxOf(bitmap.width, bitmap.height)).coerceAtMost(1.0)
        val scaled = if (ratio < 1.0) Bitmap.createScaledBitmap(bitmap,
            (bitmap.width * ratio).roundToInt().coerceAtLeast(1),
            (bitmap.height * ratio).roundToInt().coerceAtLeast(1), true) else bitmap
        try {
            val bytes = ByteArrayOutputStream().use { stream ->
                if (!scaled.compress(Bitmap.CompressFormat.JPEG, 90, stream)) {
                    return failure("unavailable", "SCREENSHOT_ENCODING_FAILED")
                }
                stream.toByteArray()
            }
            try {
                if (bytes.size > MAX_IMAGE_BYTES) return failure("unavailable", "SCREENSHOT_TOO_LARGE")
                return OperationEnvelope.success(
                    "status" to "ok", "display_id" to Display.DEFAULT_DISPLAY,
                    "image_base64" to Base64.encodeToString(bytes, Base64.NO_WRAP),
                    "mime_type" to "image/jpeg", "observed_at" to observedAt,
                    "width" to scaled.width, "height" to scaled.height,
                    "secure_content_detection" to "system_error_or_blank_pixels_only",
                    "visibility" to "captured_pixels_only_redaction_may_be_present",
                )
            } finally { bytes.fill(0) }
        } finally { if (scaled !== bitmap) scaled.recycle() }
    }

    private fun isBlankContent(bitmap: Bitmap, bounds: Rect): Boolean {
        val interior = Rect(bounds).apply { inset(width() / 20, height() / 20) }
        if (interior.isEmpty) return false
        val row = IntArray(interior.width())
        try {
            for (y in interior.top until interior.bottom) {
                bitmap.getPixels(row, 0, row.size, interior.left, y, row.size, 1)
                for (pixel in row) {
                    if (Color.alpha(pixel) > 8 && (Color.red(pixel) > 8 ||
                            Color.green(pixel) > 8 || Color.blue(pixel) > 8)) return false
                }
            }
            return true
        } finally { row.fill(0) }
    }

    private fun failure(status: String, reason: String) = OperationEnvelope.failure(reason,
        values = arrayOf("status" to status, "reason" to reason, "display_id" to Display.DEFAULT_DISPLAY))

    private companion object {
        const val MAX_IMAGE_EDGE = 1600
        const val MAX_IMAGE_BYTES = 2 * 1024 * 1024
        const val CALLBACK_TIMEOUT_MS = 8000L
    }
}
