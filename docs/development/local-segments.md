---
title: Local immutable segment files
description: Write new MeridianDB history-segment files, verify expected content identity on bounded reopen and use immutable heap readers with explicit lifetimes.
---

# Local immutable segment files

`LocalSegmentFile` writes new immutable history files and opens fully verified
heap snapshots. It uses the [v1 segment format](../architecture/immutable-segment-format.md)
without changing transaction identity, interval bounds or operation order. The
adapter is experimental and independent of the memory engine. It supplies file
I/O, not manifest selection or durable database acknowledgement.

## Write and reopen

Use an existing local directory whose paths remain stable. The caller selects a
new filename and retains the returned `SegmentId` independently of the file. A
filename alone is not evidence of its contents.

```java
import org.meridiandb.storage.segment.ImmutableSegment;
import org.meridiandb.storage.segment.LocalSegmentFile;

var original = new ImmutableSegment(databaseId, records);
var expected = LocalSegmentFile.writeNew(path, original);
try (var reader = LocalSegmentFile.open(path, expected)) {
    for (var record : reader.segment().records()) {
        consume(record);
    }
}
```

`path` is a `Path`, `databaseId` is a `DatabaseId`, and `records` contains ordered
`JournalRecord` values. `consume` represents application scan logic. The returned
records are original transactions; this is not a temporal query engine.

`writeNew` encodes and checks format limits before opening the target. It opens
with `CREATE_NEW`, `WRITE` and `NOFOLLOW_LINKS`, loops until every byte is written,
checks the resulting size, calls `FileChannel.force(true)` and closes the channel.
Only successful force and close return an ID. Existing names, including symbolic
links and earlier failed candidates, are never overwritten. The ID is SHA-256 of
the entire image, including the format's separate integrity footer.

A failed write, force or close has an uncertain on-disk outcome. Its candidate
remains at the requested path for inspection. A retry cannot silently replace it;
use a new path or explicitly inspect and resolve it outside this API. The adapter
has no deletion, truncation, automatic repair or existing-object reuse operation.

## Complete bounded reads

`open` requires an expected ID before opening the file. It rejects non-regular
final components before opening and uses no-follow behavior on the actual open.
It checks the file size against the 32 MiB whole-image limit before allocating a
buffer, reads completely, probes EOF and rechecks size. Short reads/writes are
continued; sixteen consecutive zero-progress calls fail instead of spinning.

The codec verifies the complete envelope, nested journal representation, footer,
history and expected full-object ID before a reader is exposed. No truncated or
corrupt prefix is returned. Equal-length replacement with another valid segment
still fails the expected-ID check. Concurrent external mutation is unsupported;
validation binds the bytes actually read, not an atomic filesystem snapshot.

The adapter materializes the bounded image and decoded objects. The 32 MiB limit
bounds encoded bytes, not total heap or aggregate memory across readers. There is
no streaming, memory mapping or lazy file access.

| Failure | Result |
| --- | --- |
| Existing output, missing input/parent, non-regular input or operating-system I/O failure | `IOException` or its filesystem subtype |
| Shrink, growth, stalled I/O, force or channel-close failure | `IOException`; no successful writer result or reader escapes |
| Input larger than 32 MiB | `SegmentFormatException` with `LIMIT_EXCEEDED`, before image allocation |
| Incomplete, corrupt, unsupported or invalid history | The codec's typed `SegmentFormatException` |
| Complete valid object differs from expected content ID | `IDENTIFIER_MISMATCH` |
| Reader access after close | `IllegalStateException` |

Every read path attempts channel close; a reader is returned only after close
succeeds. If both the main operation and close fail, the close exception is
suppressed on the original failure.

## Reader lifetime and retention

`LocalSegmentReader` owns immutable heap values and no file handle. The input
channel has closed before `open` returns. Later file changes cannot alter an
already opened reader; a subsequent open validates the file again.

`id()` and `segment()` are synchronized with `close()`. Close is idempotent and
clears the reader's reference; later accessors fail. An immutable segment or list
already returned remains valid for as long as its caller retains it. There is no
callback holding a lock or channel while application code scans records.

Neither closing a reader nor completing a file write permits reclaiming journal
history or referenced segments. Manifest ownership, active checkpoint selection
and retention rules must establish that permission separately.

## Local filesystem boundary

Use a trusted, stable directory on a local filesystem supporting the requested
Java open options. Parent components may resolve through links; final-component
no-follow behavior does not protect against hostile ancestor replacement. Keep
paths stable during operations. The adapter takes no advisory file lock and does
not coordinate uncooperative writers, renames or deletion.

Creation of the name is atomic with `CREATE_NEW`; creation of the complete bytes
is not. Another opener can encounter a candidate still being written and fail
complete validation. The [manifest codec and verifier](../architecture/checkpoint-manifest-format.md)
check referenced objects and their reconstructed journal-prefix binding. Future
local publication must select only completed verified objects.
Successful file force also does not establish portable persistence of the new
parent-directory entry. There is no directory sync, atomic checkpoint publication
or power-loss-tested engine recovery here. [ADR 0010](../decisions/0010-local-segment-io.md)
records the primary evidence and alternatives.

## Run the example and checks

After verification, the example creates, forces and reopens a temporary segment,
checks exact identity/history and retains immutable values after closing its reader.
Successful temporary output is removed; an optional new file path retains output.

```bash
./mvnw -B verify
```

```bash
java --class-path target/classes examples/LocalSegmentExample.java
```

Focused adapter tests:

```bash
./mvnw -B -Dtest=LocalSegmentFileTest test
```

Real-file and injected-I/O checks cover complete reopen, exact records, create-only
ownership, expected identity, malformed/missing/changed files, limits, short and
stalled I/O, failed force/close and immutable reader lifetimes. Linux and Windows
CI run verification and the example. These checks do not simulate power loss or
establish durable engine conformance.
