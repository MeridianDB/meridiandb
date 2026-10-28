---
description: Build and preview MeridianDB documentation with Zensical and MkDocs configuration, maintain portable sources, and prepare GitHub Pages publication.
---

# Documentation and publishing

The website uses **Zensical** with **MkDocs configuration** in `mkdocs.yml` and the
Material-style **classic theme**. Public source lives in `docs/`. The site was
first published at `https://meridiandb.org/` on October 28, 2026, confirmed by the
owner and an HTTPS check. This guide remains the setup and manual publication
procedure; an implementation source push does not publish a new site version.

## Local workflow

Use Python 3.13 (recorded in `.python-version`) and run commands from the repository
root. Create an isolated environment and install the hash-checked dependencies:

```bash
python3 -m venv .venv
```

```bash
.venv/bin/python -m pip install --require-hashes -r requirements-docs.txt
```

Start the local preview with automatic rebuilding:

```bash
.venv/bin/zensical serve --dev-addr 127.0.0.1:8000
```

Build the static site and fail on reported documentation issues:

```bash
.venv/bin/zensical build --strict
```

Preview the generated `site/` directory separately:

```bash
.venv/bin/python -m http.server 8000 --bind 127.0.0.1 --directory site
```

Open the local URL printed by the server. Stop it with Ctrl+C. On Windows, use the
corresponding `.venv\Scripts\` executables. Java and Maven are independent of the
Python documentation environment; contributors do not need Node.js or npm.

Inspect affected pages, navigation, search, code blocks, tables and both themes,
including a narrow mobile viewport. Strict builds check source links and anchors;
check the generated canonical URLs and sitemap when routing changes. Generated
`site/`, `.cache/`, `.venv/` and temporary `.local-qa/` evidence are ignored by Git.

## Dependency and compatibility policy

The runtime is pinned in `requirements-docs.in`; `requirements-docs.txt` records
resolved versions and package hashes for installation. Python 3.13 is the tested
baseline. To change dependencies, update the input and regenerate the lock with
[uv](https://docs.astral.sh/uv/pip/compile/), then install and validate it:

```bash
uv pip compile --universal --python-version 3.13 --generate-hashes requirements-docs.in --output-file requirements-docs.txt
```

The runtime is Zensical, not the MkDocs command-line program. Retain `mkdocs.yml`
and ordinary Markdown to keep the authoring structure familiar. A fallback MkDocs
build would also need its own pinned MkDocs and Material packages and verification;
it is not installed or tested by this configuration.

Review supported settings and plugin behavior before adopting an extension.
Zensical does not execute arbitrary MkDocs plugins or hooks. The current site uses
built-in search and no custom plugin. See the
[toolchain decision](../decisions/0002-documentation-toolchain.md) for lifecycle,
compatibility and future versioning considerations.

## Portable authoring and metadata

Use ordinary Markdown headings, lists, tables, fenced code and image links. Add a
specific `description` to each page, and `title` when needed. Use source-relative
links ending in `.md`, so the source is browsable on GitHub and the generator can
validate and rewrite links. Keep layout, navigation and theme settings in
`mkdocs.yml`; avoid Vue components, Hugo shortcodes and theme-specific content
markup unless a concrete requirement justifies the migration cost.

The site-level `site_url` supplies absolute canonical URLs and sitemap URLs.
For example, `docs/concepts/temporal-data.md` becomes
`https://meridiandb.org/concepts/temporal-data/`. Keep the origin and base path
consistent. A `canonical` or `alternative` field alone does not control GitHub's
source-page HTML. `docs/` itself has no special SEO treatment. Add `hreflang`
alternate links only for actual translated or regional content.

