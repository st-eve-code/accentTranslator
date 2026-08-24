package com.accentbridge.app.incoming

import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions

class MlKitTranslator {

    private val languageIdentifier = LanguageIdentification.getClient(
        LanguageIdentificationOptions.Builder().setConfidenceThreshold(0.45f).build()
    )

    private val translators = HashMap<String, Translator>()

    fun detectAndTranslate(text: String, onResult: (String?) -> Unit) {
        languageIdentifier.identifyLanguage(text)
            .addOnSuccessListener { tag ->
                if (tag == LANGUAGE_UNKNOWN || tag == ENGLISH_BCP47) {
                    onResult(null)
                } else {
                    translateFrom(tag, text, onResult)
                }
            }
            .addOnFailureListener { onResult(null) }
    }

    fun translateFrom(sourceBcp47: String, text: String, onResult: (String?) -> Unit) {
        val source = TranslateLanguage.fromLanguageTag(sourceBcp47)
        if (source == null) {
            onResult(null)
            return
        }
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(source)
            .setTargetLanguage(TranslateLanguage.ENGLISH)
            .build()
        val translator = translators.getOrPut(sourceBcp47) { Translation.getClient(options) }

        translator.downloadModelIfNeeded()
            .addOnSuccessListener {
                translator.translate(text)
                    .addOnSuccessListener { translated -> onResult(translated.trim()) }
                    .addOnFailureListener { onResult(null) }
            }
            .addOnFailureListener { onResult(null) }
    }

    fun close() {
        languageIdentifier.close()
        for (translator in translators.values) {
            translator.close()
        }
        translators.clear()
    }

    companion object {
        private const val LANGUAGE_UNKNOWN = "und"
        private const val ENGLISH_BCP47 = "en"
    }
}
