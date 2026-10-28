---
title: Immutable history segment format
description: Specify MeridianDB's experimental immutable transaction-history segments, bounded v1 encoding, whole-object integrity and SHA-256 content identifiers.
---

# Immutable history segment format

`org.meridiandb.storage.segment` provides a pure Java codec for an immutable range
of original transactions. The representation preserves lineage, request identity,
operation order and valid-time bounds. It is a correctness baseline for future
storage layouts, not a current-state snapshot or an optimized query index.

**Status:** types and byte-array encoding/decoding are implemented. The separate
[local adapter](../development/local-segments.md) implements create-only files and
verified heap readers. The [manifest codec and verifier](checkpoint-manifest-format.md)
check full-history coverage and the reconstructed journal-prefix binding;
[local checkpoint publication](../development/local-checkpoints.md) supplies separate
filesystem verification and generation selection.
The `InMemoryEngine` write API remains memory-only; valid segment bytes do not establish persistence,
query visibility or permission to discard journal history.
[ADR 0009](../decisions/0009-immutable-segment-format.md) records the rationale.

## History and object identity

An `ImmutableSegment` has a `DatabaseId` and a nonempty immutable list of
`JournalRecord` values. Sequences are positive and contiguous; the first sequence
can be greater than one. Each request ID occurs once within the segment, even if
its repeated payload would be identical. A new request that leaves values unchanged
remains a distinct transaction.

Store original transactions rather than only final intervals. Replaying a segment
can then retain overwritten values, retractions and earlier system-time cutoffs.
Finite minimum/maximum bounds remain distinct from unbounded endpoints, and exact
UTF-16 identities, including lone surrogates, retain their original spelling.
The [temporal contract](temporal-contract.md) still owns logical behavior.

Uniqueness and ordering here apply to one segment. The
[checkpoint verifier](checkpoint-manifest-format.md) validates lineage, ordered
range coverage, cross-segment request identity and the exact reconstructed
journal-prefix binding. The empty genesis state needs no history segment.

## Version 1 layout

All numbers use big-endian byte order. An object consists of one header, a body
of complete journal frames and a 32-byte integrity footer.

