# Katana (Android reader, built on yabai)

Katana is a personal fork of yabai (https://github.com/bostannnn/yabai). Most of this file is upstream's and
still holds; the build, signing and device notes further down describe upstream's macOS setup.
No releases or APKs are published from this repository while upstream has no license.

Kotlin + Jetpack Compose reader for EPUB/PDF/CBZ/CBR (plus MOBI, AZW, AZW3, FB2, DjVu) from Kavita, Grimmory,
Stump, or local files.
Package `com.example.unifiedreader`. Hilt DI, Room DB, Ktor networking, Readium (EPUB),
`PdfRenderer` (PDF). Ownership map in `docs/architecture/ARCHITECTURE_RULES.md`.
Screens are built from the component kit in `core:ui-components` (`AppPanel`, `AppDialog`,
`AppButton`, `AppIcons`, ...), never from raw Material `Card`/`Surface`/`Button` or the Material
Icons artifacts; a unit test enforces this. UI font: bundled Manrope; icons: Material Symbols Rounded.

## Read before changing code

- `docs/architecture/ARCHITECTURE_RULES.md` is binding: keep `app` thin, no side effects in Hilt providers,
  source-specific logic in per-source classes, don't grow the listed hotspot files, and
  performance changes need measured proof. It also holds the DB-paging pattern and the
  library presentation-coherence rules. `docs/architecture/THEME_CUSTOMIZATION_GUIDE.md` explains themes.
- `docs/design/system/` is the design source of truth; see "UI work" below.
- `docs/history/` holds upstream yabai's plans and handoffs, up to the fork (2026-11-18). They explain why the
  code is the way it is:
  - `LIBRARY_DB_PAGING_PLAN.md`: the library paging/performance work.
  - `UI_REDESIGN_PLAN.md`: the visual redesign, what was done and the design decisions.
  - `APP_REVIEW.md`: the 2026-10-18 app review, its fix list and product decisions.
  - `LISTS_PLAN.md`: the lists feature (your own lists and reading orders).
  - `UI_POLISH_PLAN.md`: the 2026-11-01 polish pass (Lists, Journal, Filters, Pages sort, Mark unread).
  - `CODE_PRACTICES_PLAN.md`: the 2026-11-05 code-practices review and the decisions left open.
  - `CURRENT_STATUS.md`, `ROADMAP.md` and the other plans are older still (April-July 2026).

## UI work (design system)

`docs/design/system/` is binding for every screen: `project/layout.md` (the frame), `project/titles.md`,
`project/tokens.json`, and a guideline plus a mock per kit component and screen. Its README links the
browsable Design System artifact; the repo copy is the canonical one. When the app and the system disagree,
the app is wrong.

- A design decision changes the system first: edit `docs/design/system/` (component folders come from
  `tools/gen_components.py` and `tools/gen_screens.py`), republish the changed files to the artifact, then
  change the code.
- Margins and gaps come from `AppLayout`: `appGutter()` (16 dp on phones, 24 from 600 dp), content at
  `contentTopInset + AppLayout.contentTop`, `inset` 20 inside panels, sheets and rows. No literal dp for these.
- No screen-name titles: the rail or drawer names the screen; the bar names only a page you opened.
  `AppScreenTitle` is only for a title that is the content (a series, a list, a first-launch question).
- Reuse the kit: `AppSideSheet` (one width) with `AppPanelHeader`, `AppGroupLabel` on pages and
  `AppSheetLabel` in sheets, `AppSheetDivider`, `AppSettingsRow`, `AppBookRow`, `AppStatusPill`,
  `AppProgressBar`, `AppButtonSpinner`. A missing piece goes into the kit and the system, not a feature file.
- Enforced by rule tests in `core:ui-components` (run in CI): `FeatureMaterialImportRuleTest`,
  `AppLayoutRuleTest`, `DesignSystemRuleTest`. The last one ratchets literal dp in paddings and corner radii per
  file against `core/ui-components/src/test/design-baselines/`: a file may not gain any. After removing some,
  lower the baselines with `./gradlew :core:ui-components:testDebugUnitTest -PupdateDesignBaselines`; never
  raise them to make a test pass.

## Build and test

The agent shell doesn't export `JAVA_HOME`; set it first (Android Studio's bundled JDK 21). A
`.claude/worktrees/*` checkout has no `local.properties`, so also set `ANDROID_HOME` there:

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export ANDROID_HOME=~/Library/Android/sdk_new
./gradlew :app:assembleDebug
./gradlew testDebugUnitTest                      # the Android modules, ~1125 tests
./gradlew :core:model:test :core:network:test :data:grimmory:test :data:kavita:test :data:stump:test   # the JVM modules, ~123
./gradlew :feature:library:testDebugUnitTest     # one module
```

- `testDebugUnitTest` skips `core:model`, `core:network`, `data:grimmory`, `data:kavita` and `data:stump`, which are
  plain Kotlin modules with a `test` task; run both lines for the full suite (CI runs both).
- CI also runs `./gradlew detekt lintDebug`. Each module's `detekt-baseline.xml` and `lint-baseline.xml` hold the
  findings from before they ran (2026-11-06): new code must pass. Regenerate a baseline (`:module:detektBaseline`,
  `:module:updateLintBaseline`) only after fixing findings, never to hide new ones. Rules: `config/detekt/detekt.yml`.
- Build settings shared by every module (SDK levels, Java 17 for all, Compose, detekt, lint) live in the convention
  plugins in `build-logic/convention` (`yabai.android.library`, `yabai.android.compose`, `yabai.jvm.library`,
  `yabai.android.application`). A module's own build file holds only its namespace and dependencies.
- Work that outlives a screen runs on the one `@ApplicationScope` scope (`AppCoroutinesModule`); a class takes a
  `CoroutineScope` parameter, never builds `CoroutineScope(SupervisorJob() + …)` itself. Tests pass `backgroundScope`
  (it doesn't run during `advanceUntilIdle`; pass the test's own scope when a test advances and expects the work).
- Inside suspend code, catch with `runSuspendCatching`, never `runCatching` (it swallows cancellation);
  `SuspendRunCatchingRuleTest` in `core:model` enforces it.

- `app` tests also compile `app/src/sharedTest` (shared with `androidTest`); the ViewModel
  test factory is `createViewModel()` in `AppViewModelTestSupport.kt`.
- A test that creates a ViewModel under `Dispatchers.setMain` must call `clearViewModel()`
  before `resetMain()`, or its collectors leak and fail an unrelated `runTest` later.
- In Robolectric tests, don't suspend inside `runBlocking` on the main thread waiting for work
  that resumes on Main (anything behind a `Dispatchers.IO` hop): the looper can't run and the
  suite hangs with no timeout. Poll while idling the looper instead (`waitForUiState { ... }`).
  If `testDebugUnitTest` stops progressing, `jstack` the Gradle Test Executor JVM.
- Room schema is v39 (`core/database/.../UnifiedReaderDatabase.kt`), no destructive fallback: every version bump
  needs a migration. `exportSchema = true` writes each version to `core/database/schemas/`: commit the new file
  with the bump (`UnifiedReaderDatabaseSchemaTest` checks every version from 36 has one) and add a
  `MigrationTestHelper` case for the new migration there. Run the tests twice after a bump: the first build
  writes the file after the test assets are merged.

## Release build (signed)

Release builds run R8 (`app/proguard-rules.pro`; `-dontobfuscate`, so crash logs keep real names) and
ship a Baseline Profile (`app/src/main/baselineProfiles/`), made on the Fire 7 from Android's own record
of what runs (`tools/baseline-profile/profman_to_baseline_prof.py` says how). A screen that breaks only in
a release build usually needs a keep rule. The Fire runs release builds only: a debug build can't use
compiled code on Android 9 and starts ~6 times slower.

`app/build.gradle.kts` signs release builds from `ANDROID_SIGNING_*` env vars (CI uses the
same contract). Locally, the passwords live in the macOS Keychain and a helper outside the
repo supplies them without printing them:

```bash
~/.config/yabai/release-signing.sh run ./gradlew :app:assembleRelease
~/.config/yabai/release-signing.sh check         # re-verify the saved passwords
```

Keystore `~/yabai.jks`, alias `key0`. The release cert SHA-256 must be
`b26aaf3bed6351e72b8bc6e6d7d4655848902e22eedc7d512bbb6680597a5baa`; check with
`apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk`
(`~/Library/Android/sdk_new/build-tools/36.1.0/apksigner`).

## GitHub Actions

- `ci.yml` runs the unit tests, detekt and lint on every push and on PRs to `fire7` (30 min job timeout).
  It only builds and tests; nothing is published.
- Upstream's `release-apk.yml` (signed APK and a GitHub Release on every version tag) and its
  `.github/scripts/release-notes.sh` were removed on 2026-11-19: Katana publishes no releases or APKs while
  yabai has no license (and DjVuLibre is GPL, see `THIRD_PARTY_NOTICES.md`). Git history keeps both.

## In-app updater (`feature:updates`)

- Checks `Dawit-Sh/Katana`'s latest GitHub release (there are none, see above; once a day after the library
  appears, or from Settings › About › Version), downloads the release's `.apk`, checks GitHub's SHA-256, the package
  name, the signing certificate and a higher `versionCode`, then installs through Android's
  `PackageInstaller`. So a release must keep the release key and raise `versionCode`.
- Debug builds never check by themselves, and their button stops at "This copy can't update
  itself" (debug key). Testing the install step needs release-signed builds on both sides.

## Device

- SDK `~/Library/Android/sdk_new`; adb is `~/Library/Android/sdk_new/platform-tools/adb`
  (not on PATH). Emulator AVD `yabai_settings_35` (Android 15, 1200x1920 tablet; RuntimeShader works):
  `ANDROID_SDK_ROOT=~/Library/Android/sdk_new ~/Library/Android/sdk_new/emulator/emulator -avd yabai_settings_35 -port 5556 -no-snapshot -no-boot-anim -no-audio -gpu host`.
  Debug builds are fine there; judging feel and performance still needs the user's tablet.
  It is the only AVD and sessions share it: check `adb -s emulator-5556 shell dumpsys package com.example.unifiedreader | grep lastUpdateTime`
  before installing over someone else's build. A second one is a copy of its `config.ini` and `.ini` under a new name
  (booted with `-wipe-data`), and needs about 7.3 GB free disk for its 6 GB data partition.
- The installed app is a release build. Update it with `adb install -r` of a **signed release**
  APK, which keeps its data. A debug build has a different signature and would need an
  uninstall, which wipes the library and journal: never uninstall.
- Never install a build with a lower Room version than the device has (no downgrade path).
- On the OnePlus tablet, streaming `logcat` is suppressed; use `adb logcat -d`. `screenrecord`
  is blocked by SELinux. Library timings log under the `LibraryPerf` tag
  (`LibraryPerfLog.ENABLED`, `LibraryShelvesPerfLog.ENABLED`, both on by choice).
