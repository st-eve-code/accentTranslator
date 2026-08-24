package com.accentbridge.app.engine

import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions

object SentenceTranslator {

    private val translators = HashMap<String, Translator>()

    fun translate(text: String, targetBcp47: String, onResult: (String?) -> Unit) {
        val target = TranslateLanguage.fromLanguageTag(targetBcp47)
        if (target == null || text.isBlank()) {
            onResult(null)
            return
        }
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(target)
            .build()
        val translator = translators.getOrPut(targetBcp47) { Translation.getClient(options) }

        translator.downloadModelIfNeeded()
            .addOnSuccessListener {
                translator.translate(text)
                    .addOnSuccessListener { translated -> onResult(translated.trim()) }
                    .addOnFailureListener { onResult(null) }
            }
            .addOnFailureListener { onResult(null) }
    }
}
