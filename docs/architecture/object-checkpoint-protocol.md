---
title: Object checkpoint heads
description: A fixed-size checkpoint-head format separates remote selection order, immutable manifest identity, journal-prefix verification and conditional publication.
---

# Object checkpoint heads

A remote checkpoint needs one small selection record pointing to complete
immutable history. `ObjectCheckpointHead`, its codec and its pure metadata checks
are implemented in the JDK-only core. A separate optional
[S3 head adapter](../development/s3-checkpoint-heads.md) now provides bounded GET
and conditional PUT. JDK-only [graph verification](../development/object-checkpoint-graphs.md)
now validates complete dependencies and actual owned-journal prefixes under shared
operation limits. [Durable local expected/pending state](../development/local-s3-publication-state.md)
is available separately. [Verified conditional publication](../development/s3-checkpoint-publication.md)
and durable candidate reconciliation are implemented experimentally. Core
[pinned checkpoint read recovery](../development/object-checkpoint-recovery.md)
is available, with [managed S3 admission](../development/s3-database.md) implemented
separately. Writable object recovery remains planned. The existing
[S3 byte adapter](../development/s3-objects.md) remains immutable-only.

This specification defines the selection protocol those implementations must
follow. It preserves the existing local journal and force-before-publication
acknowledgement. [ADR 0023](../decisions/0023-object-checkpoint-head-protocol.md)
records the choices and alternatives.

## Three different coordinates

| Coordinate | Meaning |
| --- | --- |
| Head revision | Order of remote selection attempts that become selected; starts at 1 and advances by exactly one |
| Manifest generation | Physical generation declared in the existing immutable manifest; independent of head revision |
| Journal prefix sequence | Temporal commit cutoff whose complete original history the checkpoint represents |

The first remote selection can reference an existing manifest at generation 17
covering two commits: head revision 1, manifest generation 17, cutoff 2. A later
selection may retain that cutoff while changing the physical layout. It must
preserve the exact prefix length/hash at an unchanged cutoff. Neither metadata
counter assigns temporal commit order.

## Identity and namespace

A head binds three non-nil UUIDs: a configured **namespace**, a configured local
**publisher binding**, and a **publication identity** for one logical intent.
Generate a fresh publication identity when preparing a new intent and preserve
it, the entire encoded candidate and its original precondition across retries.
The codec never generates identifiers implicitly.

The lineage identifier is SHA-256 of the complete canonical
`JournalCodec.encodeHeader(databaseId)` image, including its version, exact UTF-16
code units and checksum. This preserves the existing 32,768-code-unit identifier
domain, including unpaired surrogates. Its largest header is 65,556 bytes; hashing
that header keeps the selection record fixed-size without narrowing the domain.
Do not hash a display name or a lossy UTF-8 replacement string instead.

These are identity declarations, not credentials or fencing tokens. A copied
directory can preserve all of them. Expected namespace/publisher values must come
from independent managed configuration; expected lineage and prefix evidence must
come from the actual exclusively owned local journal. Validating a head against
the values supplied by that same untrusted head establishes no independent binding.

The specified publisher reserves these flat keys within one configured bucket.
`N` is the namespace UUID as 32 lowercase hexadecimal digits without hyphens;
`H` is a complete encoded object's lowercase SHA-256:

| Key | Purpose |
| --- | --- |
| `ns-N-current.head` | The sole mutable selection record |
| `ns-N-head-H.bin` | Immutable copy of a publication-intent head image |
| `ns-N-manifest-H.bin` | Immutable existing-format manifest |
| `ns-N-segment-H.bin` | Immutable existing-format history segment |

All names fit the existing 128-character flat-key policy. Keeping dependencies
inside the namespace avoids introducing implicit cross-namespace reclamation
roots. These names specify the verified publisher's protocol; the generic
byte adapter continues to accept explicitly supplied `ObjectKey` values.

## Exact v1 wire format

An image is **exactly 256 bytes**, big-endian. UUIDs contain most-significant then
least-significant 64-bit halves; hashes contain 32 raw bytes. Lengths and revisions
use the positive ranges enforced by the Java model. There are no strings, lists,
variable allocations or optional trailing fields in this image.

| Offset | Bytes | Field |
| --- | --- | --- |
| 0 | 8 | Magic `4d52444e48454144` (`MRDNHEAD`) |
| 8 | 4 | Version, exactly `1` |
| 12 | 4 | Flags: `0` without predecessor, `1` with predecessor; all other values invalid |
| 16 | 16 | Namespace UUID |
| 32 | 16 | Publisher-binding UUID |
| 48 | 16 | Publication UUID |
| 64 | 32 | Canonical journal-header SHA-256 |
| 96 | 8 | Remote head revision |
| 104 | 32 | Complete manifest SHA-256 |
| 136 | 4 | Exact encoded manifest byte length |
| 140 | 8 | Journal prefix sequence |
| 148 | 8 | Journal prefix byte length |
| 156 | 32 | Complete journal-prefix SHA-256 |
| 188 | 32 | Complete predecessor-head SHA-256, or all zero |
| 220 | 4 | Predecessor byte length, exactly `256`, or `0` when absent |
| 224 | 32 | SHA-256 of bytes `[0,224)` |

