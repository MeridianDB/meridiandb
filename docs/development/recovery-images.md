---
title: Independent recovery image checks
description: Validate recorded recovery history, immutable segments, manifests and continuation images independently under explicit byte and record limits.
---

# Independent recovery image checks

`scripts/storage/recovery_images.py` independently validates the binary images
exported by the [recovery comparison worker](recovery-worker.md). It uses Python's
standard library and its own CRC32C and format decoders, without calling Java
codecs or the older workload-history parser. It returns derived image facts only
after the complete supplied graph passes. [ADR 0053](../decisions/0053-independent-recovery-image-admission.md)
records this boundary.

These checks establish recorded structural consistency. Complete outer-request,
result, schedule, oracle, counter and saved-report admission remain unimplemented.
A consistently re-encoded history with different numeric values can pass this
structural stage; it does not establish the declared seed's generated history.

## Call the validator

The API accepts already extracted, bounded in-memory inputs and an independently
expected request. Keep those inputs stable throughout the call:

```python
from recovery_images import validate_images

facts = validate_images(
    source_history,       # headerHex and ordered framesHex
    continuation_hex,     # one exact fresh continuation frame
    fixture_images,       # four ordered initial nonjournal image sets
    expected_spec,
    expected_config,
    expected_limits,
)
```

Add `scripts/storage` to the importing program's module search path. The spec has
the worker's eleven seed/dimension/probability fields; configuration has `warmups`
and `repetitions`; limits have `maxObjectBytes`, `maxJournalBytes` and
`maxRecordWork`. Field sets and integer types are exact, including rejection of
booleans and floating-point values. Supply expected values from the caller rather
than treating an untrusted envelope's request as authority.

The function performs no filesystem or process operations, changes no input and
returns a fresh JSON-shaped summary. `RecoveryImageError`, a `ValueError`, rejects
invalid or excessive input without returning partial facts. This API supplies no
general JSON loader: callers must separately bound input loading, nesting and
nodes and reject duplicate keys and nonfinite values.

## Binary and graph checks

| Layer | Independently checked facts |
| --- | --- |
| Original journal | Complete v1 header and frames, CRC32C, UTF-16 identities, contiguous sequences, unique corpus request identities and finite single-operation corpus intervals |
| Immutable segment | Nested original header, expected range before body decoding, exact original frame slices, complete footer SHA-256 and full-object filename ID |
| Checkpoint manifest | Header CRC32C, complete footer, generation/name agreement, ordered dependency ranges and lengths, reconstructed original prefix length and hash |
| Continuation | Sequence immediately after the original history, fresh deterministic request/entity and the exact unbounded put of value 17 |

Integrity footers hash the bytes preceding the footer. Content IDs hash the
complete object including its footer. A self-consistent checksum alone cannot
replace the expected original history, range or lineage binding.

The four shapes are `JOURNAL_ONLY`, `GENESIS`, `CHECKPOINT_TAIL` and
`COMPACTED_CHECKPOINT_TAIL`, in that order. All use the same complete original
journal. Tail and compacted-tail shapes select `commits // 2`; journal-only
has no checkpoint and genesis selects zero. Source chunks contain at most
`min(16, max(1, cutoff // 2))` records and end at that cutoff. Genesis has a
generation-one empty checkpoint; the tail has generation-one source segments.
The compacted shape retains those exact old objects and adds a generation-two
manifest for one complete prefix segment. A one-segment source may be reused.

Only the exact initial nonjournal inventory is accepted. The checkpoint lock is
empty; a reader lock is not part of the initial image. The summary reconstructs
the original `transactions.journal` hash alongside supplied file hashes. Complete
inventory here means the supplied recorded image set, not reobserved native files.

## Work and allocation limits

| Bound | Scope |
| --- | --- |
| 2 MiB | Original header/frames plus every supplied nonjournal image copy across all four shapes, including repeated retained copies |
| 4,096 bytes | Continuation frame, separately from the aggregate raw-image cap |
| 4,096 bytes | Each original or nested frame in this numeric worker corpus; narrower than the general journal format |
| 4,096 records | Original records, continuation and records decoded from distinct segment objects; reused exact segments decode once |
| Caller checkpoint limits | Selected graph object bytes, original prefix bytes and twice-cutoff record work |

Field, hex, inventory, conservative matrix and aggregate work admission precede
binary decoding. Selected prefix/object byte limits and record limits reject
early; manifest-declared graph bounds are checked again before dependency
decoding. Hash-valid range changes still fail the expected-history binding.
Retained obsolete copies count toward raw-image admission even when they are
outside the currently selected graph. Warmups and repetitions do not multiply
image decoding because this input contains one initial image set per shape.

The returned summary includes original history length/hash, selected prefix
length/hash, continuation and continued-journal identities, raw-image bytes,
decoded record work, selected per-shape object sizes/counts and initial file
hashes. These are logical encoded facts, not heap measurements, actual I/O,
force receipts, source provenance or performance results.

## Verify Java compatibility on Linux

Run the independent format and smoke-boundary tests from the repository root:

```bash
python3 -m unittest discover -s scripts/storage/tests -p 'test_*recovery_images.py'
```

The 26 tests include independently built literal images, corruption/truncation,
re-signed malformed graphs, exact byte/work boundaries, compaction reuse, odd
cutoffs, failure receipts and input/result ownership.

After compiling test support, run the fixed compatibility smoke on Linux:

```bash
./mvnw -B test-compile
python3 scripts/storage/verify_recovery_images.py \
  --scratch-parent /absolute/existing/scratch \
  --source "$(git rev-parse HEAD)" --timeout 180
```

The caller supplies a trusted, stable scratch parent and naming ancestors.
Durability-related experiments additionally require those ancestors to be durably
named. This smoke's directory synchronization and successful file forces do not
qualify a device or establish power-loss behavior.

The command uses fresh private cooperating process groups, explicit JVM settings,
a cleaned environment, bounded logs and a deadline with cleanup reserve. It checks
seven-commit control and captured cases and a 496-commit captured case. The two
small cases must yield identical image facts; the largest decodes 993 records.
The create-only `summary.json` reports `image_checks_passed` solely for this scope.
The supplied source SHA is a declaration; the smoke does not certify clean source,
fresh classes, full result/matrix/counter evidence, oracle answers or native-file
completeness. It does not interpret or report timing comparisons.

Failures retain output, logs, fixtures and the active case's known process receipt.
A group failure before a receipt returns leaves that receipt unavailable. Linux
CI retains scoped evidence, including hidden control files, for fourteen days.
Temporary CI artifacts are compatibility evidence, not a durable evaluation
dataset. Complete source-bound supervision, independent saved-report readback,
memory diagnostics and evaluated adoption remain separate requirements.
