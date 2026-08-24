package com.accentbridge.app.incoming

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.TextView
import com.accentbridge.app.R

class BubbleOverlayManager(
    private val context: Context,
    private val canListen: () -> Boolean,
    private val onTranslateRequested: (String) -> Unit,
    private val onSeenRequested: () -> Unit
) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private val touchSlopPx = ViewConfiguration.get(context).scaledTouchSlop

    private val autoCollapseRunnable = Runnable { collapseToDot() }
    private val longPressRunnable = Runnable { hide() }
    private val stopListeningUiRunnable = Runnable { refreshListenButton(false) }

    private var rootView: View? = null
    private var cardView: View? = null
    private var dotView: TextView? = null
    private var textView: TextView? = null
    private var listenButton: TextView? = null
    private var translateButton: TextView? = null
    private var seenButton: TextView? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private var originalMessage: String = ""
    private var currentText: String = ""
    private var recognizer: SpeechRecognizer? = null
    private var listening = false

    fun showOriginal(message: String) {
        handler.post {
            originalMessage = message
            showInternal(message)
            startListening()
        }
    }

    fun showTranslated(message: String) {
        handler.post {
            stopListening()
            showInternal(message)
        }
    }

    fun hide() {
        handler.post {
            destroyRecognizer()
            handler.removeCallbacks(autoCollapseRunnable)
            handler.removeCallbacks(longPressRunnable)
            handler.removeCallbacks(stopListeningUiRunnable)
            val view = rootView ?: return@post
            runCatching { windowManager.removeView(view) }
            rootView = null
            cardView = null
            dotView = null
            textView = null
            listenButton = null
            translateButton = null
            seenButton = null
            layoutParams = null
        }
    }

    @SuppressLint("InflateParams", "ClickableViewAccessibility")
    private fun showInternal(message: String) {
        if (rootView == null) attach()
        val root = rootView ?: return
        currentText = message
        textView?.text = message
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
        listenButton = view.findViewById<TextView>(R.id.bubble_listen).also { button ->
            button.setOnClickListener { if (listening) stopListening() else startListening() }
        }
        translateButton = view.findViewById<TextView>(R.id.bubble_translate).also { button ->
            button.setOnClickListener { onTranslateRequested(originalMessage) }
        }
        seenButton = view.findViewById<TextView>(R.id.bubble_seen).also { button ->
            button.setOnClickListener { onSeenRequested() }
        }
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
        textView?.text = currentText
    }

    private fun collapseToDot() {
        stopListening()
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

    private fun startListening() {
        if (listening || !canListen()) return
        if (!SpeechRecognizer.isRecognitionAvailable(context)) return
        handler.post {
            destroyRecognizer()
            recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(VoiceCommandListener())
                startListening(listenIntent())
            }
            listening = true
            refreshListenButton(true)
        }
    }

    private fun stopListening() {
        if (!listening && recognizer == null) return
        destroyRecognizer()
        listening = false
        refreshListenButton(false)
    }

    private fun destroyRecognizer() {
        recognizer?.destroy()
        recognizer = null
    }

    private fun refreshListenButton(active: Boolean) {
        listenButton?.setText(if (active) R.string.bubble_listening else R.string.bubble_voice)
        listenButton?.setTextColor(if (active) 0xFFFFD54F.toInt() else 0xB3FFFFFF.toInt())
    }

    private fun listenIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
    }

    private fun handleUtterance(utterance: String) {
        val heard = utterance.lowercase().trim()
        when {
            SEEN_PHRASES.any { heard.contains(it) } -> onSeenRequested()
            TRANSLATE_PHRASES.any { heard.contains(it) } -> onTranslateRequested(originalMessage)
        }
    }

    private inner class VoiceCommandListener : RecognitionListener {

        override fun onResults(results: Bundle?) {
            val heard = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.joinToString(" ")
                .orEmpty()
            if (heard.isNotBlank()) handleUtterance(heard)
        }

        override fun onError(error: Int) {
            if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                destroyRecognizer()
            }
        }

        override fun onEnd() {
            listening = false
            handler.post(stopListeningUiRunnable)
        }

        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    @SuppressLint("ClickableViewAccessibility")
    private inner class GestureHandler : View.OnTouchListener {

        private var downRawX = 0f
        private var downRawY = 0f
        private var downParamX = 0
        private var downParamY = 0
        private var moved = false
        private var longPressed = false

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

        private val TRANSLATE_PHRASES = listOf("translate", "meaning", "what does it say")
        private val SEEN_PHRASES = listOf(
            "mark as seen", "mark it as seen", "as seen", "seen", "skip", "dismiss", "hide", "ignore", "read"
        )
    }
}
