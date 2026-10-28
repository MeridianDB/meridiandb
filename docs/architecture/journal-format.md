---
title: Journal format and local storage contract
description: Specify MeridianDB's experimental bounded journal v1 framing, exact transaction encoding, CRC32C validation, incomplete-tail classification and local append/force protocol.
---

# Journal format and local storage contract

**Status:** a pure Java v1 codec is implemented in
`org.meridiandb.storage.journal`, together with a
[local file adapter](../development/local-journal.md) for append, force and replay.
The `InMemoryEngine` write API remains memory-only. This experimental format is not a
compatibility promise. The
[storage research](storage-research.md) explains the comparable designs;
[ADR 0007](../decisions/0007-journal-format.md) records the selected tradeoffs.

## Minimum local capabilities

A local file implementation must demonstrate the following capabilities on a
stated filesystem. The local adapter implements these capabilities; the byte codec
alone does not establish them:

| Capability | Required behavior |
| --- | --- |
| Exclusive ownership | One writable owner for a lineage; reject a second cooperating process/instance before append or repair |
| Complete sequential append | Loop over short writes, serialize ordering and keep complete existing bytes unchanged |
| Explicit force | Distinguish copied/appended bytes from the prefix covered by a successful force; stop after uncertain write/force failure |
| Bounded sequential recovery | Validate header/framing before reading a bounded payload; enforce sequence and request identity without skipping damaged records |
| Explicit tail repair | Preserve the original suffix and require an explicit tested policy before truncating an incomplete candidate tail |
| Close and reopen | Release ownership predictably; preserve the stored lineage and exact ordered transactions across reopen |

Java's [FileChannel contract](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/channels/FileChannel.html)
provides positional/sequential I/O, locks and force operations. Its force guarantee
is specified for local devices, with metadata behavior dependent on the OS; it is
not a generic object-store guarantee. A local backend must document file creation
and directory-entry persistence assumptions. A process-restart test is not proof of
power-loss behavior on every device/filesystem. Object storage needs its own
capability and publication protocol.

## Version 1 byte layout

All numbers use **big-endian** byte order. Signed integer/long values retain their
exact bits. A file contains one lineage header followed by complete transaction
frames. Each frame represents one logical transaction; multiple frames may later
share one physical force operation.

### Lineage header

| Field | Bytes | Meaning |
| --- | ---: | --- |
| Magic | 8 | Hex `4d52444e4a4e4c00` (`MRDNJNL` followed by zero) |
| Version | 4 | Signed int `1` |
| Lineage | Variable | Length-prefixed exact Java string, defined below |
| Header CRC32C | 4 | CRC32C of every preceding header byte |

A complete valid header is required even for an empty journal. A partial header is
an error, not a recoverable transaction prefix. The lineage is authoritative for
all following record positions; no database ID is repeated inside each frame.

### Transaction frame

| Field | Bytes | Meaning |
| --- | ---: | --- |
| Magic | 4 | Hex `4d54584e` (`MTXN`) |
| Version | 4 | Signed int `1` |
| Payload length | 4 | Number of payload bytes, excluding both CRC fields |
| Prefix CRC32C | 4 | CRC32C of the preceding 12 prefix bytes |
| Payload | Variable | Sequence, original request identity and ordered operations |
| Payload CRC32C | 4 | CRC32C of all payload bytes |

Validate the prefix checksum before trusting payload length. A complete frame
has `20 + payload length` bytes. The checksum covers sequence and every logical
payload field. Checksums detect accidental corruption; they provide no
cryptographic authenticity and do not prove that bytes were forced.

The payload contains:

1. A positive signed 64-bit commit sequence.
2. The request ID string.
3. A positive signed 32-bit operation count.
4. Each operation in declaration order: one kind byte (`1` put, `2` retract), the
   entity ID string, one endpoint-flags byte, the selected finite bounds, then the
   signed 64-bit value for a put. Retractions have no value field.

