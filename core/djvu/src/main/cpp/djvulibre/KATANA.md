# DjVuLibre in Katana

- **What:** `libdjvu` from DjVuLibre **3.5.28**: every `.cpp` and `.h` of its `libdjvu/` folder, plus the
  release's `COPYING`, `COPYRIGHT` and `README`.
- **From:** the release tarball `djvulibre-3.5.28.tar.gz`,
  <https://sourceforge.net/projects/djvu/files/DjVuLibre/3.5.28/>.
- **License:** GPL-2.0-or-later (see `COPYING` and `COPYRIGHT`). An APK that contains it may only be given
  to others under the GPL, with the whole app's source; see `THIRD_PARTY_NOTICES.md` at the repository root.
- **Changes:** none. The files are byte-for-byte the release's.

How it is built (`../CMakeLists.txt`):

- `../config.h` is hand-written for Android and replaces the one `configure` generates (POSIX threads,
  `stdint`/`inttypes`, wide characters, `mmap`; no JPEG library, so JPEG-encoded chunks are skipped, and no
  iconv).
- Every file of `libdjvulibre_la_SOURCES` (from `libdjvu/Makefile.am`) is compiled as C++14 (the code
  predates C++17: `GBitmap.h` still uses `register`), with `-DHAVE_CONFIG_H -fexceptions -frtti -O2`, and
  warnings off for these files only.
- It links into `libkatana_djvu.so` together with `../djvu_jni.cpp`, which uses only the public C API in
  `ddjvuapi.h`. Symbols are hidden except the JNI entry points, so `--gc-sections` drops what the app never
  calls (the encoders, the PostScript writer, the document editor).

To update: replace the files here with a newer release's `libdjvu/*.cpp`, `libdjvu/*.h` and the three text
files, compare its `libdjvulibre_la_SOURCES` with the list in `../CMakeLists.txt` and its
`config/config.h.in` with `../config.h`, and update this note.
