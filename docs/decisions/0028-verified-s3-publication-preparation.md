---
title: "ADR 0028: Verified S3 publication preparation"
description: Compose actual client scope, complete owned-journal graph checks, synchronization and exact durable pending intent before introducing conditional submission and reconciliation.
---

# ADR 0028: Verified S3 publication preparation

Date: October 31, 2026. Status: preparation implemented, experimental;
[ADR 0029](0029-verified-s3-checkpoint-publication.md) adds conditional submission/
reconciliation. [Managed S3 read recovery](0032-managed-s3-read-recovery.md) is now available separately; managed S3 writable recovery is implemented separately;
[ADR 0031](0031-pinned-checkpoint-engine-recovery.md) adds core pinned read-only recovery.

## Context

A correct graph, a forced local journal and a saved candidate are separate facts.
Preparation must compose them for the same actual clients, owned journal and
original precondition before a conditional selection can be attempted. A matching
caller-provided target cannot establish the configuration of an already-created
client. An observed old head cannot erase unresolved pending intent.

## Decision

Add a preparer in the optional artifact over caller-owned concrete object/head
clients and local state/checkpoint/journal owners. Check the actual clients'
lexical endpoint, region and bucket, head binding, actual journal lineage/path,
pure next-state transition and local capacity before requests or force/upload
effects. Require a fresh GET to match both original expected bytes and token;
absence fits only independently fresh state. Preserve the existing pending refusal.

Verify the complete candidate graph or adjacent pair against actual journal
prefixes under shared limits. Synchronize the journal through its existing owner
and checkpoint namespace. Install or verify/reuse one immutable exact head image,
then force the same pending metadata through the local state protocol. Serialize
the cooperating owners in a fixed monitor order throughout preparation.

Return immutable historical evidence only after all preparation stages succeed.
Send no mutable head PUT and expose no pending clearing or selection result.
Keep exact original bytes/token through failures; retain old and uncertain objects.
Conditional submission and reconciliation must still establish their own fresh
observations, graph/owner checks, original-precondition requests and durable result.

The [preparation guide](../development/s3-publication-preparation.md) owns the API,
ordering, fixed metadata allowance, graph/local limits and failure semantics.
No dependency, core format or transaction acknowledgement changes are required.

## Primary evidence and limits

AWS sources rechecked November 1, 2026 at 00:24:54 UTC (October 31, 20:24:54 EDT):

- [Consistency model](https://docs.aws.amazon.com/AmazonS3/latest/userguide/Welcome.html#ConsistencyModel)
  gives completed-write visibility and single-key atomicity, not a transaction
  spanning immutable objects, a selection key and local state.
- [Conditional writes](https://docs.aws.amazon.com/AmazonS3/latest/userguide/conditional-writes.html)
  and [PUT API](https://docs.aws.amazon.com/AmazonS3/latest/API/API_PutObject.html)
  define conditional creation/replacement and distinct conflict outcomes.
- [GET API](https://docs.aws.amazon.com/AmazonS3/latest/API/API_GetObject.html),
  [ETag guidance](https://docs.aws.amazon.com/AmazonS3/latest/userguide/checking-object-integrity-upload.html#checking-object-integrity-etag-and-md5)
  and [versioning](https://docs.aws.amazon.com/AmazonS3/latest/userguide/versioning-workflows.html)
  explain permission-dependent absence, opaque validators and delete-marker/version
  behavior. None makes an old-head read prove that an earlier request cannot finish.

The exact expected-state checks, monitor order, key layout and accounting are
project choices. Local directory/name synchronization retains the assumptions in
[ADR 0027](0027-durable-local-s3-publication-state.md). Fixture and process tests
provide bounded evidence, not live-provider or device-power-loss qualification.

## Alternatives

Sending selection immediately would mix durable admission with uncertainty and
reconciliation before that protocol has been verified. A generic byte adapter
with an independently supplied target could hide a client scope mismatch. Refreshing
an equal-byte head's token could abandon the original precondition. Separate graph
budgets for predecessor and candidate could exceed the caller's combined allowance.
Keep preparation explicit and bounded, then compose full selection separately.
