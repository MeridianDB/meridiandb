---
title: Coordinated remote reclamation
description: Proposed single-placement admission, durable mutation evidence and verified ordering for reclaiming obsolete S3 checkpoint objects without racing readers or publishers.
---

# Coordinated remote reclamation

**Status: accepted protocol; pure coordination state/codec implemented, native
coordination and SDK participation pending.** The current S3 byte adapter
has no deletion API. [Checkpoint publication](../development/s3-checkpoint-publication.md)
and [managed recovery](../development/s3-database-writes.md) are implemented, while
this page specifies the additional coordination required before remote reclamation
can be offered. [ADR 0036](../decisions/0036-coordinated-remote-reclamation.md)
records the decisions and alternatives. The implemented
[bounded state format](s3-coordination-state.md) validates declarations only;
it supplies no lock, durable receipt or terminal-response provenance.

Reclamation preserves every logical transaction and the complete local journal.
It may remove only obsolete physical checkpoint representations after proving
their dependencies are unused. An inventory or a conditional delete by itself
does not establish that permission.

## Initial supported mode

The proposed first mode uses a fresh coordinated namespace in a general-purpose,
never-versioned bucket. All participants share one trusted canonical local control
placement on one host. The placement binds the exact endpoint spelling, signing
region, bucket, namespace, publisher and journal lineage. Bucket configuration,
managed keys and control files remain stable under the deployment's exclusive
administration; outside writers, lifecycle deletion, rollback and copied control
directories are outside this mode.

