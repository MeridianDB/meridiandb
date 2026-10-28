---
description: Contribute to MeridianDB’s temporal semantics, reference model and documentation, with local setup instructions and focused verification steps.
---

# Contributing

MeridianDB is an early documentation and research project. The storage engine is
not implemented, and there is no engine artifact or stable API to integrate with.
Contributions are most useful when they turn an open design question into a small,
reviewable result with explicit evidence.

Start with the [architecture overview](../architecture/overview.md),
[temporal examples](../concepts/temporal-data.md) and
[language decision](../decisions/0001-language-strategy.md). Repository contributors
and coding agents should also read `AGENTS.md` at the repository root.

## Useful contributions now

- Add worked temporal histories with expected answers, especially partial
  corrections, retractions, overlapping intervals and boundary cases.
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
.venv/bin/zensical build --strict
```

Preview the generated `site/` directory separately:

```bash
.venv/bin/python -m http.server 8000 --bind 127.0.0.1 --directory site
```

These commands operate on the documentation site. Maven is selected for the Java
engine; its wrapper, JDK baseline and commands will arrive with executable engine
code. See [documentation and publishing](documentation.md) for dependency choices,
portability and the manual publication procedure.

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
