---
title: Checkpoint manifest format
description: Specify MeridianDB's bounded v1 checkpoint declarations, immutable segment references, exact reconstructed journal-prefix binding and complete-object verification.
---

# Checkpoint manifest format

A `CheckpointManifest` declares a complete contiguous transaction-history prefix.
It names immutable segments by content ID, encoded length and sequence range,
and binds their original frames to an exact journal-prefix byte count and SHA-256.
The types, pure codec and referenced-object verifier are implemented in
`org.meridiandb.storage.manifest`. They are experimental.

Decoding verifies manifest bytes and declarations. `CheckpointVerifier` separately
loads and verifies every referenced segment and reconstructs the declared prefix.
Neither operation checks an actual stored journal file, selects a generation or
publishes a durable checkpoint. The separate [local publication protocol](../development/local-checkpoints.md)
implements actual owner-journal verification and generation selection. Durable
engine writes remain planned; [local read recovery](../development/local-database.md)
now restores temporal state from the checkpoint plus journal tail. [ADR 0011](../decisions/0011-checkpoint-manifest-format.md)
records the representation decision.

## Generation, history and references

A manifest contains:

- An exact `DatabaseId` lineage and positive physical `generation`.
- A `JournalPrefix` with a nonnegative final transaction sequence, exact encoded
  prefix byte length and 64 lowercase hexadecimal SHA-256 digits.
- An immutable ordered list of `SegmentReference` values: `SegmentId`, complete
  object byte length, first sequence and last sequence.

Generation identifies a physical checkpoint candidate, independently of the
temporal cutoff. Generation 7 can cover commits 1 through 2; it does not imply a
seventh database transaction or prove that this generation is selected. Positive
Java `long` values are supported, including `Long.MAX_VALUE`; a later publication
protocol must handle allocation/exhaustion and progression explicitly.

References have unique IDs and cover exactly `1..journalPrefix.sequence`, without
gaps, overlap or reordering. Their ranges include every original transaction,
including requests that made no value change. Each individual segment has its own
[complete-object checks](immutable-segment-format.md). Request-ID uniqueness across
segments requires the whole-set verifier.

A sequence-zero genesis manifest has no segments. Its prefix is exactly the
encoded journal lineage header, not an empty byte string. The prefix hash includes
that header and every complete transaction frame through the declared sequence,
with no segment envelopes or footers. A later journal suffix is outside the binding.

## V1 binary layout

All integers use signed big-endian Java encodings. Exact identity strings reuse
the existing [journal v1 header](journal-format.md), including its lossless UTF-16
representation and CRC32C. `L` is the nested header length; `N` is the reference count.

| Field | Bytes | Meaning |
| --- | --- | --- |
| Magic | 8 | Hex `4d52444e4d414e00`, ASCII `MRDNMAN` followed by zero |
| Manifest version | 4 | `1` |
| Lineage header length | 4 | `L`, between 22 and 65,556, even |
| Journal lineage header | `L` | Exact complete journal v1 header |
| Generation | 8 | Positive physical generation |
| Prefix sequence | 8 | Final included sequence, zero for genesis |
| Prefix byte length | 8 | Bytes in the included journal prefix |
| Prefix SHA-256 | 32 | Raw digest of journal header plus included original frames |
| Reference count | 4 | `N`, zero through 4,096 |
| Header CRC32C | 4 | Checksum of all preceding manifest-header bytes |
| Ordered references | `56 × N` | Entries described below |
| Integrity footer | 32 | SHA-256 of header and references, excluding this footer |

The complete header is `80 + L` bytes. Each reference is exactly 56 bytes:
32 raw bytes of segment content ID, followed by encoded byte length, first sequence
and last sequence as three 8-byte integers. Total image size is `112 + L + 56 × N`.
There is no padding, recoverable tail or ignored extra data.

`ManifestId` is SHA-256 of the **entire image, including the integrity footer**.
The prefix hash, manifest footer and manifest ID cover three different byte ranges.
An expected manifest ID must come from independent retained metadata; extracting
an ID from an arbitrary input does not establish that it is the intended object.
Hashes detect mismatches; they do not authenticate a source or establish file force.

## Limits and decoding failures

The codec bounds each image to 1 MiB and each list to 4,096 references. A reference
can name at most 32 MiB and 65,536 records, matching the current segment format.
The declared journal prefix cannot exceed
`4096 × SegmentCodec.MAX_SEGMENT_BYTES + 65556` bytes, a conservative resource bound
of approximately 128 GiB. It cannot be shorter than its exact lineage header.
All count/length limits are checked before allocating from those declarations.

