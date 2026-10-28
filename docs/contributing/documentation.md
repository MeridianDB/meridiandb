---
title: Writing and checking documentation
description: Build, preview and review MeridianDB documentation with portable Markdown, clear technical prose, evidence-based claims and automated source and HTML checks.
---

# Writing and checking documentation

MeridianDB documentation explains temporal concepts, current behavior, design
tradeoffs and contributor tasks. The website uses Zensical with MkDocs
configuration in `mkdocs.yml` and the classic theme. Public Markdown lives in
`docs/`; generated HTML lives in `site/`.

## Set up the documentation environment

Use Python 3.13, recorded in `.python-version`, and run commands from the repository
root. Create an isolated environment:

```bash
python3 -m venv .venv
```

Install the hash-checked dependencies:

```bash
.venv/bin/python -m pip install --require-hashes -r requirements-docs.txt
```

The Python environment is independent of the Maven Java build. Node.js and npm
are not needed.

## Preview and verify a change

Start a local preview:

```bash
.venv/bin/zensical serve --dev-addr 127.0.0.1:8000
```

Run the checked production build before submitting a change:

```bash
.venv/bin/python scripts/build_docs.py
```

This checks source content and navigation, builds with strict validation, then
checks generated pages, metadata, local links, search and sitemap. The same command
runs in documentation CI and before publication. A successful generator run alone
does not complete documentation validation.

Serve the generated output separately:

```bash
.venv/bin/python -m http.server 8000 --bind 127.0.0.1 --directory site
```

Inspect affected pages, navigation, search, code blocks and tables. Check a narrow
viewport and both themes when layout or assets change. Stop either preview server
with Ctrl+C. On Windows, use the corresponding `.venv\Scripts\` executables.

## Write for the reader

Lead with the concept, behavior or task the page explains. Define terms before
using them and show a small concrete example when it makes a distinction clearer.
Use direct technical prose and preserve useful detail; remove repeated caveats,
unsupported superlatives and implementation history that does not explain a design
tradeoff.

State whether a capability is implemented, experimental or planned. Tie correctness
and performance claims to the relevant tests or measurements. Keep ingestion,
durability, query visibility and physical snapshots distinct. Describe resource
limits and failure behavior where readers need them.

For comparisons and current dependency information, cite primary sources and state
the check date. Separate source facts from design judgments. Preserve the rationale
and alternatives in architectural decisions without turning other guides into
chronological change logs.

## Structure, links and metadata

Each page needs one level-one heading and a specific, unique `description` in YAML
front matter. Add `title` when the navigation title should differ from the heading.
Add the page to `mkdocs.yml` navigation. Use ordinary headings, prose, lists,
tables, fenced code and image links.

Use source-relative links ending in `.md`. They remain useful on GitHub and can be
validated and rewritten by the generator. Keep layout, navigation and theme
configuration out of page content. Preserve established public URLs when moving
reader-facing material.

`site_url` supplies HTTPS canonical and sitemap URLs. `DOCS_SITE_URL` overrides the
origin and base path for another hosting layout. Check the project-path layout:

```bash
DOCS_SITE_URL=https://meridiandb.github.io/meridiandb/ .venv/bin/python scripts/build_docs.py
```

Build again without that variable to restore the custom-domain layout. Alternate
language annotations are appropriate only when actual translations exist.

## Tooling and diagrams

The runtime is pinned in `requirements-docs.in`; `requirements-docs.txt` contains
resolved versions and installation hashes. Configuration and plugin support must
be checked against the pinned Zensical version. Arbitrary MkDocs hooks and plugins
are not supported merely because they work in MkDocs.

Mermaid and PlantUML are options for useful diagrams. Preserve editable sources
and publish readable SVGs with useful descriptions. The first diagram must add a
pinned renderer to the shared build so local checks and CI produce the same assets.
Check labels, contrast and sizing in both themes.

When changing the documentation checks themselves, run their fixture suite:

```bash
.venv/bin/python -m unittest discover -s scripts/tests -p 'test_*.py'
```

The [toolchain decision](../decisions/0002-documentation-toolchain.md) records
compatibility and portability choices. The [roadmap](../roadmap.md) describes the
technical development direction.
