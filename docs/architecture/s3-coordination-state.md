---
title: S3 coordination state format
description: Bounded immutable root catalogs and exact remote-attempt declarations, with canonical encoding and explicit limits on what decoded state proves.
---

# S3 coordination state format

**Status: pure state model and codec implemented; durable coordination and SDK
participation remain unimplemented.** `S3CoordinationState` and
`S3CoordinationStateCodec` live in the optional `meridiandb-s3` artifact. They
represent the declarations needed by the accepted
[remote reclamation protocol](remote-reclamation.md). They perform no filesystem
or network I/O and acquire no locks. [ADR 0037](../decisions/0037-bounded-s3-coordination-state.md)
records this boundary.

Decoding a valid image proves bounded internal consistency. It proves neither
that the catalog contains every remote root nor that an operation was dispatched,
completed or durably recorded. Existing publication/recovery clients do not
automatically participate in this format. Remote deletion remains unavailable.

## Bind the placement and complete root metadata

Each state binds the exact endpoint spelling, signing region, bucket, namespace,
publisher, journal-lineage hash and a nonnil local placement UUID. Configuration
comparison requires an independent expected value for every field. URI host case
and a trailing slash remain distinct spellings. A matching placement UUID cannot
detect a copied directory or rollback; actual canonical-path ownership remains
the responsibility of the future concrete coordinator.

A root contains a complete 256-byte [head image](object-checkpoint-protocol.md)
and its complete [manifest image](checkpoint-manifest-format.md). Construction
checks exact manifest SHA-256 and encoded length, journal lineage and prefix.
The state also checks the head's independent namespace/publisher/lineage binding.
Root entries sort strictly by full head content ID. Duplicate heads and reused
publication UUIDs are invalid. Returned byte arrays are copies and collections
are immutable.

The catalog derives typed immutable intent, manifest and segment keys from each
root. A shared key must always declare the same content ID and encoded length.
Retained roots cannot be retired. Retirement leaves a permanent tombstone and
does not release catalog capacity; the same root cannot be registered again or
revived. Nonretired roots protect their dependencies in pure mutation checks.

