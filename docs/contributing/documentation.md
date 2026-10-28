---
description: Build and preview MeridianDB documentation, maintain metadata and diagrams, and prepare GitHub Pages publication with the custom domain.
---

# Documentation and publishing

The website is built from Markdown in `docs/` using VitePress. The intended
canonical address is `https://meridiandb.org`. This page documents the source
setup and publication procedure; it is not a receipt confirming a live deployment.

## Local workflow

Use Node.js 24, as recorded in `.node-version`, and run commands from the repository
root. The Java engine will use Maven; these npm commands are for documentation.

```bash
npm ci
```

```bash
npm run docs:dev
```

Before submitting a website change, build and inspect the generated site:

```bash
npm run docs:build
```

```bash
npm run docs:preview
```

Both servers bind to the local loopback address. Open the URL printed by the
command. VitePress checks unresolved Markdown page links during the build; also
inspect changed pages, heading links, navigation, search and mobile layout.

The generated site is `docs/.vitepress/dist/`. It, the cache, `node_modules/` and
temporary `.local-qa/` evidence are ignored by Git. Commit source and the lockfile.

## Tooling choices

VitePress is pinned to stable **1.6.4**. Its [version 1 documentation](https://vuejs.github.io/vitepress/v1/)
is the reference for this configuration; the main VitePress website also documents
the version 2 prerelease line.

The npm override selects **Vite 6.4.5** beneath VitePress. VitePress 1.6.4 declares
Vite 5, whose dependency tree reports development-server advisories. The patched
Vite 6 line also brings a newer esbuild. This is a project compatibility override
outside VitePress's declared Vite range, not an upstream support claim. See the
[Vite advisory](https://github.com/advisories/GHSA-587h-7wmj-h5pm) and
[esbuild advisory](https://github.com/evanw/esbuild/security/advisories/GHSA-67mh-4wv8-2f99).

When updating these versions, inspect the dependency tree and advisories, rebuild
both base paths, and check development, preview, navigation and local search.
Keep `package-lock.json` synchronized; do not silently switch to the prerelease.

## Search metadata and canonical URLs

`docs/` is the source directory. Search engines see the published HTML URLs;
the directory name itself does not give the website special ranking treatment.
GitHub source pages can be discovered separately. Arbitrary Markdown front matter
does not control the HTML head of GitHub's source viewer.

Give each page a useful, specific `description` in front matter. The page heading
supplies its title unless an explicit `title` is needed. The site configuration
generates one absolute, self-referencing `rel="canonical"` link per content page,
using the publication origin, base path and clean route. It also generates the
sitemap from the same URL policy. For example, `docs/concepts/temporal-data.md`
becomes `https://meridiandb.org/concepts/temporal-data` on the custom domain.
Canonical metadata also updates during client-side navigation. Keep this policy
central rather than repeating `canonical` fields or domain names in every page.

Use `rel="alternate"` with `hreflang` when actual translated or regional versions
exist and link those versions reciprocally. No such versions currently exist.
An `alternative` front matter key has no built-in SEO meaning; metadata only works
when a renderer consumes it. A GitHub edit/source link does not need an alternate
annotation for SEO.

MkDocs supplies canonical links through `site_url`; VitePress supplies static HTML
and allows equivalent metadata through configuration. Choose between them for
maintainability and authoring needs, rather than assuming a generator guarantees
better rankings. Canonicals communicate a preference; search engines ultimately
choose what to index. After publication, verify the live canonical URLs, sitemap,
HTTPS and redirects, then submit the sitemap in Google Search Console.

References: [Google canonical guidance](https://developers.google.com/search/docs/crawling-indexing/consolidate-duplicate-urls),
[localized versions](https://developers.google.com/search/docs/specialty/international/localized-versions),
[MkDocs site URL](https://www.mkdocs.org/user-guide/configuration/#site_url),
and [VitePress static rendering](https://vuejs.github.io/vitepress/v1/guide/what-is-vitepress).

## Authoring and diagrams

Add public pages under `docs/` and add their navigation entries in
`docs/.vitepress/config.mts`. Keep the current experimental status accurate.
The root README introduces the repository; `AGENTS.md` contains working rules.

Architecture pages may use **Mermaid** or **PlantUML** when a diagram explains a
relationship more clearly than prose or a table. Keep the editable source in Git:
Mermaid in a Markdown block or `.mmd` file, and PlantUML in a `.puml` file. External
diagram sources should live under `docs/diagrams/mermaid/` or
`docs/diagrams/plantuml/` when those directories are introduced.

The current scaffold has no diagram renderer. Introduce the first diagram together
with a pinned local renderer and its build integration. For generated SVGs, run
rendering before VitePress in the shared `docs:build` command so local builds and
both Actions workflows use the same process. Generate assets under
`docs/public/diagrams/`, ignore that generated directory, fail on rendering errors,
and inspect labels, accessibility text, mobile sizing and both color themes. This
keeps the source editable and publishing independent of a remote rendering service.

## Automated checks and manual publication

**Documentation checks** runs on affected pull requests and pushes to `main`.
It builds with `/` and `/meridiandb/` as base paths using a read-only repository
token. It neither needs GitHub Pages configured nor publishes a site.

**Publish documentation** runs only when a maintainer manually dispatches it on
`main`. Its build job reads the existing Pages configuration, installs locked
dependencies and uploads only the generated site. A separate deployment job has
the Pages write and identity-token permissions. GitHub Actions definitions are
in `.github/workflows/`; referenced actions are pinned to release commit hashes.

The publication build derives the base path and origin from Pages metadata. This
supports the custom domain at `/` and the default project URL at `/meridiandb/`.
Local development defaults to `/`. To exercise the project path locally:

```bash
DOCS_BASE_PATH=/meridiandb/ DOCS_SITE_ORIGIN=https://meridiandb.github.io npm run docs:build
```

```bash
DOCS_BASE_PATH=/meridiandb/ npm run docs:preview
```

Rebuild without those environment variables to return to the custom-domain/local
layout. The build uses the configured origin and base path for canonicals and the sitemap.

## One-time GitHub Pages and domain setup

Before the first publication, the repository owner must:

1. Push the reviewed source and workflows to GitHub under the usual source handoff.
2. In the MeridianDB organization's Pages settings, verify `meridiandb.org` with
   the DNS TXT record GitHub supplies. Keep that verification record.
3. In this repository's **Settings → Pages**, select **GitHub Actions** as the
   publishing source and set the custom domain to `meridiandb.org`.
4. Configure the apex and `www` DNS records according to GitHub's current guide.
   Set the custom domain in GitHub before pointing DNS at Pages.
5. Enable **Enforce HTTPS** when GitHub has provisioned the certificate. Configure
   the `github-pages` environment to permit deployments from `main`.
6. Run **Actions → Publish documentation → Run workflow**, selecting `main`.
   Inspect the deployment result and check the public site before recording it
   as published.

A local build or commit is not publication. The Actions-based deployment uses
the custom domain configured in GitHub; a `CNAME` file in this source tree is not
required and does not configure that setting.

References: [custom Actions workflows](https://docs.github.com/en/pages/getting-started-with-github-pages/using-custom-workflows-with-github-pages),
[domain verification](https://docs.github.com/en/pages/configuring-a-custom-domain-for-your-github-pages-site/verifying-your-custom-domain-for-github-pages),
and [custom domain and DNS setup](https://docs.github.com/en/pages/configuring-a-custom-domain-for-your-github-pages-site/managing-a-custom-domain-for-your-github-pages-site).
