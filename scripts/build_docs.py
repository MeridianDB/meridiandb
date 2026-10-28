#!/usr/bin/env python3
"""Build public documentation only after source and rendered QA pass."""

from pathlib import Path
import subprocess
import sys

from check_public_docs import configured_site_url, validate_site, validate_sources


def report(errors: list[str]) -> bool:
    for error in errors:
        print(error, file=sys.stderr)
    return bool(errors)


def main() -> int:
    root = Path(__file__).resolve().parents[1]
    if report(validate_sources(root)):
        return 1
    site_url = configured_site_url(root)
    result = subprocess.run(
        [sys.executable, "-m", "zensical", "build", "--strict", "--clean"], cwd=root
    )
    if result.returncode:
        return result.returncode
    if report(validate_site(root, root / "site", site_url)):
        return 1
    print("Public documentation build passed: source, HTML, links, search and sitemap.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
