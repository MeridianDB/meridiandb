---
title: Documentation toolchain — Zensical and portable Markdown
description: Use Zensical with MkDocs configuration and the classic theme, publish initially through GitHub Pages, and preserve content portability.
---

# 0002: Use Zensical with MkDocs configuration and portable Markdown

- Date: 2026-10-28
- Status: Adopted documentation toolchain
- Scope: Documentation source, build, hosting and future migration

## Decision

Use Zensical with `mkdocs.yml` and its `classic` theme variant. Pin the runtime and
resolved Python dependencies. Keep public content in `docs/` as Markdown with
simple YAML metadata and source-relative links. The Java engine uses Maven.

Use GitHub Pages as the initial publication target. Changes to documentation inputs
on `main` build and publish through GitHub Actions after documentation QA succeeds.
The generator and hosting provider are independent choices: the same static output
can be hosted elsewhere. The [documentation guide](../contributing/documentation.md)
describes the contributor workflow.

## Rationale and limits

MkDocs-style configuration supports a declarative documentation workflow.
Zensical reads `mkdocs.yml` and offers a Material-style classic theme. It provides a successor
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

The initial VitePress scaffold was replaced before publication. The Java/Maven
build remains independent of the Python documentation toolchain.

Canonical and sitemap URLs use explicit HTTPS with the configured host and base
path. Both custom-domain and project-path layouts are checked. Publication checks
cover live content and metadata as well as successful builds.

## Evidence checked on 2026-10-28

- [Zensical migration and classic theme](https://zensical.org/docs/compatibility/mkdocs/migration/)
- [Plugin compatibility](https://zensical.org/docs/compatibility/mkdocs/plugins/)
- [Versioning integration](https://zensical.org/docs/compatibility/mkdocs/mike/)
- [Material for MkDocs lifecycle](https://squidfunk.github.io/mkdocs-material/)
- [Cloudflare static HTML hosting](https://developers.cloudflare.com/pages/framework-guides/deploy-anything/)
- [GitHub Pages custom build/deploy workflows](https://docs.github.com/en/pages/getting-started-with-github-pages/using-custom-workflows-with-github-pages)
- [GitHub Actions branch and path filters](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax#onpushpull_requestpull_request_targetpathspaths-ignore)
- [Pinned configure-pages host and base-path outputs](https://github.com/actions/configure-pages/blob/45bfe0192ca1faeb007ade9deae92b16b8254a0d/action.yml)