| Header field | Bytes | Meaning |
| --- | ---: | --- |
| Magic | 8 | Hex `4d52444e53454700`, `MRDNSEG` followed by zero |
| Format version | 4 | Signed integer `1` |
| Lineage header length | 4 | Byte length of the following complete journal lineage header |
| Lineage header | Variable | Exact v1 [journal header](journal-format.md#lineage-header), including its magic, version, UTF-16 identity and CRC32C |
| First sequence | 8 | Positive first transaction sequence |
| Last sequence | 8 | Inclusive last transaction sequence |
| Record count | 4 | Positive transaction count; equals `last - first + 1` |
| Body length | 8 | Nonnegative byte length of all transaction frames |
| Header CRC32C | 4 | CRC32C of every preceding segment-header byte |

The header is `48 + lineage header length` bytes. The nested lineage header is
between 22 and 65,556 bytes and must be consumed exactly. An outer header does not
silently accept another nested journal version.

The body concatenates the existing v1 journal transaction frames in sequence order,
including their prefix/payload CRC32C values. Each frame retains its original
sequence, request ID and ordered operations. Count/range declarations must agree
with the actual records. No padding, extra frame or incomplete tail is permitted.

The footer is **SHA-256 of the complete header and body**, excluding the footer
itself. It validates integrity across the object. `SegmentId` is separately defined
as **SHA-256 of the entire encoded object, including that footer**, formatted as
64 lowercase hexadecimal digits. These two hashes have different coverage.

An independently supplied expected `SegmentId` binds a reference to complete
validated contents. Reading a stored footer alone is insufficient; decoding checks
all covered bytes and nested frames. Hashes and checksums provide integrity and
content identity, not authentication or a durable publication protocol.

## Limits and failure behavior

| Bound | Version 1 value |
| --- | ---: |
| Complete encoded segment, including header and footer | 32 MiB |
| Transactions per segment | 65,536 |
| Identity length | Journal v1 limit of 32,768 UTF-16 code units |
| Operations and payload per transaction | Journal v1 limits of 65,536 operations and 8 MiB payload |

Encoding validates every frame's exact size and the aggregate budget before
allocating the complete output image. It does not split a transaction or segment
implicitly. Decoding bounds the input and declared lengths/counts before allocating
from them. The 32 MiB bound applies to encoded bytes; the caller's objects, decoded
operations, strings, identity sets and temporary buffers also consume heap.

`SegmentFormatException` reports:

| Code | Meaning |
| --- | --- |
| `INCOMPLETE` | The complete declared immutable object is unavailable |
| `UNSUPPORTED_VERSION` | The outer or a nested format version is unsupported |
| `CORRUPT` | Invalid framing, checksum, digest, fields or complete-object byte consumption |
| `LIMIT_EXCEEDED` | A declared or encoded resource limit is exceeded |
| `INVALID_HISTORY` | Invalid sequence/range declarations, gaps, ordering or repeated request identity |
| `IDENTIFIER_MISMATCH` | Complete valid contents differ from the supplied expected object ID |

No error returns a usable prefix. A complete outer object containing a partial
nested item is corrupt; it is not eligible for the journal's incomplete-tail
repair policy. Trailing bytes also fail. A manifest must never admit a failed
object and continue with a later segment as if the missing history were present.

## Java usage and ownership

```java
var segment = new ImmutableSegment(databaseId, records);
byte[] image = SegmentCodec.encode(segment);
var decoded = SegmentCodec.decode(image);
SegmentId expected = decoded.id();
var verified = SegmentCodec.decode(image, expected);
for (var record : verified.segment().records()) {
    consume(record);
}
```

`databaseId` is a temporal `DatabaseId`; `records` is a list of `JournalRecord`.
Imports come from `org.meridiandb.storage.segment` and the existing temporal/journal
packages. `consume` is application scan logic. The decoded list is a simple ordered
scan baseline, not an engine query implementation.

The segment defensively copies its list and retains immutable transactions. Each
encode returns a new caller-owned byte array. Decode does not mutate or retain the
input array; the caller must not change it concurrently while validation runs.
Returned values are ordinary immutable heap objects, with no file handle to close.
This API materializes an entire bounded image and its decoded records. Local
file access remains a separate concern; the [local adapter](../development/local-segments.md)
uses the same bounded materialization with explicit reader close semantics.

Run the complete example after verification:

```bash
./mvnw -B verify
```

```bash
java --class-path target/classes examples/SegmentCodecExample.java
```

## Independent fixture and verification

The golden fixture contains lineage `db`, request `r`, sequence `1`, and one put
of entity `e` to value `7` over the unbounded valid-time interval. It has a 72-byte
header, 54-byte body and 32-byte footer, totaling 158 bytes. Its hashes are:

```text
footer: af1a2406823229a1e45d59ada79103d9b9f834e1c0171ef6a3e83478ba0e28fa
id:     d313d28a39ce9900e61fdead724735a02e09ab194861553910fe941d64b26ace
```

The [format tests](https://github.com/MeridianDB/meridiandb/blob/main/src/test/java/org/meridiandb/storage/segment/SegmentCodecTest.java)
construct bytes independently of the production encoder. They check every fixture
truncation and single-byte mutation, re-signed malformed inner fields/versions,
range/count/history failures, exact identity and interval extremes, maximum record
count, aggregate size preflight and input/output ownership.

```bash
./mvnw -B -Dtest=SegmentCodecTest test
```

These checks establish representation and integrity. Local immutable-file creation
and reader lifetimes have separate [adapter checks](../development/local-segments.md).
[Local checkpoint publication](../development/local-checkpoints.md) has separate
filesystem and failure checks. [Local recovery](../development/local-database.md)
now reconstructs temporal state, with a separate forced-write protocol. Process
crash conformance remains planned in the [roadmap](../roadmap.md). Codec success
alone is not a completed checkpoint.
