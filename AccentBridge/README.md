# AccentBridge

Lightweight cross-platform (Android + iOS) text translation system with **no cloud APIs, no backend, no expiring tokens**.

- **Outgoing**: a custom soft keyboard converts typed English into a chosen accent/dialect (Scottish, Southern US, Australian) instantly via a local JSON rule + regex engine.
- **Incoming**: on-device Google ML Kit translation of foreign-language messages, surfaced as a floating bubble overlay (Android) or a share-sheet bubble card (iOS).

## Documentation

- [Architecture](docs/architecture.md) - how the outgoing and incoming pipelines work on each platform, and why they're built that way.
- [Adding an accent](docs/adding-an-accent.md) - everything needed to contribute a new accent without touching code.

## Repository layout

```
AccentBridge/
├── shared/
│   └── accents.json                  # canonical accent catalog (edit this one)
├── android/
│   ├── settings.gradle.kts
│   ├── build.gradle.kts
│   ├── gradle.properties
│   └── app/
│       ├── build.gradle.kts          # ML Kit Translate + Language ID deps
│       └── src/
│           ├── main/
│           │   ├── AndroidManifest.xml
│           │   ├── assets/accents.json
│           │   ├── res/              # layouts, drawables, xml service configs
│           │   └── java/com/accentbridge/app/
│           │       ├── MainActivity.kt               # setup + accent picker + live preview
│           │       ├── engine/AccentEngine.kt        # local JSON parsing engine
│           │       ├── engine/AccentPrefs.kt         # active-accent storage
│           │       ├── keyboard/AccentKeyboardService.kt   # InputMethodService (outgoing)
│           │       └── incoming/
│           │           ├── MessageWatcherService.kt  # AccessibilityService (incoming)
│           │           ├── BubbleOverlayManager.kt   # draggable floating bubble
│           │           └── MlKitTranslator.kt        # ML Kit on-device translation
│           └── test/java/com/accentbridge/app/engine/AccentEngineTest.kt
└── ios/
    ├── project.yml                   # XcodeGen manifest (generates .xcodeproj)
    ├── Podfile                       # GoogleMLKit/Translate + LanguageID
    ├── AccentBridge/                 # main app (SwiftUI)
    │   ├── AccentBridgeApp.swift
    │   ├── Engine/AccentEngine.swift # Swift twin of the Kotlin engine
    │   └── Resources -> see Shared/Resources (wired via project.yml)
    ├── KeyboardExtension/            # UIInputViewController (outgoing)
    ├── ShareExtension/               # incoming: share-to-translate bubble card
    └── Shared/
        ├── AccentStore.swift         # App Group UserDefaults for active accent
        └── Resources/accents.json
```

> `shared/accents.json` is the source of truth. Copies live in `android/app/src/main/assets/` and `ios/Shared/Resources/`; re-copy after editing.

## accents.json format

```jsonc
{
  "version": 1,
  "default_accent": "scottish",
  "accents": {
    "<accent_id>": {
      "label": "Human-readable name",
      "words": { "going to": "gonnae", "yes": "aye" },     // longest keys win
      "regex": [
        { "pattern": "(?<=[a-z])ing\\b", "replace": "in'", "case_insensitive": true }
      ]
    }
  }
}
```

Engine behavior (identical in Kotlin and Swift):

1. Word swaps are compiled into ONE case-insensitive word-boundary alternation, keys sorted **longest-first**, so `"don't know"` beats `"don't"` beats `"not"`.
2. Capitalization is preserved (`YES -> AYE`, `Yes -> Aye`).
3. Regex rules run after word swaps; lookbehind keeps case handling clean (`doing -> doin'`).
4. Typographic apostrophes (`'`) are normalized to `'`.
5. Everything compiled once at load; per-keystroke conversion is pure string ops (0 ms, offline).

## Android build & usage

Requirements: JDK 17, Android Studio (AGP 8.5+, compileSdk 35).

```bash
cd android
./gradlew :app:assembleDebug      # build
./gradlew :app:testDebugUnitTest  # engine unit tests
```

First run:

1. Open the app, pick an active accent, use the preview box to sanity-check the engine.
2. **Set up keyboard** -> enable *AccentBridge Keyboard* -> switch to it in any text field.
   - Type normally; each finished word converts on space, the whole buffer converts on sentence punctuation / return.
3. **Set up message bubble** -> enable the *Incoming message bubble* accessibility service.
   - Open WhatsApp (or any chat app) with a non-English message on screen.
   - Language is auto-detected, ML Kit translates on-device, and a draggable dark bubble shows the 1-line English meaning:
     - auto-collapses to an `AB` dot after 6 s,
     - tap dot/card to expand/collapse, drag anywhere, long-press or × to dismiss.

Notes:

- `INTERNET` permission is used only by Play Services to download each language model once; translation itself runs offline afterward.
- The bubble uses `TYPE_ACCESSIBILITY_OVERLAY`, so no "draw over other apps" permission is needed.
- The message watcher reads visible screen text only while enabled and skips its own package; tune `MessageWatcherService.UI_CHROME` / heuristics per target app if needed.

## iOS build & usage

Requirements: macOS, Xcode 15+, [XcodeGen](https://github.com/yonaskolb/XcodeGen), CocoaPods. (This repo was authored on Windows; iOS code cannot be compiled here.)

```bash
cd ios
xcodegen generate
pod install
open AccentBridge.xcworkspace
```

In Xcode set your development team for all three targets (App Groups entitlement `group.com.accentbridge.shared` is pre-wired), then run the app.

- Outgoing: add *AccentBridge* under Settings > General > Keyboard > Keyboards > Add New Keyboard; the accent pill switches dialects; conversions mirror the Android triggers.
- Incoming: share any message from any app to **Translate to English**; the extension shows a bubble card with the plain-English meaning (ML Kit on-device, model downloads once).

Apple does not allow drawing over other apps system-wide, so iOS incoming translation uses the Share Extension card instead of a persistent overlay - the closest permitted equivalent of the Android bubble.

## Known limitations / roadmap

- `"going to the pub"` maps like `"going to eat"` (verb/noun disambiguation needs POS data; not feasible offline in v1).
- Accessibility scan picks the bottom-most plausible chat text; app-specific node rules would make targeting exact.
- iOS keyboard cannot read surrounding sentence context without Full Access, so buffering is local-only (same as Android).
- Add more accents by only editing `shared/accents.json` - no code changes needed.
