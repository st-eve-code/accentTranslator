package com.accentbridge.app.keyboard

import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import com.accentbridge.app.R
import com.accentbridge.app.engine.AccentEngine
import com.accentbridge.app.engine.AccentPrefs
import com.accentbridge.app.engine.SentenceTranslator

class AccentKeyboardService : InputMethodService() {

    private companion object {
        const val MAX_PENDING = 90
        const val REPEAT_DELAY_MS = 350L
        const val REPEAT_INTERVAL_MS = 60L
        const val PREVIEW_DELAY_MS = 400L
    }

    private class Outgoing(val raw: String, val trailing: String)

    private enum class Shift { OFF, ON, CAPS }
    private enum class Page { LETTERS, SYMBOLS }

    private var engine: AccentEngine? = null
    private var accents: List<Pair<String, String>> = emptyList()

    private val pending = StringBuilder()
    private val outgoingQueue = ArrayDeque<Outgoing>()
    private val repeatHandler = Handler(Looper.getMainLooper())
    private val previewHandler = Handler(Looper.getMainLooper())
    private var repeatRunnable: Runnable? = null
    private var previewRunnable: Runnable? = null
    private var previewToken = 0
    private var committing = false

    private var shift = Shift.ON
    private var page = Page.LETTERS

    private var keyboardColumn: LinearLayout? = null
    private var accentButton: Button? = null
    private var shiftButton: Button? = null
    private var pageToggle: Button? = null
    private var composeStrip: LinearLayout? = null
    private var previewOriginal: TextView? = null
    private var previewConverted: TextView? = null

