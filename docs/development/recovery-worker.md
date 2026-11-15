---
title: Recovery comparison worker
description: Run the bounded test-only Java recovery producer, inspect its raw history and fixture envelope, and understand its failure, coverage and provenance limits.
---

# Recovery comparison worker

`RecoveryComparisonWorker` exports the [comparison core](recovery-comparison.md)
through a separate version-one protocol. It supports captured and uncaptured
measurements on the same fresh recovery matrix. It is developer test support,
excluded from both shipped archives. The worker uses only the JDK at runtime.

The protocol identifier is `MERIDIANDB_RECOVERY_COMPARISON`. Existing workload
wire schemas and datasets are unchanged. [ADR 0052](../decisions/0052-bounded-recovery-worker.md)
records this boundary. Linux supervision and independently checked saved reports
for this protocol remain unimplemented.

## Run the producer

Compile test support first. On a supported Linux or macOS filesystem, the following
shell command creates a fresh scratch parent and exports an uncaptured comparison.
This is a capability/correctness example: `mktemp` and `mkdir` alone do not establish
ancestor namespace durability. Durability-related runs require a trusted, stable,
durably named scratch parent and naming ancestors prepared by the caller:

```bash
./mvnw -B test-compile
recovery_run=$(mktemp -d)
recovery_run=$(cd "$recovery_run" && pwd -P)
mkdir "$recovery_run/fixtures"
recovery_id=$(python3 -c 'import uuid; print(uuid.uuid4().hex)')
recovery_source=$(git rev-parse HEAD)
java -Xms32m -Xmx256m \
  --class-path target/test-classes:target/classes \
  org.meridiandb.workload.RecoveryComparisonWorker \
  "$recovery_id" "$recovery_source" RECOVERY_UNINSTRUMENTED \
  CORRECTIONS 42 0 2 "$recovery_run/fixtures" "$recovery_run/worker.json" \
  16 128 64 8 67108864 67108864 262144
```

The source argument is a declaration, even when obtained from Git. This invocation
does not prove a clean checkout, freshly compiled classes or unchanged inputs.
The command requests zero warmups and two measured repetitions, preserving measured
AB/BA order. Fresh files do not establish cold cache. Retain the run directory
while inspecting a failure; no automatic cleanup or storage repair occurs.

To capture native adapter work, supply `CALLER_THREAD_RECOVERY_IO_INSTRUMENTED`
in place of `RECOVERY_UNINSTRUMENTED`. Each invocation chooses one mode and creates
its own fixtures. The worker does not combine timings across modes or cohorts.

## Arguments and admission

Exactly sixteen positional arguments are required, in this order:

| Positions | Meaning |
| --- | --- |
| 1–2 | Lowercase 32-hex run ID and declared full 40-hex source SHA |
| 3–4 | Exact mode identifier and corpus profile |
| 5–7 | Signed-long seed, warmups and measured repetitions |
| 8–9 | Absolute canonical scratch directory and fresh sibling output path |
| 10–13 | Entities, commits, queries and interval width |
| 14–16 | Checkpoint object-byte, journal-byte and record-work limits |

Profiles and dimension bounds follow the [corpus contract](workload-corpus.md).
Warmups are zero through two; measured repetitions are one through five. The
checkpoint limits follow [history acquisition](local-checkpoint-history.md).
The scratch directory must already exist, have no symbolic-link alias and use a
normalized path. Output is a safe single filename in its parent directory.
Both output and sibling `worker.partial.json` must be absent, including dangling
links. Use a dedicated parent per invocation to avoid partial-name collisions.

Before inspecting paths, the worker checks conservative matrix work. For
`opens = 16 × (warmups + repetitions)`, require:

- `opens × (2 × (2 × commits + queries) + commits + 16) ≤ 40,000`.
- `opens × (commits + 1)² ≤ 32,000,000`.

The first bound uses the maximum action schedule, so it can reject a request that
the in-process core would accept using its realized actions. Original corpus
reference/encoding limits still apply. The core separately admits actual fixture
bytes and checkpoint limits before creating a comparison directory. A later core
admission failure can still produce a failed worker envelope.

Raw original history plus the four retained nonjournal image sets share a 2 MiB
export admission; the continuation frame is at most 4,096 bytes. Initial inventory
counts and filenames follow the actual shape and admitted dimensions. JSON export
is limited to 16 MiB, depth 32 and 500,000 nodes. Export failure can retain a bounded
partial candidate. These are producer bounds, not a process deadline, heap cap or
disk reservation. Use an external supervising deadline for operational experiments.

## Envelope and raw evidence

The envelope contains the protocol/version/mode, request, worker PID, status,
bounded error, optional failed root, original source history, continuation frame,
per-shape fixture images and the complete result. The request retains the exact
parsed spec/configuration/limits and canonical scratch/output paths. The result
keeps corpus/runtime identities, raw observations and explicit gaps.

Original history consists of lowercase hex header and ordered frame images. Each
fixture-image entry holds the nonjournal initial files for one shape, including
control files and retained obsolete compaction objects. All equivalent cohorts
must declare the same initial fixture. The original journal is reconstructed from
the history images; the separate continuation frame describes the one new forced
put of value 17 for a fresh entity and request. Export rereads closed journals and
retained initial files outside operation clocks, then binds their lengths/hashes
and the continuation transaction before publishing success.

Each observation retains iteration/warmup/order, shape/access/strategy, directory,
initial file hashes, selected-prefix/object metadata, operation samples, validation
and teardown durations, and closed-journal identity. Journal-only checkpoint
sequence is JSON `null`; genesis is numeric zero. Captured operations explicitly
use `ADAPTERS_ONLY` with complete snapshots. Controls use `NOT_CAPTURED` with
`null` counters. Missing counters never mean zero I/O. Raw clocks and optional JVM
telemetry retain the [comparison scopes](recovery-comparison.md#operation-and-resource-scopes).

Export rejects mismatched request/runtime/input identities, incomplete or reordered
matrices, differing equivalent fixtures, invalid operation kinds/coverage and
changed retained bytes. These are producer consistency checks. Independent saved
admission must recompute complete inputs, matrix and denominators rather than
trusting these checks or a success field. The [independent image consumer](recovery-images.md)
now validates original binary formats and graph bindings. The [seeded corpus consumer](recovery-corpus.md) now reconstructs original bytes,
schedule and oracle-summary identity. Complete outer result/matrix and native
counter admission remain separate work.

## Failure and interpretation

| Exit | Meaning |
| --- | --- |
| 0 | The producer completed its checks and export |
| 1 | Execution or export failed |
| 2 | Argument, path or preflight admission rejected the request |
| 3 | The storage capability boundary explicitly reported unavailable directory sync |

Ordinary I/O failures are failures, not capability skips. Failure envelopes have
no result, continuation frame or fixture-image result. A known owned run root is
retained for inspection. Export uses create-only final-name installation and never
replaces a destination introduced after preflight. Partial and final files can
coexist after a publication cleanup failure; an existing final file does not
override a nonzero process exit. Runtime failures are reported when export remains
possible; Errors may propagate without a final envelope.

The producer does not supervise itself or certify source provenance. A later
consumer must check successful process termination, fresh request/output bindings,
actual source/classes/runtime/filesystem, complete independently decoded evidence
and bounded process cleanup. Peak/retained-memory diagnostics and evaluated default
adoption remain separate. Sampled heap and native capture differences establish
neither a memory advantage nor a production latency gain.
