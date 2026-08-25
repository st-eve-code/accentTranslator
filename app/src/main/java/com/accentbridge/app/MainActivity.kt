package com.accentbridge.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import com.accentbridge.app.engine.AccentEngine
import com.accentbridge.app.engine.AccentPrefs

class MainActivity : Activity() {

    private var engine: AccentEngine? = null
    private var checkedAccentId: String = AccentPrefs.OFF_ID

    private lateinit var statusKeyboard: TextView
    private lateinit var statusAccessibility: TextView
    private lateinit var statusMic: TextView
    private lateinit var radioGroup: RadioGroup
    private lateinit var inputText: EditText
    private lateinit var resultText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusKeyboard = findViewById(R.id.status_keyboard)
        statusAccessibility = findViewById(R.id.status_accessibility)
        statusMic = findViewById(R.id.status_mic)
        radioGroup = findViewById(R.id.rg_accents)
        inputText = findViewById(R.id.edit_input)
        resultText = findViewById(R.id.txt_result)

        findViewById<Button>(R.id.btn_enable_keyboard).setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
        findViewById<Button>(R.id.btn_enable_accessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.btn_enable_mic).setOnClickListener {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), MIC_PERMISSION_REQUEST)
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

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == MIC_PERMISSION_REQUEST) refreshStatus()
    }

    private fun populateAccents() {
        val accents = AccentEngine.availableAccents(this)
        val saved = AccentPrefs.getOrDefault(this)
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
        resultText.text = engine?.translate(source) ?: source
    }

    private fun refreshStatus() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        val imeEnabled = imm.enabledInputMethodList.any { it.packageName == packageName }
        statusKeyboard.setText(
            if (imeEnabled) R.string.status_keyboard_on else R.string.status_keyboard_off
        )
        val services = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        statusAccessibility.setText(
            if (services.contains(packageName)) R.string.status_a11y_on else R.string.status_a11y_off
        )
        val micGranted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        statusMic.setText(if (micGranted) R.string.status_mic_on else R.string.status_mic_off)
    }

    private companion object {
        const val MIC_PERMISSION_REQUEST = 41
    }
}
