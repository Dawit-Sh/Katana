<p align="center">
  <img src="docs/images/katana-icon.png" alt="Katana icon: an ink katana on black" width="128">
</p>

<h1 align="center">Katana 刀</h1>

<p align="center">A simple Android reader for your own book server.</p>

I made Katana because I wanted a nice way to read the books on my
[Grimmory](https://github.com/grimmory-tools/grimmory) server, on my old Fire tablet that I use for reading only,
and on my phone. It's a fork of [yabai](https://github.com/bostannnn/yabai) with some things that I added because I
was missing them. This is basically my reading app that just suits my needs and has the tools I want to have
whenever I'm reading digitally.

## Download

Get it from **[dawitsh.dev/Katana](https://dawitsh.dev/Katana/)**, which picks the right version for your device,
or from the [latest release](https://github.com/Dawit-Sh/Katana/releases/latest). It needs Android 9 or newer.
Once it's installed, Katana updates itself from Settings › About, and an update keeps your books and highlights.

## What it does

- Reads your books from Grimmory, and also Kavita and Stump, or from files on your device.
- EPUB, PDF, comics (CBZ and CBR), MOBI, AZW3, FB2 and DjVu.
- Highlights and notes in four styles, synced both ways with Grimmory, so they show up in its web reader and in
  KOReader too.
- Your reading place syncs with the server, and books download for offline reading, picking up where they stopped
  if the Wi-Fi drops.
- Save words and go over them as flashcards, bookmark pages, search inside a book, and share a highlight as a
  quote card.
- Export your highlights and notes as Markdown, and back everything up to a file.
- Works away from home too, over something like Tailscale.
- Runs on old Amazon Fire tablets and on new phones alike.

## What it isn't

Katana isn't trying to be a reader with every feature piled in. It does what I need, and I'll keep fixing what
breaks. If you like it but want something it doesn't do, fork it and make it yours, with whatever AI agent you like
to work with. That's pretty much how Katana started.

## Things to implement

- **Bookmark sync.** Bookmarks stay on the device for now; they should sync both ways with Grimmory, like
  highlights do.
- **Update checks without GitHub's limit.** The in-app updater asks GitHub's API, which allows 60 checks an hour per
  connection; it could read the website's version file instead.
- **"Add books" on an empty list** opens the Local library and drops the selection. Worth a look.

## Build it yourself

You need JDK 21 and the Android SDK (Android Studio brings both). Run `./gradlew :app:assembleRelease`
(`gradlew.bat` on Windows) and the APKs land in `app/build/outputs/katana-apks/`.

## License

Built on [yabai](https://github.com/bostannnn/yabai) by bostannnn. Katana is free software under the
[GNU GPL v3](LICENSE). Fonts, icons and libraries are listed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
