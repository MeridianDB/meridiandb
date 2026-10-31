# Brand artwork maintenance

Public downloads and usage live in [Logos and colors](../docs/contributing/brand.md).
The artwork is maintained in `docs/assets/brand/`:

- `meridiandb-icon.svg` owns the symbol geometry and colors: two 24-unit rounded
  arches on a 256-unit navy tile. Keep its title/description and circular-crop margin.
- `meridiandb-wordmark.svg` owns the light-surface outlined lettering. Its
  `brand-icon` group is synchronized from the icon master by the exporter.
- `meridiandb-wordmark-dark.svg` and all PNGs are exports. Make geometry changes
  in the masters, then regenerate rather than editing raster images independently.

## Export and verify

Use local **librsvg 2.61.1** (`rsvg-convert`) and Python's standard library, from the
repository root:

```bash
python3 scripts/export_brand.py
```

The script checks the renderer version, synchronizes the wordmark icon, derives
the dark palette and exports 32/180/512/1024-pixel icons and 2× wordmarks. It checks
PNG signatures/dimensions. No font, Node, Maven or image service is required.
The earlier public `assets/favicon.svg` URL retains a generated copy of the new
icon for cached pages; edit the icon master rather than that compatibility copy.
Exports are committed; documentation CI copies them and needs no new renderer.
Review a renderer upgrade and resulting pixels explicitly before changing its pin.
Run the command twice and confirm the second run changes no asset bytes.

Inspect native 16/24/32-pixel rendering, GitHub's circular avatar crop, transparent
edges, wordmark letter spacing, and light/dark/narrow site use. Validate SVGs have
no scripts, embedded raster, text-font dependency or external references. Run the
shared strict documentation build for both URL layouts, restore production output,
and check theme logo/favicon, public downloads, README and local links. After the
authorized push, verify docs CI, automatic Pages and exact live asset bytes.

The website uses the 180-pixel PNG for its header and the 32-pixel PNG favicon.
README selects outlined SVG wordmarks by color scheme. The public download page
provides the GitHub-ready 1024-pixel PNG, with the 512-pixel version available too.
GitHub's [profile reference](https://docs.github.com/en/account-and-profile/reference/profile-reference),
checked October 31, 2026, permits PNG/JPG/GIF below 1 MB and smaller than 3000 × 3000.
The prepared 1024-pixel PNG is about 33 KB. Asset preparation and README integration
do not change the organization's avatar; uploading that file remains a separate
account action.

## Lettering provenance

The original twin-arch symbol is repository artwork. Wordmark outlines derive from
Inter **4.1 SemiBold**, downloaded from the project's official
[release](https://github.com/rsms/inter/releases/tag/v4.1), checked October 31, 2026.
Retain `INTER-LICENSE.txt` beside the assets. No font is bundled or requested at
runtime. The lettering was outlined with fontTools from the release's
`extras/otf/Inter-SemiBold.otf`, at 140 units with −2 units of tracking, baseline
178, and first advance origin 272, in a 1074 × 256 viewBox. Preserve the wordmark
paths as editable vector source. Only a lettering redesign needs the source font;
ordinary palette/geometry changes and PNG export do not.

- Release archive SHA-256:
  `9883fdd4a49d4fb66bd8177ba6625ef9a64aa45899767dde3d36aa425756b11e`.
- SemiBold OTF SHA-256:
  `0a4d30778fa2dc239368d90fd854b88b27e7ede737450da72686a90eca66f02c`.

Keep downloaded fonts, exploratory designs and visual QA under ignored
`.local-qa/mdb-0044/`; only the chosen editable artwork, license, exports and
durable guidance belong in source control. This hand-authored logo does not
introduce the Mermaid/PlantUML diagram-renderer pipeline.