Endpoint flag bit 0 includes a finite lower bound; bit 1 includes a finite upper
bound. Other bits are invalid. Included bounds are signed 64-bit microseconds,
lower then upper. An absent flag means unbounded. Preserve finite minimum/maximum
bounds separately from unbounded endpoints, even when point coverage is equivalent:
retry identity compares original ordered fields rather than resulting state.

### Exact strings and limits

Strings use a positive signed 32-bit **Java UTF-16 code-unit count**, then that
many unsigned 16-bit code units, without BOM or normalization. This intentionally
preserves every currently accepted Java identity, including supplementary
characters, composed/decomposed spellings, embedded zero and lone surrogates.
A replacing UTF-8 encoder could collapse distinct accepted IDs. Compact scalar
UTF-8 would require an explicit identifier-contract or format revision; this
prototype prioritizes exact identity over compact ASCII encoding.

| Bound | Version 1 value | Enforcement |
| --- | ---: | --- |
| Identity | 32,768 code units | Before string allocation/encoding |
| Operations per transaction | 65,536 | Before list allocation/encoding, also bounded by remaining payload |
| Payload per frame | 8 MiB | Before allocating the frame or accepting its declared body length |

These are format resource limits, not performance measurements or new temporal
semantics. An otherwise valid in-memory transaction can exceed the journal's
limits; the local writer rejects it before append without reserving its ID.
The codec computes encoded size before allocating output. Large transactions are
rejected rather than implicitly split across logical commits.

## Decoder and recovery policy

`encodeHeader(DatabaseId)` and `encodeRecord(JournalRecord)` produce deterministic
bytes. `readHeader(ByteBuffer)` and `readRecord(ByteBuffer)` decode one item at the
current position, using big-endian order regardless of the caller's buffer order.
They advance the caller's position only after successful complete decoding; order
and limit stay unchanged. Direct and read-only inputs are supported. The caller owns the input and must not
mutate its bytes concurrently with decoding.

`readRecord` returns empty only at clean EOF. An insufficient prefix (1–15 bytes),
or a valid complete prefix with insufficient payload/checksum bytes, raises
`INCOMPLETE_TAIL`. This is an **incomplete candidate**, not proof of a harmless
torn write: short arbitrary garbage is also insufficient to validate. A complete
bad checksum, invalid payload, unknown operation/flags, unexpected version, invalid
length or resource-limit violation fails closed. Never search for another magic
marker after an error. A valid checksum with a malformed complete payload does
not become a tolerated tail.

`scan(byte[])` is a convenience for an already materialized byte image. It requires
a valid complete header, sequences contiguous from `1`, and one new request ID per
frame. Even an identical repeated request ID is invalid history: a replay returns
the original commit and does not append a new one. Logical no-ops with new IDs
remain separate frames. Standalone record decoding validates a positive sequence;
only a whole-image scan establishes contiguous history.

On clean EOF the scan reports every complete record and `incompleteTail=false`.
On an incomplete candidate it reports only the prior validated prefix,
`validBytes` at the start of that candidate, and `incompleteTail=true`. It never
modifies the input or repairs a file. Corruption, unsupported versions and invalid
history return no successful scan result. A failed scan is not permission to
truncate an acknowledged transaction. The local adapter defaults to refusal and
requires explicit evidence-preserving repair of an incomplete candidate.

## Verification and boundaries

The codec suite includes independent golden bytes, every truncation offset,
checksum/length/version mutations, malformed complete payloads, exact identities,
operation order, numeric extremes and buffer-state behavior. Run it from the root:

```bash
./mvnw -B -Dtest=JournalCodecTest test
```

The ordinary `./mvnw -B verify` also retains engine/reference conformance and
concurrency checks. Codec tests establish representation and byte-prefix behavior.
The [local journal suite](../development/local-journal.md) additionally checks
short/failed I/O, force cutoffs, writer exclusion, replay and explicit tail repair.
[Local database recovery](../development/local-database.md) now verifies and
replays existing history for temporal reads. Durable engine writes, crash
conformance and journal retention still need their own integration evidence.
