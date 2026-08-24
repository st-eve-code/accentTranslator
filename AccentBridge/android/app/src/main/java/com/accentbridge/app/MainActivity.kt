package com.accentbridge.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import com.accentbridge.app.engine.AccentEngine
import com.accentbridge.app.engine.AccentPrefs
import com.accentbridge.app.engine.SentenceTranslator

class MainActivity : Activity() {

    private var engine: AccentEngine? = null
    private var checkedAccentId: String = AccentPrefs.OFF_ID
    private val previewHandler = Handler(Looper.getMainLooper())
    private var previewRunnable: Runnable? = null
    private var previewToken = 0

    private lateinit var statusKeyboard: TextView
    private lateinit var statusAccessibility: TextView
    private lateinit var radioGroup: RadioGroup
    private lateinit var inputText: EditText
    private lateinit var resultText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusKeyboard = findViewById(R.id.status_keyboard)
        statusAccessibility = findViewById(R.id.status_accessibility)
        radioGroup = findViewById(R.id.rg_accents)
        inputText = findViewById(R.id.edit_input)
        resultText = findViewById(R.id.txt_result)

        findViewById<Button>(R.id.btn_enable_keyboard).setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
        findViewById<Button>(R.id.btn_enable_accessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.btn_convert).setOnClickListener { refreshPreview() }

        inputText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = refreshPreview()
        })

        populateAccents()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun populateAccents() {
        val accents = AccentEngine.availableAccents(this)
        val saved = AccentPrefs.get(this) ?: AccentPrefs.OFF_ID
        checkedAccentId = saved
        radioGroup.removeAllViews()
        radioGroup.setOnCheckedChangeListener { _, checkedId ->
            val button = findViewById<RadioButton?>(checkedId) ?: return@setOnCheckedChangeListener
            val id = button.tag as? String ?: return@setOnCheckedChangeListener
            if (id == checkedAccentId) return@setOnCheckedChangeListener
            checkedAccentId = id
            AccentPrefs.set(this, id)
            reloadEngine()
        }
        val entries = mutableListOf(AccentPrefs.OFF_ID to "None")
        entries.addAll(accents)
        for ((accentId, label) in entries) {
            val button = RadioButton(this).apply {
                text = label
                tag = accentId
                this.id = View.generateViewId()
                isChecked = accentId == saved
            }
            radioGroup.addView(button)
        }
        reloadEngine()
    }

    private fun reloadEngine() {
        engine = AccentPrefs.get(this)
            ?.takeIf { it != AccentPrefs.OFF_ID }
            ?.let { AccentEngine.load(this, accentId = it) }
        refreshPreview()
    }

    private fun refreshPreview() {
        val source = inputText.text.toString()
        val eng = engine
        resultText.text = eng?.translate(source) ?: source
        previewRunnable?.let { previewHandler.removeCallbacks(it) }
        previewRunnable = null
        previewToken++
        val lang = eng?.language
        if (eng == null || lang == null || source.isBlank()) return
        val token = previewToken
        val runnable = Runnable {
            SentenceTranslator.translate(source, lang) { result ->
                if (token == previewToken && result != null) {
                    resultText.text = result
                }
            }
        }
        previewRunnable = runnable
        previewHandler.postDelayed(runnable, 350)
    }

    private fun refreshStatus() {
        val ime = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) ?: ""
        statusKeyboard.setText(
            if (ime.contains(packageName)) R.string.status_keyboard_on else R.string.status_keyboard_off
        )
        val services = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        statusAccessibility.setText(
            if (services.contains(packageName)) R.string.status_a11y_on else R.string.status_a11y_off
        )
    }
}
