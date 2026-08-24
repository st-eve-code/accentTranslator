# Adding an accent

Accents are pure data. If the accent you want can be expressed as "swap these words, apply these regexes", you never touch Kotlin or Swift. This guide walks through the whole routine, including the copy step people forget.

## The short version

1. Edit `shared/accents.json`.
2. Copy it to `android/app/src/main/assets/accents.json` and `ios/Shared/Resources/accents.json`.
3. Bump `"version"` at the top of the file.
4. Test in the Android app's preview box (fastest feedback loop), then rebuild.

## The format

Each accent lives under `accents` with an id you choose:

```jsonc
"nigerian": {
  "label": "Nigerian",          // shown in the picker
  "words": {
    "friend": "padi",
    "a lot": "plenty"
  },
  "regex": [
    { "pattern": "\\bthe\\b", "replace": "de", "case_insensitive": true }
  ]
}
```

- **`label`** - what users see in the accent picker. The id is only used internally, so make it lowercase-with-underscores and stable; renaming it later resets everyone's selection.
- **`words`** - literal phrase swaps. Keys match case-insensitively on word boundaries.
- **`regex`** - ordered list of pattern/replacement pairs, applied after all word swaps.
- **`language`** (optional) - a BCP-47 tag like `"fr"`. Only relevant for accents that aren't English-based; it hints the translator which target language is involved.

## How matching actually works

A few behaviors are worth internalizing before you write rules:

**Longest key wins.** All `words` keys are compiled into a single alternation sorted by descending length. So `"going to go": "gonnae go"` beats `"going to": "gonnae"` beats `"go"` - put the specific phrase in, and don't bother deleting shorter entries that overlap.

**Word boundaries are enforced.** A key like `"art"` won't fire inside "particle". Conversely this means single letters match as whole words only - `"u"` matches a standalone "u", which is usually what you want for slang anyway.

**Case carries over.** `Yes -> Aye`, `YES -> AYE`, `yes -> aye`. You write replacements in lowercase and the engine handles the rest.

**Regex runs last**, so word-swap output feeds into regex rules. That ordering lets you do things like swap `"thank you" -> "ta"` first without your `-ing` rule mangling anything.

**Apostrophes normalize.** Both `'` (U+2019) and `'` become plain `'` before matching, and keys get the same treatment. Write rules with straight quotes and stop worrying about it.

## Regex tips

The `-ing` rule most dialects use is the canonical example:

```json
{ "pattern": "(?<=[a-z])ing\\b", "replace": "in'", "case_insensitive": true }
```

The lookbehind (`(?<=[a-z])`) requires a letter before `ing`, which protects words like "king", "ring", and "bring". Without it every one of those becomes `kin'`, which reads as a typo rather than a dialect.

Other patterns in the catalog worth stealing:

- `\bth\b`... actually no - note that `\bth` (no trailing boundary) is how French/German do their `th -> z` shtick, catching "this" but not "bath".
- Jamaican's `(?<=[a-z])er\b -> ah` turns "water" into "watah".
- Remember JSON needs double backslashes: `\\b`, not `\b`.

One caution: replacement text goes through case-matching too, so if your pattern can match ALL-CAPS input, expect an ALL-CAPS replacement.

## Testing

Quickest loop, no device needed:

```bash
cd android
./gradlew :app:testDebugUnitTest   # AccentEngineTest covers swaps, case, longest-first
```

Then install the debug APK, pick your new accent in the app, and hammer the preview box with sentences containing your edge cases - overlapping phrases, capitalized words, contractions with curly quotes from another keyboard.

## Common mistakes

- Editing only one of the three copies of `accents.json`. Android and iOS will then disagree, and you'll waste twenty minutes convincing yourself the engine is broken when it isn't. Make the copies immediately after editing.
- Adding a rule that maps *to* a word that's also a *source* key elsewhere ("mate" <-> "bro" style loops). Word swaps happen in a single pass over the original text, so this doesn't infinite-loop, but results can surprise you - trace one example by hand before shipping.
- Forgetting that `regex` order matters within an accent. First match wins; put more specific patterns earlier.

When you've got something good, a PR adding just the JSON diff (all three copies) plus a line in the README's accent list is the entire change.