The decoder checks complete length, header CRC, footer, nested lineage, generation,
ordered ranges and reference limits before returning a value. Metadata consistency
does not prove that referenced objects exist or have the stated contents.

| `ManifestFormatException.Code` | Meaning |
| --- | --- |
| `INCOMPLETE` | Header or declared complete image is truncated |
| `UNSUPPORTED_VERSION` | Manifest or nested journal lineage version is unsupported |
| `CORRUPT` | Invalid framing, magic, checksum, footer, nested lineage or extra bytes |
| `LIMIT_EXCEEDED` | Image, lineage, reference count, per-segment or prefix resource limit is exceeded |
| `INVALID_CHECKPOINT` | Invalid generation, range coverage, fields or whole-set consistency |
| `IDENTIFIER_MISMATCH` | Complete valid manifest differs from its expected full-object ID |

Constructing invalid Java metadata directly throws `IllegalArgumentException` or
`NullPointerException`; malformed encoded fields become typed format failures.
Encoding checks v1 resource limits before allocating the output image. All decode
failures return no partial manifest.

## Verify the complete referenced history

```java
var decoded = ManifestCodec.decode(manifestImage, expectedManifestId);
CheckpointVerifier.verify(decoded.manifest(), id -> segmentImages.get(id));
```

`manifestImage` contains complete bytes. `segmentImages` maps IDs to complete
segment byte arrays, with a missing object represented by `null`. The source may
instead throw `IOException` for a retrieval failure. The caller keeps each supplied
array stable while it is being validated. These APIs have no built-in file access.

For every reference, the verifier checks the supplied image length, the segment's
complete encoding and expected ID, exact database lineage and first/last sequences.
It rejects request-ID reuse across segment boundaries, including identical retries.
It then hashes the exact reconstructed journal header and original transaction
frames in sequence order and checks both final byte length and prefix SHA-256.
The existing v1 codecs preserve those original frame bytes without normalization.

Resolution and hashing proceed one segment/frame at a time, without allocating a
whole journal-prefix buffer. The global request-ID set grows with history; a
currently decoded segment and temporary frame buffers also consume heap. Byte and
reference limits do not establish a total-heap or throughput guarantee. The v1
metadata limits imply at most `4096 × 65536` declared records; validation can still
be substantial. Keep the source's retrieval bounded independently: the verifier
cannot prevent a source from allocating or retaining unrelated objects.

Missing objects, length/lineage/range differences, global identity reuse and
prefix differences throw `ManifestFormatException(INVALID_CHECKPOINT)`. A corrupt
or mismatched segment propagates `SegmentFormatException`; a source I/O failure
propagates `IOException`. Verification reports success only after the whole graph
passes. Source calls made before a failure are not rolled back, and no transaction
consumer receives a partially accepted history.

This establishes that supplied immutable objects reconstruct the declared prefix.
The separate [local checkpoint store](../development/local-checkpoints.md) compares
the **actual stored journal prefix**, forces the owner journal and referenced
objects, then atomically selects a manifest with explicit directory synchronization.
The pure verifier supplies none of those receipts or reclamation permission.

## Ownership, example and independent fixture

Lists and referenced temporal values are immutable. Each encode returns fresh
caller-owned bytes; decode retains no input array. Keep the manifest input array
unchanged during decoding; the multiple validation passes do not provide an atomic
snapshot of concurrently mutable bytes. The resolver transfers no file
handle or deletion responsibility to the verifier. These values need no close;
[local segment readers](../development/local-segments.md) retain their own explicit
heap-reader lifetime.

After Java verification, run the in-memory two-segment example:

```bash
java --class-path target/classes examples/ManifestExample.java
```

The independent nonempty golden manifest uses lineage `db`, generation `7`, one
segment containing sequence `1`, and the journal golden put of `e = 7` over an
unbounded interval under request `r`. The segment is 158 bytes; the reconstructed
journal prefix is 78 bytes; the manifest is 192 bytes. Its fixed hashes are:

```text
prefix: fc0bccc3589bcab2f55535bf1acf6199dab723cbdbd1526329f511413e9e0dc3
footer: 9e3dd8ae82bd0a3238a899500d9d5d3ac917dcc1fdee93484c058358190629d4
id:     e40b7bf552979c50d92fa672bba19ed93f2716fca847410d5406157f59cfae8e
```

Focused codec and whole-set tests:

```bash
./mvnw -B -Dtest=ManifestCodecTest,CheckpointVerifierTest test
```

Their representation and dependency checks are separate from filesystem publication
and durable engine conformance. Run `./mvnw -B verify` for the full existing suite.
