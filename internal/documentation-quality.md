# Public documentation quality standard

This is the authoritative editorial and publication QA procedure for repository
maintainers and coding agents. It applies to `docs/`, root `README.md`, navigation,
generated pages, search and sitemap whenever public documentation changes.

## Audience and publication boundary

- Public copy explains MeridianDB's concepts, behavior, APIs, examples, technical
  decisions and contribution tasks. Lead with what the reader needs to understand
  or accomplish. Explain why a choice matters in technical terms.
- Keep conversation provenance, owner prompts/preferences/instructions, AI or agent
  workflow narration, authorization boundaries, task IDs, queue cursors, commit
  trailers, delivery receipts and administrative account/DNS procedures under
  `internal/` or `AGENTS.md`, outside `docs_dir`. They remain repository records;
  they are not website content. A technical writer's use of queues, checkpoints,
  writer ownership or request identity is legitimate and must remain clear.
- `docs/` contains only deliberate public material. Every Markdown page belongs in
  navigation; hiding a page from nav alone does not prevent indexing or publication.
  Keep internal files outside the build input, not merely excluded from a menu.
- Use a reader-facing technical roadmap for capabilities and planned outcomes.
  Execution state remains in [the live queue](planning/queue.md); do not duplicate
  those IDs, dependencies or active states on public pages.
- Preserve technical decision dates, reasons, alternatives and primary evidence.
  Remove conversational attribution; retain operational history in internal receipts
  and Git rather than rewriting it as public product prose.

## Editorial review

1. Identify the page's audience and purpose. Make the opening describe the subject,
   behavior or task directly; remove how the request arrived and who directed it.
2. Read the entire affected page for coherence, not only the edited sentence.
   Remove repeated warnings, generic marketing, unsupported superlatives, artificial
   question/answer transitions and chronological implementation chatter that does
   not explain a technical tradeoff. Preserve useful examples and precise detail.
3. Verify implemented/experimental/planned status against code, tests and current
   build configuration. Distinguish memory visibility, durable storage, indexing,
   query freshness and recovery. Never infer durability from codec or unit tests.
4. Support external technical/version claims with dated primary evidence. Distinguish
   facts from design inference and measurement. Do not invent benchmark results.
5. Check terminology, one H1, unique useful descriptions, headings, table readability,
   commands, examples, source-relative links and navigation. Keep metadata human and
   specific rather than keyword stuffing or a fixed prose quota.
6. Review affected callers in README, guides, ADRs, agent rules and current internal
   workflow. Keep one authoritative procedure for each decision and repair conflicts
   where they occur. Preserve dated evidence as historical rather than a live rule.

## Required automated and rendered QA

Run from the repository root using the hash-checked documentation environment:

```bash
.venv/bin/python scripts/build_docs.py
```

The shared build performs source checks, strict Zensical generation, then rendered
checks. Both documentation CI and publication must use it. It rejects clear
internal narrative, task/workflow markers, internal links/routes and local file
paths; checks source/navigation coverage; and verifies rendered metadata, expected
routes, local links, search and sitemap. A raw `zensical build` is useful for
troubleshooting but does not satisfy publication QA.

After changing the checker, run its meaningful positive/negative fixtures:

```bash
.venv/bin/python -m unittest discover -s scripts/tests -p 'test_*.py'
```

For routing, navigation, metadata or publication changes, also run the project-path
build:

```bash
DOCS_SITE_URL=https://meridiandb.github.io/meridiandb/ .venv/bin/python scripts/build_docs.py
```

Preview the final generated site over local HTTP. Inspect affected copy, tables,
code, navigation and search. Include light/dark and narrow viewport checks when
visual structure changes. Automated pattern checks are guardrails; they do not
replace editorial judgment or justify allowlisting conversational prose.

## Completion evidence

Record the audited surfaces, corrected narrative patterns, source/rendered checks,
fixture results, link/caller reconciliation and preview results in the internal
progress receipt. Inspect and commit only task-owned source and guidance. Follow
[the implementation workflow](planning/index.md) and
[site operations](site-operations.md) for authorized push, CI, deployment and live
content/search/sitemap verification. Report the actual state. Keep receipts and
approval/authority explanations out of public documentation.
