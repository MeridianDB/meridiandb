#!/usr/bin/env python3
"""Check reader-facing documentation sources and optional rendered site output.

Run from any directory. This checks publication hygiene and metadata; editorial
review still establishes factual accuracy, useful explanations and audience fit.
"""

from __future__ import annotations

import argparse
import html
import json
import os
import re
import sys
from html.parser import HTMLParser
from pathlib import Path
from urllib.parse import unquote, urljoin, urlsplit
from xml.etree import ElementTree

import yaml


class _ConfigLoader(yaml.SafeLoader):
    """Support the existing MkDocs !ENV tag without loading executable YAML."""


def _environment(loader: _ConfigLoader, node: yaml.Node) -> str:
    if isinstance(node, yaml.SequenceNode):
        values = loader.construct_sequence(node)
        if not values or not all(isinstance(value, str) for value in values):
            raise ValueError("!ENV requires a nonempty list of strings")
        names, fallback = (values[:-1], values[-1]) if len(values) > 1 else (values, "")
        return next((os.environ[name] for name in names if name in os.environ), fallback)
    if isinstance(node, yaml.ScalarNode):
        return os.environ.get(loader.construct_scalar(node), "")
    raise ValueError("!ENV requires a variable name or list of strings")


_ConfigLoader.add_constructor("!ENV", _environment)

# Deliberately target process narration, rather than technical storage vocabulary.
_INTERNAL_MARKERS = (
    ("owner narration", r"\bowner(?:[’']s)?\s+(?:prompts?|instructions?|directions?|preferences?|approvals?|requests?|asked|asks?|approved|approves?|authorized|required|requires?|requested|directed|prefers?|preferred|instructed|specified|wants?|decided|agreed|said|chose|selected|recommended|suggested)\b"),
    ("conversation narration", r"\bthis\s+(?:chat|conversation)\b|\bas\s+we\s+(?:discussed|agreed)\b"),
    ("agent process narration", r"\b(?:coding|AI)\s+agents?\b|\bagentic\b|\bCodex\b|\bagent\s+(?:workflow|instructions?|handoff)\b"),
    ("authorization or handoff narration", r"\bauthori[sz](?:ation|ations|ed)\b|\bhand[ -]?offs?\b"),
    ("workflow state narration", r"\bworkflow\s+(?:state|checkpoint|iteration|cursor)\b|\bqueue\s+iteration\b|\bsource[ -]ready\b"),
    ("internal task identifier", r"\bMDB-\d{4}\b|\bStep-(?:ID|Result)\s*:"),
)
_MARKERS = [(label, re.compile(pattern, re.IGNORECASE)) for label, pattern in _INTERNAL_MARKERS]
_LOCAL_PATH = re.compile(r"(?<![\w:/])(?:file://[^\s<>)]+|/(?:Users|private|var|tmp|home|Volumes)/[^\s<>)]+|[A-Za-z]:[\\/][^\s<>)]+)")
_MARKDOWN_LINK = re.compile(r"!?\[[^\]]*\]\(\s*(?:<([^>]+)>|([^\s)]+))")
_REFERENCE_LINK = re.compile(r"^\s*\[[^\]]+\]:\s*(?:<([^>]+)>|(\S+))", re.MULTILINE)
_HTML_LINK = re.compile(r"\b(?:href|src)\s*=\s*[\"']([^\"']+)[\"']", re.IGNORECASE)


def _config(root: Path) -> dict:
    value = yaml.load((root / "mkdocs.yml").read_text(encoding="utf-8"), Loader=_ConfigLoader)
    if not isinstance(value, dict):
        raise ValueError("mkdocs.yml must contain a mapping")
    return value


def configured_site_url(root: Path) -> str:
    """Resolve site_url, including its safe !ENV override."""
    value = _config(root).get("site_url")
    if not isinstance(value, str) or not value:
        raise ValueError("mkdocs.yml needs a nonempty site_url")
    return value


def _visible(path: Path, base: Path) -> bool:
    return not any(part.startswith(".") for part in path.relative_to(base).parts)


def _internal_path(value: str) -> bool:
    path = unquote(urlsplit(value).path).replace("\\", "/")
    return any(part.casefold() in {"internal", "planning", ".local-qa"} for part in path.split("/"))


def _narration(text: str, label: str) -> list[str]:
    # JSON and HTML may encode apostrophes or split text between markup nodes.
    visible = re.sub(r"<[^>]+>", " ", text)
    normalized = " ".join(html.unescape(text + " " + visible).split())
    failures = []
    for kind, pattern in _MARKERS:
        if pattern.search(normalized):
            failures.append(f"{label}: contains {kind}")
    if _LOCAL_PATH.search(normalized):
        failures.append(f"{label}: contains an absolute local filesystem path")
    return failures


