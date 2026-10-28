---
title: Documentation toolchain — Zensical and portable Markdown
description: Use Zensical with MkDocs configuration and the classic theme, publish initially through GitHub Pages, and preserve content portability.
---

# 0002: Use Zensical with MkDocs configuration and portable Markdown

- Date: 2026-10-28
- Status: Working decision following the owner's documentation preference
- Scope: Documentation source, build, hosting and future migration

## Decision

Use Zensical with `mkdocs.yml` and its `classic` theme variant. Pin the runtime and
resolved Python dependencies. Keep public content in `docs/` as Markdown with
simple YAML metadata and source-relative links. The Java engine uses Maven.

Use GitHub Pages as the initial publication target. Under the owner's October 28,
2026 update, documentation input changes pushed to `main` automatically build and
publish through Actions; retain manual dispatch from `main` for optional rebuilds.
The [website guide](../contributing/documentation.md#initial-hosting-and-publication)
owns the input filters and publication checks. The generator and hosting provider
are independent choices: the same static output can be hosted elsewhere.
Cloudflare remains an option; no hosting migration is currently requested.

## Rationale and limits

The owner prefers MkDocs-style authoring and may later adopt Hugo. Zensical reads
`mkdocs.yml` and offers a Material-style classic theme. It provides a successor
path as Material for MkDocs approaches its announced May 5, 2027 end of life.

Compatibility is selective. Arbitrary MkDocs plugins and hooks do not run in
Zensical; check each capability against the installed version. Its current mike
integration uses a Zensical-compatible fork while native versioning is planned.
This site currently has one documentation set and no version selector.

Zensical is still in its 0.0 release series. Keep upgrades deliberate and check the
built pages, links, metadata and search. The current site has no large reference
corpus; no comparative build-speed or search-scale result has been measured.
Neither MkDocs nor Zensical is inherently better for SEO than correctly configured
static HTML from another generator.

## Portability and later versions

Prefer headings, prose, lists, tables, code fences and ordinary image links.
Keep generator configuration and theme features outside the content. Shared
`title` and `description` metadata is sufficient for current pages. A Hugo change
would still need navigation, link and asset adaptation; Markdown portability does
not make that conversion automatic.

Before adding release versions, choose URL paths, aliases, canonical policy and
supported-version retention. Recheck the versioning implementation then available.
The publication artifact must contain every retained version; a single-version
build must not replace older documentation. There is no need to introduce this
machinery before the first engine release.

## Historical choice

Commit `bb3431d` introduced the initial VitePress scaffold. This decision replaces
that toolchain before publication. Its source remains in Git history. The earlier
Java/Maven decision remains current.

The initial Pages workflow required manual publication. After the site went live,
the owner requested automatic publication on October 28, 2026. The current workflow
adds filtered pushes to `main` while preserving its strict build, deployment
permissions and manual rebuild option. Earlier progress receipts describe the
then-current manual policy; this update supersedes that policy.

The first automatic deployment passed, but its live HTML exposed HTTP canonical
URLs on the HTTPS site. Construct the production URL with explicit HTTPS and the
Pages host/base-path outputs, preserving the configured domain and project path.
A successful workflow alone is insufficient publication evidence; check live
metadata as well as content.

## Evidence checked on 2026-10-28

- [Zensical migration and classic theme](https://zensical.org/docs/compatibility/mkdocs/migration/)
- [Plugin compatibility](https://zensical.org/docs/compatibility/mkdocs/plugins/)
- [Versioning integration](https://zensical.org/docs/compatibility/mkdocs/mike/)
- [Material for MkDocs lifecycle](https://squidfunk.github.io/mkdocs-material/)
- [Cloudflare static HTML hosting](https://developers.cloudflare.com/pages/framework-guides/deploy-anything/)
- [GitHub Pages custom build/deploy workflows](https://docs.github.com/en/pages/getting-started-with-github-pages/using-custom-workflows-with-github-pages)
- [GitHub Actions branch and path filters](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax#onpushpull_requestpull_request_targetpathspaths-ignore)
- [Pinned configure-pages host and base-path outputs](https://github.com/actions/configure-pages/blob/45bfe0192ca1faeb007ade9deae92b16b8254a0d/action.yml)