The executor must validate bucket mode before reclamation and reject enabled,
suspended, directory-bucket or unrecognized modes. A successful configuration read
is an observation; stable configuration remains a deployment requirement.
AWS distinguishes never-versioned deletion from versioned delete markers, and
an enabled bucket can subsequently be suspended rather than returned to its
original unversioned state. [DeleteObject semantics](https://docs.aws.amazon.com/AmazonS3/latest/API/API_DeleteObject.html),
[versioning states](https://docs.aws.amazon.com/AmazonS3/latest/userguide/Versioning.html).

Existing raw S3 clients, explicit pinned readers and current publication APIs
have no coordinated-namespace lease. They remain independently usable under their
existing contracts, but do not become participants automatically. Coordinated
reclamation cannot operate on a namespace with such untracked activity.

Initial enrollment requires independently fresh namespace identity and no earlier
remote mutations in that namespace. An absent current head alone proves neither
freshness nor the absence of outstanding requests. Adoption or migration of an
existing uncoordinated namespace is outside the first implementation. Missing,
damaged or rolled-back control evidence in an existing placement must fail closed;
it cannot authorize a new empty generation. Client shutdown or process restart is
insufficient evidence for enrollment after an unknown historical mutation.

## Admission and resource lifetime

| Participant | Protection and responsibility |
| --- | --- |
| Coordinated reader | Acquire shared namespace admission before selecting or fetching a graph; retain it through every lazy remote access and cleanup |
| Coordinated uploader or publisher | Acquire shared admission and existing concrete local ownership; account durably for every potentially mutating request |
| Root catalog and retention update | Register roots before remote use and serialize durable changes within the same coordination; readers admit only selected or explicitly retained roots |
| Reclaimer | Acquire exclusive maintenance admission before observing roots or planning effects; retain it through verification, ordered removal and durable outcome recording |

Any live shared admission defers reclamation without a partial removal. While
exclusive maintenance is held, new participating reads, uploads and publication
cannot enter. Eager immutable heap results can outlive their source; a closed lazy
reader must lose remote access before releasing its lease. A managed database may
conservatively retain shared admission for its whole lifetime.

Use one permanent native guard inode and one registered channel per canonical
identity in a JVM, with actual shared-lock support across cooperating processes.
Store immutable binding and activity generations beside the guard; never rename,
replace or delete its locked name. Reserve common file identity before opening
channels, reject aliases, and preserve native uncertain-close quarantine. Require
the existing directory durability capabilities rather than translating ordinary
I/O failures into unsupported-placement results.

Acquire namespace admission before publication metadata, checkpoint store, journal
and SDK client monitors. No participating API may return an unguarded mutable
client or borrowed delegate that bypasses this order. Close invalidates access
first, independently attempts all owned cleanup and preserves primary/suppressed
failures. A local file lock provides no cross-host fencing or protection against
an independent copied placement.

## Record every remote mutation attempt

Before a possible remote mutation, force a bounded immutable intent identifying
that particular attempt, exact target/key, operation and original condition/content
identity. Reserve both intent and completion capacity before sending the request.
This applies to immutable object creation and preparation's immutable intent upload
as well as mutable head selection and deletion. A logical `createOrVerify` call
does not hide its mutating PUT from this accounting.

Only the terminal result of that same attempt can supply its completion evidence.
The effect adapter must qualify which complete validated responses establish a
terminal result. Unrecognized responses, transport failure, timeout, malformed
success, `Error` or uncertain durable settlement retain the unresolved record.
The current SDK configuration explicitly uses one attempt; no automatic retry or
new condition may be introduced by the removal path. The selected SDK exposes
this configuration, and AWS counts the initial request in maximum attempts.
[SDK retry configuration](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/retry-strategy.html),
[maximum attempts](https://docs.aws.amazon.com/sdkref/latest/guide/feature-retry-behavior.html).

| Durable evidence | Admission consequence |
| --- | --- |
| Validated binding and no unresolved attempt | Normal participation may enter; cleanup still requires no live reader, no pending publication and complete root verification |
| Intent with no validated completion | Maintenance and unrelated new remote activity refuse; only the already admitted attempt's continuation may finish its own evidence |
| Validated completion for the exact attempt | Reopen may recognize that recorded terminal result; normal admission resumes only when every attempt and required local state are clean |
| Unknown outcome or damaged/missing control evidence | Retain quarantine and evidence across close and process restart; no automatic retry, reuse or reclamation |

A crash after intent force but before sending can conservatively leave an
unresolved attempt. A failed completion call may already have installed a valid
durable completion; validated reopen can recognize that exact record. It must
never manufacture completion from a remote observation or fall back past damaged
newest state. Process exit releases native locks, but does not clear the durable
remote hazard.

Logical publication reconciliation and physical attempt settlement are distinct.
A fresh exact candidate may resolve the existing publication protocol without
proving that every earlier attempt is finished. Cleanup requires both clean
expected/pending state and independently complete per-attempt evidence. Read-only
diagnostics may inspect retained evidence under a restricted path; they grant no
mutation, key reuse or quarantine-clearing permission. The initial protocol offers
no automatic clearing of an unresolved remote attempt.

### Why absence and retry success do not clear an older attempt

AWS documents client-side deadlines and warns that timeout or failure need not
mean no side effect. These sources provide no operation-drain certificate from
client close or restart. [Java timeout guide](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/timeouts.html),
[AWS timeout guidance](https://aws.amazon.com/builders-library/timeouts-retries-and-backoff-with-jitter/).

Consider this conservative failure schedule:

1. Conditional delete D1 has an unknown outcome and may still take effect.
2. A later delete D2 succeeds; a GET reports the key absent.
3. A publisher recreates the same content-addressed key with identical bytes.
4. D1 later matches its original tag and removes the now-live object.

This is a protocol deduction, not a claimed observed AWS incident. An ETag can
depend on content and need not distinguish recreations of identical content.
Strong consistency does not fence an older overlapping request or make a graph
update atomic across keys. [ETag behavior](https://docs.aws.amazon.com/AmazonS3/latest/API/API_Object.html),
[S3 consistency model](https://docs.aws.amazon.com/AmazonS3/latest/userguide/Welcome.html#ConsistencyModel).
The proposed protocol blocks D2 and key resurrection while D1 remains unresolved.
Neither a later success nor observed absence settles D1's individual record.

## Verify roots and bound the inventory

Under exclusive admission, validate actual client scope, bucket mode, complete
control evidence, publication state and a fresh exact selected head image/token.
Refuse pending publication, unresolved activity or a selection contradiction before
planning deletion. Hold the actual local checkpoint/journal owners while comparing
every root's complete graph with the stored full journal.

Retain the mutable selection key, its current immutable intent, selected manifest
and segments, and every explicitly retained or backup root and its dependencies.
Retained-root membership is durable and belongs to the same namespace coordination.
Admission must reject retired or unretained explicit roots; knowing an old head's
bytes alone is no future availability promise.

Root coverage must be authoritative and complete. Register every managed root and
its dependencies durably before any manifest/head upload or selection can make it
available. This includes pending and failed candidates, whose dependency protection
remains until an explicit validated retirement policy permits otherwise. The live
set includes every registered nonretired root except the exact eligible roots being
retired under exclusive maintenance, together with every mandatory retained root.
Preserve enough bounded graph/retirement evidence to protect interrupted candidates
after metadata removal.

Retaining an unknown manifest's bytes alone would leave it dangling if a shared
segment were deleted through another supplied candidate. Root completeness follows
from fresh enrollment and participation by every root-writing path; arbitrary
outside writes cannot be fenced by this local protocol. If an unregistered root or
uncertain catalog coverage is observed, defer deletion. An explicit candidate list
does not by itself prove complete liveness. Failed/orphan root evidence with known
dependencies must protect those dependencies, not just the root key.

Obsolete candidates are explicit bounded identities, never the result of an
unqualified listing sweep. Verify every supplied candidate root and its graph;
subtract live dependencies across all roots before identifying unused objects.
Unknown keys, failed candidates, orphans and diagnostic evidence remain retained.
No unbounded predecessor walk is required. A predecessor hash establishes an
adjacent head's identity; it does not by itself make every historical graph a
recovery dependency. Backup or audit retention must name the required roots.

Obtain each deletion candidate's complete verified body and original opaque ETag
from the same GET. Check exact namespace/key, SHA-256, encoded length and graph
binding before exposing an inventory. A separate HEAD cannot supply a token for
an earlier body read. The current byte adapter does not expose this evidence;
the coordinated implementation must add its own bounded observation path.

Bound roots, candidate count, total fetches/bytes/records and retained identity work
before requests or allocation, including repeated dependencies. Capacity exhaustion
precedes mutation. Report fixed head/configuration reads, control generations,
actual journal scanning, full replay, synchronization and retained heap separately
from graph budgets. An immutable preview grants no later permission to delete;
the executor recomputes under exclusive admission.

## Remove metadata before dependencies

The planned executor performs this sequence while retaining exclusive admission:

1. Recompute all protected roots, exact observations, liveness and work/capacity
   reservations. Refuse any changed or incomplete evidence.
2. Durably record the exact removal intent and each impending request before its
   effect. Conditional requests use the original same-GET tag for that object.
3. Retire proven obsolete immutable head-intent metadata, then obsolete manifests.
   Retain current selection and every live/retained metadata object. After each
   validated terminal reply, force that attempt's completion before starting the
   next mutation. Uncertain local completion stops the sequence immediately.
4. Remove only segments proven unused after that metadata retirement. Shared
   dependencies remain live even when one obsolete root is removed.
5. Confirm every attempt has its durable terminal evidence before releasing
   admission. Stop immediately on uncertainty, retaining quarantine and all
   remaining objects; settlement is per attempt rather than one final batch record.

The lower primitive is exact `DeleteObject` with `If-Match`, which AWS documents
for general-purpose buckets. Directory-only size/time predicates are not available
as additional safeguards in this mode. Wildcard conditions, condition refresh,
unconditional deletion and batch success summaries cannot replace exact evidence.
[DeleteObject request](https://docs.aws.amazon.com/AmazonS3/latest/API/API_DeleteObject.html).

The conditional-delete guide describes successful deletion, mismatched conditions
and concurrent-operation outcomes. The implementation must test its exact status/
body handling; an arbitrary 404 is not a successful-removal or older-request-drain
receipt. Conditional evaluation applies to the current version, which is another
reason the initial mode excludes versioning. [Conditional deletes](https://docs.aws.amazon.com/AmazonS3/latest/userguide/conditional-deletes.html).

Successful receipts account for exact removed names, identities and encoded bytes,
not freed physical blocks or replicated durability. If a process stops after
metadata retirement, remaining dependencies may become retained orphans. Reopen
validates the durable evidence and surviving selected/retained graphs; it does not
automatically replay deletion, invent missing roots or sweep those orphans.

## Required implementation and verification

| Boundary | Required evidence before offering reclamation |
| --- | --- |
| Namespace admission and durable hazards | Real shared/exclusive cross-process races, alias/identity and close failures, exact attempt records, native capability checks and restart-persistent quarantine |
| Verified inventory | Selected/retained/shared dependencies, actual full-journal binding, stale/foreign/corrupt evidence, exact same-GET body/tag and aggregate limits with zero delete effects |
| Conditional deletion | Real SDK request/condition checks, supported bucket mode, classified responses, late or dropped replies and same-key resurrection refusal across reopen |
| Ordered removal and integration | Actual supported-mode removal, live reader/publisher exclusion, metadata-before-dependency ordering, interruption at every effect and canonical/seeded recovery, retry and new-write behavior |

The existing recovery conformance supplies no remote deletion qualification.
Until these stages pass, managed remote objects remain unreclaimed. Cross-host
admission, legacy namespace migration, version-aware deletion, unresolved-attempt
recovery and performance measurement remain separate extensions.

Primary sources were checked November 1, 2026, starting at 19:12:05 UTC /
14:12:05 EST. The [exact AWS SDK 2.57.8 sources](https://repo.maven.apache.org/maven2/software/amazon/awssdk/s3/2.57.8/s3-2.57.8-sources.jar)
confirm `DeleteObjectRequest.Builder.ifMatch`;
this is source inspection, not an executed delete or live-provider qualification.