The head's **complete content identity** is SHA-256 of all 256 bytes, including
the footer. It differs from the footer and from the provider's opaque ETag.
An integrity hash detects disagreement with bytes; it does not authenticate a
deliberately rewritten image or prove which head the service selected.

Revision 1 requires flag 0 and zero predecessor fields. Every later revision
requires flag 1 and a complete 256-byte predecessor reference. Revisions range
from 1 through `Long.MAX_VALUE`; the last value can be decoded but cannot advance.
Manifest lengths range from 1 through the manifest codec's 1 MiB cap; this is a
bounded declaration, not evidence that even a positive-length image is a manifest.
Prefix limits derive from the existing manifest/segment limits: at most
268,435,456 records and `ManifestCodec.MAX_PREFIX_BYTES`. An empty prefix hashes
exactly the lineage header, whose actual length is checked when binding a manifest.

Decoding rejects short/oversized images, unknown versions/flags, noncanonical
absent-predecessor bytes, invalid declarations, damaged footers and mismatching
expected complete references. It returns immutable values and retains no input.
Keep input unchanged during decoding. Encoding returns fresh canonical bytes.

## Pure checks and their boundary

Head declarations and helpers live in `org.meridiandb.storage.manifest`:

| API | Evidence supplied |
| --- | --- |
| `ObjectCheckpointHeadCodec.encode/decode/reference` | Canonical bounded image, integrity and complete content identity |
| `ObjectCheckpointHeadVerifier.lineageId` | Hash of the existing canonical journal-header encoding |
| `requireBinding` | Equality with independently supplied namespace, publisher and lineage expectations |
| `requireSuccessor` | Adjacent revision, unchanged bindings, distinct adjacent publication identity, exact predecessor image and noncontradictory prefix coordinates |
| `bindManifest` | Complete expected manifest identity/length plus matching lineage and prefix declarations |

`ObjectCheckpointHeadException.code()` distinguishes format, reference, binding,
transition and manifest failures. Invalid direct model construction and null inputs
use the usual Java argument/null exceptions.

`requireSuccessor` accepts an unchanged cutoff only with identical prefix
sequence, length and hash. An increased cutoff needs a strictly larger byte
length. It cannot prove that the new history extends the previous prefix.
`bindManifest` decodes the existing manifest format and checks the actual canonical
header length for an empty prefix; it does not fetch segments or compare journal
bytes. Manifest generation is intentionally not compared to head revision.

A test constructs two valid graphs with the same database lineage but different
first transactions. The divergent graph has a larger sequence/length and passes
the pure adjacent metadata checks. This demonstrates their limit: the publisher
must compare the predecessor's prefix against the actual owned journal at that
cutoff, then validate the candidate's prefix as well. Internal consistency of
each graph is insufficient to establish common history.

## Specified conditional publication

The [verified publisher](../development/s3-checkpoint-publication.md) implements
this sequence over admitted pending state and supported local placement. The separate
conditional-head adapter supplies its bounded provider operations; it does not
establish the other stages:

1. Acquire the supported local journal/publisher ownership. Load durable namespace,
   expected-head and pending-intent state. Refuse unsupported shared-writer modes,
   missing required local evidence or a new candidate while an earlier publication
   remains unresolved. Resume/reconcile that exact pending intent first.
2. Read the mutable head once, obtaining complete bounded bytes and one bounded
   strong ETag from the **same GET response**. Validate status, length, codec,
   independent bindings and response cleanup. A separate HEAD followed by GET
   cannot establish that pairing.
3. Verify the entire selected manifest/segment graph and reconstruct its original
   journal-prefix binding. Compare it with the actual owner journal at that cutoff
   and the durable expected head. A missing/corrupt selected dependency, unexpected
   predecessor or observed prefix conflict fails closed. Do not select an older
   generation or a LIST-derived candidate as fallback.
4. Prepare a candidate that passes the pure transition rules, verify its complete
   graph and actual owned journal prefix, and establish the required local force
   barriers. Install or fully verify/reuse every immutable dependency and the
   immutable candidate-head image before mutating the selection key. Bound
   aggregate graph bytes, records, request identities and repeated work, not just
   each individual image. Reserve capacity before effects.
5. Durably record the exact pending intent, expected predecessor observation and
   conditional operation before attempting selection. This state must survive
   process exit; an in-memory timeout flag alone is insufficient.
6. For an independently established uninitialized namespace, use
   `If-None-Match: *`. Otherwise use `If-Match` with the original observed strong
   ETag. Never use unconditional replacement, wildcard/list replacement or a token
   reconstructed from the content hash. Refuse missing, weak, malformed or
   over-limit tokens; the bound is 1,024 bytes for one quoted strong tag.
7. Reconcile success or uncertainty before acknowledging checkpoint selection or
   allowing another publication. Persist the new expected state when established.
   Preserve old, pending and uncertain objects. This selects a checkpoint only;
   it does not alter local transaction acknowledgement or permit deletion.

