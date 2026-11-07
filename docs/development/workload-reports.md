---
title: Saved workload reports
description: Check saved Linux workload evidence and render per-iteration latency, resource and accounting reports without reobserving the original machine or mixing warmups with measured samples.
---

# Saved workload reports

The [Linux supervisor](supervised-workloads.md) checks a run against its actual
processes, source and files. The report command checks the saved evidence later,
including after those original directories have disappeared. It validates the
request, worker bytes, producer receipt and raw-sample arithmetic before producing
JSON or Markdown. [ADR 0044](../decisions/0044-recorded-workload-reports.md)
defines this boundary.

This command reports recorded observations. It does not run the engine or establish
new filesystem, process, source or hardware qualification. Keep the original raw
files with the generated report so every displayed value remains traceable.

## Read a saved run

Use Python 3.13 on Linux or macOS from the repository root. Copy `request.json`,
`report.json` and `worker.json` from the same completed schema-two or attributed
schema-three run into one
directory. The copied directory can have a different location and name; the paths
inside the evidence retain their original meaning.

```bash
python3 scripts/storage/summarize_workload.py \
  --run-dir /path/to/saved-run \
  --format json
```

For a human-readable report:

```bash
python3 scripts/storage/summarize_workload.py \
  --run-dir /path/to/saved-run \
  --format markdown
```

Both formats go to standard output. The command changes no input file. Preserve
the three raw documents when saving or sharing the output.

To select one expected source revision or run, add `--expect-source` with its full
40-character commit SHA or `--expect-run` with its 32-character run ID. A mismatch
fails reporting. Without these options, a coherent older observation remains
reportable with its recorded identity; the current checkout is not substituted
for that identity.

Exit zero means `recorded_consistency_passed`. A nonzero exit refuses incomplete,
failed, mixed, malformed, excessive or inconsistent evidence. It prints a short
diagnostic to standard error and emits no successful report. This reader supports
schema-two and attributed schema-three supervisor documents; legacy worker
validation remains available in the live evidence layer. Attributed output is
explicitly mode-labeled and independently checks original history, raw scopes and
named ratios; see [attributed workload evidence](attributed-workload-evidence.md).

## What is checked

| Input | Recorded consistency checks |
| --- | --- |
| Request | Exact schema, run/source/profile/seed, independent dimensions and repetition budgets |
| Worker bytes | Exact recorded length and SHA-256, complete success envelope and original request/PID binding |
| Raw samples | Operation inventory, ordering, nearest-rank summaries, rates, optional-counter availability and clock equations |
| Producer receipt | Reported clean compilation, class/source fingerprints, settled process groups, command/heap bindings and no recorded deadline exhaustion |
| Recorded context | Java/kernel/architecture/filesystem agreement, cache policy and unknown device facts |
| Recorded accounting | Exact declared file names/lengths, per-file allocation units and totals, iteration paths, process-I/O deltas and their denominators |

The command checks consistency between documents, including a worker hash stored
in the receipt. These hashes do not authenticate the producer. The command opens
only the three copied input files; it never opens the original database paths,
checks a current PID or executes a recorded command. A coherent receipt remains a
statement about what the producer observed at that time.

Inputs must be ordinary files. Request JSON is capped at 64 KiB; report and worker
JSON at 16 MiB each, with an aggregate input limit and the shared JSON depth/node/
string checks. Output is capped at 4 MiB. Use a trusted stable directory while
reading. These bounds limit input/output work, not exact Python heap consumption.

## Read the metrics

Warmup and measured iterations have separate lists and report sections. Each
iteration keeps its own raw-sample distributions; the command neither averages
percentiles nor silently combines repetitions. Retain iteration variation when
designing a later comparison.

| Metric | Meaning |
| --- | --- |
| p50/p95/p99/max, ns | Operation service wall times from that iteration's raw samples, using nearest rank |
| Service operations/s | Group count divided by summed service time; zero-resolution time stays unavailable |
| Action-loop operations/s | All scheduled actions divided by loop wall time, including checks and instrumentation |
| Loop/service/validation/other, ns | Distinct scopes with the original additive clock equation |
| CPU, ns | Optional executing-platform-thread deltas with available-sample counts; unavailable totals remain unavailable |
| Allocated bytes | Optional executing-platform-thread allocation estimates with available-sample counts |
| Max sampled used heap, bytes | Maximum of before/after gauges for the group; not peak heap, retained heap or RSS |
| Logical / attributed allocated bytes | Declared file lengths versus the producer's recorded Linux block attribution |
| Process I/O | Deltas for the whole instrumented iteration, including validation, maintenance and runtime activity |

Creation, commits/retries, each read operator, writable reopen, segment writes,
checkpoint publication, pure compaction planning and pinned-reader compaction
publication retain their phase and operation names. Namespace preparation and
validation remain separate from service clocks. Small maintenance groups can
contain one sample, so their percentiles are descriptive observations.

Process-byte ratios use encoded journal bytes as their denominator. They are not
device amplification, cold-read or durable-media receipts. Recorded block
attribution does not establish exclusive physical extents or reclaimed storage.
The reader retains producer gaps and adds its own recorded-evidence limits.

CI exercises fresh default and scaled Linux runs, including a warmup and separate
measured repetitions, then checks their saved reports. Analysis files are retained
alongside raw evidence for 14 days. CI remains a correctness check; controlled
baseline evaluation and production-device qualification require their own evidence.
