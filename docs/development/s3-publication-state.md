---
title: S3 publication state format
description: Preserve exact S3 target, expected observation and pending intent in a bounded snapshot, with pure checked transitions separate from durable installation.
---

# S3 publication state format

`S3PublicationState` and `S3PublicationStateCodec` represent exact expected and
pending publication metadata in the optional `meridiandb-s3` artifact. They perform
no filesystem or network operations and own no credentials or clients. Core and
ordinary local embedding remain independent of the optional SDK artifact.

The format and pure transitions are implemented. A separate
[local state owner](local-s3-publication-state.md) adds exclusive storage, ordered
forces, atomic installation and fail-closed reopen. The separate
[verified publisher](s3-checkpoint-publication.md) now composes complete graph checks,
original-precondition submission and durable candidate reconciliation. Encoding or decoding this value is
neither a durable intent receipt nor proof of remote selection.
[ADR 0026](../decisions/0026-bounded-s3-publication-state.md) records the decision.

## Exact state and transitions

The state stores one target, independent namespace/publisher/lineage bindings, a
positive local generation, an optional expected observation and at most one exact
pending candidate. It reuses the [256-byte head format](../architecture/object-checkpoint-protocol.md)
and [opaque token policy](s3-checkpoint-heads.md#token-policy).

| Expected observation | Pending candidate | Meaning and conditional precondition |
| --- | --- | --- |
| Absent | Absent | Declared uninitialized state; this alone grants no genesis permission |
| Present | Absent | Declared expected head; no request is pending |
| Absent | Present | Revision-1 create intent; `If-None-Match: *` |
| Present | Present | Exact adjacent replacement; `If-Match` uses the original expected token |

`fresh(target, binding)` creates local generation 1. `prepare(candidateImage)`
requires no existing pending intent, checks the complete candidate and binding,
then advances the local generation. Creation requires head revision 1; replacement
uses the exact expected predecessor, next head revision and existing pure transition
rules. Publication UUIDs and candidate bytes are supplied, never regenerated.

`recordSelected(observation)` requires a pending intent, matching scope/bindings
and the **exact pending candidate image**. It advances the local generation, stores
that observation as expected and removes pending from the new immutable value.
This is pure metadata recognition: the supplied observation may itself have been
restored from declared bytes. The method performs no GET, graph/actual-journal
verification, local force or durable installation. The complete publisher must
establish those facts before treating the result as selected state.

An old-head observation, missing head, 409/412 response or transport failure has
no transition that clears pending. There is no generic adopt, reset, clear,
cancel or rebase operation. Retry data comes from the existing pending value and
its unchanged expected observation. Local generation overflow rejects the next
transition. Local generation is independent of head revision and manifest generation;
decoding a positive generation does not prove the earlier transition history.

## Target and immutable values

`Target` contains the original endpoint URI, explicit signing-region ID and bucket.
Its strings use nonempty printable nonspace ASCII. Endpoint and bucket retain the
[S3 adapter's origin/bucket restrictions](s3-objects.md#configure-an-embedding-application).
The endpoint permits no user information, query or fragment. No credentials are
serialized. Region IDs are preserved without consulting a region catalog.

| Field | Maximum encoded bytes | Policy |
| --- | ---: | --- |
| Endpoint URI text | 2,048 | Preserve `URI.toString()` spelling, including case, explicit port and trailing slash |
| Signing-region ID | 128 | Preserve the explicit ASCII identifier; validity for a provider is a separate concern |
| Bucket | 63 | Existing general-purpose, lowercase, no-dot bucket policy |
| Expected ETag | 1,024 | One original quoted strong ASCII token, including quotes |
| Each head image | 256 exactly | Complete existing-format expected or candidate head |

`requireConfiguration(target, binding)` compares independent managed configuration.
Endpoint comparison uses **exact spelling**, intentionally stricter than
`URI.equals()` where scheme/host case may compare equal. Observations must use the
same exact endpoint spelling and bucket. Changing a region or endpoint alias is
not an implicit retry migration.

Expected observations are immutable heap metadata. Pending `image()` returns a
copy; `head()` and `reference()` expose immutable decoded metadata. Its operation
and `precondition()` derive from the unchanged expected observation. The literal
creation wildcard `*` differs from a replacement token containing quoted `"*"`.
Comma, literal backslash and empty quoted ETag content retain the existing token
policy. SHA-256 never substitutes for the provider token.

For independently configured variables, this example shows only the pure API:

```java
var initial = S3PublicationState.fresh(target, binding);
var pending = initial.prepare(candidateImage);
var image = S3PublicationStateCodec.encode(pending);
var reference = S3PublicationStateCodec.reference(pending);
var restored = S3PublicationStateCodec.decode(image, reference).state();
restored.requireConfiguration(target, binding);
var retryBytes = restored.pending().orElseThrow().image();
var precondition = restored.pending().orElseThrow().precondition();
```

The classes live in `org.meridiandb.storage.s3`. This code creates no local file
and attempts no conditional request. Fresh local metadata must not silently replace
missing initialized state; the [local owner](local-s3-publication-state.md) enforces
separate create/open policy.

## Version 1 encoding

All integers are big-endian. UUIDs contain most-significant then least-significant
64-bit halves. SHA-256 values contain 32 raw bytes. Text fields have a signed
32-bit byte count followed by exact ASCII bytes. There is no normalization,
padding, optional unknown field or trailing data.

| Offset | Bytes | Field |
| --- | ---: | --- |
| 0 | 8 | Magic `4d52444e53505354` (`MRDNSPST`) |
| 8 | 4 | Version, exactly `1` |
| 12 | 4 | Complete encoded byte length, including footer |
| 16 | 8 | Positive local state generation |
| 24 | 4 | Flags: bit 0 expected, bit 1 pending; all other bits invalid |
| 28 | 4 | Reserved, exactly zero |
| 32 | 16 | Namespace UUID |
| 48 | 16 | Publisher UUID |
| 64 | 32 | Canonical journal-header SHA-256 |
| 96 | Variable | Endpoint text, region text, bucket text, each length-prefixed |
| Following target | Optional | Expected head image, then length-prefixed quoted ETag |
| Following expected | Optional | Operation int: `1` create or `2` replace, then exact pending head image |
| Final 32 bytes | 32 | SHA-256 of every preceding byte |

The decoder caps the complete image at **4,096 bytes before copying**, and bounds
each variable field before allocation. The largest current valid combination is
3,923 bytes. The complete content reference hashes the **entire** encoded image,
including the footer; it differs from the footer and from an ETag.

Only the indicated optional fields may occur. The operation must agree with
expected presence. Both heads must match the persisted binding, and replacement
must identify its exact expected predecessor. Negative/over-limit lengths,
unknown versions/flags, reserved bytes, damaged hashes, invalid nested values,
contradictory operations and trailing data fail closed with no partial value.
Returned values and decoded identity use an owned bounded copy of the input.

`S3PublicationStateException.code()` distinguishes incomplete, unsupported-version,
corrupt, limit, invalid-state, expected-reference, binding and transition failures.
Decoding wraps contradictory nested state as `INVALID_STATE` while retaining its
cause. Direct invalid configuration/null arguments use ordinary Java exceptions;
bad candidate bytes supplied to `prepare` retain head-codec/argument failures.

Checksum success is structural evidence, not authentication or proof of a legal
prior transition, observed GET, complete graph, ownership or force. Initializing,
selecting and reopening durable local state uses a [separate I/O protocol](local-s3-publication-state.md). The
[full publication protocol](../architecture/object-checkpoint-protocol.md#specified-conditional-publication)
also retains original preconditions across uncertainty, actual graph/prefix checks,
single-placement assumptions and protection of old/pending/uncertain objects.

## Verification

The optional build runs 36 pure state/transition cases and 29 codec cases. Four
independent Python `struct`/SHA-256 vectors cover fresh, pending creation, expected
and pending replacement states. Tests cover all 1,758 shorter images and 1,758
single-byte mutations, checksum-correct malformed/nested values, exact text/token
bounds, the maximum legal image, full references, copy ownership and overflow.
Existing SDK lost-response/in-flight-write tests remain separate protocol evidence.
No local force/process-reopen or live-provider qualification follows from these
pure checks. Run the two builds in the [S3 build guide](s3-objects.md#build-and-verify).
