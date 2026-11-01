---
title: "ADR 0029: Verified S3 checkpoint publication"
description: Select exact durable checkpoint intent with original conditional preconditions, bounded complete graph passes and fresh-candidate durable reconciliation across uncertain outcomes.
---

# ADR 0029: Verified S3 checkpoint publication

Date: October 31, 2026. Status: implemented, experimental optional checkpoint
publisher; remote engine recovery, replication and reclamation remain planned.

## Context

Preparation synchronizes exact pending evidence before selection, but a timeout
can precede a later provider effect. Even a successful PUT reply is separate from
complete graph verification and durable local expected state. Conditional tokens
and local identity declarations cannot supply distributed writer admission.

## Decision

Compose preparation with an explicit one-attempt `submit` and a `reconcile` that
sends no remote head PUT while synchronizing local expected state. Admit the
actual clients, journal/path, existing immutable intent,
local capacity and result-generation arithmetic before remote work. Compare
current state with the exact pending candidate first; its newly observed token
is legitimate. Otherwise only original predecessor bytes/token or independently
fresh absence permit submission. No old, missing, conflicting or error observation
clears pending or refreshes an original precondition.

Verify the complete original graph set against actual owned journal prefixes.
Before force/PUT, reserve another identical complete pass under the same total
budget. Force the journal/name, issue one conditional request, then require a fresh
exact-candidate observation and the second complete graph pass before forcing and
installing local expected state. Reconciliation or an already observed candidate
uses one graph pass and no PUT. Keep cooperating resource monitor order and caller
ownership unchanged.

A failed write reply can produce a reconciled result after exact readback. An old
head after uncertainty retains the original intent, including across process exit;
later reconciliation can recognize the late effect without another write. Preserve
all old/pending/uncertain objects, and require reopen after local I/O uncertainty.
A result-installation error may already have changed durable expected state.

The [publication guide](../development/s3-checkpoint-publication.md) owns APIs,
summed graph budgets, fixed metadata allowance, failure semantics and supported
single-placement/no-external-rollback conditions. The protocol changes no local
transaction acknowledgement or core wire format.

## Primary evidence and design inference

AWS sources checked November 1, 2026 at 00:39:23 UTC (October 31, 20:39:23 EDT):

- [Consistency model](https://docs.aws.amazon.com/AmazonS3/latest/userguide/Welcome.html#ConsistencyModel)
  defines completed-write visibility and single-key atomicity; independent keys
  and local state do not become one transaction.
- [Conditional writes](https://docs.aws.amazon.com/AmazonS3/latest/userguide/conditional-writes.html)
  and [PUT API](https://docs.aws.amazon.com/AmazonS3/latest/API/API_PutObject.html)
  define creation/replacement conditions and conflict responses. Generic retry
  guidance does not justify rebasing this database's durable intent.
- [GET API](https://docs.aws.amazon.com/AmazonS3/latest/API/API_GetObject.html),
  [ETag guidance](https://docs.aws.amazon.com/AmazonS3/latest/userguide/checking-object-integrity-upload.html#checking-object-integrity-etag-and-md5)
  and [versioning](https://docs.aws.amazon.com/AmazonS3/latest/userguide/versioning-workflows.html)
  retain permission, opaque-validator and delete-marker/version limitations.

The exact state machine, two-pass reservation, immutable intent and local barriers
are project choices supported by integration/fault/process evidence. The fixture's
delayed atomic condition/effect demonstrates why an old-head read cannot resolve
an in-flight attempt. Local capabilities retain [ADR 0027](0027-durable-local-s3-publication-state.md)
assumptions; neither fixture nor process termination proves device-power-loss or
live-provider guarantees.

## Alternatives

Automatic retry loops hide repeated work and uncertainty. Refreshing a token or
discarding pending on an old-head GET abandons the original request. Separate full
budgets per pass could double the caller's limit, while discovering result-capacity
or generation exhaustion after PUT would permit avoidable partial progress.
Repairing missing immutable evidence or cleaning old objects needs separate
recovery/retention policy. Keep selection and exact reconciliation explicit.
