---
title: Memory-only embedded engine
description: Record MeridianDB's immutable interval snapshots, serialized writers, atomic publication, pinned read views and explicit close semantics for the first Java engine.
---

# ADR 0006: Memory-only embedded engine

- **Date:** October 28, 2026
- **Status:** implemented; experimental Java API
- **Scope:** In-process execution without persistence

## Context

The reference interpreter executes whole transaction history backwards. An
embedded engine needs explicit resource lifetimes and observable commit
publication, using an independently reasoned representation. Durable layout,
journal and recovery remain planned capabilities.

## Decision

Implement `InMemoryEngine` in the existing production artifact, without a runtime
dependency. Each instance creates a fresh database lineage. Retain immutable value
snapshots for every commit and committed request identity for retries. Represent
entity state as sorted, coalesced present-value intervals. Apply ordered operations
by splitting/replacing intervals in a private copy; point/scan/aggregate queries
read that state rather than interpreting the operation log.

Serialize writes and engine close with one monitor. Publish a single immutable
state through a volatile reference only after every operation, historical cutoff
and request identity is ready. Readers capture that reference without the writer
monitor and bind to one immutable snapshot. The Java specification's
[happens-before rules](https://docs.oracle.com/javase/specs/jls/se25/html/jls-17.html#jls-17.4.5)
provide the visibility basis for monitor serialization and volatile publication;
focused concurrent tests exercise the resulting observable behavior.

A read view implements `AutoCloseable`; closing it invalidates only that view.
Engine close is idempotent, rejects future operations and invalidates existing
views. An operation that already captured open state may finish during a concurrent
close; there is no cancellation guarantee. New engine instances start empty with
new lineages. Acknowledgement promises in-memory publication only.

Keep types and syntax experimental. Reuse canonical expected answers and the
independent dense-state seed model without changing fixture answers to match the
engine. Add direct lifecycle/concurrency tests and one source-launch Java example.
The [engine guide](../development/embedded-engine.md) owns current usage, failure
behavior, verification commands and limits.

## Alternatives and consequences

Using the reference interpreter in production would duplicate its assumptions and
add resource/concurrency obligations to the correctness oracle. Reusing only the
immutable logical types keeps the interpreter test-only and the algorithms separate.

A mutable shared interval index would require locking each reader or proving a
more complex publication protocol. Immutable snapshots make pinned reads explicit
and cross-entity consistency inspectable. Copying maps and retaining all versions
costs memory and write time; this is acceptable for the first correctness prototype,
with no measured performance claim. Persistent collections and indexes need a
specific workload and separate evaluation.

Per-entity publication or a separate retry cache could expose incomplete
transactions or disagree about whether an ID committed. Publishing state and
identity together avoids that split. There is no durable recovery guarantee until
the journal and engine recovery steps implement and test it.

Revisit retention only through an explicit contract that preserves supported
historical answers and active readers. Revisit writer coordination for a real
multi-process/backend requirement. Changes to close behavior or public API shape
need corresponding documentation and observable tests; this decision promises no
stable API or persistent format.
