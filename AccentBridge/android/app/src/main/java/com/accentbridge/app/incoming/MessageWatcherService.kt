package com.accentbridge.app.incoming

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class MessageWatcherService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var bubbleManager: BubbleOverlayManager? = null
    private var translator: MlKitTranslator? = null

    private var scanRunnable = Runnable {}
    private var translationToken = 0
    private var lastSeenHash = 0
    private var lastTranslatedAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        translator = MlKitTranslator()
        bubbleManager = BubbleOverlayManager(
            context = this,
            canListen = { micGranted() },
            onTranslateRequested = ::translateCurrentMessage,
            onSeenRequested = { bubbleManager?.hide() }
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.packageName != null && event.packageName.toString() == packageName) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> scheduleScan()
        }
    }

    private fun scheduleScan() {
        handler.removeCallbacks(scanRunnable)
        scanRunnable = Runnable { scanVisibleMessages() }
        handler.postDelayed(scanRunnable, SCAN_DEBOUNCE_MS)
    }

    private fun scanVisibleMessages() {
        val root = rootInActiveWindow ?: return
        val texts = ArrayList<String>()
        collectLeafTexts(root, 0, texts)
        val candidate = newestChatCandidate(texts) ?: return
        if (candidate.hashCode() == lastSeenHash) return
        val now = System.currentTimeMillis()
        if (now - lastTranslatedAt < MIN_INTERVAL_MS) return
        lastSeenHash = candidate.hashCode()
        lastTranslatedAt = now
        bubbleManager?.showOriginal(candidate.take(MAX_MESSAGE_LENGTH))
    }

    private fun translateCurrentMessage(text: String) {
        val token = ++translationToken
        translator?.detectAndTranslate(text) { english ->
            handler.post {
                if (token != translationToken) return@post
                bubbleManager?.showTranslated(
                    english?.takeIf { it.isNotBlank() } ?: text
                )
            }
        }
    }

    private fun micGranted(): Boolean =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun collectLeafTexts(node: AccessibilityNodeInfo?, depth: Int, out: MutableList<String>) {
        if (node == null || depth > MAX_TREE_DEPTH) return
        val text = node.text?.toString()
        if (!text.isNullOrBlank() && !node.isEditable) {
            out.add(text)
        }
        for (i in 0 until node.childCount) {
            collectLeafTexts(node.getChild(i), depth + 1, out)
        }
    }

    private fun newestChatCandidate(texts: List<String>): String? {
        for (i in texts.indices.reversed()) {
            val trimmed = texts[i].trim()
            if (trimmed.length < MIN_TEXT_LENGTH) continue
            if (trimmed.length > MAX_MESSAGE_LENGTH) continue
            if (UI_CHROME.any { trimmed.equals(it, ignoreCase = true) }) continue
            if (ACTION_LABELS.any { trimmed.equals(it, ignoreCase = true) }) continue
            return trimmed
        }
        return null
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        bubbleManager?.hide()
        bubbleManager = null
        translator?.close()
        super.onDestroy()
    }

    companion object {
        private const val SCAN_DEBOUNCE_MS = 350L
        private const val MIN_INTERVAL_MS = 1500L
        private const val MIN_TEXT_LENGTH = 3
        private const val MAX_MESSAGE_LENGTH = 400
        private const val MAX_TREE_DEPTH = 40

        private val ACTION_LABELS = setOf("translate", "seen", "voice", "mark as seen")

        private val UI_CHROME = setOf(
            "online", "typing", "typing\u2026", "recording", "last seen", "last seen today",
            "type a message", "message", "search", "camera", "chats", "updates",
            "communities", "calls", "status", "settings", "archived", "new chat"
        )
    }
}
