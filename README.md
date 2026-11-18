<p align="center">
  <img src="docs/images/katana-icon.png" alt="Katana icon: an ink katana on black" width="128">
</p>

<h1 align="center">Katana</h1>

<p align="center">An Android reader for your own book server and the books on your device.</p>

Katana is Dawit's personal Android reader. It reads books from self-hosted book servers, mainly Grimmory,
and also Kavita and Stump, plus files kept on the device.
It was made first for the Amazon Fire 7 (2019, Fire OS 7, 32-bit Android 9); phones come later.

**Built on yabai.** Katana is a fork of [yabai](https://github.com/bostannnn/yabai) by
[bostannnn](https://github.com/bostannnn). The reader, the server clients, the library, the journal, the
design system and most of the code come from yabai. Katana adds the Fire 7 build and the features marked
below as new.

## Formats

| Format | How Katana opens it |
| --- | --- |
| EPUB | Readium |
| PDF | Android's PDF renderer |
| CBZ, CBR | the comic reader |
| MOBI, AZW, AZW3, FB2 | converted to EPUB on the device, so highlights and notes work as in EPUB (new) |
| DjVu | DjVuLibre, shown in the PDF page view (new) |

## Features

**Library and servers**

- Connect to Grimmory, Kavita and Stump, and add files from the device.
- Browse by library, series, shelves and tags, with filters, sorting and reading status.
- Download books for reading offline.
- Reading progress goes to and comes from the server, with a sync status on the library bar.
- Lists: your own lists and reading orders, Want to read, Grimmory shelves and smart lists such as
  Stalled.

**Reading**

- Search in the book: results with a snippet and page, matches marked on the page, previous and next (new).
- Light: brightness just for reading or following the device, a warmth filter that can run only at night,
  and brightness by sliding along the left edge (new).
- Volume keys turn pages, and the screen can stay on while you read (new).
- Default look for new books, themes and fonts, and translation of a selection.
- Comics and manga: page turns or continuous scroll, two-page spreads and right-to-left reading.

**Highlights, notes and words** (new)

- Highlights in four styles (highlight, underline, strikethrough, squiggly) and five colours, with notes.
- Save a word with its sentence and review saved words as flashcards.
- Page bookmarks, and a Bookmarks page that gathers every book's marks with search and filters.
- Quote cards: share a highlight as an image or save it to Pictures.
- Export one book's highlights and notes, or all of them, as Markdown.

**Journal**

- Reading days, the hours you usually read, finished books and reading stats.
- This month's highlights, and a reading card with your streak in the side menu (new).

**Settings**

- Appearance, Reading, Sources, Library, Storage (sizes, manage and remove books, clear covers) and
  Highlights and backup: a JSON backup of highlights, words and bookmarks, and a restore that only adds (new).

## Build

You need JDK 21 and the Android SDK with platform 37 (Android Studio brings both). Then, from the
repository folder:

```bash
./gradlew :app:assembleRelease
```

On Windows use `gradlew.bat`. The APK lands in `app/build/outputs/apk/release/app-release.apk`.

- **Fire 7 build.** The release build contains native code for `armeabi-v7a` only, because the Fire 7 (2019)
  runs 32-bit Android. For a modern 64-bit phone, change `abiFilters` in `app/build.gradle.kts` to
  `arm64-v8a`.
- **Signing.** With the `ANDROID_SIGNING_*` environment variables set, the release build is signed with
  that key. Without them it is signed with your computer's debug key, so a new build installs over an older
  one only when both came from the same computer.
- **Checks.** `./gradlew testDebugUnitTest detekt lintDebug` runs the unit tests and the code checks, as
  CI does on every push.

The minimum Android version is 9 (API 28).

## Project layout

The code follows the module layout of Google's
[Now in Android](https://github.com/android/nowinandroid) sample.

| Folder | What it holds |
| --- | --- |
| `app/` | the app shell: start-up, top-level navigation and wiring the modules together |
| `core/` | shared pieces: the data model, the Room database, networking, the theme and component kit, the MOBI/AZW/FB2 converter and DjVuLibre |
| `data/` | the Grimmory, Kavita and Stump clients, and the repositories that combine them |
| `feature/` | each part of the app: library, the readers (EPUB, PDF, comics), highlights and bookmarks, journal, lists, downloads, settings, translation, updates |
| `build-logic/` | the Gradle convention plugins every module shares |
| `config/` | detekt rules |
| `docs/` | architecture rules, the design system and yabai's earlier plans (see [docs/README.md](docs/README.md)) |

The Kotlin package is still `com.example.unifiedreader`, as in yabai.

## Status

Katana is a personal fork, in daily use on one Fire 7. **No releases or APKs are published**: yabai has no
license, so its author keeps all rights to its code, and Katana is shared here as source only until that
changes. DjVu support uses DjVuLibre, which is GPL licensed; see
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for what that means for any future release.