    override fun onCreateInputView(): View {
        accents = AccentEngine.availableAccents(this)
        reloadEngine()
        keyboardColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFF1F3F6.toInt())
        }
        rebuild()
        return keyboardColumn!!
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        if (!restarting) {
            pending.setLength(0)
            updatePreview()
            shift = Shift.ON
        }
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        refreshAccentButton()
        updatePreview()
    }

    override fun onFinishInput() {
        stopRepeat()
        flushPending("")
        super.onFinishInput()
    }

    override fun onDestroy() {
        stopRepeat()
        previewHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun reloadEngine() {
        val saved = AccentPrefs.get(this)
        engine = saved?.takeIf { it != AccentPrefs.OFF_ID }?.let { AccentEngine.load(this, accentId = it) }
        updatePreview()
    }

    private fun rebuild() {
        val column = keyboardColumn ?: return
        column.removeAllViews()
        val stripParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)).apply {
            setMargins(dp(4), dp(6), dp(4), dp(2))
        }
        column.addView(composeStrip(), stripParams)
        if (page == Page.LETTERS) {
            column.addView(letterRow("qwertyuiop"), rowParams())
            column.addView(letterRow("asdfghjkl"), rowParams())
            column.addView(homeRow(), rowParams())
        } else {
            column.addView(symbolRow("1234567890"), rowParams())
            column.addView(symbolRow("@#\$&-+()"), rowParams())
            column.addView(symbolBottomRow(), rowParams())
        }
        column.addView(bottomRow(), rowParams())
        refreshAccentButton()
        refreshShift()
        updatePreview()
    }

    private fun rowParams() =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52))

    private fun row(vararg cells: Pair<View, Float>): LinearLayout {
        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.HORIZONTAL
        layout.setPadding(dp(3), dp(3), dp(3), 0)
        for ((view, weight) in cells) {
            layout.addView(view, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight))
        }
        return layout
    }

    private fun key(
        label: String,
        backgroundRes: Int = R.drawable.key_bg,
        onClick: () -> Unit
    ): Button {
        val button = Button(this)
        button.text = label
        button.textSize = 17f
        button.setAllCaps(false)
        button.setTextColor(0xFF1B1B1F.toInt())
        button.setBackgroundResource(backgroundRes)
        button.stateListAnimator = null
        button.setOnClickListener { onClick() }
        return button
    }

    private fun letterRow(chars: String): LinearLayout {
        val cells = chars.map { char -> key(char.toString()) { typeChar(char) } to 1f }
        return row(*cells.toTypedArray())
    }

    private fun symbolRow(chars: String): LinearLayout {
        val cells = chars.map { char -> key(char.toString(), R.drawable.key_bg_alt) { rawInsert(char.toString()) } to 1f }
        return row(*cells.toTypedArray())
    }

    private fun homeRow(): LinearLayout {
        val shiftKey = key("\u21E7") { cycleShift() }.also { shiftButton = it }
        val letters = "zxcvbnm".map { char -> key(char.toString()) { typeChar(char) } to 1f }
        val backspaceKey = repeatingBackspaceKey() to 1.4f
        return row(*(listOf(shiftKey to 1.4f) + letters + listOf(backspaceKey)).toTypedArray())
    }

    private fun symbolBottomRow(): LinearLayout {
        val toggle = pageToggleButton()
        val symbols = listOf("*", "\"", "'", ":", ";", "!", "?", "%")
            .map { sym -> key(sym, R.drawable.key_bg_alt) { rawInsert(sym) } to 1f }
        val backspaceKey = repeatingBackspaceKey() to 1.4f
        return row(*(listOf(toggle to 1.4f) + symbols + listOf(backspaceKey)).toTypedArray())
    }

    private fun composeStrip(): LinearLayout {
        val strip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(4), dp(10), dp(4))
            setBackgroundResource(R.drawable.compose_bg)
        }
        previewOriginal = TextView(this).apply {
            textSize = 14f
            setTextColor(0xFF1B1B1F.toInt())
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            hint = "Type here\u2026"
            setHintTextColor(0xFF9AA0AC.toInt())
        }.also { strip.addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.15f)) }
        strip.addView(
            TextView(this).apply {
                text = "\u2192"
                textSize = 13f
                setTextColor(0xFF9AA0AC.toInt())
                setPadding(dp(6), 0, dp(6), 0)
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        previewConverted = TextView(this).apply {
            textSize = 14f
            setTextColor(0xFF3B55D9.toInt())
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }.also { strip.addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
        composeStrip = strip
        return strip
    }

    private fun topStrip(): LinearLayout {
        val pill = key(engine?.accentLabel ?: "Off", R.drawable.key_bg_active) { showAccentPicker() }.also { accentButton = it }
        pill.setTextColor(0xFFFFFFFF.toInt())
        pill.textSize = 14f
        val switcher = key("\u21C4", R.drawable.key_bg_alt) { switchToNextIme() } to 0.35f
        return row(pill to 1f, switcher)
    }

    private fun pageToggleButton(): Button {
        val label = if (page == Page.LETTERS) "123" else "ABC"
        val toggle = key(label, R.drawable.key_bg_alt) { togglePage() }.also { pageToggle = it }
        return toggle
    }

    private fun bottomRow(): LinearLayout {
        val toggle = pageToggleButton()
        val comma = key(",", R.drawable.key_bg_alt) { trigger(",") } to 1f
        val space = key("space", R.drawable.key_bg_alt) { trigger(" ") } to 4.5f
        val period = key(".", R.drawable.key_bg_alt) { flushPending(".") } to 1f
        val enter = key("\u21B5", R.drawable.key_bg_alt) { enter() } to 1.4f
        return row(toggle to 1.4f, comma, space, period, enter)
    }

    private fun typeChar(c: Char) {
        val out = when (shift) {
            Shift.OFF -> c.lowercaseChar()
            else -> c.uppercaseChar()
        }
        if (engine == null) {
            currentInputConnection?.commitText(out.toString(), 1)
        } else {
            pending.append(out)
            updatePreview()
        }
        if (shift == Shift.ON) shift = Shift.OFF
        refreshShift()
    }

    private fun rawInsert(text: String) {
        if (engine == null) {
            currentInputConnection?.commitText(text, 1)
        } else {
            pending.append(text)
            updatePreview()
        }
    }

    private fun trigger(separator: String) {
        if (engine == null) {
            currentInputConnection?.commitText(separator, 1)
            return
        }
        pending.append(separator)
        updatePreview()
        if (pending.length >= MAX_PENDING) flushPending("")
    }

    private fun flushPending(trailing: String) {
        val raw = pending.toString()
        pending.setLength(0)
        shift = Shift.ON
        refreshShift()
        updatePreview()
        val ic = currentInputConnection ?: return
        if (raw.isNotBlank()) {
            outgoingQueue.addLast(Outgoing(raw, trailing))
            drainOutgoing()
        } else {
            ic.commitText(trailing, 1)
        }
    }

    private fun drainOutgoing() {
        if (committing) return
        while (outgoingQueue.isNotEmpty() && !committing) {
            val ic = currentInputConnection ?: return
            val eng = engine
            val item = outgoingQueue.first()
            val lang = eng?.language
            if (eng == null || lang == null) {
                ic.commitText((eng?.translate(item.raw) ?: item.raw) + item.trailing, 1)
                outgoingQueue.removeFirst()
                continue
            }
            committing = true
            SentenceTranslator.translate(item.raw, lang) { result ->
                currentInputConnection?.commitText((result ?: eng.translate(item.raw)) + item.trailing, 1)
                committing = false
                if (outgoingQueue.isNotEmpty()) outgoingQueue.removeFirst()
                drainOutgoing()
            }
        }
    }

    private fun backspace() {
        if (pending.isNotEmpty()) {
            pending.deleteCharAt(pending.length - 1)
            updatePreview()
        } else {
            currentInputConnection?.deleteSurroundingText(1, 0)
        }
    }

    private fun repeatingBackspaceKey(): Button =
        key("\u232B", R.drawable.key_bg_alt) {}.apply {
            setOnClickListener(null)
            setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        view.isPressed = true
                        backspace()
                        startRepeat()
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        view.isPressed = false
                        stopRepeat()
                        true
                    }
                    else -> false
                }
            }
        }

    private fun startRepeat() {
        stopRepeat()
        val runnable = object : Runnable {
            override fun run() {
                backspace()
                repeatHandler.postDelayed(this, REPEAT_INTERVAL_MS)
            }
        }
        repeatHandler.postDelayed(runnable, REPEAT_DELAY_MS)
        repeatRunnable = runnable
    }

    private fun stopRepeat() {
        repeatRunnable?.let { repeatHandler.removeCallbacks(it) }
        repeatRunnable = null
    }

    private fun enter() {
        flushPending("")
        currentInputConnection?.commitText("\n", 1)
    }

    private fun cycleShift() {
        shift = when (shift) {
            Shift.OFF -> Shift.ON
            Shift.ON -> Shift.CAPS
            Shift.CAPS -> Shift.OFF
        }
        refreshShift()
    }

    private fun togglePage() {
        page = if (page == Page.LETTERS) Page.SYMBOLS else Page.LETTERS
        rebuild()
    }

    private fun switchToNextIme() {
        if (!switchToPreviousInputMethod()) {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showInputMethodPicker()
        }
    }

    private fun showAccentPicker() {
        val anchor = accentButton ?: return
        val popup = PopupMenu(this, anchor)
        accents.forEachIndexed { index, (_, label) ->
            popup.menu.add(1, index, index, label)
        }
        val offIndex = accents.size
        popup.menu.add(1, offIndex, offIndex, "None")
        popup.menu.setGroupCheckable(1, true, true)
        val activeId = engine?.accentId
        accents.forEachIndexed { index, (id, _) ->
            popup.menu.findItem(index)?.isChecked = id == activeId
        }
        popup.menu.findItem(offIndex)?.isChecked = activeId == null
        popup.setOnMenuItemClickListener { item ->
            val newId = if (item.itemId == offIndex) AccentPrefs.OFF_ID else accents[item.itemId].first
            AccentPrefs.set(this, newId)
            reloadEngine()
            refreshAccentButton()
            Toast.makeText(this, "Accent: ${engine?.accentLabel ?: "Off"}", Toast.LENGTH_SHORT).show()
            true
        }
        popup.show()
    }

    private fun refreshAccentButton() {
        accentButton?.text = engine?.accentLabel ?: "Off"
    }

    private fun updatePreview() {
        val strip = composeStrip ?: return
        strip.visibility = if (engine == null) View.GONE else View.VISIBLE
        val raw = pending.toString()
        previewOriginal?.text = raw
        previewRunnable?.let { previewHandler.removeCallbacks(it) }
        previewRunnable = null
        previewToken++
        if (raw.isBlank()) {
            previewConverted?.text = ""
            return
        }
        val eng = engine ?: return
        previewConverted?.text = eng.translate(raw)
        val lang = eng.language ?: return
        val token = previewToken
        val runnable = Runnable {
            SentenceTranslator.translate(raw, lang) { result ->
                if (token == previewToken && result != null) {
                    previewConverted?.text = result
                }
            }
        }
        previewRunnable = runnable
        previewHandler.postDelayed(runnable, PREVIEW_DELAY_MS)
    }

    private fun refreshShift() {
        val active = shift == Shift.ON || shift == Shift.CAPS
        shiftButton?.setBackgroundResource(if (active) R.drawable.key_bg_active else R.drawable.key_bg)
        shiftButton?.setTextColor(if (active) 0xFFFFFFFF.toInt() else 0xFF1B1B1F.toInt())
        shiftButton?.text = if (shift == Shift.CAPS) "\u21EA" else "\u21E7"
        pageToggle?.text = if (page == Page.LETTERS) "123" else "ABC"
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
