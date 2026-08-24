# Architecture

AccentBridge does two jobs, and it's worth keeping them separate in your head because they barely share any code:

1. **Outgoing** - you type English, a custom keyboard rewrites it into whatever accent is active before it reaches the text field.
2. **Incoming** - someone sends you a message in another language, and you get a small English translation floating on screen.

Neither direction talks to a server. The only network use anywhere in the app is Google Play Services downloading ML Kit language models once, on first encounter of a new language. After that, everything runs on-device.

## The accent engine

The core of both platforms is `AccentEngine` - Kotlin in `android/app/src/main/java/com/accentbridge/app/engine/`, its Swift twin in `ios/AccentBridge/Engine/`. They're deliberately kept behaviorally identical, so a sentence converts the same way whether it went through the Android keyboard or the iOS one.

The engine takes an accent definition from JSON and compiles it once at load time:

- Every entry in `words` gets folded into **one** big case-insensitive alternation pattern, with keys sorted longest-first. That ordering matters: `"don't know"` has to win over `"don't"`, which has to win over `"not"`. One compiled regex also means each conversion pass is a single scan instead of hundreds.
- Optional `regex` rules run afterwards, in order. These handle the fiddly phonetic stuff that word swaps can't express cleanly - turning every `-ing` into `-in'` without touching words like "king", which is what the lookbehind in `(?<=[a-z])ing\b` is for.
- Capitalization survives the trip (`YES` becomes `AYE`, not `aye`).
- Curly quotes get normalized to straight ones first, so `"don't"` typed by a smart-keyboard still matches.

Per-keystroke cost is basically zero - it's all precompiled patterns and string ops, no parsing at conversion time. That's the whole reason the keyboard feels instant.

## Data flow: outgoing

```
your typing ──> InputMethodService (Android) / UIInputViewController (iOS)
                     │  finished word? convert on space
                     │  sentence end or return? convert the whole buffer
                     ▼
               AccentEngine.translate()
                     │
                     ▼
             committed into the text field
```

Which accent is active lives in `AccentPrefs` on Android (SharedPreferences) and `AccentStore` on iOS (App Group UserDefaults). The App Group part isn't optional on iOS - the keyboard extension is a separate process from the app, so plain UserDefaults wouldn't be shared between picking an accent in the app and using it while typing.

## Data flow: incoming (Android)

```
chat app renders a message
        │
        ▼
MessageWatcherService (AccessibilityService)
  watches TYPE_WINDOW_* events, debounces,
  collects visible leaf texts, picks the most
  plausible chat candidate
        │
        ▼
MlKitTranslator.detectAndTranslate()
  Language ID first, then Translate, on-device
        │
        ▼
BubbleOverlayManager
  dark bubble with the English line; collapses
  to an "AB" dot after ~6 s, drag/tap/dismiss
```

A few decisions here that aren't obvious from the outside:

- The bubble uses `TYPE_ACCESSIBILITY_OVERLAY`, which piggybacks on the accessibility permission the watcher already needs. That's why there's no separate "draw over other apps" prompt.
- The watcher ignores events from our own package, hashes candidates so unchanged screens don't re-translate, and rate-limits translations. Chat apps fire window-content events constantly while animating; without those guards the translator would spin.
- It reads *visible* screen text only, and heuristics decide which text node is "the newest message". That works well for standard chat layouts and occasionally grabs UI chrome on unusual apps - if you're targeting a specific app, tune `UI_CHROME` in `MessageWatcherService`.

## Data flow: incoming (iOS)

iOS doesn't let an app draw over other apps, full stop. So there's no persistent bubble. Instead the ShareExtension gives you a **Translate to English** target in the share sheet: share any message, get a card with the English meaning. It's the closest thing to the Android bubble that Apple permits, and honestly it's fine in practice - you're usually sharing the one message you didn't understand anyway.

## Where the accent catalog lives

`shared/accents.json` is the source of truth. Because Android wants assets inside `app/src/main/assets/` and iOS wants resources inside the built targets, the file is copied to both places:

- `android/app/src/main/assets/accents.json`
- `ios/Shared/Resources/accents.json` (wired into every target via `project.yml`)

If you edit one copy and forget the other, platforms will silently drift apart. See [adding-an-accent.md](adding-an-accent.md) for the exact routine.

## What's intentionally missing

No accounts, no sync, no telemetry, no server-side anything. The whole product fits in a few hundred KB and works offline forever. If a future feature seems to require a backend, it's probably out of scope.
