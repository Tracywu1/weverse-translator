# Weverse DM Translator

An Android accessibility overlay that translates Korean Weverse DM messages into natural Simplified Chinese in real time.

> **Unofficial project.** This project is not affiliated with, endorsed by, or sponsored by Weverse, HYBE, or OpenAI.

## Overview

Weverse DM Translator watches the visible text in the Weverse DM screen through Android's `AccessibilityService`, detects Korean message text, translates it online with ChatGPT, and renders the Chinese translation directly over the corresponding message bubble.

The goal is to make translation feel closer to a native in-app experience instead of requiring copy/paste, screenshot sharing, or a separate translation app.

Current version: **v0.5.0**

## Features

- Real-time Korean → Simplified Chinese translation for visible Weverse DM messages
- `Continue with ChatGPT` OAuth flow for eligible ChatGPT Plus / Pro accounts
- No API key field in the app
- Plus/Pro model selection now prefers **Sol** for translation quality and falls back when needed
- Context-aware translation using recent DM messages
- Prompt tuned for idol/fan messaging, Korean ellipsis, slang, member names, nicknames, animal-role jokes, `ㅋㅋ`, `ㅎㅎ`, `ㅠㅠ`, emoji, and conversational tone
- Queues untranslated messages while another translation request is running
- Persistent local translation cache to reduce repeated calls after scrolling or service restarts
- Filters sender names and non-message Korean UI text
- Inline bubble overlay: Chinese is rendered over the original artist-message region rather than as a separate subtitle card
- Bubble geometry follows the original source area instead of expanding across the screen
- Smooth overlay position updates while scrolling
- Tap an individual translated bubble to switch between **original Korean / Chinese translation**
- Small in-Weverse **译 / 原** quick control for pausing or restoring translated overlays
- Main screen status indicators for ChatGPT, AccessibilityService, Weverse detection, translation state, and OCR state
- Optional Korean OCR fallback using on-device ML Kit recognition when Weverse exposes almost no Korean accessibility text
- Translation requests are sent with `store: false`

## How it works

```text
Weverse DM screen
      ↓
Android AccessibilityService
      ↓
Extract visible Korean text + screen bounds
      ↓
If accessibility text is insufficient:
ML Kit Korean OCR fallback (local recognition only)
      ↓
Filter sender names / UI text
      ↓
Queue new messages + recent context
      ↓
ChatGPT OAuth + Responses API
      ↓
Natural Chinese translation
      ↓
Persistent local cache
      ↓
Accessibility overlay positioned on the source bubble
```

### Why AccessibilityService?

The app needs to know which Korean messages are currently visible and where each message is located on screen. Android accessibility nodes provide both visible text and screen bounds, allowing translations to follow the corresponding DM bubble without continuously taking screenshots.

The service is restricted to the Weverse Android package:

```text
co.benx.weverse
```

### OCR fallback

Accessibility text remains the primary source. OCR is used only when the current Weverse screen exposes almost no Korean text through accessibility nodes.

The OCR fallback:

- uses Google's bundled Korean ML Kit recognizer on-device
- does not perform translation locally
- only supplies Korean text and bounds to the normal online ChatGPT translation path
- requires Android 11+ for AccessibilityService screenshot capture
- can be disabled from the app home screen

## Installation

This project is currently distributed as a sideloaded debug APK during development.

1. Download/build the APK.
2. Install it on Android.
3. Open **Weverse DM Translator**.
4. Tap **Continue with ChatGPT** and complete authorization.
5. Open Android Accessibility settings.
6. Enable **Weverse DM 翻译**.
7. Confirm the status page shows ChatGPT connected, AccessibilityService enabled, and Weverse detected.
8. Open Weverse and enter a DM conversation.
9. Visible Korean messages will be translated automatically.

Inside Weverse:

- tap the small **译 / 原** chip to pause or resume translated overlays
- tap one translated message bubble to switch that bubble between the original Korean and Chinese translation

> Android clears accessibility permissions and local app data after uninstalling the app. If you uninstall before installing a newer debug APK, ChatGPT authorization and AccessibilityService permission must be enabled again.

## ChatGPT authentication

The app uses OpenAI's `Sign in with ChatGPT` OAuth flow rather than asking the user to paste an API key.

Authentication uses OAuth + PKCE. Access and refresh credentials are stored in the app's private local storage and refreshed when required.

The translation client uses the Responses API with streaming enabled. The app prefers a Sol model when the connected plan exposes one because short conversational Korean often depends on member names, implicit subjects, role-play vocabulary, and cross-message context.

For account availability, usage limits, and supported plans, refer to OpenAI's current Sign in with ChatGPT documentation.

## Translation behavior

The v0.5.0 translation prompt is tuned for idol/fan instant messaging rather than formal Korean translation. It aims to:

