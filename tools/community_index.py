#!/usr/bin/env python3
"""Rebuild the public community index from the uploaded preset and XML folders."""
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DATA = ROOT / "docs/community/data"
INDEX = DATA / "index.json"


def title(name: str) -> str:
    text = re.sub(r"[-_]+", " ", name.rsplit(".", 1)[0]).strip()
    return text.title() if text else "Community file"


def main() -> int:
    entries = []
    if INDEX.is_file():
        try:
            entries = json.loads(INDEX.read_text())
        except Exception:
            entries = []
    by_url = {e.get("url"): e for e in entries if e.get("url")}
    for kind, folder in (("preset", DATA / "presets"), ("xml", DATA / "xml")):
        for path in sorted(folder.glob("*")):
            if not path.is_file() or path.name.startswith("."):
                continue
            if path.suffix.lower() not in (".xml", ".absproj", ".zip"):
                continue
            rel = f"{kind}/{path.name}" if kind == "preset" else f"xml/{path.name}"
            old = by_url.get(rel, {})
            by_url[rel] = {
                "id": path.name,
                "title": old.get("title") or title(path.name),
                "author": old.get("author") or "Schnitt community",
                "type": kind,
                "status": old.get("status") or "approved",
                "url": rel,
            }
    result = sorted(by_url.values(), key=lambda e: (e.get("type", ""), e.get("title", "").lower()))
    DATA.mkdir(parents=True, exist_ok=True)
    INDEX.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
    print(f"community index: {len(result)} entries")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
