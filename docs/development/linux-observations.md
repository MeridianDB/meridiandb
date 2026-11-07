---
title: Linux workload observations
description: Reproduce a bounded sequential temporal workload evaluation with independent dimensions, separate repetitions, exact saved evidence and explicit hosted-machine measurement limits.
---

# Linux workload observations

This evaluation examines the unchanged local engine with a bounded set of temporal
workloads. It uses the [Linux supervisor](supervised-workloads.md) and
[saved-report reader](workload-reports.md), preserving the current forced local
acknowledgement, complete history and original request identities. Hosted-machine
observations are exploratory evidence for choosing later experiments; they do not
establish controlled-device performance or production durability.

## Evaluation protocol

The repository's `scripts/storage/evaluation.tsv` fixes ten sequential cases. Each
row contains case name, profile, seed, entities, commits, reads and interval width.
Every case uses a fresh JVM with a 256 MiB heap, one complete warmup on a fresh
store, then three measured repetitions on separate fresh stores. Inputs and oracle
answers are prepared outside operation clocks. Each call includes the existing
force/acknowledgement contract and validates exact expected answers outside its
service clock.

| Case | Profile | Seed | Entities | Commits | Reads | Width |
| --- | --- | --- | --- | --- | --- | --- |
| corrections-base | CORRECTIONS | 42 | 16 | 128 | 64 | 8 |
| append-base | APPEND | 42 | 16 | 128 | 64 | 8 |
| retractions-base | RETRACTIONS | 42 | 16 | 128 | 64 | 8 |
| hot-base | HOT_ENTITY | 42 | 16 | 128 | 64 | 8 |
| corrections-history | CORRECTIONS | 42 | 16 | 512 | 64 | 8 |
| corrections-entities | CORRECTIONS | 42 | 64 | 128 | 64 | 8 |
| corrections-reads | CORRECTIONS | 42 | 16 | 128 | 256 | 8 |
| corrections-width | CORRECTIONS | 42 | 16 | 128 | 64 | 16 |
| corrections-seed | CORRECTIONS | 43 | 16 | 128 | 64 | 8 |
| corrections-repeat | CORRECTIONS | 42 | 16 | 128 | 64 | 8 |

Compare the four dimension variants with `corrections-base`: each changes one
requested dimension. Profile comparisons hold dimensions and seed fixed, but
their generated transactions differ; record realized mixes and both fingerprints.
Changing the read count preserves write history. A second seed is a sensitivity
check; it is not a statistical population. The final identical case exposes
run-order variation rather than assuming the host stayed constant.

On Linux with Python 3.13 and JDK 25, repeat one case from the repository root:

```bash
mkdir -p .local-qa
```

```bash
python3 scripts/storage/run_workload.py \
  --scratch-parent .local-qa \
  --profile CORRECTIONS --seed 42 \
  --entities 16 --commits 128 --queries 64 --interval-width 8 \
  --warmups 1 --repetitions 3 --timeout 90
```

Run each row in table order to repeat the evaluation. The dedicated Linux workflow
does this sequentially on one runner. It checks each saved report against the
exact source revision and retains request/report/worker files, logs and analyses
for 90 days. A failed case stops later cases and preserves available failure
evidence. Each supervisor has its own 90-second budget, including compilation;
the workflow also has a 20-minute ceiling. These are execution bounds, not latency
targets. Retained supervisor output is bounded per case as documented in the
[supervisor guide](supervised-workloads.md#limits-and-failure-handling).

## Measurement scope

Keep every measured iteration and its p50/p95/p99/max values separate. Report
operation service latency, the complete action-loop clock, validation and other
instrumentation scopes explicitly. Service operations/s excludes gaps and does
not measure offered arrival load. The mixed action schedule alternates ingestion
and pinned queries synchronously; it has no concurrent reader/writer arrival queue.

Journal and checkpoint writable reopen measure acquisition, verification, complete
reconstruction and startup synchronization. They do not isolate pure replay or
compare checkpointing with the same journal-only state. Checkpoint and compaction
publication include existing verification and force barriers, with one old reader
pinned during compaction. There is no asynchronous merge backlog. Obsolete objects
remain retained; file accounting is not a reclamation result.

Cache state is uncontrolled. The warmup runs the same lifecycle on another fresh
store in the same JVM; it neither establishes steady-state JIT behavior nor evicts
the OS page cache. No cold-cache experiment, forced GC, privileged cache operation
or device configuration change is performed. Process I/O includes validation,
maintenance and runtime activity. Ratios use encoded journal bytes as their
denominator and do not establish query-specific or device amplification.

CPU and allocation are optional platform-thread observations. Sampled heap is not
retained heap, peak memory or RSS. Unknown hardware/cache details and unavailable
counters remain explicit. The [Linux storage design](../architecture/linux-storage.md)
describes subsequent controlled comparisons and deployment qualification.
