package com.sedu.assistant.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator

class SeduOverlay(private val context: Context) {

    companion object {
        private const val TAG = "SeduOverlay"
    }

    private var windowManager: WindowManager? = null
    private var overlayView: GlowView? = null
    private var isShowing = false
    private var pulseAnimator: ValueAnimator? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    fun show() {
        mainHandler.post { showInternal() }
    }

    private fun showInternal() {
        if (isShowing) return
        try {
            windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            overlayView = GlowView(context)

            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY
            }

            var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

            // Show on lock screen
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                flags = flags or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                flags,
                PixelFormat.TRANSLUCENT
            )
            params.gravity = Gravity.TOP or Gravity.START

            windowManager?.addView(overlayView, params)
            isShowing = true
            startPulse()
            Log.d(TAG, "Overlay shown with lock screen support")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show overlay", e)
        }
    }

    fun hide() {
        mainHandler.post { hideInternal() }
    }

    private fun hideInternal() {
        if (!isShowing) return
        try {
            stopPulse()
            windowManager?.removeView(overlayView)
            overlayView = null
            isShowing = false
            Log.d(TAG, "Overlay hidden")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to hide overlay", e)
        }
    }

    fun setColor(color: Int) {
        overlayView?.glowColor = color
        overlayView?.postInvalidate()
    }

    fun setConversationMode() {
        overlayView?.glowColor = Color.parseColor("#00E676")
        overlayView?.postInvalidate()
    }

    fun setProcessingMode() {
        overlayView?.glowColor = Color.parseColor("#76FF03")
        overlayView?.postInvalidate()
    }

    fun setActiveMode() {
        overlayView?.glowColor = Color.parseColor("#00C853")
        overlayView?.postInvalidate()
    }

    private fun startPulse() {
        pulseAnimator?.cancel()
        pulseAnimator = ValueAnimator.ofFloat(0.5f, 1.0f).apply {
            duration = 800
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { animator ->
                overlayView?.glowAlpha = animator.animatedValue as Float
                overlayView?.postInvalidate()
            }
            start()
        }
    }

    private fun stopPulse() {
        pulseAnimator?.cancel()
        pulseAnimator = null
    }

    class GlowView(context: Context) : View(context) {
        var glowColor: Int = Color.parseColor("#00E676")
        var glowAlpha: Float = 0.8f
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            val alpha = (glowAlpha * 220).toInt().coerceIn(0, 255)
            val glowWidth = 80f  // Much thicker glow — visible like iOS Siri
            val innerGlow = 40f

            val r = Color.red(glowColor)
            val g = Color.green(glowColor)
            val b = Color.blue(glowColor)

            // === TOP edge — bright outer glow ===
            paint.shader = LinearGradient(0f, 0f, 0f, glowWidth,
                Color.argb(alpha, r, g, b), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, w, glowWidth, paint)

            // === BOTTOM edge ===
            paint.shader = LinearGradient(0f, h, 0f, h - glowWidth,
                Color.argb(alpha, r, g, b), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, h - glowWidth, w, h, paint)

            // === LEFT edge ===
            paint.shader = LinearGradient(0f, 0f, glowWidth, 0f,
                Color.argb(alpha, r, g, b), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, glowWidth, h, paint)

            // === RIGHT edge ===
            paint.shader = LinearGradient(w, 0f, w - glowWidth, 0f,
                Color.argb(alpha, r, g, b), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            canvas.drawRect(w - glowWidth, 0f, w, h, paint)

            // === Inner soft glow layer for depth ===
            val innerAlpha = (alpha * 0.4f).toInt().coerceIn(0, 255)
            paint.shader = LinearGradient(0f, 0f, 0f, innerGlow,
                Color.argb(innerAlpha, r, g, b), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, glowWidth, w, glowWidth + innerGlow, paint)

            paint.shader = LinearGradient(0f, h, 0f, h - innerGlow,
                Color.argb(innerAlpha, r, g, b), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, h - glowWidth - innerGlow, w, h - glowWidth, paint)

            // === Corner accents — radial glow at each corner ===
            val cornerR = glowWidth * 1.5f
            val cornerAlpha = (alpha * 0.6f).toInt().coerceIn(0, 255)
            val corners = arrayOf(
                floatArrayOf(0f, 0f),       // top-left
                floatArrayOf(w, 0f),        // top-right
                floatArrayOf(0f, h),        // bottom-left
                floatArrayOf(w, h)          // bottom-right
            )
            for (corner in corners) {
                paint.shader = RadialGradient(corner[0], corner[1], cornerR,
                    Color.argb(cornerAlpha, r, g, b), Color.TRANSPARENT, Shader.TileMode.CLAMP)
                canvas.drawCircle(corner[0], corner[1], cornerR, paint)
            }
        }
    }
}
