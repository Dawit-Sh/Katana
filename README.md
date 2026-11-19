<p align="center">
  <img src="docs/images/katana-icon.png" alt="Katana icon: an ink katana on black" width="128">
</p>

<h1 align="center">Katana</h1>

<p align="center">An Android book reader for your own book server.</p>

I forked [yabai](https://github.com/bostannnn/yabai) because I wanted a nice reader for my old Fire 7 that
works with my [Grimmory](https://github.com/grimmory-tools/grimmory) server, and yabai was the closest thing
I could find. It was missing a few things I needed, mostly highlights and a build that runs on the Fire, so I
added them, plus a bunch of smaller things along the way. It runs on modern Android phones too.

Most of what makes it good (the reader, the server connections, the library, the journal and the whole look)
is yabai's work, by [bostannnn](https://github.com/bostannnn).

## Download

Grab an APK from the [latest release](https://github.com/Dawit-Sh/Katana/releases/latest). There are three,
and they all need Android 9 or newer:

| APK | Use it on |
| --- | --- |
| `katana-…-fire7-armeabi-v7a.apk` | Amazon Fire tablets and other older 32-bit devices |
| `katana-…-arm64-v8a.apk` | pretty much any phone or tablet from the last several years (Pixel, Samsung and so on) |
| `katana-…-universal.apk` | anything, if you're not sure which one you need. It's bigger because it carries every version inside |

The first time, Android will ask you to allow installs from your browser or file manager. A newer APK
installs over an older one and keeps your books and highlights, and Katana can update itself from
Settings › About. The APKs aren't from the Play Store, so Play Protect may show a warning the first time.

## What I added

- **Fire 7 support.** A 32-bit build, longer network time limits for slow Wi-Fi, and a much faster start
  (about 2 seconds on the Fire).
- **Highlights and notes.** Four styles (highlight, underline, strikethrough, squiggly) in five colours, with
  notes. Syncing them with Grimmory is coming in the next update.
- **Words.** "Add to dictionary" saves a word with its sentence, and you can go over saved words as
  flashcards.
- **Bookmarks**, and one page that collects every book's bookmarks and highlights.
- **Search inside a book.**
- **Comfort.** Brightness and a warmth filter just for reading, volume keys to turn pages, and the time and
  battery in the page corner.
- **Quote cards.** Share a highlight as an image.
- **Export and backup.** Highlights and notes as Markdown, and a backup of highlights, words and bookmarks.
- **More formats.** MOBI, AZW, AZW3, FB2 and DjVu.
- **Journal bits.** A reading streak card in the side menu and this month's highlights in the journal.
- **Settings regrouped**, with a Storage page to see and clear what's on the device.
- **Steadier sync.** The Grimmory sign-in survives flaky Wi-Fi, and Katana notices by itself when the server
  is back.
- **Modern phones.** The page keeps clear of camera holes and notches, one page at a time in landscape, and
  back leaves a book in one swipe.

## What it already did, thanks to yabai

- Connects to Grimmory, Kavita and Stump, and opens files from your device.
- Browse by library, series, shelves and tags, with filters and sorting, and download books for offline.
- Reading progress syncs with the server.
- EPUB, PDF, and CBZ/CBR comics and manga (right to left, two-page spreads, scrolling).
- Your own lists and reading orders, plus Grimmory shelves.
- A reading journal with stats, translation of a selection, themes and fonts.

## Build it yourself

You need JDK 21 and the Android SDK with platform 37 (Android Studio brings both). Then:

```bash
./gradlew :app:assembleRelease
```

On Windows use `gradlew.bat`. The three APKs land in `app/build/outputs/katana-apks/`. If you set the
`ANDROID_SIGNING_*` environment variables they're signed with your key; otherwise with your computer's debug
key, so a new build only installs over an older one made on the same computer.

`./gradlew testDebugUnitTest detekt lintDebug` runs the tests and the code checks, same as CI.

## Where things are

| Folder | What's in it |
| --- | --- |
| `app/` | start-up, navigation and the wiring between modules |
| `core/` | the data model, database, networking, theme and components, the MOBI/AZW/FB2 converter, DjVu |
| `data/` | the Grimmory, Kavita and Stump clients |
| `feature/` | each part of the app: library, readers, highlights, journal, lists, downloads, settings, updates |
| `build-logic/`, `config/`, `tools/` | Gradle plugins, detekt rules, small helper scripts |
| `docs/` | architecture notes, the design system and yabai's old plans ([docs/README.md](docs/README.md)) |

The Kotlin package is still `com.example.unifiedreader`, same as yabai.

## License

Katana is free software under the [GNU GPL, version 3 or later](LICENSE). DjVu support uses DjVuLibre, which
is GPL, so the whole app is shared that way, and bostannnn is fine with yabai's code going out under it.

## Credits

- [yabai](https://github.com/bostannnn/yabai) by bostannnn, which everything here is built on.
- Fonts, icons and libraries: see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
