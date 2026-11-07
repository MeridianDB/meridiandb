---
title: Bounded local checkpoint history decision
description: Retain original checkpoint records from one complete local verification pass under explicit encoded-work budgets, separating heap acquisition from recovery adoption and ordinary lazy readers.
---

# ADR 0048: Bounded local checkpoint history

- **Date:** November 7, 2026
- **Status:** Opt-in acquisition core implemented; recovery integration and measured adoption pending
- **Scope:** Local checkpoint history acquisition, without changing default engine opens

## Context

The [attributed Linux baseline](../development/linux-attributed-observations.md)
shows checkpoint writable reopen reading selected segment images during complete
graph verification and again during replay. The engine also performs three
journal passes with distinct open, actual-prefix and replay responsibilities.
The measurements justify investigating one segment pass; they do not prove that
an integrity check or journal pass can be omitted.

The existing [object-graph history loader](../development/object-checkpoint-graphs.md#load-pinned-history)
already retains private original records during verification, exposing them only
after complete graph/actual-prefix acceptance. Local lazy readers have a different
lifetime: they may outlive store ownership and must revalidate later file opens.

## Decision

Add an explicit bounded `loadLatestHistory` operation to the local checkpoint
store. Reuse strict newest-generation selection and complete range-aware graph
verification, collecting immutable original records privately. Preflight all
declared object bytes, prefix bytes and twice-cutoff record work before segment
fetches. Cap image bodies at declared lengths while retaining the existing bounded
EOF growth probe, verify the actual bounded owner prefix and expose records only
after all checks and transient closes succeed. Failed growth probes can return
one additional byte; encoded-work usage does not disguise that native overhead.

Return a heap-owned immutable value with selected metadata, actual prefix and
encoded-work usage. Keep native ownership and alias defenses unchanged; no file
handle, lazy shared-reader protection or reclamation authority escapes. The
[history guide](../development/local-checkpoint-history.md) owns limits, commands,
usage definitions and failure/lifetime policy.

Preserve ordinary `readLatest`, lazy `openSegment`, default engine recovery and
all existing formats and acknowledgement. This is an opt-in acquisition core,
not adoption of a default cache or a persistent temporal index.

## Alternatives and consequences

A global or database-lifetime cache would add eviction, invalidation, aggregate
memory and ownership concerns before evidence supports it. Caching ordinary lazy
readers would change their later corruption/alias observations. Reusing records
within one private acquisition is narrower and needs no replacement file format.

Retaining the decoded record list avoids a second fetch/decode for a future
consumer but increases transient live references. Encoded-byte/record-work caps
do not promise an equivalent heap limit. Prior journal open and later full-history
engine rebuilding remain outside this acquisition budget.

Recovery integration and equivalent Linux comparisons must measure byte, force,
allocation, latency and memory tradeoffs before an adoption/rejection decision.
The heap-only result needs no continuing reader barrier; account for that changed
admission work while retaining writable startup journal/name barriers. Existing
ordinary observations remain immutable. No performance gain or production
qualification follows from this primitive's correctness tests.
