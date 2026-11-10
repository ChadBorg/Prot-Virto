#!/usr/bin/env python3
"""Pulls Capri's renderer + art from upstream (mochiiwuff/capri-desktop, pinned) into the Android
assets, fetches her fonts from npm, and layers the Thor additions (web/) on top.

Nothing from upstream is committed here: run this before building (CI does).
    python3 capri-thor/tools/sync_upstream.py
"""
from __future__ import annotations

import io
import json
import math
import re
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


OKLCH = re.compile(r"oklch\(\s*([\d.]+%?)\s+([\d.]+%?)\s+([\d.]+)(?:deg)?\s*(?:/\s*([\d.]+%?))?\s*\)")


def _num(v: str, scale: float = 1.0) -> float:
    return float(v[:-1]) / 100 * scale if v.endswith("%") else float(v)


def oklch_to_rgb(m: re.Match) -> str:
    """oklch(L C H [/ a]) -> rgb()/rgba(), clipped to sRGB (Björn Ottosson's OKLab matrices)."""
    lightness, chroma, hue = _num(m[1]), _num(m[2], 0.4), math.radians(float(m[3]))
    a, b = chroma * math.cos(hue), chroma * math.sin(hue)
    l_ = (lightness + 0.3963377774 * a + 0.2158037573 * b) ** 3
    m_ = (lightness - 0.1055613458 * a - 0.0638541728 * b) ** 3
    s_ = (lightness - 0.0894841775 * a - 1.2914855480 * b) ** 3
    linear = (
        4.0767416621 * l_ - 3.3077115913 * m_ + 0.2309699292 * s_,
        -1.2684380046 * l_ + 2.6097574011 * m_ - 0.3413193965 * s_,
        -0.0041960863 * l_ - 0.7034186147 * m_ + 1.7076147010 * s_,
    )

    def channel(x: float) -> int:
        x = min(max(x, 0.0), 1.0)
        x = 12.92 * x if x <= 0.0031308 else 1.055 * x ** (1 / 2.4) - 0.055
        return round(x * 255)

    r, g, bl = (channel(x) for x in linear)
    if m[4] is None:
        return f"rgb({r}, {g}, {bl})"
    return f"rgba({r}, {g}, {bl}, {round(_num(m[4]), 3)})"


def downlevel_colors(root: Path) -> int:
    """The Thor ships Android System WebView 109 (AOSP, not updated by the Play Store), and oklch() needs
    Chrome 111: every upstream colour was dropped, so her speech panel had no plate, no outline and
    unreadable default-black text. Rewrite oklch() as rgb() in the renderer's CSS and JS."""
    count = 0
    for f in list(root.rglob("*.css")) + list(root.rglob("*.js")):
        text = f.read_text("utf-8")
        new, n = OKLCH.subn(oklch_to_rgb, text)
        if n:
            f.write_text(new, "utf-8")
            count += n
        if "oklch(" in new:
            sys.exit(f"{f.name}: an oklch() form this script can't convert (upstream changed?)")
    return count


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
    # Crossing to the other screen: moving her window to another display takes a moment on Android,
    # and a slow arrival fell straight down just outside the edge she came in by (seen on the Thor:
    # standing past the right edge, 13 px of her showing). Always come in with some speed, inward.
    patch(OUT / "src/renderer/js/capri.js",
          "f.c = { x: side > 0 ? -r.maxX - 4 : this.view.w - r.minX + 4, y: clamp(there.y, 80, this.view.floor - 80) };",
          "f.c = { x: side > 0 ? -r.maxX - 4 : this.view.w - r.minX + 4, y: clamp(there.y, 80, this.view.floor - 80) };\n"
          "    f.v.x = side * Math.max(Math.abs(f.v.x), 560); // Thor: always far enough in\n"
          "    f.v.y = Math.min(f.v.y, -120); // and a little up, not dropping at the edge")
    converted = downlevel_colors(OUT / "src/renderer")
    print(f"Capri synced from {UPSTREAM}@{COMMIT[:8]} into {OUT.relative_to(ROOT)} ({converted} oklch colours -> rgb)")


if __name__ == "__main__":
    main()
