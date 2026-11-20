#!/usr/bin/env python3
"""Builds Katana's English dictionary pack from an Open English WordNet WNDB release.

    python -I tools/build-dictionary.py <oewn folder> <out.sqlite> [--json dictionary.json --url URL]

<oewn folder> holds the release's data.*, index.* and *.exc files (english-wordnet-2025.zip, unzipped).
The pack is a plain SQLite file the app opens read-only (feature/dictionary, DictionaryPack.kt):

    meta(key, value)                        format, edition, source, licence
    synsets(id, definition, example)        one gloss per WordNet synset
    senses(word, pos, rank, synset)         every lemma's senses, rank 0 = most common first
    exceptions(form, pos, lemma)            WordNet's irregular forms (geese -> goose, ran -> run)

Words are lowercase with spaces (WordNet's underscores replaced); pos is n, v, a (adjectives and
satellites) or r. The regular endings (morphy's detachment rules) live in the app, not the pack.
With --json it also writes the small file published next to the pack: its URL, size and SHA-256.

Open English WordNet is CC BY 4.0 (https://github.com/globalwordnet/english-wordnet).
"""

import argparse
import hashlib
import json
import os
import re
import sqlite3
import sys

FORMAT = 1
POS_FILES = {"n": "noun", "v": "verb", "a": "adj", "r": "adv"}
POS_DIGIT = {"n": 1, "v": 2, "a": 3, "r": 4}
EXAMPLE = re.compile(r'"([^"]*)"')


def synset_id(pos, offset):
    return int(offset) * 10 + POS_DIGIT[pos]


def split_gloss(gloss):
    """'draw air into the lungs; "I can breathe"; "..."' -> ('draw air into the lungs', 'I can breathe')."""
    gloss = gloss.strip()
    quote = gloss.find('"')
    definition = gloss if quote < 0 else gloss[:quote]
    definition = definition.strip().rstrip(";").strip()
    example = EXAMPLE.search(gloss)
    if not definition and example:
        definition, example = example.group(1), None
    return definition, (example.group(1).strip() if example else None)


def read_lines(path):
    with open(path, encoding="utf-8") as source:
        for line in source:
            # The licence header lines start with two spaces.
            if line.startswith("  ") or not line.strip():
                continue
            yield line.rstrip("\n")


def word_key(lemma):
    return lemma.replace("_", " ").lower()


def build(folder, out_path):
    if os.path.exists(out_path):
        os.remove(out_path)
    db = sqlite3.connect(out_path)
    db.executescript(
        """
        PRAGMA page_size = 4096;
        CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT NOT NULL);
        CREATE TABLE synsets(id INTEGER PRIMARY KEY, definition TEXT NOT NULL, example TEXT);
        CREATE TABLE senses(word TEXT NOT NULL, pos TEXT NOT NULL, rank INTEGER NOT NULL, synset INTEGER NOT NULL);
        CREATE TABLE exceptions(form TEXT NOT NULL, pos TEXT NOT NULL, lemma TEXT NOT NULL);
        """
    )
    synsets = 0
    for pos, name in POS_FILES.items():
        rows = []
        for line in read_lines(os.path.join(folder, "data." + name)):
            head, _, gloss = line.partition(" | ")
            offset = head.split(" ", 1)[0]
            definition, example = split_gloss(gloss)
            rows.append((synset_id(pos, offset), definition, example))
        db.executemany("INSERT INTO synsets VALUES (?, ?, ?)", rows)
        synsets += len(rows)

    senses = 0
    for pos, name in POS_FILES.items():
        rows = []
        for line in read_lines(os.path.join(folder, "index." + name)):
            fields = line.split()
            lemma, synset_count, pointer_count = fields[0], int(fields[2]), int(fields[3])
            offsets = fields[4 + pointer_count + 2:]
            if len(offsets) != synset_count:
                raise ValueError("index.%s: %s lists %d of %d synsets" % (name, lemma, len(offsets), synset_count))
            for rank, offset in enumerate(offsets):
                rows.append((word_key(lemma), pos, rank, synset_id(pos, offset)))
        db.executemany("INSERT INTO senses VALUES (?, ?, ?, ?)", rows)
        senses += len(rows)

    exceptions = 0
    for pos, name in POS_FILES.items():
        path = os.path.join(folder, name + ".exc")
        if not os.path.exists(path):
            continue
        rows = []
        for line in read_lines(path):
            fields = line.split()
            for lemma in fields[1:]:
                rows.append((word_key(fields[0]), pos, word_key(lemma)))
        db.executemany("INSERT INTO exceptions VALUES (?, ?, ?)", rows)
        exceptions += len(rows)

    db.executemany(
        "INSERT INTO meta VALUES (?, ?)",
        [
            ("format", str(FORMAT)),
            ("name", "English (Open English WordNet 2025)"),
            ("source", "https://github.com/globalwordnet/english-wordnet"),
            ("license", "CC BY 4.0, Open English WordNet team; WordNet 3.1 (c) Princeton University"),
        ],
    )
    db.executescript(
        """
        CREATE INDEX senses_word ON senses(word);
        CREATE INDEX exceptions_form ON exceptions(form);
        """
    )
    db.commit()
    db.execute("VACUUM")
    db.close()
    return synsets, senses, exceptions


def sha256(path):
    digest = hashlib.sha256()
    with open(path, "rb") as source:
        for block in iter(lambda: source.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("folder")
    parser.add_argument("out")
    parser.add_argument("--json", help="also write the published dictionary.json here")
    parser.add_argument(
        "--url",
        default="https://github.com/Dawit-Sh/Katana/releases/download/dict-en-1/katana-dictionary-en-1.sqlite",
    )
    args = parser.parse_args()
    synsets, senses, exceptions = build(args.folder, args.out)
    size = os.path.getsize(args.out)
    digest = sha256(args.out)
    print("%s: %d synsets, %d senses, %d exceptions" % (args.out, synsets, senses, exceptions))
    print("size %d bytes, sha256 %s" % (size, digest))
    if args.json:
        with open(args.json, "w", encoding="utf-8") as out:
            json.dump({"version": 1, "url": args.url, "sizeBytes": size, "sha256": digest}, out, indent=2)
            out.write("\n")


if __name__ == "__main__":
    sys.exit(main())
