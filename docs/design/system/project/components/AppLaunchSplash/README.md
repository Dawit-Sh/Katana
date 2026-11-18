# Launch splash · Katana

Approved 2026-11-18, replacing upstream's Book hop. Native launch artwork belongs to the Android launch resources; it runs before Compose can draw.

The mark is the launcher icon's own drawing: the ink katana close up on its four-lobed guard, cut to a circle inside a hand-inked ring, so the cut ends of the handle, cord and blade read as a round frame. It is one ink shape in `?attr/launchMarkColor`, so every saved theme's splash shows it in that theme's primary on that theme's background.

On Android 12 and later one glint (a four-point sparkle) flares near the collar, runs down the blade's cutting edge, turns a little and fades, all within 820 ms, then the plain mark stays. A halo in `?attr/launchPaperColor` (always the splash background) leaves a thin gap where the glint crosses the blade's ink. Every channel ends where it started, so an app that is ready early, or animations turned off, always leaves the plain mark. Run once; never repeat or hold an already-ready app just to finish it. Android 9–11 show the static mark, because the system splash compat implementation cannot animate its starting-window icon.

The whole drawing stays inside Android's 192 dp splash icon circle: the mark reaches 90.3 dp from the centre, the glint 65.2 dp, against the 96 dp limit.

`art/katana-mark.svg` and `art/katana-glint.svg` are drawn outside the repo by Dawit's logo tools (`Universe/projects/katana/logo/tools/splash.py`, from the icon's `katana.py`). `../../../tools/gen_launch_splash.py` converts them into the static Android vector and the Android 12+ animated vector, with the glint's motion. Regenerate with `python3 docs/design/system/tools/gen_launch_splash.py`; verify the generated resources, the mask and the return to rest with `--check`.

`art/book-hop/` keeps upstream's Book hop layers (approved 2026-11-01) for reference; nothing uses them now.

See [verification results](verification.md) for build, mask and resource checks.