A head response must fit the fixed 256-byte format. Existing manifest/segment
decoding bounds still apply; the publisher must supply aggregate operation limits
before it can establish a verified graph. No unbounded ancestry walk or listing
is needed to validate one adjacent transition. Retained immutable intent history
is diagnostic evidence, not permission to infer selection from object existence.

The [owned-journal prefix API](../development/local-journal.md#bound-prefix-verification-by-encoded-bytes)
accepts a byte cap before framing/body reads. The implemented
[graph verifier](../development/object-checkpoint-graphs.md) uses that cap for each
actual owner-journal comparison and reserves object bytes/fetches, prefix bytes
and graph/journal record work across both graphs, including repeated dependencies.
It checks segment header ranges before record decoding. Those read-only results
still supply no durable intent, conditional publication or recovery protocol.
A separate [S3 state format](../development/s3-publication-state.md) now represents
exact target/bindings, expected observation and one pending intent with pure checked
transitions. [Durable local installation](../development/local-s3-publication-state.md)
now preserves those snapshots under exclusive ownership. Serializing alone does
not establish step 5. [Verified preparation](../development/s3-publication-preparation.md)
now composes actual client scope, complete graph/owner checks, synchronization,
immutable intent and durable pending state. It sends no selection PUT; the complete
conditional publisher is implemented separately through
[exact submission and reconciliation](../development/s3-checkpoint-publication.md).

## Failure and retry outcomes

S3's strong single-key consistency does not make several immutable uploads plus
the head update one atomic transaction. Conditional operations compare current
state. ETags are provider validators, not monotonically increasing versions.
Checked October 31, 2026 against the
[consistency model](https://docs.aws.amazon.com/AmazonS3/latest/userguide/Welcome.html#ConsistencyModel),
[conditional-write semantics](https://docs.aws.amazon.com/AmazonS3/latest/userguide/conditional-writes.html),
[GET API](https://docs.aws.amazon.com/AmazonS3/latest/API/API_GetObject.html) and
[ETag guidance](https://docs.aws.amazon.com/AmazonS3/latest/userguide/checking-object-integrity-upload.html#checking-object-integrity-etag-and-md5).

| Observation | Required handling |
| --- | --- |
| Complete current head equals the exact pending candidate | Verify its entire graph/actual prefix, then reconcile that intent as selected |
| Current head still equals the exact predecessor after a lost response | Preserve uncertainty: the original request may still finish. Any retry uses the same candidate bytes and original precondition |
| `412` precondition failure or `409` concurrent-operation conflict | Re-read and validate against the original intent/expected state; do not adopt a new token and silently rebase the old candidate |
| Missing head on first access | Genesis is possible only with independently established uninitialized local/namespace state and conditional creation |
| Missing head after initialization or unexpected successor/predecessor | Refuse publication and cleanup; retain evidence for explicit reconciliation |
| `403`, missing bucket, malformed response/head or damaged selected graph | Failure, not absence, successful reuse or permission to fall back |

Only `404 NoSuchKey` classifies a missing key, and that observation alone grants
no genesis permission. A current versioning delete marker can appear missing;
an ETag can recur if old bytes are restored. A version ID does not turn an
ETag-based `If-Match` into a compare-and-swap on version IDs.
[Versioning behavior](https://docs.aws.amazon.com/AmazonS3/latest/userguide/versioning-workflows.html).

Strict revision growth and fresh publication identities prevent a compliant
`A → B → A` reuse of the same head bytes. Adjacent checks do not detect arbitrary
external rollback, deletion, nonadjacent UUID reuse or an unseen copied publisher.
Durable expected-state evidence detects observed contradictions; it does not
replace distributed writer admission. Support one exclusive local publisher
placement per namespace, with no external overwrite, deletion, lifecycle expiry
or rollback of managed heads/dependencies. Local locks exclude cooperating owners
of the same paths; they do not exclude independent copied directories. These are
explicit deployment assumptions until a separate distributed protocol exists.

## Current verification

Independent Python `struct`/SHA-256 vectors are literal Java test expectations.
The revision-1 vector has complete ID
`51d39de57bc442d01e3bd6ffdb69e42b47405f7e69a00de5e680b8db019fc66b`;
its revision-2 successor has ID
`105b3638d6ca7fbda71139d696bba5157e48cb38543cdc91e6b368c78f9891e8`.
They bind the existing journal/manifest vectors for database `db` and distinguish
footer hashes from complete head identities.
[Exact vectors and field tests](https://github.com/MeridianDB/meridiandb/blob/main/src/test/java/org/meridiandb/storage/manifest/ObjectCheckpointHeadCodecTest.java).

The 46 focused tests cover all shorter byte lengths, corruption, reserved fields,
versions, expected references, numeric/lineage bounds, exact predecessor and
manifest binding, unchanged-cutoff replacement, overflow, large/unpaired-UTF-16
lineage and the copied-history limitation. These are pure format/metadata checks.
They execute no head GET/CAS, actual journal publication or reclamation, and do
not qualify a live provider or establish remote durability.