These are metadata checks, without segment bodies, actual journal comparison,
selected-head authority or complete root-liveness evidence. A retirement declaration
does not authorize physical removal. Concrete integration must establish those
facts under the [accepted admission and retention rules](remote-reclamation.md#verify-roots-and-bound-the-inventory).

## Preserve each original attempt

One unresolved attempt may exist. Its compound identity is placement UUID,
contiguous positive ordinal and nonnil nonce. The next ordinal is exactly the
completed watermark plus one. Reusing a nonce with a new ordinal produces a
different attempt. Every attempt preserves its exact operation, key, content
reference and original condition; replacement also preserves the original head
reference.

| Operation | Required declaration |
| --- | --- |
| `CREATE_IMMUTABLE` | Exact typed dependency of a registered nonretired root; create-only condition shape |
| `CREATE_HEAD` | Mutable selection key and registered nonretired revision-one head; create-only condition shape |
| `REPLACE_HEAD` | Mutable selection key, both registered nonretired original/candidate roots, exact adjacent successor metadata and original opaque tag |
| `DELETE_IMMUTABLE` | Exact typed dependency of a retired root, no nonretired root sharing that key, and original opaque tag |

Create-only declarations encode no tag; future dispatch must supply `If-None-Match:
*`. Replacement and deletion require the original opaque tag. The model never
refreshes a condition or turns a changed request into the same attempt.

| Pure transition | Result |
| --- | --- |
| Register, retain or retire | Increment physical state generation; refuse while an attempt is unresolved |
| Begin | Preserve the full original attempt, increment generation and reserve a second generation for completion |
| Settle | Require equality with the complete pending attempt; preserve its terminal declaration, advance watermark and clear only that pending attempt |

The last completion contains the exact last attempt, its ordinal and a declared
status: 200 for PUT operations, 204 for deletion, or conditional rejection 412.
Other statuses cannot settle an attempt. These accepted values are a declaration
schema, **not SDK response qualification**. A future effect owner must validate the
actual same-attempt response before it can durably install a completion. Arbitrary
caller values, a matching decoded status, later retry success or observed absence
supply no terminal provenance.

Pending state survives encoding/decoding and blocks catalog changes and new
attempts. There is no timeout, reset or automatic retry transition. Completion
requires the original full attempt, including its original content and condition.
The watermark and last record compress declarations; they are not a substitute
for the full immutable durable receipt history required by the future ledger.
Historical last-completion checks permit its root to have been retired later,
without granting new mutation permission for that retired root.

Inspection and encoding are public. Mutating transitions remain package-private
for future composition with an actual owner. The public `fresh` factory creates
only empty in-memory declarations; it does not enroll a namespace.

## Bound state and completion capacity

| Limit | Version 1 bound |
| --- | --- |
| Whole encoded image | 1,048,576 bytes |
| Roots, including tombstones | 32 |
| One manifest image | 16,384 bytes |
| Segment-reference occurrences across all roots | 4,096; repeated/shared references count again |
| Endpoint, region and bucket text | 2,048, 128 and 63 printable nonspace ASCII bytes |
| Key and opaque condition text | 128 and 1,024 printable nonspace ASCII bytes |
| Completed and pending records | One exact last completion and at most one unresolved attempt |

The bounds give an upper image-size bound of 537,715 bytes even with maximum
target strings, 32 maximum-sized manifests and two maximum-sized replacement
attempts. Completion growth therefore fits below the 1 MiB codec cap. Beginning
also reserves two positive `long` generation increments; a pending generation
cannot be `Long.MAX_VALUE`. Exhausted generation or attempt ordinal refuses new
work rather than wrapping. Concrete durable storage must separately reserve its
own files, names, space and force operations before remote effects.

Decoding checks the whole-image cap before copying, verifies the outer digest
before nested decoding, and checks text lengths and root counts before allocating.
It preflights each manifest's lineage/header length and declared reference count,
charging the aggregate 4,096-reference budget before nested manifest decoding.
Malformed metadata returns no partial state. Bounded size does not prove full
remote coverage or limit future journal scanning and graph-body verification.

## Canonical version 1 bytes

Integers are signed big-endian. UUIDs occupy 16 bytes, content hashes 32 bytes.
All reserved fields must be zero; unknown flags, operations or versions fail.
Text is a four-byte byte length followed by printable nonspace ASCII. The
SHA-256 footer covers every preceding byte; the separate object reference hashes
the **entire image including that footer**.

| Offset | Bytes | Fixed field |
| --- | --- | --- |
| 0 | 8 | ASCII `MRDNSCST` |
| 8 | 4 | Version 1 |
| 12 | 4 | Total image length including footer |
| 16 | 8 | Positive physical generation |
| 24 | 16 | Placement UUID |
| 40 | 16 | Namespace UUID |
| 56 | 16 | Publisher UUID |
| 72 | 32 | Journal-lineage SHA-256 |
| 104 | 8 | Nonnegative completed ordinal |
| 112 | 4 | Flags: bit 0 pending, bit 1 last completion |
| 116 | 4 | Root count |
| 120 | 8 | Reserved zero |

The fixed header is followed by endpoint, region and bucket text; sorted root
entries; optional last completion; optional pending attempt; and the 32-byte
integrity footer. A root entry contains four-byte flags (bit 0 retained, bit 1
retired), its 256-byte head, a four-byte manifest length and manifest bytes.
Retained and retired together are invalid.

An attempt contains placement UUID, eight-byte ordinal, nonce UUID, four-byte
operation code (1–4 in the operation-table order), four-byte reserved zero, content
reference, optional original-head reference for replacement, key text and condition
text. A reference is four-byte encoded length followed by SHA-256. Condition text
is empty only for create operations. A completion appends four-byte status and
four-byte reserved zero to its attempt. No padding or trailing fields are allowed.

## Verification and limits

The optional build includes 108 coordination tests: 50 state/transition checks
and 58 codec checks. Five independently assembled literal images bind exact
bytes, length and full-image hash:

| Image | Bytes | Full-image SHA-256 |
| --- | --- | --- |
| Fresh | 208 | `af3343f60cf34a4b5b4906192331a5cbffbc33abb496ee0a66e909ad329479e6` |
| Registered root | 608 | `209acd900969fcb2bb0bd85a5e844d022fb5d5f6f9f68cde5a7907a8231e7177` |
| Pending create | 809 | `2aac563e45969bc647f9f6f601f6d3354dd9c94580cf7c8aec2d3290df5e04f7` |
| Completed create | 817 | `c36d4dd5193686e29cc7fcffbd464c6646c5683caea9e7ee8722b6f25e173e93` |
| Pending replacement | 1,466 | `152f58c8bb259bba0cc1efa27e97eb8c627d371a730ec2b1e18df7d5b2f46a49` |

The test-only hex resources reuse independent head/manifest literals and assemble
coordination fields with separate byte packing and SHA-256, without the production
coordination encoder. Tests reject every proper prefix and single-byte mutation
of these five images, then exercise checksum-valid malformed fields, nested limits,
ordering, immutable lifetimes, exact settlement, shared dependencies, tombstones
and numeric/capacity boundaries. These are pure checks, with no native capability
skip or remote service. Follow the [optional build commands](../development/s3-objects.md#build-and-verify).

Permanent native guard ownership, durable state/receipt generations, fresh-only
namespace admission, complete root registration in every SDK path, actual terminal
provenance and integrated process/late-effect conformance remain required.
Inventory, exact same-GET deletion evidence and ordered removal are also pending.
No codec result permits reclamation or changes temporal acknowledgement.
