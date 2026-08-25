package com.accentbridge.app.engine

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.regex.Matcher
import java.util.regex.Pattern

class RegexRule internal constructor(val pattern: Pattern, val replacement: String)

class AccentDefinition internal constructor(
    val id: String,
    val label: String,
    val language: String?,
    val combinedWordPattern: Pattern,
    val wordMap: Map<String, String>,
    val regexRules: List<RegexRule>
)

class AccentEngine private constructor(private val definition: AccentDefinition) {

    val accentId: String get() = definition.id
    val accentLabel: String get() = definition.label
    val language: String? get() = definition.language

    fun translate(text: String): String {
        if (text.isBlank()) return text
        var out = swapWords(normalize(text))
        for (rule in definition.regexRules) {
            out = applyRule(out, rule)
        }
        return out
    }

    private fun normalize(text: String): String =
        text.replace('\u2019', '\'').replace('\u2018', '\'')

    private fun swapWords(text: String): String {
        val matcher = definition.combinedWordPattern.matcher(text)
        if (!matcher.find()) return text
        matcher.reset()
        val sb = StringBuffer()
        while (matcher.find()) {
            val original = matcher.group()
            val replacement = definition.wordMap.getValue(original.lowercase(Locale.ROOT))
            matcher.appendReplacement(sb, Matcher.quoteReplacement(matchCase(original, replacement)))
        }
        matcher.appendTail(sb)
        return sb.toString()
    }

    private fun applyRule(text: String, rule: RegexRule): String {
        val matcher = rule.pattern.matcher(text)
        if (!matcher.find()) return text
        matcher.reset()
        val sb = StringBuffer()
        while (matcher.find()) {
            matcher.appendReplacement(sb, Matcher.quoteReplacement(matchCase(matcher.group(), rule.replacement)))
        }
        matcher.appendTail(sb)
        return sb.toString()
    }

    private fun matchCase(original: String, replacement: String): String {
        if (original.isEmpty() || replacement.isEmpty()) return replacement
        val letters = original.filter { it.isLetter() }
        return when {
            letters.length > 1 && letters.all { it.isUpperCase() } -> replacement.uppercase(Locale.ROOT)
            original.firstOrNull { it.isLetter() }?.isUpperCase() == true ->
                replacement.replaceFirstChar { it.uppercaseChar() }
            else -> replacement
        }
    }

    companion object {

        const val DEFAULT_ASSET = "accents.json"

        fun load(context: Context, assetName: String = DEFAULT_ASSET, accentId: String? = null): AccentEngine =
            fromJson(context.assets.open(assetName).bufferedReader().use { it.readText() }, accentId)

        fun availableAccents(context: Context, assetName: String = DEFAULT_ASSET): List<Pair<String, String>> =
            availableAccents(context.assets.open(assetName).bufferedReader().use { it.readText() })

        fun availableAccents(json: String): List<Pair<String, String>> {
            val accents = JSONObject(cleanJson(json)).getJSONObject("accents")
            val result = ArrayList<Pair<String, String>>(accents.length())
            for (key in accents.keys()) {
                result.add(key to accents.getJSONObject(key).optString("label", key))
            }
            return result.sortedBy { it.first }
        }

        fun defaultAccentId(context: Context, assetName: String = DEFAULT_ASSET): String =
            defaultAccentId(context.assets.open(assetName).bufferedReader().use { it.readText() })

        fun defaultAccentId(json: String): String {
            val root = JSONObject(cleanJson(json))
            val accents = root.getJSONObject("accents")
            val declared = root.optString("default_accent").takeIf { accents.has(it) }
            return declared ?: accents.keys().next()
        }

        fun fromJson(json: String, accentId: String? = null): AccentEngine {
            val root = JSONObject(cleanJson(json))
            val accents = root.getJSONObject("accents")
            require(accents.length() > 0) { "accents.json contains no accents" }

            val defaultId = root.optString("default_accent").takeIf { accents.has(it) }
                ?: accents.keys().next()
            val requested = accentId ?: root.optString("default_accent").takeIf { it.isNotBlank() }
            val resolvedId = if (requested != null && accents.has(requested)) requested else defaultId

            val accentObj = accents.getJSONObject(resolvedId)
            val wordsObj = accentObj.getJSONObject("words")

            val entries = ArrayList<Pair<String, String>>(wordsObj.length())
            for (key in wordsObj.keys()) {
                entries.add(normalizeKey(key) to wordsObj.getString(key))
            }
            entries.sortByDescending { it.first.length }

            val alternation = entries.joinToString("|") { Pattern.quote(it.first) }
            val combined = Pattern.compile("\\b(?:$alternation)\\b", Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE)

            val rulesArray = accentObj.optJSONArray("regex") ?: JSONArray()
            val regexRules = ArrayList<RegexRule>(rulesArray.length())
            for (i in 0 until rulesArray.length()) {
                val ruleObj = rulesArray.getJSONObject(i)
                var flags = 0
                if (ruleObj.optBoolean("case_insensitive", false)) flags = flags or Pattern.CASE_INSENSITIVE
                regexRules.add(RegexRule(Pattern.compile(ruleObj.getString("pattern"), flags), ruleObj.getString("replace")))
            }

            return AccentEngine(
                AccentDefinition(
                    id = resolvedId,
                    label = accentObj.optString("label", resolvedId),
                    language = accentObj.optString("language").takeIf { it.isNotBlank() },
                    combinedWordPattern = combined,
                    wordMap = entries.toMap(),
                    regexRules = regexRules
                )
            )
        }

        private fun normalizeKey(key: String): String =
            key.lowercase(Locale.ROOT).replace('\u2019', '\'').replace('\u2018', '\'')

        private fun cleanJson(json: String): String =
            if (json.startsWith("\uFEFF")) json.substring(1) else json
    }
}