def _nav_paths(value: object) -> list[str]:
    if isinstance(value, str):
        return [value]
    if isinstance(value, list):
        return [path for item in value for path in _nav_paths(item)]
    if isinstance(value, dict):
        return [path for item in value.values() for path in _nav_paths(item)]
    return []


def _frontmatter(source: str, label: str) -> tuple[dict, str, list[str]]:
    match = re.match(r"\A---\s*\n(.*?)\n---\s*(?:\n|\Z)", source, re.DOTALL)
    if not match:
        return {}, source, [f"{label}: missing YAML front matter"]
    try:
        metadata = yaml.safe_load(match.group(1))
    except yaml.YAMLError as error:
        return {}, source[match.end():], [f"{label}: invalid front matter: {error}"]
    if not isinstance(metadata, dict):
        return {}, source[match.end():], [f"{label}: front matter must be a mapping"]
    return metadata, source[match.end():], []


def _h1_count(body: str) -> int:
    count = 0
    fence = None
    for line in body.splitlines():
        marker = re.match(r"^\s{0,3}(`{3,}|~{3,})", line)
        if marker:
            token = marker.group(1)
            if fence is None:
                fence = token
            elif token[0] == fence[0] and len(token) >= len(fence):
                fence = None
        elif fence is None and re.match(r"^#\s+\S", line):
            count += 1
    return count


def _sources(root: Path, config: dict) -> tuple[dict[str, tuple[Path, dict]], list[str]]:
    errors: list[str] = []
    docs = root / "docs"
    if config.get("docs_dir", "docs") != "docs":
        errors.append("mkdocs.yml: docs_dir must remain docs for the public content boundary")
    nav = _nav_paths(config.get("nav"))
    if not nav:
        errors.append("mkdocs.yml: nav must explicitly list public Markdown pages")
    paths = {path.relative_to(docs).as_posix(): path for path in docs.rglob("*.md") if _visible(path, docs)}
    for path in docs.rglob("*"):
        if path.is_file() and _visible(path, docs) and _internal_path(path.relative_to(docs).as_posix()):
            errors.append(f"docs/{path.relative_to(docs).as_posix()}: internal planning material is inside the public source directory")
    for relative in nav:
        candidate = docs / relative
        if _internal_path(relative):
            errors.append(f"mkdocs.yml: internal or outside-docs navigation target: {relative}")
        elif urlsplit(relative).scheme or relative.startswith("//"):
            continue
        elif not candidate.resolve().is_relative_to(docs.resolve()):
            errors.append(f"mkdocs.yml: internal or outside-docs navigation target: {relative}")
        elif relative not in paths:
            errors.append(f"mkdocs.yml: navigation target is not a public Markdown page: {relative}")
    descriptions: dict[str, str] = {}
    pages = {}
    for relative, path in sorted(paths.items()):
        label = "docs/" + relative
        if relative not in nav:
            errors.append(f"{label}: public Markdown page is missing from nav")
        if path.is_symlink() or not path.resolve().is_relative_to(docs.resolve()):
            errors.append(f"{label}: public source must be a regular file inside docs")
            continue
        source = path.read_text(encoding="utf-8")
        metadata, body, metadata_errors = _frontmatter(source, label)
        errors.extend(metadata_errors)
        if _h1_count(body) != 1:
            errors.append(f"{label}: expected exactly one Markdown H1")
        description = metadata.get("description")
        if not isinstance(description, str) or not description.strip():
            errors.append(f"{label}: needs a specific nonempty description")
        else:
            normalized = " ".join(description.split()).casefold()
            if normalized in descriptions:
                errors.append(f"{label}: description duplicates {descriptions[normalized]}")
            descriptions[normalized] = label
        errors.extend(_content_errors(source, label))
        pages[relative] = (path, metadata)
    readme = root / "README.md"
    if readme.is_file():
        errors.extend(_content_errors(readme.read_text(encoding="utf-8"), "README.md"))
    return pages, errors


def _content_errors(source: str, label: str) -> list[str]:
    errors = _narration(source, label)
    targets = [a or b for a, b in _MARKDOWN_LINK.findall(source) + _REFERENCE_LINK.findall(source)]
    targets.extend(_HTML_LINK.findall(source))
    for target in targets:
        if _internal_path(target):
            errors.append(f"{label}: links to internal planning or operations material: {target}")
        if _LOCAL_PATH.search(unquote(target)) or target.startswith("file:"):
            errors.append(f"{label}: links to an absolute local filesystem path: {target}")
    return errors


def validate_sources(root: Path) -> list[str]:
    """Return actionable source errors; an empty list means this check passed."""
    try:
        _, errors = _sources(root.resolve(), _config(root))
        return errors
    except (OSError, ValueError, yaml.YAMLError) as error:
        return [f"public documentation source QA: {error}"]


