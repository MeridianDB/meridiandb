#!/usr/bin/env python3
"""Export the committed MeridianDB SVG artwork with local librsvg 2.61.1."""

import copy
from pathlib import Path
import shutil
import struct
import subprocess
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "docs" / "assets" / "brand"
SVG = "http://www.w3.org/2000/svg"
ET.register_namespace("", SVG)


def write_svg(path, root):
    ET.indent(root, space="  ")
    path.write_text(ET.tostring(root, encoding="unicode") + "\n")


def render(renderer, source, destination, width, height):
    subprocess.run(
        [renderer, "--width", str(width), "--height", str(height),
         "--output", str(destination), str(source)],
        check=True,
    )
    data = destination.read_bytes()
    if data[:8] != b"\x89PNG\r\n\x1a\n" or struct.unpack(">II", data[16:24]) != (width, height):
        raise RuntimeError(f"Invalid PNG dimensions: {destination.name}")
    print(f"{destination.relative_to(ROOT)}: {width} x {height}, {len(data):,} bytes")


def main():
    renderer = shutil.which("rsvg-convert")
    if not renderer:
        raise SystemExit("Install librsvg 2.61.1 to provide rsvg-convert; see internal/brand.md.")
    version = subprocess.check_output([renderer, "--version"], text=True).strip()
    if version != "rsvg-convert version 2.61.1":
        raise SystemExit(f"Expected librsvg 2.61.1, found {version}; review renderer changes first.")

    parser = ET.XMLParser(target=ET.TreeBuilder(insert_comments=True))
    wordmark_path = ASSETS / "meridiandb-wordmark.svg"
    wordmark = ET.parse(wordmark_path, parser=parser).getroot()
    icon = ET.parse(ASSETS / "meridiandb-icon.svg").getroot()
    slot = wordmark.find(f"{{{SVG}}}g[@id='brand-icon']")
    if slot is None:
        raise RuntimeError("Wordmark is missing its brand-icon group")
    for child in list(slot):
        slot.remove(child)
    for child in icon:
        if child.tag not in {f"{{{SVG}}}title", f"{{{SVG}}}desc"}:
            slot.append(copy.deepcopy(child))
    write_svg(wordmark_path, wordmark)
    # Preserve the original public favicon URL for previously cached pages.
    shutil.copyfile(ASSETS / "meridiandb-icon.svg", ASSETS.parent / "favicon.svg")

    dark = copy.deepcopy(wordmark)
    dark.find(f"{{{SVG}}}desc").text = (
        "MeridianDB wordmark with the twin-arch m symbol. "
        "White lettering and mint DB on a transparent background for dark surfaces."
    )
    for path in dark.findall(f"{{{SVG}}}g[@id='wordmark']/{{{SVG}}}path"):
        path.set("fill", {"#102c38": "#f0fdfa", "#0f766e": "#5eead4"}[path.get("fill")])
    dark_path = ASSETS / "meridiandb-wordmark-dark.svg"
    write_svg(dark_path, dark)

    for size in [32, 180, 512, 1024]:
        name = "meridiandb-github-1024.png" if size == 1024 else f"meridiandb-icon-{size}.png"
        render(renderer, ASSETS / "meridiandb-icon.svg", ASSETS / name, size, size)
    for source in [wordmark_path, dark_path]:
        render(renderer, source, source.with_suffix(".png"), int(wordmark.get("width")) * 2, 512)


if __name__ == "__main__":
    main()
