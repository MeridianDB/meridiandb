---
title: Independent seeded recovery inputs
description: Reconstruct version-one seeded histories, schedule fingerprints and realized workload summaries independently before complete recovery result admission.
---

# Independent seeded recovery inputs

`scripts/storage/recovery_corpus.py` reconstructs the version-one
[workload corpus](workload-corpus.md) independently in Python. It binds recorded
original history, schedule/history fingerprints, encoded length and realized
workload summary to a caller's expected specification. It executes no Java code
and performs no filesystem or process operations.

Use it alongside the [binary image checks](recovery-images.md). Checksums and
consistent graph references can accept a differently encoded numeric history;
seeded reconstruction additionally requires the exact expected original bytes.
[ADR 0054](../decisions/0054-independent-seeded-corpus-admission.md) records this
separate input boundary. Complete worker request/result, matrix, native counters,
live-file/source provenance and saved-report admission remain unimplemented.

## Prepare and validate inputs

Add `scripts/storage` to the importing program's module search path:

```python
from recovery_corpus import prepare_corpus, validate_corpus

expected = prepare_corpus(expected_spec)
facts = validate_corpus(source_history, recorded_metadata, expected_spec)
```

`expected_spec` contains the eleven v1 seed, dimension and percentage fields.
`recorded_metadata` contains exactly `corpusVersion`, `fingerprint`,
`historyFingerprint`, `encodedHistoryBytes` and `workload`. The original image
has `headerHex` and ordered `framesHex`. Obtain the expected spec independently
from the caller's intended request. An envelope cannot authorize its own seed
or dimensions merely by declaring them.

Preparation returns the spec, original images and metadata. Validation returns
fresh derived metadata only after exact source and metadata agreement. Both
reject invalid typed inputs through `CorpusError`, a `ValueError`, without input
mutation or partial results. Keep supplied objects stable during each call.
Neither function is a general JSON loader; callers still bound JSON bytes,
nesting and nodes and reject duplicate keys and nonfinite values.

## Reconstruction and meaning

| Input fact | Derivation |
| --- | --- |
| Original history | Three salted Java-compatible 48-bit random streams, exact draw order, seed writes, append/correction/retraction/repeat intent and original journal framing |
| Schedule fingerprint | Full signed-long seed/spec, interleaved original/retry/read actions, pinned read cutoffs and canonical binary typed-query encoding |
| History fingerprint | Original header and committed frames only; retries and reads add no journal records |
| Realized summary | Original effects, no-ops, presence changes, geometric overlap, entity touches, scheduled intent, query operators, cutoff classes and empty/hit counts |

Java-compatible random generation follows the [Java 25 Random specification](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/Random.html),
checked November 15, 2026. It includes bounded-integer rejection with signed
overflow and signed 32-bit halves in `nextLong`. Values include signed-long
extremes. Seeds separated by an upper sixteen-bit difference can share effective
random states and original history; the schedule fingerprint still binds the
complete signed-long seed.

The summary oracle searches original history independently at each requested
cutoff and uses finite integer windows to compare effects. It preserves absence,
retractions, half-open intervals, value predicates and exact aggregate semantics.
Scheduled intent does not imply a realized effect: a requested retraction can
restore an empty primary window as a put, and a repeated operation may be a no-op.
Historical/head read counts use the head available at that scheduled action.

Preparation and validation enforce exact field/types, v1 dimensions, percentages,
the 32-million-unit reference-work bound, action/encoding bounds and finite query
fanout. The corpus has its own admission; recovery matrix limits remain separate.
The validator bounds canonical source hex and frame sizes before reconstruction.
It retains original history rather than a snapshot for every transaction cutoff.
These bounds limit preparation work and encoded inputs; they are not exact heap,
CPU deadline or storage guarantees.

## Linux compatibility

The existing image-only smoke remains unchanged by default. Opt into seeded input
checks after compiling test support:

```bash
./mvnw -B test-compile
python3 scripts/storage/verify_recovery_images.py --corpus \
  --scratch-parent /absolute/existing/scratch \
  --source "$(git rev-parse HEAD)" --timeout 180
```

The six fixed cases cover all four profiles, captured/control modes, negative and
minimum/maximum signed-long seeds, and the maximum admitted recovery history of
496 commits. The small captured/control pair must produce identical input facts.
The scoped version-two report uses `input_checks_passed`, retains original worker
output/fixtures/logs and known failed-case receipts, and reports no timing gain.
Its source SHA remains a declaration. The image-only version-one report and
existing ordinary/attributed workload schemas and datasets retain their behavior.

Run the pure corpus and smoke-boundary tests from the repository root:

```bash
python3 -m unittest discover -s scripts/storage/tests -p 'test_*recovery_corpus.py'
```

Literal vectors, independent expectations and adversarial cases complement fresh
Java compatibility. Reconstructed inputs and oracle metadata do not prove that
a measured engine executed the schedule or returned the expected answers. Full
result admission and source-bound supervision remain required before interpreting
recovery observations. No memory, device durability or adoption decision follows
from input consistency.
