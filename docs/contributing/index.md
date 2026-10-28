---
description: Contribute to MeridianDB’s temporal semantics, reference model and documentation, with local setup instructions and focused verification steps.
---

# Contributing

MeridianDB is an early implementation and research project. Its Java/Maven build
contains immutable temporal types, a memory-only engine and a separate test
reference. The experimental local database adds checkpoint recovery and forced
commit publication. Process crash conformance remains pending; there is no
published artifact or stable API yet.
Contributions are most useful when they turn an open design question into a small,
reviewable result with explicit evidence.

Start with the [architecture overview](../architecture/overview.md),
[temporal contract and fixtures](../architecture/temporal-contract.md) and
[language decision](../decisions/0001-language-strategy.md).

## Useful contributions now

- Extend the temporal contract's worked histories with exact expected answers.
  Preserve the adopted behavior or explain a proposed revision in a decision record.
- Strengthen the [reference checks](../development/reference-model.md) with an
  independently reasoned boundary case or reproducible failing history.
- Identify a realistic workload and state its query, scale, embedding and
  durability requirements without assuming a particular storage layout.
- Investigate a specific persistence capability or failure scenario using dated
  primary sources and a reproducible experiment where appropriate.
- Improve explanations, navigation or examples when they hide an unresolved
  assumption or imply that a proposed feature already exists.

For an implementation proposal, first state the observable behavior and how it
will be checked. Java is the current engine default. A different language or
dependency should answer a concrete engineering question under the existing
language decision.

## Propose a focused change

Use the [roadmap](../roadmap.md) to understand the current implementation and
planned capabilities. Describe the problem, observable behavior and validation in
a contribution. Update affected examples and documentation alongside code, and
include reproducible evidence for semantic or performance claims.

## Build and test Java

Use JDK 25 and the tracked wrapper from the repository root:

```bash
./mvnw -B verify
```

Windows uses `.\mvnw.cmd -B verify`. Follow the [Java/Maven guide](../development/build.md)
for bootstrap tools, current artifact research, exact version pins and test scope.
Research latest artifact versions for every Maven change under that procedure.
The [reference guide](../development/reference-model.md) describes fixture replay,
seeded histories and focused reproduction commands. Follow the
[engine guide](../development/embedded-engine.md) to run the Java example and
understand close semantics, concurrency checks and memory-only limits. Storage
work also follows the [journal specification](../architecture/journal-format.md)
and [comparable-system research](../architecture/storage-research.md), with focused
codec versus filesystem/recovery evidence kept separate. The
[local immutable segment guide](../development/local-segments.md) documents
create-only objects, expected IDs and heap-reader lifetimes. The
[local database guide](../development/local-database.md) defines writable startup,
acknowledgement, uncertainty and the remaining crash-conformance boundary.

## Run the documentation locally

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
.venv/bin/python scripts/build_docs.py
```

Preview the generated `site/` directory separately:

```bash
.venv/bin/python -m http.server 8000 --bind 127.0.0.1 --directory site
```

These commands operate on the documentation site; Java verification uses the
independent Maven Wrapper command above. See [documentation and publishing](documentation.md) for dependency choices,
portability, automatic publication and optional manual rebuilds.

## Keep evidence and checks focused

For documentation changes, check links, paths, terminology, proposed-versus-built
status and the rendered pages. Review the diff for unrelated changes. A successful
site build does not validate the proposed storage semantics.

Research should name the question, sources, date checked, assumptions and remaining
uncertainty. Use primary project documentation for current technology claims.
Separate estimates, hypotheses and measurements. A reproducible experiment should
record its revision, runtime, workload, configuration and relevant environment.

When executable behavior is added, test expected outcomes and relevant failures.
Preserve failing seeds and small reproductions. Temporal and persistence changes
may need randomized histories, retries, concurrency or crash/restart checks;
choose checks that establish the changed contract.

Describe what changed, why it helps and which checks actually ran. Update the
relevant decision record when a material architectural choice changes, preserving
the rationale for earlier decisions.
