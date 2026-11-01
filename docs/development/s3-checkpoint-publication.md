---
title: Verified S3 checkpoint publication
description: Submit exact durable checkpoint intent and reconcile fresh candidate observations with shared graph budgets, actual owned-journal verification and synchronized local results.
---

# Verified S3 checkpoint publication

`S3CheckpointPublisher` submits an exact durable pending intent or reconciles it
without a remote head PUT. Reconciliation still writes and synchronizes local
expected state. A result requires a fresh observation of the exact candidate,
complete graph verification against the actual owned journal, and synchronized
local expected-state installation. The optional `meridiandb-s3` artifact now
supports this experimental checkpoint publication protocol. Core
[pinned checkpoint read recovery](object-checkpoint-recovery.md) is separate;
managed S3 startup, writable recovery, replication and remote object reclamation
remain planned.

The publisher operates on already installed immutable dependencies and the pending
state produced by [verified preparation](s3-publication-preparation.md). It does
not acknowledge a database transaction, provide distributed writer admission or
permit deletion. [ADR 0029](../decisions/0029-verified-s3-checkpoint-publication.md)
records the decision.

## Resources and operations

Construct `S3CheckpointPublisher(objects, heads, localState, checkpoints, journal)`
with the same caller-owned resources used for preparation. The class closes none
of them. It holds their monitors in local-state, checkpoint-store, journal,
head-client, object-client order through each complete operation; cooperating
transitions and close wait. Reentrant operations are rejected. Use that ordering
in caller coordination and keep resource callbacks out of credential providers.

| Operation | Behavior |
| --- | --- |
| `submit(limits)` | Revalidate pending intent; issue at most one conditional SDK PUT with its original bytes/precondition, then reconcile a fresh exact candidate observation |
| `reconcile(limits)` | Make no PUT; resolve only a fresh exact candidate observation |
| Candidate already observed during `submit` | Reconcile it without another PUT |

Both operations require a durable pending intent and an available local result
generation. Exhausted local generation or namespace capacity rejects before
remote selection. Actual client endpoint spelling, region, bucket and head
binding must match managed local state. Journal lineage and its canonical owned
checkpoint path are checked independently. The existing immutable intent image
must match exactly; missing or corrupt evidence is not repaired automatically.

After reading the intent, one complete same-GET head observation is classified:

| Observation | Handling |
| --- | --- |
| Exact pending candidate, with any valid current opaque ETag | Verify the original graph set and actual prefixes, force owner/name and install new local expected state |
| Original predecessor bytes and original expected token | `submit` may attempt the same conditional request; `reconcile` retains pending |
| Absence with independently fresh, expected-absent pending state | `submit` may attempt conditional creation; `reconcile` retains pending |
| Changed predecessor token, missing initialized head, unexpected bytes or binding | Refuse advancement and retain pending; never adopt a token or fallback graph |

The selected candidate's ETag is normally different from the predecessor's. Keep
the original token for resubmission, while storing the newly observed candidate's
token as expected only after successful reconciliation.

## Submit and reconcile sequence

For an original predecessor or fresh absence, submission:

1. Verifies the complete candidate graph, or both original predecessor and candidate
   graphs, against the actual owned journal under one shared budget.
2. Reserves a **second identical complete graph pass** from the remaining budget
   before force or PUT. Insufficient room refuses selection before its effects.
3. Synchronizes the actual journal and its name through the existing owners.
   Pending state has already been synchronized by preparation or validated reopen.
4. Issues one conditional request: literal `If-None-Match: *` for creation, or
   `If-Match` with the original expected strong token for replacement. Candidate
   bytes and publication identity are unchanged. There is no automatic retry loop.
5. Reads the current head once more. Only the exact candidate permits the second
   complete original graph pass, using the remaining budget, followed by another
   owner/name barrier and durable local `recordSelected` installation.

A successful PUT reply alone does not complete selection. A failed reply can be
resolved when fresh readback proves the exact candidate and all remaining checks
succeed. An old head after timeout does not prove that the request cannot finish
later. Pending remains, new preparation is refused, and later reconciliation
can observe the late effect without sending another PUT.

