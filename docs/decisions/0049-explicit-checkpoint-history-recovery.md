---
title: Explicit checkpoint history recovery decision
description: Reuse bounded verified checkpoint records during private local recovery, release the transient container before journal replay and preserve complete verification and writable acknowledgement.
---

# ADR 0049: Explicit checkpoint history recovery

- **Date:** November 7, 2026
- **Status:** Explicit experimental factories implemented; measured adoption pending
- **Scope:** Local read-only and writable recovery; ordinary opens unchanged

## Context

[Bounded history acquisition](0048-bounded-local-checkpoint-history.md) keeps
original records from complete graph verification. The ordinary recovery path
subsequently fetches and decodes selected segments again through a lazy reader.
Avoiding that second pass requires a consumer with an explicit heap lifetime;
retaining the acquisition result throughout the database lifetime would add
unnecessary references while the engine already retains full historical state.

## Decision

Expose `LocalDatabase.openWithCheckpointHistory(directory, limits)` and
`openWritableWithCheckpointHistory(directory, limits)` as explicit experimental
choices. Keep ordinary `open`, `openWritable` and creation unchanged. Use the same
strict selection and complete graph/actual-prefix verification. Admit checkpoint
budgets without silently falling back on rejection. Null inputs reject before
storage effects. [The history guide](../development/local-checkpoint-history.md)
owns API usage, limit definitions and lifecycle details.

Complete acquisition in a helper that returns only the owned recovery source;
its history-value frame exits before private engine rebuilding. Keep only the
selected cutoff and transient record list, release the list after checkpoint
replay on success or failure, and clear it on close. Retain no whole history value
in the database source. Reconstructed temporal state and retry payloads still
consume heap; releasing a container does not release data the engine needs.

Preserve full journal-open and replay scans, including prefix order/cardinality
checks, and the selected checkpoint's actual-prefix verification. Publish no
engine before complete recovery. Writable startup still forces the owner journal
and its directory before new or retry acknowledgement; later commits retain the
same preparation, append, force, atomic publication and uncertain-outcome rules.
Attempt all acquired-owner cleanup on I/O, runtime and Error failures, retaining
primary and suppressed failures. No new repair or reclamation authority is added.

## Alternatives and consequences

Default adoption before equivalent measurements would obscure allocation and
memory tradeoffs. A global or database-lifetime cache adds invalidation and
aggregate-lifetime concerns. Reusing the existing owned-source recovery protocol
keeps one temporal reconstruction and write implementation instead.

Checkpoint limits exclude earlier journal open, complete replay, tail work and
engine heap. A journal-only database can therefore recover with zero checkpoint
limits; selected genesis requires manifest and journal-header bytes. The temporary
record list can raise peak references even when each selected segment is fetched
once. No file format or dependency changes are required.

Heap recovery needs no continuing lazy-reader barrier. Removed reader-admission
forces must be reported separately from segment-read reduction; writable startup
synchronization remains. Native literal counter checks establish expected adapter
work, not latency, allocation, device traffic or production performance. Equivalent
Linux comparison and an explicit adoption/rejection decision remain necessary.
