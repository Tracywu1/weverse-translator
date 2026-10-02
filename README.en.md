# Weverse DM Translator

<p align="center">
  <a href="README.md">简体中文</a> · <a href="README.en.md"><b>English</b></a>
</p>

An Android utility for real-time Korean → Simplified Chinese translation in Weverse DM. It reads Korean messages exposed by Weverse through Android `AccessibilityService`, translates them online with ChatGPT, and renders Chinese directly over the corresponding artist-message area.

> Unofficial project. This project is not affiliated with, endorsed by, or sponsored by Weverse, HYBE, or OpenAI.

## Download

### [⬇ Download WeverseTranslator v0.5.4 APK](https://github.com/Tracywu1/weverse-translator/releases/download/v0.5.4/WeverseTranslator-v0.5.4.apk)

You can also visit [GitHub Releases](https://github.com/Tracywu1/weverse-translator/releases) for version history.

Current version: **v0.5.4**

## Features

- Real-time Korean → Simplified Chinese translation for visible Weverse DM messages
- `Continue with ChatGPT` login for eligible ChatGPT Plus / Pro accounts
- No API key field in the app
- Prefers a **Sol** model for translation quality and falls back when needed
- Uses only consecutive **artist-side Korean messages** as translation context; the user's Chinese replies are excluded
- **Cross-bubble semantic stitching**: two or three adjacent short bubbles are first interpreted as one semantic unit, then mapped back to the original bubble count
- When a new bubble completes the previous fragment, the recent short bubbles can be retranslated instead of being frozen as independent sentences
- **Wordplay / coined-word handling** using the artist name, nearby keywords and preceding bubbles to infer puns, visual substitutions, blends, nickname variants and temporary jokes
- Recreates a wordplay relationship in Chinese when possible; otherwise gives a concise understandable Chinese explanation rather than dumping unexplained Korean
- More conservative proper-name detection to reduce false person-name interpretations
- Queues new messages while another translation request is running
- Persists longer, stable translations locally to reduce repeated requests
- Short messages are not persisted across restarts, and cache entries are namespaced by artist to reduce stale cross-context reuse
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
Read visible Korean + screen bounds + current artist name
      ↓
Filter sender names / UI text
      ↓
Stitch the latest 2–3 bubbles + recent artist-side Korean context
      ↓
ChatGPT OAuth + Responses API
      ↓
Natural Chinese translation / wordplay reconstruction
      ↓
Map the result back to the original bubble count
      ↓
Local translation cache
      ↓
Render Chinese overlay on the source message area
```

The accessibility service is restricted to the Weverse Android package:

```text
co.benx.weverse
```

## Translation behavior

The current translation logic is tuned for idol DM conversation rather than formal sentence-by-sentence translation:

- **Bubble boundaries are not treated as sentence boundaries.** Adjacent short bubbles are interpreted together before output is mapped back to individual bubbles.
- A translated bubble may intentionally end with a comma, term of address or unfinished phrase so the next bubble can complete the sentence naturally.
- For example, `근데 내여자야` + `졸릴때 잠깨는법 좀` can, when the context supports it, be rendered as:
  - `但是，我的女孩呀，`
  - `困的时候有没有什么醒困的方法？`
- The user's Chinese replies are not included in translation context.
- Omitted Korean subjects, references and tone are recovered only from artist-side Korean context.
- For puns, visual substitutions, blends and nickname variants, the translator checks the current artist name and nearby keywords before deciding what the coined form is doing.
- A pattern such as `명 / 띵 + 코알라 → 띵알라` can be recreated in Chinese when the relationship is clear instead of being mechanically transliterated.
- If a joke cannot be fully recreated, the app prefers a very short Chinese explanation that remains understandable.
- Proper names are handled conservatively. Without clear person evidence, normal syntax and lexical meaning are preferred.
- Forms such as `수정`, which can look like a name but also have ordinary lexical uses, are resolved from syntax and topic before any proper-name interpretation.
- Cat/dog role-play jokes, tone, terms of address, `ㅋㅋ`, `ㅎㅎ`, `ㅠㅠ`, emoji and teasing or affectionate style are preserved when supported by the Korean.
- The translator avoids inventing names, relationships, stronger romantic implications or facts absent from the source.

## Privacy

- AccessibilityService listens only to the Weverse package
- Only artist-side Korean selected for translation, the current artist name and recent artist-side Korean context are sent to OpenAI
- The user's Chinese replies are not sent as translation context
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
- Extremely short lines that depend on missing dialogue can still remain ambiguous
- Very new fandom-internal jokes may still need future translation-rule tuning
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
5. v0.5.0: context-focused translation and persistent cache
6. v0.5.1: removed OCR and screenshot fallback
7. v0.5.2: removed in-Weverse source/translation switching
8. v0.5.3: artist-only Korean context, more conservative name handling, and invalidated stale translation cache
9. v0.5.4: cross-bubble semantic stitching, recent-bubble retranslation, artist-name-assisted wordplay handling and artist-scoped cache

## Disclaimer

Weverse DM content may be subject to Weverse's own terms and content-use rules. Users are responsible for how they access, process, store or share DM content. This repository is intended as a personal translation utility and development project.
