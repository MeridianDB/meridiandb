---
title: Attributed workload evidence
description: Run separately versioned Linux file-work experiments and independently check original-history bytes, ordered counter scopes and named ratios in live and saved evidence.
---

# Attributed workload evidence

Explicit attributed runs connect the [local lifecycle core](attributed-local-workloads.md)
to the Linux supervisor and saved-report reader. Schema three binds an instrumented
worker to its source, request, runtime and native file checks. It exports original
journal bytes and raw operation/auxiliary counters; Python independently validates
them before producing named ratios. [ADR 0047](../decisions/0047-attributed-workload-evidence.md)
defines the evidence boundary.

The original schema-one/two workloads and [Linux observations](linux-observations.md)
retain their formats and data. Attributed timings belong to the instrumented run.
Evaluated attributed profiles, durable evaluation data and integrated baseline
acceptance remain separate from this execution/reporting infrastructure.
The [Linux attributed observation protocol](linux-attributed-observations.md)
defines bounded profile/dimension contrasts and repeated controls for evaluation.

## Run on Linux

Use Python 3.13, JDK 25 and the repository's pinned Maven Wrapper. Choose an existing
trusted, stable, durably named scratch parent on the filesystem to examine:

```bash
python3 scripts/storage/run_workload.py \
  --attributed \
  --scratch-parent /path/to/trusted-scratch \
  --profile CORRECTIONS \
  --warmups 1 \
  --repetitions 2 \
  --timeout 300
```

The `--attributed` flag selects schema three and mode
`CALLER_THREAD_LOCAL_IO_INSTRUMENTED` on the request, worker and producer receipt.
Without it, the supervisor retains schema two. Independent E/C/Q/W options and
the four existing profiles remain available within their bounds. Default
CORRECTIONS/seed42 with warmup1/repetition3 fits the attributed scope budget.

Before inspecting the scratch parent or creating output, admission independently
reproduces the deterministic retry schedule and checks the complete scope budget.
It includes the extra random draw used to select each retry's original request.
Reference work, aggregate actions, 2,048 reserved scopes and existing byte/process
bounds all apply. A configuration accepted for ordinary timing can therefore be
too large for attribution.

The [supervisor's](supervised-workloads.md) clean compilation, source/class
stability, private process-group cleanup, deadline, retained-file and actual
journal identity checks still apply. Process-I/O observations remain outside
the operation captures. Failure and explicit unsupported outcomes retain their
raw evidence; they cannot supply a successful ratio report.

## Read a saved run

Copy `request.json`, `worker.json` and `report.json` from one completed run into
one directory. The same reader supports ordinary schema-two and attributed
schema-three evidence:

```bash
python3 scripts/storage/summarize_workload.py \
  --run-dir /path/to/saved-run \
  --format markdown
```

Use `--format json` for named role/counter matrices and exact ratio pairs.
`--expect-source` and `--expect-run` retain their exact identity checks. The
attributed analysis has output schema two with its explicit mode; ordinary
analyses retain output schema one. The Markdown title and mode identify
instrumented file work, with each warmup and measured repetition kept separate.

Saved reporting checks recorded consistency. It does not execute recorded
commands, reopen original database paths or authenticate the producer. Preserve
all three input files; their recorded hashes bind the displayed analysis to
specific bytes. Live filesystem/process observations belong to the producer's
supervisor and are not newly established by a later saved readback.

## Independent admission

| Evidence | Checks before reporting |
| --- | --- |
| Schema, mode and source | Exact request/worker/receipt fields, command flag, source/run/PID/config bindings and complete successful status |
| Original source history | Decode the original header and every non-retry frame; validate version, lengths, sequence/request/entity identities, finite intervals and both CRC32C boundaries; recompute complete SHA-256 and length |
| Logical denominators | Derive header, seed/mixed frame lengths and original counts from source bytes and the independently reproduced schedule; compare exported declarations |
| Raw scopes | Require the complete lifecycle order, including preparation, namespace, each operation/check, owner/prefix/reader acquisition and teardown |
| Native counters | Validate eight roles × fourteen counters, nonnegative integers, attempt/return/failure and byte coherence, with exact bounded sums |
| Derived reports | Recompute groups, totals and ratio pairs; reject changed cached values even when the worker file hash has been rebound |

Boolean counts, unknown roles, malformed shapes, missing/failed observations,
overflow and inconsistent source/history are rejected. Partial failure scopes
remain inspectable in worker output but never become admitted successful data.

The original-history vector contains the journal header and original frames as
bounded lowercase hex. Retries add no source frame. The decoder derives byte
denominators from these bytes, independently of native counters, requested I/O
lengths or final file sizes. The live supervisor also compares the complete actual
journal image to the same history identity.

## Ratios and coverage

Every ratio retains its integer `numerator`, integer `denominator` and computed
floating `value`. The integer pair is the exact definition; the displayed quotient
is a floating representation. Read and write numerators sum positive returned
adapter bytes for the named operation group, including repeated integrity work.

| Operation group | Logical denominator | Interpretation |
| --- | --- | --- |
| Load commits | Original seed frame bytes | `fileReadBytesPerOriginalFrameByte` and `fileWriteBytesPerOriginalFrameByte` |
| Mixed commits | Original mixed frame bytes | The same names, keeping new no-op commits and original identity |
| Exact retry / each query kind | Zero | N/A; retain actual counters without inventing a byte denominator |
| Create, segment/checkpoint/compaction and writable reopen | Complete original journal image | Explicit `fileReadBytesPerOriginalHistoryByte` and `fileWriteBytesPerOriginalHistoryByte` |
| All operation scopes in one iteration | Complete original journal image | A named lifecycle normalization, excluding auxiliary work |

The complete original-history denominator includes its lineage header and every
original frame at the final workload cutoff. It is a common lifecycle
normalization, not a claim that each operation consumed that image once. Force
attempts/successes/failures remain separate counters, without estimated metadata
bytes. Heap queries and pure planning can perform zero adapter I/O while still
doing engine work.

Preparation has its own totals. Namespace, validation, owner/prefix/reader
acquisition and teardown have separate auxiliary groups. Their bytes do not enter
operation numerators. External raw hashing and metadata inventory carry
`EXCLUDED_EXTERNAL_IO`; zero captured bytes in those scopes do not prove zero
filesystem activity. Export, process telemetry and supervisor validation are
explicit uncaptured categories with unavailable counters. Full named matrices
remain in JSON; Markdown shows compact operation, ratio and coverage tables.

## Bounds and verification

Worker/report inputs retain the existing 16 MiB per-document limit and shared
depth32/node500,000/string4,096 bounds. Attributed admission additionally caps the
complete run at 2,048 reserved scopes. Saved output remains capped at 4 MiB.
Maximum-admitted native fixtures exercise both decoders and output formats;
Linux CI also executes that boundary through the actual supervisor and saved
reader, alongside an attributed warmup/measured case and both ordinary cases.

These checks establish bounded execution, source admission and metric
recomputation. They supply no controlled-device comparison, cold-cache result,
arrival-latency measurement, power-loss guarantee or production qualification.
Evaluated findings must retain their own raw data, workload contrasts and
original baseline acceptance audit.
