---
title: "ADR 0032: Managed S3 read recovery"
description: Admit exact durable expected S3 selection and actual client scope before privately owned core read recovery, preserving pending evidence and caller resources.
---

# ADR 0032: Managed S3 read recovery

Date: November 1, 2026. Status: implemented, experimental optional read-only mode;
[managed S3 writable recovery](0034-managed-s3-writable-recovery.md) is implemented separately.

## Context and decision

An explicit pinned checkpoint is a caller choice. Managed S3 startup must also
bind durable expected/pending state and actual clients to one fresh selection
observation. Returning only a checked head would detach that admission from
metadata ownership and later engine startup.

Add `S3Database.open` in the optional artifact. Own existing local publication
metadata until the returned database closes. Refuse pending or absent expected
state before remote reads, check actual client configuration, then require a
fresh complete head image and its original expected opaque token. Privately invoke
[core pinned recovery](0031-pinned-checkpoint-engine-recovery.md), retaining local
store/journal ownership through the wrapper lifetime. Expose queries and original
request lookup with transactions rejected by this read-only mode and no underlying-owner getter. Close invalidates
views and attempts every owned resource, preserving suppressed cleanup failures.
Clients and credential providers remain caller-owned.

The [managed recovery guide](../development/s3-database.md) owns API usage,
admission order, explicit reconciliation, capability/lifetime and budget details.
The metadata format binds endpoint/region/bucket and namespace/publisher/lineage,
not a database path. Independent directory configuration and actual journal-prefix
validation retain trusted placement/no-external-rollback assumptions. Canonical
metadata/database directory identity is rejected; no wire format changes.

Pending state requires the existing publisher's explicit reconciliation before a
later open. Startup never publishes, adopts a changed token, initializes absent
state, enumerates prior versions or falls back to another graph. No additional
state generation is installed. Metadata reopen still synchronizes local selected
state/name. An optional independently known minimum catches insufficient local
coverage after head observation and before graph fetches; unknown clean tail loss
remains unknowable. Offline callers choose an existing explicit local or pinned
mode separately. The complete matching local journal remains required.

## Primary evidence and limits

Official AWS sources checked November 1, 2026 at **13:35:18 UTC /08:35:18 EST**:

- [GET API](https://docs.aws.amazon.com/AmazonS3/latest/API/API_GetObject.html)
  defines current versus explicit-version access, complete response metadata and
  permission-dependent missing-key errors. Access denied is not absence.
- [Consistency model](https://docs.aws.amazon.com/AmazonS3/latest/userguide/Welcome.html#ConsistencyModel)
  describes whole single-key observations; it provides no lock after GET or atomic
  transaction with local metadata and immutable dependencies.
- [Object ETag](https://docs.aws.amazon.com/AmazonS3/latest/API/API_Object.html),
  [versioning](https://docs.aws.amazon.com/AmazonS3/latest/userguide/Versioning.html)
  and [delete markers](https://docs.aws.amazon.com/AmazonS3/latest/userguide/DeleteMarker.html)
  distinguish validators, retained versions and deleted current state.

Requiring exact bytes and token is a project admission policy. A same-body rewrite
need not change its ETag, and restoring both evades this comparison. The startup
observation is historical evidence, not a continuing lease, unique version identity,
distributed fence, replicated durability or permission for remote reclamation.
Object identity remains the complete verified SHA-256/length contract.

Real SDK/HTTP, native ownership/failure and process checks exercise this composition
without qualifying a live provider or device power loss. Graph budgets remain
separate from the fixed head body, local metadata synchronization, initial journal
scan, full replay and retained engine heap. Reusing the core private reconstruction
preserves its independent temporal conformance. [Managed writable recovery](0034-managed-s3-writable-recovery.md)
now supplies the separate integration and focused acknowledgement checks.
[Remote writable conformance](0035-remote-writable-recovery-conformance.md) adds
the unchanged full corpus and cross-publication evidence.
