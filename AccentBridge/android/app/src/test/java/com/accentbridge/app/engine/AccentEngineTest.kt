package com.accentbridge.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class AccentEngineTest {

    private val json = """
        {
          "version": 1,
          "default_accent": "scottish",
          "accents": {
            "scottish": {
              "label": "Scottish",
              "words": {
                "don't know": "dinnae ken",
                "going to": "gonnae",
                "don't": "dinnae",
                "about": "aboot",
                "know": "ken",
                "yes": "aye",
                "you": "ye",
                "small": "wee"
              },
              "regex": [
                {"pattern": "\\bwith\\b", "replace": "wi'", "case_insensitive": true},
                {"pattern": "(?<=[a-z])ing\\b", "replace": "in'", "case_insensitive": true}
              ]
            },
            "southern_us": {
              "label": "Southern US",
              "words": {
                "you all": "y'all",
                "you guys": "y'all",
                "aren't": "ain't",
                "isn't": "ain't",
                "is not": "ain't",
                "are not": "ain't"
              },
              "regex": [
                {"pattern": "(?<=[a-z])ing\\b", "replace": "in'", "case_insensitive": true}
              ]
            },
            "australian": {
              "label": "Australian",
              "words": {
                "good day": "g'day",
                "thank you": "ta",
                "thanks": "ta",
                "friend": "mate",
                "barbecue": "barbie"
              },
              "regex": [
                {"pattern": "\\bvery\\b", "replace": "heaps", "case_insensitive": true}
              ]
            }
          }
        }
    """.trimIndent()

    private fun engine(accent: String) = AccentEngine.fromJson(json, accent)

    @Test
    fun `scottish swaps words and preserves case`() {
        assertEquals("Ye should ken aboot it.", engine("scottish").translate("You should know about it."))
    }

    @Test
    fun `longest phrase wins over single words`() {
        assertEquals("I dinnae ken yet.", engine("scottish").translate("I don't know yet."))
    }

    @Test
    fun `scottish gerund rule`() {
        assertEquals("We were doin' great.", engine("scottish").translate("We were doing great."))
    }

    @Test
    fun `southern yall and aint`() {
        assertEquals("Y'all ain't goin'?", engine("southern_us").translate("You all aren't going?"))
    }

    @Test
    fun `australian slang`() {
        assertEquals(
            "G'day mate, ta for the barbie.",
            engine("australian").translate("Good day friend, thanks for the barbecue.")
        )
    }

    @Test
    fun `very becomes heaps`() {
        assertEquals("It is heaps good.", engine("australian").translate("It is very good."))
    }

    @Test
    fun `all caps preserved`() {
        assertEquals("AYE", engine("scottish").translate("YES"))
    }

    @Test
    fun `typographic apostrophes normalized`() {
        assertEquals("dinnae worry", engine("scottish").translate("don\u2019t worry"))
    }

    @Test
    fun `blank passthrough`() {
        val text = "   "
        assertEquals(text, engine("scottish").translate(text))
    }

    @Test
    fun `unknown accent falls back to default`() {
        val fallback = AccentEngine.fromJson(json, "klingon")
        assertEquals("scottish", fallback.accentId)
        assertEquals("aye", fallback.translate("yes"))
    }

    @Test
    fun `real asset catalog loads and translates`() {
        val asset = File("src/main/assets/accents.json")
        assumeTrue(asset.exists())
        val real = AccentEngine.fromJson(asset.readText(), null)
        assertTrue(AccentEngine.availableAccents(json).size >= 3)
        assertEquals("Ye ken aboot it.", real.translate("You know about it."))
    }

    @Test
    fun `scottish home becomes hame`() {
        val asset = File("src/main/assets/accents.json")
        assumeTrue(asset.exists())
        val scottish = AccentEngine.fromJson(asset.readText(), "scottish")
        assertEquals("Hame.", scottish.translate("Home."))
        assertEquals("I am at hame noo.", scottish.translate("I am at home now."))
    }

    @Test
    fun `language accents expose ml kit target`() {
        val asset = File("src/main/assets/accents.json")
        assumeTrue(asset.exists())
        val text = asset.readText()
        assertEquals("it", AccentEngine.fromJson(text, "italian").language)
        assertEquals("fr", AccentEngine.fromJson(text, "french").language)
        assertEquals(null, AccentEngine.fromJson(text, "scottish").language)
    }

    @Test
    fun `bom prefixed json parses`() {
        val asset = File("src/main/assets/accents.json")
        assumeTrue(asset.exists())
        val real = AccentEngine.fromJson("\uFEFF" + asset.readText(), "australian")
        assertEquals("australian", real.accentId)
    }
}
