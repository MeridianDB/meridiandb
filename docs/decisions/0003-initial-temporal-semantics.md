---
title: Initial temporal semantics
description: "Record the rationale for MeridianDB's first temporal contract: whole-value replacement, microsecond intervals, ordered commits, pinned views and idempotent requests."
---

# ADR 0003: Initial temporal semantics

- **Date:** October 28, 2026
- **Status:** working decision; temporal types and test reference implemented, engine planned
- **Scope:** MDB-0002 semantics, exercised by MDB-0004 reference checks

## Context

The original salary example distinguishes valid time from committed knowledge but
does not decide how overlapping assertions, bounded corrections, retractions or
retries work. Storage work needs an independently checkable logical contract.
Choosing a format or index before resolving those cases would make physical
accidents determine behavior.

## Decision

Adopt the [initial temporal contract](../architecture/temporal-contract.md) as the
authoritative behavior for the prototype, backed by its JSON histories. In brief:

- Start with one serialized writer, whole entity values and signed 64-bit integer
  conformance values. Absence is explicit; field merging and richer values wait.
- Use UTC microsecond coordinates and half-open valid intervals, with explicit
  unbounded endpoints. Reject invalid ranges and finer precision instead of
  silently repairing inputs.
- Apply range replacement in commit order, then operation order within a commit.
  A retraction supplies absence over its range and preserves prior cutoffs.
- Use database-local commit positions for system time, with genesis position 0.
  Publish complete transactions atomically. Pin read views to one position.
- Require request IDs, retain their committed identity and return the original
  position for an identical retry. Conflicting reuse is an error. Distinguish
  memory-only acknowledgement from future durable acknowledgement and recovery.
- Specify entity lookup, coalesced interval scan and exact point aggregate results
  without adopting public query syntax or selecting a storage layout.

The contract owns detailed rules; this record preserves why they were chosen.
Fixture field names are test notation, not an API/format compatibility promise.

## Rationale and alternatives

**Whole values before documents.** Integers make temporal overlap and aggregate
answers small and exact. A document/attribute model would additionally need type,
null, merge and indexing rules. This choice bounds the first correctness model;
it is not a claim that all eventual database values must be integers.

**Half-open intervals and explicit infinity.** Adjacent intervals have one owner
at a boundary. A closed-upper-bound scheme needs predecessor arithmetic or special
boundary treatment. Explicit infinity keeps the maximum finite instant usable and
avoids a hidden sentinel. Microseconds provide a concrete starting precision;
nanoseconds, calendar dates and domain-specific coordinates remain possible later
choices when an actual workload requires them. No measurement claims motivate
this precision choice.

**Range replacement instead of conflict rejection or multiple competing facts.**
It directly expresses retroactive correction while preserving values outside the
specified interval. Rejecting overlap would require an explicit delete/split
protocol. Retaining simultaneous competing assertions would need another
resolution dimension. Those are valid alternative products, but neither is needed
to test the initial bitemporal engine.

**Commit positions instead of wall-clock ordering.** A serialized writer can give
an unambiguous knowledge prefix without clock ties or event-time assumptions. A
calendar-time-to-cutoff index could be added later under a separate definition.
Pinned views make entity scans and aggregates consistent while new commits arrive.
The initial writer model does not decide a replication or multiwriter protocol.

**Request identity as part of commit state.** Clients need to resolve a lost
acknowledgement without creating another correction. A best-effort retry cache
with expiry would weaken that guarantee. Retaining identities costs storage, but
keeps the first contract explicit. Future retention or identity expiration needs
a separately documented revision and tests.

## Consequences and reconsideration

The reference model and actual engine must agree on the same observable results
while using independently reasoned algorithms. Existing conceptual examples,
README and agent guidance point to this specification. The
[implemented reference model](../development/reference-model.md) now executes the
sequential fixtures; their success does not establish concurrent visibility,
durability or crash recovery in a database engine.

Revisit this decision with a concrete need for richer values, another time domain
or precision, intentional conflict handling, calendar-time queries, history/ID
expiry, optimistic preconditions or multiple writers. Record compatibility and
fixture changes explicitly; do not change semantics as a side effect of storage
optimization. Java/Maven direction, JDK choice, physical formats and backend
capabilities remain governed by their own decisions and queue items.
