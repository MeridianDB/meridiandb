---
title: Independent temporal reference model
description: Record MeridianDB's immutable temporal types, test-only sequential reference oracle, canonical fixture replay and independent dense-state verification.
---

# ADR 0005: Independent temporal reference model

- **Date:** October 28, 2026
- **Status:** implemented in test support
- **Scope:** Logical types and sequential conformance testing

## Context

The temporal contract has exact histories and the Maven foundation has an interval
primitive. Before implementing storage, the project needs an executable oracle
that remains easy to inspect and independent of future engine algorithms.
Generated tests also need independently derived answers; agreement between two
copies of the same algorithm would give weak evidence.

## Decision

Add immutable experimental command, query, result and commit-position types to the
single production artifact. Keep the reference interpreter under `src/test/java`
and its history/seed data on the test classpath. Share the logical types with the
future engine, while keeping their execution algorithms separate.

Store whole transactions in commit order. Resolve point values by searching
history backwards. Derive scans by partitioning at relevant boundaries, evaluating
each cell and coalescing adjacent equal answers. Use arbitrary-precision boundary
coordinates to represent the exclusive edge beyond the maximum finite instant.
Keep request identity separately from historical data views. The interpreter is
sequential and memory-only; it makes no concurrency or durability claim.

Execute the canonical JSON source directly through Maven test resources. Add the
researched current stable Jackson release as a test dependency for exact tree
parsing. Reject non-integral and out-of-range numeric inputs before applying any
operation. This adapter is fixture notation, not a public serialization contract.
The [build guide](../development/build.md) owns coordinates, pins and dated evidence.

Verify the interpreter against hard-coded fixture expectations and a seeded forward
dense-state model. The latter assigns each finite cell directly, independently of
history search and scan boundary partitioning. Persist reproduction seeds and any
future minimized failing histories. The [reference guide](../development/reference-model.md)
owns current commands, coverage and limits.

## Alternatives and consequences

A production reference engine would add lifecycle and concurrency obligations
before the oracle is needed by consumers. A separate Maven test artifact would add
packaging without a second consumer. Test support is sufficient now; it can be
extracted when another module needs it.

A mutable interval map could be smaller or faster for some histories, but it would
likely resemble the next engine's splitting/indexing logic. Whole-history lookup
and boundary partitioning prioritize inspectability and independence. Their cost
is acceptable for small correctness histories and is not an engine benchmark.

A hand-written JSON parser would add unrelated parsing work. Test-only Jackson
provides strict scalar inspection without a production runtime dependency.
Canonical expectations stay in one source; CI now runs Java checks when that
fixture directory or its contract changes.

The reference interpreter is a sequential test oracle. The
[separate interval-state engine](0006-in-memory-engine.md) compares its observable
answers with this oracle and independent dense snapshots, with additional tests
for atomic visibility, serialized writes and resource lifetimes. The interpreter
remains test-only. [Local read recovery](0013-local-database-recovery.md) exercises
canonical histories through existing storage. The [writable local protocol](0014-durable-commit-publication.md)
and [durability conformance](../development/durability-conformance.md) add actual
writable/reopen histories and process-exit checks. Changes to logical type semantics
must update the contract and fixtures together.