After publication, verify the live URLs, HTTPS, redirects and sitemap, then submit
the sitemap in Google Search Console. Google treats canonical annotations as a
preference, not an indexing guarantee. See
[Google's canonical guidance](https://developers.google.com/search/docs/crawling-indexing/consolidate-duplicate-urls).

## Architecture diagrams

Mermaid and PlantUML remain options for useful diagrams. Preserve editable `.mmd`
or `.puml` sources under `docs/diagrams/mermaid/` or `docs/diagrams/plantuml/` when
introduced. Prefer SVG output with useful image descriptions in Markdown for
portability across documentation generators.

There is currently no diagram renderer. Add the first actual diagram together with
a pinned local renderer and one shared build command that renders before
`zensical build --strict`. Both Actions workflows must use that same command.
Generate SVGs under `docs/assets/diagrams/`, ignore that generated directory, fail
on rendering errors, and inspect labels, mobile sizing and both themes.

## Initial hosting and publication

GitHub Pages is the initial host because source, review and publication already
live on GitHub. Open-source status does not require this host. The generated
`site/` directory can also be served by Cloudflare Pages; that change would not
require Hugo. A later Hugo migration would be a separate generator change and
would need URL, link, navigation, asset and metadata checks.

**Documentation checks** runs on affected pull requests and pushes to `main`.
It installs the locked dependencies and builds both custom-domain and GitHub
project-path variants with read-only repository permissions. It does not publish.

**Publish documentation** runs only on manual dispatch from `main`. The build job
reads the existing Pages configuration, builds the site and uploads `site/`.
A separate deployment job holds Pages write and identity-token permissions.
The workflow definitions live in `.github/workflows/` and pin action releases by
commit hash. They have been prepared locally; an Actions run verifies CI itself.

`DOCS_SITE_URL` can override the production URL for another host or base path.
Exercise the default GitHub project path locally with:

```bash
DOCS_SITE_URL=https://meridiandb.github.io/meridiandb/ .venv/bin/zensical build --strict --clean
```

```bash
DOCS_SITE_URL=https://meridiandb.github.io/meridiandb/ .venv/bin/zensical serve --dev-addr 127.0.0.1:8000
```

Rebuild without that variable to return to the custom-domain layout. Use `--clean`
when changing configuration/base paths or diagnosing stale generated output.

## One-time GitHub Pages and domain setup

Owner direction, October 28, 2026: publish at the apex `meridiandb.org` only;
`www` is outside this setup. DNS was managed by GoDaddy nameservers when checked
on that date. Use the authoritative DNS provider if this changes later.

Before the first publication, the repository owner must:

1. Ensure the reviewed source and workflows are on GitHub's `main` branch.
2. In the MeridianDB organization's **Settings → Pages**, add `meridiandb.org`
   under verified domains. Add the generated TXT challenge at the DNS provider,
   return to GitHub and verify it. Copy the exact challenge name and value from
   GitHub, and retain the TXT record after verification.
3. In this repository's **Settings → Pages**, select **GitHub Actions** as the
   publishing source and save `meridiandb.org` as the custom domain. Set this
   before pointing the apex DNS records at GitHub Pages.
4. At the DNS provider, replace the existing apex A records with the four below.
   For GoDaddy, open **Domain Portfolio → meridiandb.org → DNS**, use `@` as the
   Name and 1 hour as TTL. Remove an existing `www` alias for the apex-only setup;
   preserve unrelated email, verification and service records. Nameservers can
   stay at GoDaddy.
5. Configure the `github-pages` environment to allow `main`, then run
   **Actions → Publish documentation → Run workflow** on `main`. Inspect both
   build and deploy jobs.
6. When GitHub's certificate is ready, enable **Enforce HTTPS** in repository
   Pages settings. Verify the public site and its sitemap before recording the
   site as published. DNS propagation and certificate issuance may finish after
   the workflow, so a pending certificate need not prevent the first dispatch.

Apex A records checked against GitHub's documentation on October 28, 2026:

| Type | Name | Value | TTL |
| --- | --- | --- | --- |
| A | `@` | `185.199.108.153` | 1 hour |
| A | `@` | `185.199.109.153` | 1 hour |
| A | `@` | `185.199.110.153` | 1 hour |
| A | `@` | `185.199.111.153` | 1 hour |

Recheck the linked GitHub DNS instructions before a later hosting change. No
`www` CNAME, apex CNAME or URL-forwarding service is needed for this setup.

A local build or commit is not publication. The Actions deployment uses the custom
domain configured in GitHub; no source `CNAME` file is needed for that setting.
Cloudflare deployments remain owner-performed under the owner's standing policy.

References: [Zensical migration](https://zensical.org/docs/compatibility/mkdocs/migration/),
[custom Actions workflows](https://docs.github.com/en/pages/getting-started-with-github-pages/using-custom-workflows-with-github-pages),
[domain and DNS setup](https://docs.github.com/en/pages/configuring-a-custom-domain-for-your-github-pages-site/managing-a-custom-domain-for-your-github-pages-site),
and [Cloudflare static hosting](https://developers.cloudflare.com/pages/framework-guides/deploy-anything/).