class _Page(HTMLParser):
    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.canonicals: list[str] = []
        self.descriptions: list[str] = []
        self.links: list[str] = []
        self.ids: set[str] = set()
        self.text: list[str] = []

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        values = dict(attrs)
        if tag == "link" and "canonical" in (values.get("rel") or "").split():
            self.canonicals.append(values.get("href") or "")
        if tag == "meta" and (values.get("name") or "").lower() == "description":
            self.descriptions.append(values.get("content") or "")
        if values.get("id"):
            self.ids.add(values["id"])
        self.links.extend(values[key] for key in ("href", "src") if values.get(key))

    def handle_data(self, data: str) -> None:
        self.text.append(data)


def _output_path(source: str, directory_urls: bool) -> str:
    path = Path(source)
    if path.name == "index.md":
        return path.with_suffix(".html").as_posix()
    return (path.with_suffix("") / "index.html").as_posix() if directory_urls else path.with_suffix(".html").as_posix()


def _route(output: str) -> str:
    return output.removesuffix("index.html") if output.endswith("/index.html") or output == "index.html" else output


def _json_strings(value: object):
    if isinstance(value, str):
        yield value
    elif isinstance(value, dict):
        for key, item in value.items():
            yield str(key)
            yield from _json_strings(item)
    elif isinstance(value, list):
        for item in value:
            yield from _json_strings(item)


def _search_errors(value: object, site: Path, expected: dict, pages: dict[Path, _Page]) -> list[str]:
    label = "site/search.json"
    errors: list[str] = []
    if not isinstance(value, dict) or not isinstance(value.get("config"), dict) or not isinstance(value.get("items"), list):
        return [f"{label}: expected a Zensical search object with config and items"]
    if not value["items"]:
        return [f"{label}: search items must not be empty"]
    routes = {_route(output): site / output for output in expected}
    covered: set[str] = set()
    seen: set[tuple[str, str]] = set()
    for number, item in enumerate(value["items"], 1):
        entry = f"{label}: item {number}"
        if not isinstance(item, dict):
            errors.append(f"{entry}: search item must be an object")
            continue
        location = item.get("location")
        if not isinstance(location, str):
            errors.append(f"{entry}: location must be a string")
            continue
        if not isinstance(item.get("title"), str) or not item["title"].strip():
            errors.append(f"{entry}: title must be a nonempty string")
        if not isinstance(item.get("text"), str):
            errors.append(f"{entry}: text must be a string (empty section text is allowed)")
        if type(item.get("level")) is not int or item["level"] < 1:
            errors.append(f"{entry}: level must be a positive integer")
        try:
            parts = urlsplit(location)
        except ValueError:
            errors.append(f"{entry}: invalid search location: {location}")
            continue
        route, fragment = unquote(parts.path), unquote(parts.fragment)
        if (
            location != location.strip() or any(character.isspace() for character in location)
            or parts.scheme or parts.netloc or route.startswith("/")
            or "?" in location or "\\" in location
            or any(component in {".", ".."} for component in route.split("/"))
            or _internal_path(location)
            or ("#" in location and not fragment)
        ):
            errors.append(f"{entry}: location must stay relative to the public site base: {location}")
            continue
        identity = (route, fragment)
        if identity in seen:
            errors.append(f"{entry}: duplicate search location: {location}")
        seen.add(identity)
        destination = routes.get(route)
        if destination is None:
            errors.append(f"{entry}: search location is not an expected public page: {location}")
            continue
        if fragment:
            page = pages.get(destination)
            if page is None or fragment not in page.ids:
                errors.append(f"{entry}: search location has no matching page anchor: {location}")
        else:
            covered.add(route)
    for route in sorted(set(routes) - covered):
        errors.append(f"{label}: missing page-level search entry: {route or '<home>'}")
    return errors


