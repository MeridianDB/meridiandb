---
title: Linux attributed file-work observations
description: Reproduce bounded Linux operation-level read, write and force measurements with independently checked logical denominators, repeated controls and explicit instrumentation coverage.
---

# Linux attributed file-work observations

This evaluation measures completed adapter file work for the local temporal
engine. It uses the separately instrumented [attributed evidence path](attributed-workload-evidence.md),
preserving forced commit acknowledgement, exact original history and historical
answers. Read/write ratios normalize actual returned bytes by independently
decoded original journal bytes. They describe file work at the adapter boundary;
they do not measure physical device traffic.

The earlier [timing and resource observations](linux-observations.md) retain their
original data and interpretation. Instrumented operation clocks belong to this
evaluation and cannot establish a speed change against those ordinary timings.

## Evaluation protocol

`scripts/storage/attributed-evaluation.tsv` fixes ten cases in execution order.
Each runs in a fresh JVM with a 256 MiB heap, one warmup and two measured
repetitions, each using a separate fresh store. Corpus and expected answers are
prepared outside operation service clocks. Every new commit retains its existing
force/acknowledgement barrier. Independent answer/history checks remain outside
those service clocks and have separate file-work scopes.

| Case | Profile | Seed | Entities | Commits | Queries | Width | Reserved scopes |
| --- | --- | --- | --- | --- | --- | --- | --- |
| corrections-base | CORRECTIONS | 42 | 16 | 128 | 64 | 8 | 1,472 |
| append-base | APPEND | 42 | 16 | 128 | 64 | 8 | 1,424 |
| retractions-base | RETRACTIONS | 42 | 16 | 128 | 64 | 8 | 1,472 |
| hot-base | HOT_ENTITY | 42 | 16 | 128 | 64 | 8 | 1,520 |
| corrections-history | CORRECTIONS | 42 | 16 | 192 | 64 | 8 | 1,934 |
| corrections-entities | CORRECTIONS | 42 | 64 | 128 | 64 | 8 | 1,448 |
| corrections-reads | CORRECTIONS | 42 | 16 | 128 | 128 | 8 | 1,856 |
| corrections-width | CORRECTIONS | 42 | 16 | 128 | 64 | 16 | 1,472 |
| corrections-seed | CORRECTIONS | 43 | 16 | 128 | 64 | 8 | 1,460 |
| corrections-repeat | CORRECTIONS | 42 | 16 | 128 | 64 | 8 | 1,472 |

The history/entity/query/width variants each change one requested dimension from
`corrections-base`. Profiles hold dimensions and seed fixed but change generated
transactions; realized effects and both content fingerprints remain recorded.
The query-count variant preserves original write history. The second seed checks
sensitivity, and the final identical control checks repeatability across run
order. Two measured repetitions do not establish a stable latency tail or a
statistical population.

The common one-warmup/two-measured configuration keeps all cases below the existing
2,048 reserved-scope cap. History 192 and queries 128 are bounded contrasts; this
is a separate dataset from the earlier ordinary history-512/query-256 experiment.
Scope reservations include conservative failure/cleanup capacity and exceed the
successful scopes actually emitted. No evidence cap is raised for this evaluation.

On Linux with Python 3.13, JDK 25 and a clean checkout, repeat one case from the
repository root using an existing trusted scratch parent:

```bash
python3 scripts/storage/run_workload.py \
  --attributed --scratch-parent /path/to/trusted-scratch \
  --profile CORRECTIONS --seed 42 \
  --entities 16 --commits 128 --queries 64 --interval-width 8 \
  --warmups 1 --repetitions 2 --timeout 90
```

Repeat each TSV row in order to reproduce the complete protocol. The dedicated
Linux workflow preflights every case before execution, runs them sequentially on
one hosted placement, checks saved reports against the exact source revision and
retains raw inputs/logs/analyses for 90 days. A failure stops subsequent cases and
preserves available evidence. Each supervisor has a 90-second execution budget
including compilation; the job has a 20-minute ceiling. These are process bounds,
not latency targets. Per-document, output, parser and retained-byte bounds remain
those of the [attributed evidence contract](attributed-workload-evidence.md#bounds-and-verification).

## Interpretation and coverage

Each measured repetition retains every raw counter matrix and exact integer ratio
pair. Commits use original seed/mixed frame bytes. Recovery, segment/checkpoint/
compaction and complete-operation totals use the full original journal image as
an explicitly named normalization. Retry/query byte denominators are zero and
remain N/A, including when captured bytes are also zero. Force outcomes are
reported separately; they are not estimated metadata bytes.

Preparation, validation, owner/prefix/reader acquisition and teardown remain
separate from operation numerators. External hashing/inventory carry excluded
coverage; export, process telemetry and supervisor validation have unavailable
captured counters. A captured zero does not erase these gaps. Integrity reads
performed inside an engine operation remain attributed to that operation.

The workload is synchronous, with heap-backed queries and one old reader pinned
during compaction. Writable reopen includes acquisition, verification, complete
reconstruction and startup synchronization. There is no asynchronous merge
backlog, checkpoint-tail speedup claim or reclamation measurement. Cache state is
uncontrolled: fresh stores and one warmup neither evict the page cache nor prove
steady-state JIT behavior. Returned file bytes can repeat while physical traffic
differs. Device/controller/cache, cold-cache, sustained-arrival, power-loss and
production qualification remain separate from these hosted observations.
