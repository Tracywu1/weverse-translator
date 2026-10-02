# Weverse DM Translator

An Android accessibility overlay that translates Korean Weverse DM messages into natural Simplified Chinese in real time.

> **Unofficial project.** This project is not affiliated with, endorsed by, or sponsored by Weverse, HYBE, or OpenAI.

## Overview

Weverse DM Translator watches the visible text in the Weverse DM screen through Android's `AccessibilityService`, detects Korean message text, translates it online with ChatGPT, and renders the Chinese translation directly over the corresponding message bubble.

The goal is to make translation feel closer to a native in-app experience instead of requiring copy/paste or a separate screenshot/OCR workflow.

Current version: **v0.4.0**

## Features

- Real-time Korean → Simplified Chinese translation for visible Weverse DM messages
- `Continue with ChatGPT` OAuth flow for eligible ChatGPT Plus / Pro accounts
- No API key field in the app
- Context-aware translation using recent DM messages
- Handles short messages, slang, omitted subjects, `ㅋㅋ`, `ㅎㅎ`, `ㅠㅠ`, nicknames, emoji, and conversational tone
- Queues untranslated messages while another translation request is running
- Caches completed translations to avoid repeated requests while scrolling
- Filters sender names and non-message Korean UI text
- Inline bubble overlay: Chinese is rendered inside the original message region instead of as a separate floating subtitle card
- Adaptive font sizing for long translations
- Translation requests are sent with `store: false`

## How it works

```text
Weverse DM screen
      ↓
Android AccessibilityService
      ↓
Extract visible Korean text + screen bounds
      ↓
Filter sender names / UI text
      ↓
Queue new messages + recent context
      ↓
ChatGPT OAuth + Responses API
      ↓
Natural Chinese translation
      ↓
Accessibility overlay positioned on the source bubble
```

### Why AccessibilityService?

The app needs to know which Korean messages are currently visible and where each message is located on screen. Android accessibility nodes provide both the visible text and its screen bounds, allowing translations to follow the corresponding DM bubble without taking screenshots continuously.

The service is restricted to the Weverse Android package:

```text
co.benx.weverse
```

## Installation

This project is currently distributed as a sideloaded debug APK during development.

1. Download/build the APK.
2. Install it on Android.
3. Open **Weverse DM Translator**.
4. Tap **Continue with ChatGPT** and complete authorization.
5. Open Android Accessibility settings.
6. Enable **Weverse DM 翻译**.
7. Open Weverse and enter a DM conversation.
8. Visible Korean messages will be translated automatically.

> Android clears accessibility permissions and local app data after uninstalling the app. If you uninstall before installing a newer debug APK, ChatGPT authorization and AccessibilityService permission must be enabled again.

## ChatGPT authentication

The app uses OpenAI's `Sign in with ChatGPT` OAuth flow rather than asking the user to paste an API key.

Authentication uses OAuth + PKCE. Access and refresh credentials are stored in the app's private local storage and refreshed when required.

The translation client uses the Responses API with streaming enabled.

For account availability, usage limits, and supported plans, refer to OpenAI's current Sign in with ChatGPT documentation.

## Translation behavior

The translation prompt is tuned for idol/fan instant messaging rather than formal Korean translation. It aims to:

- preserve intimacy and speaking style
- keep emoji and emoticons
- understand omitted Korean subjects when context makes them clear
- keep `ㅋㅋ`, `ㅎㅎ`, `ㅠㅠ` natural in Chinese
- avoid unnecessary literary or machine-translation phrasing
- avoid inventing stronger romantic meaning than the Korean source contains

Messages are translated in batches, while recent visible messages are included as context.

## Privacy

The app is designed around a narrow scope:

- the accessibility service listens only to the Weverse package
- only Korean text selected for translation and recent message context are sent to OpenAI
- translation requests use `store: false`
- OAuth credentials stay in the app's private storage
- the app does not maintain a cloud database or its own translation server

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
│       │   ├── OpenAiAuth.kt
│       │   ├── OverlayController.kt
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
| `WeverseAccessibilityService` | Reads visible Weverse text nodes, filters messages, maintains translation queue/cache |
| `TranslationClient` | Selects an available model and calls the Responses API |
| `OpenAiAuth` | ChatGPT OAuth, PKCE, token refresh |
| `OverlayController` | Draws translated Chinese over the corresponding source bubble |
| `AppPrefs` | Local model/auth/application preferences |
| `MainActivity` | Authorization, testing, accessibility settings, and Weverse launch controls |

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

- UI-node extraction depends on Weverse exposing message text through Android accessibility APIs.
- Weverse UI changes may require message-filter or positioning adjustments.
- Inline overlays approximate the original bubble bounds; font size may become smaller for very long Chinese translations.
- Translation cache currently lives in memory and is reset when the accessibility service/process restarts.
- Debug builds may use different signing keys between CI environments, so Android may require uninstalling the previous debug build before installing a new one.
- This project currently targets Korean → Simplified Chinese DM translation.

## Roadmap

Planned improvements:

- [ ] Translation on/off quick toggle
- [ ] Better status indicators for ChatGPT login, AccessibilityService, and Weverse detection
- [ ] More robust bubble geometry and scrolling synchronization
- [ ] Optional original/translation toggle per message
- [ ] Persistent local translation cache
- [ ] OCR fallback for message content that is unavailable through accessibility nodes
- [ ] Release signing and versioned APK releases
- [ ] Additional target languages

## Development history

The early MVP went through several UI approaches:

1. bottom translation panel
2. per-message white translation cards
3. full-height adaptive cards
4. **v0.4.0 inline bubble overlay**

The current inline approach was chosen to reduce visual obstruction and make the translated DM screen easier to read during normal scrolling.

## Notes for contributors

Keep privacy and scope narrow when adding features. In particular:

- avoid collecting accessibility text outside Weverse
- avoid logging OAuth tokens or private DM content
- do not hardcode credentials
- keep network requests explicit and minimal
- prefer message-level caching over repeated translation calls

## Disclaimer

Weverse DM content may be subject to Weverse's own terms and content-use rules. Users are responsible for how they access, process, store, or share DM content. This repository is intended as a personal accessibility/translation utility and development project.