- understand a batch as one continuous conversation before translating individual lines
- preserve omitted subjects and pronoun references when context resolves them
- avoid word-for-word Chinese that sounds unnatural
- treat likely member names and nicknames as proper nouns instead of ordinary vocabulary
- preserve cat/dog/animal-role jokes and fan-community wordplay
- handle Korean typos, spacing mistakes, coined words, and casual contractions conservatively
- keep terminology consistent across adjacent messages
- preserve intimacy, teasing, `ㅋㅋ`, `ㅎㅎ`, `ㅠㅠ`, emoji, emoticons, and terms of address
- avoid inventing stronger romantic meaning or extra facts that are absent from the Korean source

Messages are translated in batches, while recent messages are included as context.

## Privacy

The app is designed around a narrow scope:

- the accessibility service listens only to the Weverse package
- only Korean text selected for translation and recent message context are sent to OpenAI
- translation requests use `store: false`
- OAuth credentials stay in the app's private storage
- completed translations are cached locally on the device
- OCR recognition is performed locally with ML Kit and is only triggered as a fallback
- the app does not maintain its own cloud database or translation server

Because Android accessibility permission is powerful, review the source code before enabling the service if you are using a build from an untrusted source.

## Project structure

```text
.
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/cc/weversetranslator/
│       │   ├── AppPrefs.kt
│       │   ├── MainActivity.kt
│       │   ├── OcrFallback.kt
│       │   ├── OpenAiAuth.kt
│       │   ├── OverlayController.kt
│       │   ├── TranslationCache.kt
│       │   ├── TranslationClient.kt
│       │   └── WeverseAccessibilityService.kt
│       └── res/
├── .github/workflows/build-apk.yml
├── build.gradle.kts
├── gradle.properties
└── settings.gradle.kts
```

### Main components

| Component | Responsibility |
| --- | --- |
| `WeverseAccessibilityService` | Reads visible Weverse text nodes, manages OCR fallback, filtering, context, queueing and cache use |
| `TranslationClient` | Selects an available model and calls the Responses API with DM-specific translation instructions |
| `OpenAiAuth` | ChatGPT OAuth, PKCE and token refresh |
| `OverlayController` | Keeps translated bubbles aligned, provides per-message source/translation switching and the quick toggle |
| `TranslationCache` | Persists successful translations locally |
| `OcrFallback` | Uses Korean ML Kit OCR when accessibility text is insufficient |
| `AppPrefs` | Local model/auth/feature preferences |
| `MainActivity` | Authorization, health/status indicators, feature toggles, testing and Weverse launch controls |

## Building

### Requirements

- Android Studio / Android SDK
- JDK 17
- Android compile SDK 37
- Gradle 9.6+

Build a debug APK:

```bash
gradle :app:assembleDebug
```

Output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

A GitHub Actions workflow is also included and builds the debug APK on pushes to `main` and on manual dispatch.

## Current limitations

- UI-node extraction still depends on Weverse's current accessibility hierarchy.
- OCR fallback is intentionally conservative and activates mainly when accessibility extraction returns almost no Korean text.
- Very long Chinese translations can require a smaller font to stay inside the source bubble geometry.
- Tapping a translated overlay switches that message to the original source, so the overlay temporarily receives touch input in the bubble area.
- Reusing a persistent translation for the exact same Korean text can occasionally be less context-sensitive for very ambiguous short phrases; very short items are therefore excluded from persistent storage.
- Debug builds may use different signing keys between CI environments, so Android may require uninstalling the previous debug build before installing a new one.
- This project currently targets Korean → Simplified Chinese DM translation.

## Roadmap

Implemented in v0.5.0:

- [x] Translation on/off quick toggle
- [x] Better status indicators for ChatGPT login, AccessibilityService, and Weverse detection
- [x] More robust bubble geometry and scrolling synchronization
- [x] Optional original/translation toggle per message
- [x] Persistent local translation cache
- [x] OCR fallback for message content that is unavailable through accessibility nodes

Still planned:

- [ ] Release signing and versioned APK releases
- [ ] Additional target languages

## Development history

The project has gone through several UI and extraction approaches:

1. bottom translation panel
2. per-message white translation cards
3. full-height adaptive cards
4. **v0.4.0 inline bubble overlay**
5. **v0.5.0 context-focused translation + persistent cache + OCR fallback + interactive overlays**

## Notes for contributors

Keep privacy and scope narrow when adding features. In particular:

- avoid collecting accessibility text outside Weverse
- avoid logging OAuth tokens or private DM content
- do not hardcode credentials
- keep network requests explicit and minimal
- prefer message-level caching over repeated translation calls
- keep OCR as a fallback rather than the default extraction path

## Disclaimer

Weverse DM content may be subject to Weverse's own terms and content-use rules. Users are responsible for how they access, process, store, or share DM content. This repository is intended as a personal accessibility/translation utility and development project.