def validate_site(root: Path, site: Path, site_url: str) -> list[str]:
    """Check exact public routes, metadata, search content and local link targets."""
    errors: list[str] = []
    try:
        config = _config(root)
        sources, source_errors = _sources(root.resolve(), config)
        errors.extend(source_errors)
        base = urlsplit(site_url)
        if base.scheme != "https" or not base.netloc or base.query or base.fragment or not site_url.endswith("/"):
            return errors + ["site URL must be an HTTPS base URL ending in /, without query or fragment"]
        site = site.resolve()
        expected = {_output_path(source, config.get("use_directory_urls", True)): data for source, data in sources.items()}
        actual = {path.relative_to(site).as_posix(): path for path in site.rglob("*.html")}
        for output in sorted(set(expected) - set(actual)):
            errors.append(f"site/{output}: expected public page is missing")
        for output in sorted(set(actual) - set(expected) - {"404.html"}):
            errors.append(f"site/{output}: unexpected generated page outside the public navigation")
        pages: dict[Path, _Page] = {}
        expected_urls = {site_url + _route(output) for output in expected}
        for output, path in actual.items():
            parser = _Page()
            parser.feed(path.read_text(encoding="utf-8"))
            pages[path] = parser
            if output == "404.html":
                if parser.canonicals:
                    errors.append("site/404.html: error page must not declare a canonical URL")
            elif output in expected:
                canonical = site_url + _route(output)
                if parser.canonicals != [canonical]:
                    errors.append(f"site/{output}: expected exactly one canonical URL: {canonical}")
                description = expected[output][1].get("description")
                if parser.descriptions != [description]:
                    errors.append(f"site/{output}: rendered description must exactly match its source description")
            errors.extend(_narration(" ".join(parser.text), "site/" + output))
        for path, page in pages.items():
            output = path.relative_to(site).as_posix()
            page_url = site_url + _route(output)
            for target in page.links:
                if _internal_path(target):
                    errors.append(f"site/{output}: links to internal planning or operations material: {target}")
                resolved = urlsplit(urljoin(page_url, target))
                if resolved.scheme not in {"http", "https"} or resolved.netloc != base.netloc:
                    continue
                if not resolved.path.startswith(base.path):
                    errors.append(f"site/{output}: local link escapes the configured site path: {target}")
                    continue
                destination = site / unquote(resolved.path[len(base.path):])
                if not destination.resolve().is_relative_to(site):
                    errors.append(f"site/{output}: local link escapes the site directory: {target}")
                    continue
                if destination.is_dir():
                    destination /= "index.html"
                if not destination.is_file():
                    errors.append(f"site/{output}: missing local link or asset: {target}")
                elif resolved.fragment and destination in pages and unquote(resolved.fragment) not in pages[destination].ids:
                    errors.append(f"site/{output}: missing local anchor: {target}")
        sitemap = site / "sitemap.xml"
        if not sitemap.is_file():
            errors.append("site/sitemap.xml: missing sitemap")
        else:
            try:
                locations = [node.text for node in ElementTree.parse(sitemap).getroot().iter("{http://www.sitemaps.org/schemas/sitemap/0.9}loc")]
                if set(locations) != expected_urls or len(locations) != len(expected_urls):
                    errors.append("site/sitemap.xml: URLs must match the exact public page set without duplicates")
            except ElementTree.ParseError as error:
                errors.append(f"site/sitemap.xml: invalid XML: {error}")
        search = site / "search.json"
        if not search.is_file():
            errors.append("site/search.json: missing search index")
        else:
            try:
                errors.extend(_search_errors(json.loads(search.read_text(encoding="utf-8")), site, expected, pages))
            except json.JSONDecodeError as error:
                errors.append(f"site/search.json: invalid JSON: {error}")
        for path in sorted(site.rglob("*")):
            if not path.is_file() or path.suffix.lower() not in {".html", ".json", ".xml", ".txt"}:
                continue
            label = "site/" + path.relative_to(site).as_posix()
            if _internal_path(path.relative_to(site).as_posix()):
                errors.append(f"{label}: internal material was copied into the rendered site")
            text = path.read_text(encoding="utf-8")
            if path.suffix.lower() == ".json":
                try:
                    text = " ".join(_json_strings(json.loads(text)))
                except json.JSONDecodeError as error:
                    errors.append(f"{label}: invalid JSON: {error}")
            errors.extend(_narration(text, label))
            # Catch route leaks in JSON indexes as well as rendered HTML links.
            if re.search(r"(?:^|[\s\"'=<>])(?:\S*/)?(?:internal|planning)/", text, re.IGNORECASE):
                errors.append(f"{label}: contains an internal planning or operations route")
        return list(dict.fromkeys(errors))
    except (OSError, ValueError, yaml.YAMLError) as error:
        return errors + [f"rendered public documentation QA: {error}"]


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--site-dir", type=Path, help="also check a rendered site directory")
    parser.add_argument("--site-url", help="expected HTTPS base URL (defaults to resolved mkdocs site_url)")
    arguments = parser.parse_args(argv)
    root = arguments.root.resolve()
    if arguments.site_url and arguments.site_dir is None:
        parser.error("--site-url requires --site-dir")
    errors = validate_sources(root)
    if arguments.site_dir is not None:
        try:
            site_url = arguments.site_url or configured_site_url(root)
            site = arguments.site_dir if arguments.site_dir.is_absolute() else root / arguments.site_dir
            errors.extend(validate_site(root, site, site_url))
        except (OSError, ValueError, yaml.YAMLError) as error:
            errors.append(f"public documentation configuration: {error}")
    for error in dict.fromkeys(errors):
        print(error, file=sys.stderr)
    if errors:
        return 1
    print("Public documentation QA passed" + (" (sources and rendered site)." if arguments.site_dir else " (sources)."))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
