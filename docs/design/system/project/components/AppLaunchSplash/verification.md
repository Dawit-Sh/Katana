# Katana splash verification · 2026-11-18

- `python3 docs/design/system/tools/gen_launch_splash.py --check` passes: 820 ms, 42 keyframes, mark radius 90.33 / 96 dp, glint radius 65.24 / 96 dp (the whole interpolated motion, checked every 5 ms), and every glint channel returns to its starting pose.
- The release APK builds (`:app:assembleRelease`, Windows, JDK 21).
- The splash unit checks pass: `AppLaunchSplashResourceTest` (3: a static vector before Android 12 and one finite animated target after, the mark in `launchMarkColor` with the glint's halo in `launchPaperColor` drawn over it, the glint hidden at rest, every channel back to its start) and `AppLaunchSplashThemeTest` (4, unchanged: every theme style keeps the icon, colours and the 820 ms duration).
- The review page showed the mark on all eleven reading themes at 1 px per dp, and the glint frame by frame, from the same art and the same `motion()`; Dawit approved it on 2026-11-18.
- `LaunchSplashDrawableTest` (instrumented) was updated for the new art (solid ink on the guard at 140, 108; the glint's starting point at 136, 169 clear at rest) but not run: the app's instrumentation sources still fail to compile for the unrelated `LibraryTopBarSearchTest` (see the Book hop notes of 2026-11-01).
- Not checked on a device yet: the Android 12+ glint (no Android 12 device here) and the static mark on the Fire 7 (Android 9).

## Book hop verification · 2026-11-01 (upstream, replaced)

- The normal app debug APK builds successfully with package `com.example.unifiedreader`.
- Seven focused app unit checks pass: launch palette parity, finite AVD targets, opaque layers, and return to rest.
- Thirteen focused component checks pass: splash readiness hold, layout, Material imports, and design rules.
- `python3 docs/design/system/tools/gen_launch_splash.py --check` passes: 820 ms, 90 keyframes, maximum conservative masked radius 94.77 / 96 dp.
- The native Android 15 test passes for every launch palette: the animated vector inflates and the eyes render opaque on a transparent canvas.
- The full existing app instrumentation sources fail to compile because `LibraryTopBarSearchTest` still passes removed `seriesTitle` / `downloadBadgeCount` arguments and omits `contextTitle` / `downloadsButtonState`.
