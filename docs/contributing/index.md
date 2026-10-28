---
description: Contribute to MeridianDB’s temporal semantics, reference model and documentation, with local setup instructions and focused verification steps.
---

# Contributing

MeridianDB is an early documentation and research project. The storage engine is
not implemented, and there is no engine artifact or stable API to integrate with.
Contributions are most useful when they turn an open design question into a small,
reviewable result with explicit evidence.

Start with the [architecture overview](/architecture/overview),
[temporal examples](/concepts/temporal-data) and
[language decision](/decisions/0001-language-strategy). Repository contributors
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

Use Node.js 24 for the documentation tooling. Run each command from the
repository root. Install the locked dependencies first:

```bash
npm ci
```

Start the development server and open the local URL it prints:

```bash
npm run docs:dev
```

Build the documentation before submitting a change:

```bash
npm run docs:build
```

Preview that production build locally:

```bash
npm run docs:preview
```

These commands operate on the documentation site. Maven is selected for the Java
engine; its wrapper, JDK baseline and commands will arrive with executable engine
code. See [documentation and publishing](/contributing/documentation) for the
dependency choices and manual publication procedure.

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
