package com.accentbridge.app.incoming

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.TextView
import com.accentbridge.app.R

class BubbleOverlayManager(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private val touchSlopPx = ViewConfiguration.get(context).scaledTouchSlop

    private val autoCollapseRunnable = Runnable { collapseToDot() }
    private val longPressRunnable = Runnable { hide() }

    private var rootView: View? = null
    private var cardView: View? = null
    private var dotView: TextView? = null
    private var textView: TextView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var lastMessage: String = ""

    fun show(message: String) {
        lastMessage = message
        handler.post { showInternal() }
    }

    fun hide() {
        handler.post {
            handler.removeCallbacks(autoCollapseRunnable)
            handler.removeCallbacks(longPressRunnable)
            val view = rootView ?: return@post
            runCatching { windowManager.removeView(view) }
            rootView = null
            cardView = null
            dotView = null
            textView = null
            layoutParams = null
        }
    }

    @SuppressLint("InflateParams", "ClickableViewAccessibility")
    private fun showInternal() {
        if (rootView == null) attach()
        val root = rootView ?: return
        textView?.text = lastMessage
        expandCard()
        layoutParams?.let { windowManager.updateViewLayout(root, it) }
        scheduleAutoCollapse()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attach() {
        val view = View.inflate(context, R.layout.bubble_overlay, null)
        cardView = view.findViewById(R.id.bubble_card)
        dotView = view.findViewById(R.id.bubble_dot)
        textView = view.findViewById(R.id.bubble_text)
        view.findViewById<TextView>(R.id.bubble_close).setOnClickListener { hide() }
        view.setOnTouchListener(GestureHandler())

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(16)
            y = dp(120)
        }
        layoutParams = params
        windowManager.addView(view, params)
        rootView = view
    }

    private fun expandCard() {
        cardView?.visibility = View.VISIBLE
        dotView?.visibility = View.GONE
        textView?.text = lastMessage
    }

    private fun collapseToDot() {
        cardView?.visibility = View.GONE
        dotView?.visibility = View.VISIBLE
    }

    private fun toggleExpanded() {
        val expanded = cardView?.visibility == View.VISIBLE
        if (expanded) collapseToDot() else expandCard()
        scheduleAutoCollapse()
    }

    private fun scheduleAutoCollapse() {
        handler.removeCallbacks(autoCollapseRunnable)
        handler.postDelayed(autoCollapseRunnable, AUTO_COLLAPSE_MS)
    }

    private inner class GestureHandler : View.OnTouchListener {

        private var downRawX = 0f
        private var downRawY = 0f
        private var downParamX = 0
        private var downParamY = 0
        private var moved = false
        private var longPressed = false

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    layoutParams?.let {
                        downParamX = it.x
                        downParamY = it.y
                    }
                    moved = false
                    longPressed = false
                    handler.removeCallbacks(autoCollapseRunnable)
                    handler.postDelayed(longPressRunnable, LONG_PRESS_MS)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!moved && Math.hypot(dx.toDouble(), dy.toDouble()) < touchSlopPx) return true
                    moved = true
                    val params = layoutParams ?: return true
                    params.x = (downParamX + dx).toInt().coerceAtLeast(0)
                    params.y = (downParamY + dy).toInt().coerceAtLeast(0)
                    rootView?.let { windowManager.updateViewLayout(it, params) }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longPressRunnable)
                    when {
                        longPressed -> Unit
                        !moved -> toggleExpanded()
                        else -> scheduleAutoCollapse()
                    }
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPressRunnable)
                    scheduleAutoCollapse()
                    return true
                }
            }
            return false
        }
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), context.resources.displayMetrics)
            .toInt()

    companion object {
        private const val AUTO_COLLAPSE_MS = 6000L
        private const val LONG_PRESS_MS = 550L
    }
}
