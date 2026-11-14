#!/usr/bin/env python3
"""Publish an approved GitHub issue attachment into the Schnitt community data."""
import html
import io
import json
import os
import re
import sys
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DATA = ROOT / "docs/community/data"
INDEX = DATA / "index.json"
ATTACHMENT = re.compile(r"https://github\.com/user-attachments/(?:assets|files)/[^\s)>\]]+")
HEADING = re.compile(r"^###\s+(.+?)\s*$", re.M)


def value(body: str, name: str) -> str:
    match = re.search(r"^###\s+" + re.escape(name) + r"\s*\n+([\s\S]*?)(?=\n###\s+|\Z)", body, re.M | re.I)
    return html.unescape(match.group(1).strip()) if match else ""


def slug(text: str) -> str:
    return re.sub(r"[^a-z0-9]+", "-", text.lower()).strip("-")[:60] or "community-file"


def get(url: str) -> bytes:
    request = urllib.request.Request(url, headers={"User-Agent": "Schnitt community publisher"})
    with urllib.request.urlopen(request, timeout=60) as response:
        return response.read(8 << 20)


def xml_from(data: bytes) -> bytes | None:
    text = data.decode("utf-8", "replace").lstrip("\ufeff")
    if "<scene" in text or "<project" in text:
        return text.encode("utf-8")
    if data[:2] == b"PK":
        import zipfile
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            for name in archive.namelist():
                if not name.lower().endswith((".xml", ".absproj")):
                    continue
                inner = archive.read(name).decode("utf-8", "replace")
                if "<scene" in inner or "<project" in inner:
                    return inner.encode("utf-8")
    return None


def main() -> int:
    body = os.environ.get("ISSUE_BODY", "")
    title = value(body, "Title") or os.environ.get("ISSUE_TITLE", "").replace("Community submission:", "").strip() or "Community file"
    author = value(body, "Your name or GitHub handle") or os.environ.get("ISSUE_AUTHOR", "Schnitt community")
    kind = value(body, "What are you sharing?").lower()
    kind = "xml" if "xml" in kind else "preset"
    links = ATTACHMENT.findall(body)
    if not links:
        print("No GitHub attachment found in the issue body.", file=sys.stderr)
        return 1
    payload = None
    for link in links:
        try:
            payload = xml_from(get(link))
        except Exception as error:
            print("Download failed:", error, file=sys.stderr)
        if payload:
            break
    if not payload:
        print("No readable scene XML or project zip was attached.", file=sys.stderr)
        return 1
    DATA.mkdir(parents=True, exist_ok=True)
    folder = DATA / ("xml" if kind == "xml" else "presets")
    folder.mkdir(parents=True, exist_ok=True)
    name = slug(title) + ".xml"
    path = folder / name
    path.write_bytes(payload)
    entries = json.loads(INDEX.read_text()) if INDEX.is_file() else []
    entries = [e for e in entries if e.get("id") != name]
    entries.append({
        "id": name,
        "title": title,
        "author": author,
        "type": kind,
        "url": ("xml/" if kind == "xml" else "presets/") + name,
    })
    entries.sort(key=lambda e: (e.get("type", ""), e.get("title", "").lower()))
    INDEX.write_text(json.dumps(entries, ensure_ascii=False, indent=2) + "\n")
    print("Published", path.relative_to(ROOT))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
