---
title: Verified S3 publication preparation
description: Verify selected and candidate checkpoint graphs against the actual owned journal, synchronize local history and preserve an exact immutable and durable pending S3 intent before selection.
---

# Verified S3 publication preparation

`S3PublicationPreparer` composes existing SDK object/head access, complete graph
verification and durable local publication state. It verifies already installed
manifest/segment dependencies, synchronizes the actual owned journal and its name,
installs or verifies the exact immutable head image, then durably records that
pending intent. It performs **no mutable selection PUT**.

The class lives in `org.meridiandb.storage.s3` in the optional artifact. Preparation
is implemented and experimental. The separate [verified publisher](s3-checkpoint-publication.md) now supplies
conditional submission and uncertain-result reconciliation. Managed S3 startup
and writable recovery remain planned. The result
is historical evidence of preparation, not remote selection, a continuing lease,
transaction acknowledgement or permission to delete objects.
[ADR 0028](../decisions/0028-verified-s3-publication-preparation.md) records the decision.

## Resources and admission

Construct the preparer with an existing `S3ObjectStore`, `S3CheckpointHeads`,
`LocalS3PublicationState`, `LocalCheckpointStore` and `LocalJournal`, in that order.
All resources remain caller-owned; the preparer closes none of them. The local
state must have been explicitly created or opened with independently managed
configuration under the [local state protocol](local-s3-publication-state.md).
Its metadata directory remains separate from the database checkpoint directory.

Before a remote request, preparation checks:

- The candidate's exact bytes, binding, genesis/adjacent transition and local
  generation; an existing pending intent prevents another preparation.
- The **actual object and head clients'** endpoint spelling, explicit signing
  region ID and bucket against the durable target, plus the head client's binding.
  Supplying a matching target alongside a differently configured client cannot pass.
- The actual journal's canonical lineage, and its exact owned
  `transactions.journal` path in the supplied checkpoint store.
- The selected local image and capacity for another local generation. Storage
  failures poison that owner. Installation rechecks these conditions later.

Fresh local state accepts only an absent remote head. Initialized state requires
one same-GET observation matching **both original expected bytes and original
opaque token**. A changed token with equal bytes is still a conflict here; there
is no refresh/adoption or rebase. Missing, unexpected or malformed state fails
closed without selecting an older graph. Independently fresh local/remote
placement is a deployment condition, not something an absent GET proves.

Preparation holds cooperating resource monitors in this order: local publication
state, checkpoint store, journal, head client, object client. The complete operation
serializes with their ordinary transitions and close. Keep caller coordination in
that order and avoid calling back into these resources from SDK credential
providers. Reentrant preparation is rejected. Local locks do not exclude a copied
publisher on another machine or detect arbitrary external rollback.

## Verification and installation order

1. Complete admission, then read the current head once and compare it with the
   original durable expected state.
2. Use [graph verification](object-checkpoint-graphs.md) to verify the candidate
   alone for genesis, or both predecessor and candidate under **one shared budget**.
   Check complete dependencies, original record/request history and actual owned
   journal prefixes. Repeated dependencies count again. A damaged selected graph
   prevents advancing to candidate segment reads; there is no fallback.
3. Invoke `LocalCheckpointStore.synchronizeJournal(journal)`. It forces the actual
   journal through its owner and synchronizes its directory name. The force can
   include an appended suffix beyond the candidate's cutoff; it does not publish
   a local database transaction or alter the engine acknowledgement contract.
4. Create or fully verify/reuse `ns-N-head-H.bin`, containing the exact 256-byte
   candidate image. `N` is the namespace's 32 lowercase UUID digits; `H` is the
   complete head SHA-256. A conflicting/corrupt existing object fails; failed or
   uncertain output remains. No new publication UUID is generated.
5. Install the same exact pending image/precondition through the local state owner,
   including its file force, atomic rename and directory force. Return only after
   that completes. The mutable `ns-N-current.head` has not been written.

For independently configured, exclusively owned resources and an already installed
candidate graph:

```java
var preparer = new S3PublicationPreparer(objects, heads, localState, checkpoints, journal);
var prepared = preparer.prepare(candidateImage, ObjectCheckpointGraphs.DEFAULT_LIMITS);
var pending = prepared.pending().pending().orElseThrow();
var exactBytes = pending.image();
var originalPrecondition = pending.precondition();
var verifiedGraphs = prepared.verification();
```

`Preparation` has a private constructor. It exposes immutable pending metadata,
complete verification/usage, immutable intent reference, `intentCreated()` and
the observed `forcedSequence()`. It retains no segment images/readers or resource
ownership. The [verified publisher](s3-checkpoint-publication.md) reloads/validates
current state and reconciles using the original intent; this value does not grant
selection authority.

## Work limits and failures

The supplied `ObjectCheckpointGraphs.Limits` bounds all graph object bytes,
actual-prefix bytes, record/request work and logical graph fetches. In addition,
preparation has fixed bounded metadata work: one 256-byte head GET, one 256-byte
immutable intent PUT and at most one 256-byte GET for identical-content reuse.
That is at most three logical SDK operations and 768 metadata body bytes beyond
the graph budget, excluding protocol framing and error bodies, which retain the existing separate caps. Count them separately from
graph usage and from possible transport-level retransmission. Existing SDK
response, timeout and cleanup guards still apply.

Local capacity preflight and installation each perform bounded directory discovery
and selected-image validation under the [local state limits](local-s3-publication-state.md#files-and-bounded-selection).
Graph budgets exclude those scans, codec copies, initial journal-open recovery,
caller-retained objects and file-force work. The operation does not promise a JVM
heap, CPU, aggregate wall-clock or device-write byte bound.

Pure argument/transition errors occur before storage effects. Actual SDK target/
binding and journal-lineage mismatches use argument errors; journal-placement
and remote expected-state mismatches use I/O errors.
Existing typed graph failures and SDK/local I/O errors propagate. Missing objects,
denied access and corrupt selected graphs are failures, not absence or reuse.

Failure before local pending installation can leave a synchronized journal and
retained immutable intent without changing local expected state. A lost immutable
PUT reply is still a failed preparation; later identical reuse must verify the
stored bytes. Failure during local installation follows its poisoning/uncertainty
protocol. A lost reply after local pending was installed leaves that exact pending
state; another `prepare` is refused. This API neither clears pending nor attempts
conditional submission/reconciliation after any failure.

The 53 preparation cases comprise 36 API/graph/scope invocations, 15 ordered
boundary/reuse/immutable-reply/process invocations and two concurrency/reentrancy
cases. They use actual SDK/loopback HTTP and supported local storage, including
scope conflicts, original-token checks, copied divergent journals, bounded graph
work, retained intent/state and process boundaries. Native placement cases report
explicit capability skips separately; they establish no live-provider, TLS/signature,
remote durability, replication or throughput qualification. Run both builds in the
[S3 build guide](s3-objects.md#build-and-verify).
