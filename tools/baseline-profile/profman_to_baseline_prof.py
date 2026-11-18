"""Turns the profile Android recorded on a device into Katana's Baseline Profile.

Android 9 records which methods and classes an app runs (its JIT profile). `cmd package dump-profiles`
writes that profile as text with Java signatures; this script rewrites the entries in the Baseline
Profile format (HSPLpkg/Cls;->name(params)ret). Made for the Fire 7, where macrobenchmark can't collect
a profile (Android 9 without root). First made on 2026-11-19 from Dawit's own library (Grimmory,
6,216 books): cold starts, the library, Lists, Bookmarks, Journal, Settings, a book page.

How to collect it again:
  1. Build a release without R8 (isMinifyEnabled = false for the run, so names are the source's),
     install it with `adb install -r`, open it once.
  2. adb shell cmd package compile -m quicken -f com.example.unifiedreader   (runs from the JIT, so all is recorded)
  3. Use the app: several cold starts, then the screens to cover; leave it open ~50 s so Android saves.
  4. adb shell cmd package dump-profiles com.example.unifiedreader
     adb pull /data/misc/profman/com.example.unifiedreader-primary.prof.txt dump.txt
  5. python tools/baseline-profile/profman_to_baseline_prof.py dump.txt > app/src/main/baselineProfiles/baseline-prof.txt
     python tools/baseline-profile/profman_to_baseline_prof.py --startup dump.txt > app/src/main/baselineProfiles/startup-prof.txt
     (several dumps can be given; they are merged)

Only the recorded part ("=== profile ===") is read: the reference part holds what was compiled before,
mostly the libraries' own Baseline Profiles, which the build adds by itself. Every method that ran is
marked hot (H), because Android 9 compiles only hot methods ahead of time; S and P say whether it ran
during start-up or after. With --startup only what ran during start-up is written (and its classes):
R8 puts that code together at the front of the dex files, so a cold start reads fewer pages.
"""

import sys

PRIMITIVES = {
    "void": "V",
    "boolean": "Z",
    "byte": "B",
    "char": "C",
    "short": "S",
    "int": "I",
    "long": "J",
    "float": "F",
    "double": "D",
}

# Start-up code written after the last collection, added by hand so it is compiled too; it can go once
# a new collection has recorded it.
EXTRA_RULES = (
    "HSPLcom/example/unifiedreader/core/model/ItemMetadataKt;->**(**)**",
    "HSPLcom/example/unifiedreader/core/model/ReadableItem;->**(**)**",
    "HSPLcom/example/unifiedreader/core/model/ReadableItem$**;->**(**)**",
    "HSPLcom/example/unifiedreader/core/model/AuthorOrderEntry;->**(**)**",
    "HSPLcom/example/unifiedreader/core/model/LibraryFiltersKt;->**(**)**",
    "HSPLcom/example/unifiedreader/ui/components/AppStartupLog;->**(**)**",
)

# Code that is not ours to profile, or that only tests and tooling use.
SKIPPED_PREFIXES = (
    "Ldalvik/",
    "Ljava/",
    "Ljavax/",
    "Landroid/",
    "Lsun/",
    "Lorg/junit/",
)


def descriptor(java_type: str) -> str:
    java_type = java_type.strip()
    dims = 0
    while java_type.endswith("[]"):
        dims += 1
        java_type = java_type[:-2]
    base = PRIMITIVES.get(java_type) or "L" + java_type.replace(".", "/") + ";"
    return "[" * dims + base


def method_rule(entry: str) -> tuple[str, str] | None:
    """('Lpkg/Cls;', 'Lpkg/Cls;->name(params)ret') for 'ret pkg.Cls.name(params)[caches]'."""
    entry = entry.strip().rstrip(",").strip()
    if not entry:
        return None
    open_paren = entry.index("(")
    close_paren = entry.index(")", open_paren)
    head = entry[:open_paren]
    params = entry[open_paren + 1:close_paren]
    return_type, qualified = head.split(" ", 1)
    owner, name = qualified.rsplit(".", 1)
    owner_descriptor = descriptor(owner)
    param_descriptors = "".join(descriptor(p) for p in params.split(",") if p.strip())
    return owner_descriptor, f"{owner_descriptor}->{name}({param_descriptors}){descriptor(return_type)}"


def read_dump(path: str, methods: dict[str, set[str]], classes: set[str]) -> None:
    in_recorded = False
    section = None
    with open(path, encoding="utf-8", errors="replace") as dump:
        for line in dump:
            if line.startswith("=== profile"):
                in_recorded = True
                continue
            if line.startswith("==="):
                in_recorded = False
                continue
            if not in_recorded:
                continue
            stripped = line.strip()
            if not line.startswith("\t\t"):
                if stripped.endswith(":"):
                    section = stripped[:-1]
                continue
            entry = stripped.rstrip(",").strip()
            if section == "classes":
                classes.add(descriptor(entry))
                continue
            if "(" not in entry:
                continue
            # Inline caches ("[{0:(3,8870)}]") follow the signature.
            if "[" in entry[entry.rindex(")"):]:
                entry = entry[:entry.rindex("[")]
            parsed = method_rule(entry)
            if parsed is None:
                continue
            _, rule = parsed
            flags = methods.setdefault(rule, set())
            flags.add("H")
            if section == "startup methods":
                flags.add("S")
            elif section == "post startup methods":
                flags.add("P")


def main(args: list[str]) -> None:
    startup_only = "--startup" in args
    paths = [arg for arg in args if arg != "--startup"]
    methods: dict[str, set[str]] = {}
    classes: set[str] = set()
    for path in paths:
        read_dump(path, methods, classes)
    lines = []
    startup_classes = set()
    for rule, flags in methods.items():
        if rule.startswith(SKIPPED_PREFIXES):
            continue
        if startup_only and "S" not in flags:
            continue
        startup_classes.add(rule.split("->", 1)[0])
        lines.append("".join(flag for flag in "HSP" if flag in flags) + rule)
    for class_descriptor in (startup_classes if startup_only else classes | startup_classes):
        if class_descriptor.startswith(SKIPPED_PREFIXES):
            continue
        lines.append(class_descriptor)
    lines = sorted(lines, key=lambda l: l.lstrip("HSP"))
    if not startup_only:
        # R8 reads the start-up profile and takes no wildcards there.
        lines += list(EXTRA_RULES)
    sys.stdout.write("\n".join(lines) + "\n")


if __name__ == "__main__":
    main(sys.argv[1:])
