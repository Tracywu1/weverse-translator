# Weverse DM Translator

<p align="center">
  <a href="README.md">简体中文</a> · <a href="README.en.md"><b>English</b></a>
</p>

An Android utility for real-time Korean → Simplified Chinese translation in Weverse DM. It reads Korean messages exposed by Weverse through Android `AccessibilityService`, translates them online with ChatGPT, and renders Chinese directly over the corresponding artist-message area.

> Unofficial project. This project is not affiliated with, endorsed by, or sponsored by Weverse, HYBE, or OpenAI.

## Download

### [⬇ Download WeverseTranslator v0.5.2 APK](https://github.com/Tracywu1/weverse-translator/releases/download/v0.5.2/WeverseTranslator-v0.5.2.apk)

You can also visit [GitHub Releases](https://github.com/Tracywu1/weverse-translator/releases) for version history.

Current version: **v0.5.2**

## Features

- Real-time Korean → Simplified Chinese translation for visible Weverse DM messages
- `Continue with ChatGPT` login for eligible ChatGPT Plus / Pro accounts
- No API key field in the app
- Prefers a **Sol** model for translation quality and falls back when needed
- Context-aware translation using recent DM messages
- Prompt tuned for idol/fan conversations, member names, nicknames, omitted Korean subjects, slang, typos, coined words, cat/dog role-play jokes, `ㅋㅋ`, `ㅎㅎ`, `ㅠㅠ`, emoji and conversational tone
- Queues new messages while another translation request is running
- Persists successful translations locally to reduce repeated requests
- Filters sender names and some non-message Korean UI text
- Renders Chinese directly over the artist-message region instead of using a bottom subtitle panel
- Keeps overlays aligned while the conversation scrolls
- Main screen status indicators for ChatGPT, AccessibilityService, Weverse detection and translation state
- Translation requests use `store: false`
- Text-only accessibility extraction: no screenshots and no OCR

## How to use

1. Download and install the APK above.
2. Open **Weverse DM Translator**.
3. Tap **Continue with ChatGPT** and complete ChatGPT plan authorization.
4. Tap **Open Accessibility settings**.
5. Enable **Weverse DM 翻译**.
6. Return to the app and confirm that ChatGPT, AccessibilityService and Weverse are detected correctly.
7. Open Weverse and enter a DM conversation.
8. Visible Korean messages will be translated automatically.

To pause translation, return to the app home screen and switch off real-time translation.

> Android clears accessibility permissions and local app data after uninstalling an app. If you uninstall an older build before installing a newer one, ChatGPT authorization and AccessibilityService permission need to be enabled again.

## How it works

```text
Weverse DM screen
      ↓
Android AccessibilityService
      ↓
Read visible Korean text + screen bounds
      ↓
Filter sender names / UI text
      ↓
Queue new messages + recent conversation context
      ↓
ChatGPT OAuth + Responses API
      ↓
Natural Chinese translation
      ↓
Persistent local cache
      ↓
Render Chinese overlay on the source message area
```

The accessibility service is restricted to the Weverse Android package:

```text
co.benx.weverse
```

## Translation behavior

The current translation logic is tuned for instant-message conversation rather than formal sentence-by-sentence translation. It aims to:

- understand a batch of consecutive DMs as one conversation before translating individual messages
- recover omitted Korean subjects and references when context makes them clear
- prefer natural Chinese over rigid word-for-word output
- treat likely member names and nicknames as proper nouns
- preserve cat/dog role-play jokes and fandom-specific wordplay
- handle typos, spacing mistakes, colloquial contractions and coined words conservatively
- keep names and terms consistent across adjacent messages
- preserve `ㅋㅋ`, `ㅎㅎ`, `ㅠㅠ`, emoji, teasing, affectionate tone and terms of address
- avoid inventing stronger romantic meaning or facts that are absent from the Korean source

## Privacy

- AccessibilityService listens only to the Weverse package
- Only Korean text selected for translation and recent context are sent to OpenAI
- Translation requests use `store: false`
- ChatGPT OAuth credentials are stored in the app's private storage
- Completed translations are cached locally
- The app does not capture screenshots
- The app does not use OCR
- The project has no custom cloud database or translation proxy server

Android accessibility permission is powerful. When installing APKs from third parties, review the source code and release source first.

## Current limitations

- Message extraction depends on the accessibility text nodes exposed by the current Weverse UI
- A message with no accessible text will be skipped in this build
- Very long Chinese output may require a smaller font to fit the source message area
- Extremely short, context-sensitive messages can still have occasional translation ambiguity
- The app is currently optimized only for Korean → Simplified Chinese
- Development APKs currently use debug signing, so some upgrades may require uninstalling the previous build first

## Project structure

```text
.
├── app/
│   └── src/main/
│       ├── java/com/cc/weversetranslator/
│       │   ├── AppPrefs.kt
│       │   ├── MainActivity.kt
│       │   ├── OpenAiAuth.kt
│       │   ├── OverlayController.kt
│       │   ├── TranslationCache.kt
│       │   ├── TranslationClient.kt
│       │   └── WeverseAccessibilityService.kt
│       └── res/
├── .github/workflows/
└── README.*
```

## Roadmap

- Release signing
- Additional target languages

## Development history

1. Bottom translation panel
2. Separate white translation cards
3. Adaptive long-text cards
4. v0.4.0: inline overlay aligned to message bubbles
5. v0.5.0: context-focused translation, persistent cache and interaction improvements
6. v0.5.1: removed OCR and screenshot fallback
7. v0.5.2: removed in-Weverse source/translation switching for a simpler automatic translation experience

## Disclaimer

Weverse DM content may be subject to Weverse's own terms and content-use rules. Users are responsible for how they access, process, store or share DM content. This repository is intended as a personal translation utility and development project.
