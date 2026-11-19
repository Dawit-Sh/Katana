"""Writes the newest release into the download site before it's published.

The Website workflow puts the release in site/latest.json, then runs this: the button's text gets the version
("Download v1.0.4") and the page gets the APK links, so the page shows them from its first frame with nothing
to load. Usage: python3 bake_site.py site/latest.json site/index.html
"""

import json
import sys

PLACEHOLDER_LABEL = "Download the latest version"
PLACEHOLDER_DATA = "/*KATANA_LATEST*/null"


def main(latest_path: str, page_path: str) -> None:
    with open(latest_path, encoding="utf-8") as file:
        latest = json.load(file)
    version = str(latest.get("version") or "").strip()
    if not version or not latest.get("apks"):
        sys.exit("latest.json has no version or APKs; leaving the page as it is")
    with open(page_path, encoding="utf-8") as file:
        page = file.read()
    if PLACEHOLDER_LABEL not in page or PLACEHOLDER_DATA not in page:
        sys.exit("the page's placeholders are missing")
    page = page.replace(PLACEHOLDER_LABEL, f"Download v{version}", 1)
    # "</" can't appear inside the inline script, so escape it in the data.
    page = page.replace(PLACEHOLDER_DATA, json.dumps(latest, separators=(",", ":")).replace("</", "<\\/"), 1)
    with open(page_path, "w", encoding="utf-8") as file:
        file.write(page)
    print(f"Baked v{version} into {page_path}")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
