"""Regression coverage for the public-source and rendered-publication boundary."""

from __future__ import annotations

import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch


_SPEC = importlib.util.spec_from_file_location("public_docs_qa", Path(__file__).resolve().parents[1] / "check_public_docs.py")
qa = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(qa)


class PublicDocumentationTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.write("mkdocs.yml", """site_name: MeridianDB
site_url: !ENV [MERIDIAN_DOCS_TEST_SITE_URL, 'https://meridiandb.org/']
docs_dir: docs
use_directory_urls: true
nav:
  - Home: index.md
  - Technical guide: guide.md
""")
        self.write("docs/index.md", self.source("MeridianDB", "Explore the experimental temporal database.", "A bounded queue applies backpressure before checkpoint publication."))
        self.write("docs/guide.md", self.source("Technical guide", "Use immutable read views and explicit writer ownership.", "Preserve request identity and commit positions during recovery."))

    @staticmethod
    def source(title, description, body):
        return f"---\ntitle: {title}\ndescription: {description}\n---\n\n# {title}\n\n{body}\n"

    def write(self, relative, content):
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")
        return path

    def append(self, relative, content):
        path = self.root / relative
        path.write_text(path.read_text(encoding="utf-8") + "\n" + content, encoding="utf-8")

    def assertSourceError(self, fragment):
        errors = qa.validate_sources(self.root)
        self.assertTrue(any(fragment in error for error in errors), errors)

    def render(self, site_url="https://meridiandb.org/"):
        site = self.root / "site"
        self.write("site/assets/site.css", "body { color: black; }")
        for output, title, description, link in (
            ("index.html", "MeridianDB", "Explore the experimental temporal database.", "guide/#details"),
            ("guide/index.html", "Technical guide", "Use immutable read views and explicit writer ownership.", "../#details"),
        ):
            route = output.removesuffix("index.html")
            canonical = site_url + route
            stylesheet = "assets/site.css" if output == "index.html" else "../assets/site.css"
            self.write("site/" + output, f'''<!doctype html><html><head>
<link rel="canonical" href="{canonical}">
<meta name="description" content="{description}">
<link rel="stylesheet" href="{stylesheet}"></head>
<body><h1>{title}</h1><h2 id="details">Details</h2>
<p>A checkpoint preserves request identity and writer ownership.</p>
<a href="{link}">Related page</a></body></html>''')
        self.write("site/404.html", '<html><body>Page unavailable. <a href="' + site_url + '">Home</a></body></html>')
        self.write_search(self.search_index())
        self.write("site/sitemap.xml", f'''<?xml version="1.0"?>
<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
<url><loc>{site_url}</loc></url><url><loc>{site_url}guide/</loc></url>
</urlset>''')
        return site

    @staticmethod
    def search_index():
        return {
            "config": {"lang": ["en"]},
            "items": [
                {"location": "", "level": 1, "title": "MeridianDB", "text": "<p>Bounded queue and checkpoint publication.</p>", "path": ["Home"], "tags": []},
                {"location": "#details", "level": 2, "title": "Details", "text": "", "path": ["Home", "Details"], "tags": []},
                {"location": "guide/", "level": 1, "title": "Technical guide", "text": "<p>Writer ownership and request identity.</p>", "path": ["Technical guide"], "tags": []},
                {"location": "guide/#details", "level": 2, "title": "Details", "text": "<p>Read views.</p>", "path": ["Technical guide", "Details"], "tags": []},
            ],
        }

    def write_search(self, value):
        self.write("site/search.json", json.dumps(value))

    def assertSiteError(self, fragment, site_url="https://meridiandb.org/"):
        errors = qa.validate_site(self.root, self.root / "site", site_url)
        self.assertTrue(any(fragment in error for error in errors), errors)

    def test_sources_allow_technical_vocabulary_and_fenced_examples(self):
        self.append("docs/guide.md", "\nThe owner of the writer lock may append frames.\nThe lock owner's lifetime bounds exclusive writing.\n\n```shell\n# This is an example shell comment\n```\n")
        self.assertEqual([], qa.validate_sources(self.root))

    def test_hidden_cache_is_not_public_source(self):
        self.write("docs/.vitepress/cache/internal.md", "The owner asked for MDB-0016.")
        self.assertEqual([], qa.validate_sources(self.root))

    def test_environment_default_and_explicit_override(self):
        with patch.dict(os.environ, {}, clear=True):
            self.assertEqual("https://meridiandb.org/", qa.configured_site_url(self.root))
        with patch.dict(os.environ, {"MERIDIAN_DOCS_TEST_SITE_URL": "https://meridiandb.github.io/meridiandb/"}):
            self.assertEqual("https://meridiandb.github.io/meridiandb/", qa.configured_site_url(self.root))

    def test_yaml_executable_tags_are_rejected(self):
        self.append("mkdocs.yml", "unsafe: !!python/object/apply:os.system ['touch bad-file']")
        self.assertSourceError("could not determine a constructor")
        self.assertFalse((self.root / "bad-file").exists())

    def test_owner_conversation_and_agent_narration_is_rejected(self):
        original = (self.root / "docs/guide.md").read_text(encoding="utf-8")
        for sentence in (
            "The owner asked for this design.", "Owner's instruction is recorded here.",
            "Owner direction: implement local storage.", "The owner's preference is Java.",
            "The owner’s instructions require a source update.", "The owner prefers Maven.",
            "The owner requested this design.", "Owner approval is pending.",
            "This conversation changed the API.",
            "As we discussed, use Java.", "As we agreed, use Java.",
            "A coding agent will finish this.", "An AI agent will finish this.",
            "Agentic workflow.", "Codex will commit this.", "Agent instructions describe this.",
        ):
            with self.subTest(sentence=sentence):
                self.write("docs/guide.md", original + "\n" + sentence)
                self.assertSourceError("contains")

    def test_task_and_process_state_markers_are_rejected(self):
        original = (self.root / "docs/guide.md").read_text(encoding="utf-8")
        for sentence in (
            "Implemented in MDB-0016.", "Step-ID: MDB-0016", "Step-Result: completed",
            "The workflow state is active.", "Next queue iteration.",
            "Source-ready changes.", "Authorization is pending.", "Delivery handoff.",
        ):
            with self.subTest(sentence=sentence):
                self.write("docs/guide.md", original + "\n" + sentence)
                self.assertSourceError("contains")

    def test_unlisted_markdown_is_rejected(self):
        self.write("docs/unlisted.md", self.source("Unlisted", "A separate description.", "An unlisted page."))
        self.assertSourceError("missing from nav")

    def test_internal_planning_source_is_rejected_even_when_in_nav(self):
        self.write("docs/planning/queue.md", self.source("Queue", "Internal progress details.", "Pending operations."))
        self.append("mkdocs.yml", "  - Internal queue: planning/queue.md")
        self.assertSourceError("internal planning material")
        self.assertSourceError("internal or outside-docs navigation target")

    def test_internal_static_source_is_rejected(self):
        self.write("docs/internal/receipt.json", '{"status": "complete"}')
        self.assertSourceError("internal planning material")

    def test_external_internal_navigation_target_is_rejected(self):
        self.append("mkdocs.yml", "  - Internal queue: https://github.com/MeridianDB/meridiandb/blob/main/internal/planning/queue.md")
        self.assertSourceError("internal or outside-docs navigation target")

    def test_readme_is_checked_without_page_metadata_rules(self):
        self.write("README.md", "# MeridianDB\n\nAn immutable engine using writer ownership.\n")
        self.assertEqual([], qa.validate_sources(self.root))
        self.append("README.md", "The owner asked for MDB-0016.")
        self.assertSourceError("README.md: contains")
        self.write("README.md", "# MeridianDB\n\n[Internal plan](internal/planning/queue.md)\n")
        self.assertSourceError("README.md: links to internal")

    def test_outside_docs_navigation_target_is_rejected(self):
        self.append("mkdocs.yml", "  - Operations: ../internal/site-operations.md")
        self.assertSourceError("outside-docs navigation target")

    def test_missing_navigation_target_is_rejected(self):
        self.append("mkdocs.yml", "  - Missing page: missing.md")
        self.assertSourceError("not a public Markdown page")

    def test_internal_links_are_rejected_in_markdown_reference_and_html(self):
        original = (self.root / "docs/guide.md").read_text(encoding="utf-8")
        for link in (
            "[Queue](planning/queue.md)", "[Operations](../internal/site-operations.md)",
            "[Reference][queue]\n\n[queue]: ../internal/planning/queue.md",
            '<a href="../internal/site-operations.md">Operations</a>',
            "[Queue](https://github.com/MeridianDB/meridiandb/blob/main/internal/planning/queue.md)",
        ):
            with self.subTest(link=link):
                self.write("docs/guide.md", original + "\n" + link)
                self.assertSourceError("links to internal")

    def test_absolute_local_paths_are_rejected(self):
        original = (self.root / "docs/guide.md").read_text(encoding="utf-8")
        for text in (
            "[Local source](/Users/fe/Projects/meridiandb/pom.xml)",
            "[Local source](file:///private/tmp/notes.md)",
            "Saved to /private/tmp/document.md.",
            r"Saved to C:\Users\developer\project\pom.xml.",
        ):
            with self.subTest(text=text):
                self.write("docs/guide.md", original + "\n" + text)
                self.assertSourceError("absolute local filesystem path")

    def test_missing_empty_and_duplicate_descriptions_are_rejected(self):
        self.write("docs/guide.md", "# Technical guide\n")
        self.assertSourceError("missing YAML front matter")
        self.assertSourceError("specific nonempty description")
        self.write("docs/guide.md", self.source("Technical guide", "", "Body."))
        self.assertSourceError("specific nonempty description")
        self.write("docs/guide.md", self.source("Technical guide", "Explore the experimental temporal database.", "Body."))
        self.assertSourceError("description duplicates")

    def test_exactly_one_h1_is_required(self):
        self.append("docs/guide.md", "# Second title\n")
        self.assertSourceError("exactly one Markdown H1")
        self.write("docs/guide.md", "---\ndescription: A unique description.\n---\n\n## Subheading only\n")
        self.assertSourceError("exactly one Markdown H1")

    def test_root_and_project_layouts_pass(self):
        for site_url in ("https://meridiandb.org/", "https://meridiandb.github.io/meridiandb/"):
            with self.subTest(site_url=site_url):
                site = self.render(site_url)
                self.assertEqual([], qa.validate_site(self.root, site, site_url))

    def test_http_base_url_is_rejected(self):
        self.render()
        self.assertSiteError("HTTPS base URL", "http://meridiandb.org/")

    def test_missing_or_duplicate_or_wrong_canonical_is_rejected(self):
        for replacement in (
            "", '<link rel="canonical" href="http://meridiandb.org/">',
            '<link rel="canonical" href="https://meridiandb.org/"><link rel="canonical" href="https://meridiandb.org/">',
        ):
            with self.subTest(replacement=replacement):
                self.render()
                path = self.root / "site/index.html"
                path.write_text(path.read_text().replace('<link rel="canonical" href="https://meridiandb.org/">', replacement))
                self.assertSiteError("exactly one canonical")

    def test_rendered_description_must_match_source(self):
        self.render()
        path = self.root / "site/index.html"
        path.write_text(path.read_text().replace("Explore the experimental temporal database.", "Generic documentation."))
        self.assertSiteError("description must exactly match")

    def test_sitemap_exact_set_and_unique_https_routes(self):
        for loc in ("https://meridiandb.org/planning/", "http://meridiandb.org/", "https://meridiandb.org/"):
            with self.subTest(loc=loc):
                self.render()
                path = self.root / "site/sitemap.xml"
                path.write_text(path.read_text().replace("</urlset>", f"<url><loc>{loc}</loc></url></urlset>"))
                self.assertSiteError("exact public page set")

    def test_extra_generated_internal_page_is_rejected(self):
        self.render()
        self.write("site/planning/queue/index.html", "<html><body>Historical status.</body></html>")
        self.assertSiteError("unexpected generated page")

    def test_static_internal_output_is_rejected(self):
        self.render()
        self.write("site/internal/receipt.json", '{"status": "complete"}')
        self.assertSiteError("internal material was copied")

    def test_missing_expected_page_is_rejected(self):
        self.render()
        (self.root / "site/guide/index.html").unlink()
        self.assertSiteError("expected public page is missing")

    def test_search_rejects_escaped_owner_narration_and_task_ids(self):
        for text in ("The owner asked for this.", "Owner\u2019s instruction.", "Next MDB-0016.", "<p>The <b>owner</b> asked for this.</p>"):
            with self.subTest(text=text):
                self.render()
                search = self.search_index()
                search["items"][0]["text"] = text
                self.write_search(search)
                self.assertSiteError("contains")

    def test_search_rejects_internal_routes(self):
        self.render()
        search = self.search_index()
        search["items"][0]["location"] = "planning/queue/"
        self.write_search(search)
        self.assertSiteError("internal planning or operations route")

    def test_search_requires_real_zensical_shape_and_nonempty_items(self):
        for search, error in (
            ({"unrelated": "JSON"}, "expected a Zensical search object"),
            ({"items": []}, "expected a Zensical search object"),
            ({"config": {}, "items": []}, "search items must not be empty"),
            ({"config": {}, "items": ["not an object"]}, "search item must be an object"),
        ):
            with self.subTest(search=search):
                self.render()
                self.write_search(search)
                self.assertSiteError(error)

    def test_search_requires_page_level_entry_even_when_sections_exist(self):
        self.render()
        search = self.search_index()
        search["items"] = [item for item in search["items"] if item["location"] != "guide/"]
        self.write_search(search)
        self.assertSiteError("missing page-level search entry: guide/")

    def test_search_requires_home_page_entry(self):
        self.render()
        search = self.search_index()
        search["items"] = [item for item in search["items"] if item["location"] != ""]
        self.write_search(search)
        self.assertSiteError("missing page-level search entry: <home>")

    def test_search_rejects_nonexistent_route_or_anchor(self):
        for location, error in (
            ("unrelated/", "not an expected public page"),
            ("404.html", "not an expected public page"),
            ("assets/site.css", "not an expected public page"),
            ("guide/#nonexistent", "no matching page anchor"),
        ):
            with self.subTest(location=location):
                self.render()
                search = self.search_index()
                search["items"].append(dict(search["items"][0], location=location))
                self.write_search(search)
                self.assertSiteError(error)

    def test_search_rejects_external_query_and_escaping_locations(self):
        for location in (
            "https://example.org/guide/", "//example.org/guide/", "/guide/",
            "../guide/", "%2e%2e/guide/", "guide/../../guide/", r"guide\details",
            "guide/?query=x", "guide/?", "guide/#", " guide/", "guide/ section/",
        ):
            with self.subTest(location=location):
                self.render()
                search = self.search_index()
                search["items"].append(dict(search["items"][0], location=location))
                self.write_search(search)
                self.assertSiteError("location must stay relative to the public site base")

    def test_search_project_locations_cannot_escape_base(self):
        site_url = "https://meridiandb.github.io/meridiandb/"
        for location in ("/guide/", "/meridiandb/guide/", "../guide/", "https://meridiandb.github.io/guide/"):
            with self.subTest(location=location):
                self.render(site_url)
                search = self.search_index()
                search["items"].append(dict(search["items"][0], location=location))
                self.write_search(search)
                self.assertSiteError("location must stay relative to the public site base", site_url)

    def test_search_rejects_duplicate_effective_locations(self):
        for location in ("guide/#details", "guide/#%64etails"):
            with self.subTest(location=location):
                self.render()
                search = self.search_index()
                search["items"].append(dict(search["items"][0], location=location))
                self.write_search(search)
                self.assertSiteError("duplicate search location")

    def test_search_requires_string_location_title_text_and_integer_level(self):
        for field, value, error in (
            ("location", None, "location must be a string"),
            ("title", "  ", "title must be a nonempty string"),
            ("title", 42, "title must be a nonempty string"),
            ("text", [], "text must be a string"),
            ("level", True, "level must be a positive integer"),
            ("level", 0, "level must be a positive integer"),
        ):
            with self.subTest(field=field, value=value):
                self.render()
                search = self.search_index()
                search["items"][0][field] = value
                self.write_search(search)
                self.assertSiteError(error)

    def test_rendered_html_and_plain_text_leaks_are_rejected(self):
        self.render()
        self.append("site/index.html", "<p>The <b>owner</b> asked for this.</p>")
        self.write("site/notes.txt", "Delivery handoff is source-ready.")
        self.assertSiteError("owner narration")
        self.assertSiteError("site/notes.txt: contains")

    def test_extra_xml_cannot_leak_internal_routes_or_narration(self):
        self.render()
        self.write("site/extra.xml", '<details><url>https://example.org/internal/planning/queue/</url><p>The <b>owner</b> asked for this.</p></details>')
        self.assertSiteError("site/extra.xml: contains owner narration")
        self.assertSiteError("site/extra.xml: contains an internal planning or operations route")

    def test_missing_search_index_is_rejected(self):
        self.render()
        (self.root / "site/search.json").unlink()
        self.assertSiteError("missing search index")

    def test_404_must_have_no_canonical(self):
        self.render()
        self.append("site/404.html", '<link rel="canonical" href="https://meridiandb.org/404.html">')
        self.assertSiteError("error page must not declare a canonical")

    def test_missing_local_asset_and_anchor_are_rejected(self):
        self.render()
        (self.root / "site/assets/site.css").unlink()
        self.append("site/index.html", '<a href="guide/#missing-anchor">Invalid section</a>')
        self.assertSiteError("missing local link or asset")
        self.assertSiteError("missing local anchor")

    def test_project_absolute_link_cannot_escape_project_prefix(self):
        site_url = "https://meridiandb.github.io/meridiandb/"
        self.render(site_url)
        self.append("site/index.html", '<a href="/guide/">Wrong project route</a>')
        self.assertSiteError("escapes the configured site path", site_url)


if __name__ == "__main__":
    unittest.main()
