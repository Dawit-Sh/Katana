"""Writes Katana Desk's newest release into its page (site/desk/) before the site is published.

The Website workflow puts Katana Desk's release in site/desk/latest.json, then runs this: the button's text gets the version
("Download v0.1.0") and the page gets the file links, so the page shows them from its first frame with nothing
to load. Usage: python3 bake_desk.py site/desk/latest.json site/desk/index.html
"""

import json
import sys

PLACEHOLDER_LABEL = "Download the latest version"
PLACEHOLDER_DATA = "/*KATANA_DESK_LATEST*/null"


def main(latest_path: str, page_path: str) -> None:
    with open(latest_path, encoding="utf-8") as file:
        latest = json.load(file)
    version = str(latest.get("version") or "").strip()
    if not version or not latest.get("files"):
        sys.exit("latest.json has no version or files; leaving the page as it is")
    with open(page_path, encoding="utf-8") as file:
        page = file.read()
    if PLACEHOLDER_LABEL not in page or PLACEHOLDER_DATA not in page:
        sys.exit("the page's placeholders are missing")
    page = page.replace(PLACEHOLDER_LABEL, f"Download v{version}", 1)
    # "</" can't appear inside the inline script, so escape it in the data.
    page = page.replace(PLACEHOLDER_DATA, json.dumps(latest, separators=(",", ":")).replace("</", "<\\/"), 1)
    with open(page_path, "w", encoding="utf-8", newline="\n") as file:
        file.write(page)
    print(f"Baked v{version} into {page_path}")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
