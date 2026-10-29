---
title: Temporal query operators decision
description: Add typed signed-value predicates, explicit-entity selection and filtered interval scans over one pinned snapshot while preserving exact temporal resolution and eager result ownership.
---

# ADR 0015: Temporal query operators

- **Date:** October 29, 2026
- **Status:** Implemented; experimental API
- **Scope:** Logical numeric selection and interval filtering for memory and local views

## Context

Point lookup, coalesced state scans and exact point sums already implement the
initial [temporal contract](../architecture/temporal-contract.md). Predicate queries
must preserve its latest-applicable-operation rule and pinned commit cutoff.
Filtering transaction history before resolving that state would revive overwritten
or retracted facts. Query expansion should remain inspectable without committing
to SQL, a general optimizer or a physical index.

## Decision

Add immutable `ValuePredicate` with six signed comparisons (`EQ`, `NE`, `LT`, `LE`,
`GT`, `GE`), an explicit candidate `Select` query and one-entity `FilterScan`.
Keep the original `Get`, `Scan` and `Sum` behavior unchanged. Candidate IDs are
nonnull and distinct; empty lists are valid. Numeric comparisons have no subtraction
overflow. Absence fails every predicate, including `NE`.

Resolve one immutable snapshot before evaluating any value predicate. Selection
returns present entity/value rows sorted by exact Java `String.compareTo` order.
Filtered scans preserve window clipping, canonical endpoints, coalescing and gaps;
an excluded interval never joins matching intervals across that gap.

Return eagerly materialized immutable heap results. Keep existing snapshot/close
semantics and use the same implementation after verified local recovery. No query
changes transaction identity or stored journal, segment or manifest bytes. The
[query guide](../development/temporal-queries.md) owns API usage, exact ordering,
resource lifetimes, costs and reproduction commands. Extend conformance with a
separate query fixture corpus, preserving existing canonical histories and seeds.

## Primary evidence and design inference

Sources checked October 29, 2026:

- XTDB separates valid/system-time constraints and documents intersection-clipped
  validity windows. [SQL queries](https://docs.xtdb.com/reference/main/sql/queries.html),
  [Time in XTDB](https://docs.xtdb.com/about/time-in-xtdb.html).
- Datomic describes immutable database values as a basis for query reasoning.
  [Transaction model](https://docs.datomic.com/transactions/model.html).
- JDK defines exact character-unit lexical ordering and signed numeric comparison.
  [String.compareTo](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/String.html#compareTo(java.lang.String)),
  [Long.compare](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/Long.html#compare(long,long)).

MeridianDB's explicit candidate set, absence semantics, comparison types and eager
results are local design choices. These sources inform fixed-view reasoning and
ordering; they do not supply MeridianDB's transaction or storage guarantees.

## Alternatives and consequences

Application predicate callbacks would complicate deterministic behavior, failure,
resource use and later query representation. Fixed data predicates keep the initial
surface small and independently testable. General composition needs a concrete
workload and its own depth/resource contract.

Input-order results would make equivalent candidate sets return different row
orders. Exact string ordering supplies one deterministic order without locale or
normalization dependencies. It deliberately differs from numeric-suffix and
Unicode code-point ordering.

Eager scans provide a simple correctness baseline. Explicit candidates and windows
do not bound total heap or work; indexes, result budgets and paginated cursors need
separate evidence and lifetime contracts. Performance experiments must preserve
these answers at the same temporal cutoff and durability boundary.
