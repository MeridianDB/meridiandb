---
title: Independent temporal reference model
description: Record MeridianDB's immutable temporal types, test-only sequential reference oracle, canonical fixture replay and independent dense-state verification.
---

# ADR 0005: Independent temporal reference model

- **Date:** October 28, 2026
- **Status:** implemented in test support
- **Scope:** MDB-0004; logical types and sequential conformance, without an engine

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

The passing sequential checks establish an oracle, not an implemented database.
The next engine must demonstrate its own atomic visibility, immutable read views,
serialized writes, retries and resource lifecycle. Durable storage and recovery
remain separate queue outcomes. Revisit the type/API shape with concrete consumers,
recording semantic changes in the contract and fixtures together.
