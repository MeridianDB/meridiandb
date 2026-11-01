---
title: "ADR 0034: Managed S3 writable recovery"
description: Compose exact managed S3 admission with core writable startup and serialize local transactions and cleanup while preserving committed snapshot capture.
---

# ADR 0034: Managed S3 writable recovery

Date: November 1, 2026. Status: implemented, experimental optional writable mode;
[remote writable corpus and cross-publication conformance](0035-remote-writable-recovery-conformance.md)
is now implemented separately.

## Context and decision

[Managed read recovery](0032-managed-s3-read-recovery.md) admits exact durable expected
selection and actual clients. [Core pinned writes](0033-pinned-checkpoint-writes.md)
establish the local journal/name barrier before durable retry/new-write acknowledgement.
Neither primitive alone composes those requirements for a managed writer lifetime.

Add `S3Database.openWritable` with the same independent configuration and coverage
arguments as read-only `open`. Share exact metadata/client/head admission and private
acquisition; select the concrete writable core factory only for the explicit new
mode. All public factories retain concrete core wiring. A package-private opener
seam lets tests inject existing core boundary failures into actual native recovery;
it adds no public backend or restore interface.

Expose `transact(Transaction)` by delegating to the core's existing forced transaction
protocol. Read-only instances reject it, including exact retries. Retain all local
owners until close and keep supplied S3 clients/credentials caller-owned. New commits
change only local history; checkpointPosition and the original startup observation
stay fixed. No transaction mutates S3 selection or publication metadata.

Serialize transactions and close on the private core monitor; reject reentrant write/
close before invalidation and always clear the write guard. Query capture uses volatile
closed state and the core's immutable publication, without waiting for the write monitor.
Core poison therefore rejects wrapper access, including observation, while existing
views invalidate with their core. Close independently attempts core/metadata cleanup
with primary/suppressed failures and retry. Private owner encapsulation avoids an
additional borrowed-resource monitor cycle or public delegate escape.

The [managed writable guide](../development/s3-database-writes.md) owns API, lifetime,
admission, visibility, uncertainty and cost details. It preserves the complete local
journal, optional independent minimum, exact original retry identity, strict missing/
corrupt/pending evidence and supported placement. Reopen remains online; no fallback,
adoption, repair, remote acknowledgement or replicated durability is introduced.

## Evidence and remaining qualification

This composes existing project contracts without changing wire formats, SDK request
settings or the local commit protocol. It reuses ADRs 0032/0033 and their dated primary
evidence. AWS sources in ADR 0032 were checked November 1 at 13:35:18 UTC /08:35:18 EST;
a historical exact head observation supplies no continuing lease, rollback detector
or local force guarantee. No new external provider write behavior is asserted.

Actual SDK/HTTP plus local ownership, core boundary interruption, process exit and
concurrency checks exercise this composition. A test helper in the optional test
sources accesses the existing package-private core probe; it is excluded from the
production and shared test-support artifacts. No fake commit protocol substitutes
for real core append/force/publication. These tests do not establish device power
loss or live-provider qualification. The separate conformance suite adds full
remote writable corpus coverage.

[ADR 0035](0035-remote-writable-recovery-conformance.md) now supplies full unchanged
canonical/query and persisted-seed SDK histories, late/pending selection and
cross-publication interruption checks. Replication and reader-safe remote reclamation retain
separate contracts. Keeping query methods under a wrapper transaction mutex would
unnecessarily block committed-state capture during force; independently implementing
write publication here would split the existing acknowledgement and poisoning rules.