For an already observed candidate, or a successful `reconcile`, there is one full
original graph pass, an owner/name barrier and local result installation. Both
graphs are still checked when a predecessor exists; copied equal-lineage journals
with a divergent original prefix cannot substitute for the actual history.

For independently managed variables and already installed candidate dependencies:

```java
var preparer = new S3PublicationPreparer(objects, heads, localState, checkpoints, journal);
preparer.prepare(candidateImage, preparationLimits);
var publisher = new S3CheckpointPublisher(objects, heads, localState, checkpoints, journal);
var selected = publisher.submit(publicationLimits);
var observed = selected.observation();
var usage = selected.usage();
```

On uncertain failure, close/reopen failed owners as required by their contracts,
inspect durable state, and use `reconcile(publicationLimits)` when pending remains.
An explicit `submit` retry, when allowed by original-state checks, uses the same
candidate and original token. It does not refresh, rebase, clear or invent intent.
Preparation and submission have separate explicit operation budgets; callers
manage their combined work and retries.

## Results, limits and failures

`Result` has a private constructor and contains immutable selected state, the
same-GET observation, summed graph `usage()` and observed `forcedSequence()`.
`writeReply()` distinguishes `NOT_ATTEMPTED`, `ACKNOWLEDGED` and `FAILED`.
`writeAttempted()`, `writeAcknowledged()` and optional `writeFailureCode()` describe
this operation's SDK call. A `FAILED` reply can accompany a fully reconciled result.
The value retains no readers or resource ownership and grants no continuing lease.

The supplied [graph limits](object-checkpoint-graphs.md#operation-limits-and-usage)
cover **all passes in one operation**, including repeated dependencies and actual
prefixes. A submitted creation uses two candidate passes; a submitted replacement
uses two predecessor/candidate passes. Reconciliation without a remote PUT uses one pass.
Hard graph maxima still apply to the total, so some format-valid large graphs
need a different future bounded strategy rather than exceeding the policy.

Outside graph usage, actual submission makes at most four logical metadata SDK
calls: intent GET, initial head GET, conditional PUT and final head GET. Their
normal body allowance is 1,024 bytes. Reconciliation or already-selected submission
uses two GETs / 512 bytes. Existing framing/error-body caps, timeout/cleanup guards
and transport behavior apply separately. Local scans/copies/open recovery and
forces, which may include an appended suffix, are outside the graph byte budget.
These are not exact heap, CPU, device-write, aggregate wall-clock or throughput bounds.

Before PUT, pure/configuration, typed graph and local storage failures retain their
existing error behavior. `S3CheckpointPublicationException` distinguishes
`REMOTE_CONFLICT`, `STILL_PENDING` and second-pass `LIMIT_EXCEEDED`, with
`writeAttempted()` identifying an attempted SDK write. After a write attempt,
observation, graph or result-installation failures report `STILL_PENDING` with
their cause and any additional failed-reply evidence. Errors propagate too.

That error means no reconciled result was returned; it does **not** prove that
local installation had no effect. A failed rename/force or lost reply after result
installation may leave valid new expected state. Inspect by validated reopen;
never reset to the old state. Local I/O failures retain poisoning/quarantine and
all old, failed and uncertain objects under the [local state contract](local-s3-publication-state.md).

## Supported placement and evidence

Use one exclusive local publisher placement per remote namespace and stable,
trusted local paths. No external overwrite, deletion, rollback or lifecycle expiry
may invalidate retained evidence. Copied IDs and conditional ETags are not fences;
the protocol rejects observed contradictions but cannot detect every unseen clone.
S3 version IDs and strong single-key consistency do not supply a multi-key transaction.

The 78 publication cases comprise 39 API/scope/graph/budget invocations, 30
recovery/late-effect/process invocations and nine local-result-fault/concurrency
cases. The actual SDK/combined HTTP fixture tests creation/replacement, graph/budget
failure, changing tokens, lost and malformed replies, stale captured reads, late
effects across local reopen, native result-installation failures and real child-JVM
exits. Linux/macOS supported local placement is distinct from explicit Windows
capability skips. Fixtures do not authenticate signatures, emulate device power
loss or qualify a live provider, replication or performance. Run both builds in
the [S3 guide](s3-objects.md#build-and-verify).
