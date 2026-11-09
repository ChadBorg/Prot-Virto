#!/usr/bin/env python3
"""Pulls Capri's renderer + art from upstream (mochiiwuff/capri-desktop, pinned) into the Android
assets, fetches her fonts from npm, and layers the Thor additions (web/) on top.

Nothing from upstream is committed here: run this before building (CI does).
    python3 capri-thor/tools/sync_upstream.py
"""
from __future__ import annotations

import io
import json
import shutil
import subprocess
import sys
import tarfile
import tempfile
import urllib.request
from pathlib import Path

UPSTREAM = "https://github.com/mochiiwuff/capri-desktop"
COMMIT = "c011764ca1fdbd191925321e3aaa8af5b84babe8"  # v0.5.2
FONTS = {
    "@fontsource/chakra-petch": "5.2.5",
    "@fontsource/jetbrains-mono": "5.2.5",
}
ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "app/src/main/assets/capri"
WEB = ROOT / "web"


def run(*cmd: str, cwd: Path | None = None) -> None:
    subprocess.run(cmd, cwd=cwd, check=True)


def fetch_upstream(dest: Path) -> None:
    run("git", "init", "-q", str(dest))
    run("git", "remote", "add", "origin", UPSTREAM, cwd=dest)
    run("git", "fetch", "-q", "--depth", "1", "origin", COMMIT, cwd=dest)
    run("git", "checkout", "-q", "FETCH_HEAD", cwd=dest)


def fetch_font(pkg: str, version: str) -> None:
    meta = json.load(urllib.request.urlopen(f"https://registry.npmjs.org/{pkg}/{version}", timeout=60))
    data = urllib.request.urlopen(meta["dist"]["tarball"], timeout=120).read()
    target = OUT / "node_modules" / pkg / "files"
    target.mkdir(parents=True, exist_ok=True)
    with tarfile.open(fileobj=io.BytesIO(data), mode="r:gz") as tar:
        for m in tar.getmembers():
            name = m.name.split("/", 1)[-1]  # strip "package/"
            # only the woff2 files capri.css uses (latin + cyrillic)
            if name.startswith("files/") and name.endswith(".woff2") and ("-latin-" in name or "-cyrillic-" in name) and "-ext-" not in name:
                f = tar.extractfile(m)
                if f:
                    (target / Path(name).name).write_bytes(f.read())


def patch(path: Path, old: str, new: str) -> None:
    text = path.read_text("utf-8")
    if old not in text:
        sys.exit(f"patch failed: {path.name} no longer contains {old[:60]!r} (upstream changed?)")
    path.write_text(text.replace(old, new, 1), "utf-8")


def main() -> None:
    if OUT.exists():
        shutil.rmtree(OUT)
    OUT.mkdir(parents=True)
    with tempfile.TemporaryDirectory() as tmp:
        up = Path(tmp) / "capri"
        fetch_upstream(up)
        shutil.copytree(up / "src/renderer", OUT / "src/renderer")
        shutil.copytree(up / "assets", OUT / "assets")
        shutil.copy(up / "LICENSE", OUT / "LICENSE")
    for pkg, version in FONTS.items():
        fetch_font(pkg, version)

    js = OUT / "src/renderer/js"
    for f in WEB.glob("*.js"):
        shutil.copy(f, js / f.name)

    index = OUT / "src/renderer/index.html"
    # the Android host bridge goes in before her app; the Thor module after it
    patch(index, '<script type="module" src="js/app.js"></script>',
          '<script src="js/android.js"></script>\n  <script type="module" src="js/app.js"></script>\n'
          '  <script type="module" src="js/thor.js"></script>')
    patch(index, "<html lang=\"fr\">", "<html lang=\"en\">")
    # a touch screen: no hover, no text selection, no callouts on long-press
    patch(OUT / "src/renderer/styles/capri.css", "@font-face {",
          "html, body { -webkit-user-select: none; user-select: none; -webkit-touch-callout: none; "
          "-webkit-tap-highlight-color: transparent; }\n.panel__input { -webkit-user-select: text; user-select: text; }\n@font-face {")
    print(f"Capri synced from {UPSTREAM}@{COMMIT[:8]} into {OUT.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
