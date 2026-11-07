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
answers are prepared outside operation clocks. Each new commit includes the
existing force/acknowledgement contract. Exact expected answers are checked outside
operation service clocks.

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

## Observed results — November 7, 2026

All ten cases completed successfully: forty fresh stores, ten warmups and thirty
measured iterations, with 6,656 original commits across all stores. Source
`fa0a26df9455e1d30f4c5bdb873a22436d52514f` was clean. Every case had the same
compiled-class fingerprint and recorded runtime/mount/cache context. The repeated
control reproduced both content fingerprints; the read-count variant preserved
the base write-history fingerprint.

Placement was Linux 7.0.0-1017-azure, x86_64, ext4, Eclipse Adoptium
25.0.5+8-LTS, four available processors and a 256 MiB worker heap. Recognized mount
options included `data=writeback`, `nobarrier`, `commit=30`, `discard`,
`errors=remount-ro`, `relatime` and `rw`; one option was unreported. Backing device,
controller, cache and block properties were unknown. This placement is not a
qualified production profile.

The [durable raw evidence](https://github.com/MeridianDB/meridiandb/tree/main/evidence/linux-observations/2026-11-07)
includes each request, receipt, raw worker result and log, with original and gzip
byte counts and SHA-256 hashes. Recomputing each saved analysis after downloading
the artifact reproduced its CI JSON byte-for-byte. Raw samples retain nanosecond
precision, all operations, warmups, counter availability and unrounded values.

Every `a / b / c` cell below lists measured repetitions 1, 2 and 3 in order;
percentiles are neither pooled nor averaged. Display values are rounded. These
short runs have continuing JVM/host variation and small query groups, so p99 is
descriptive, often the largest sample, rather than a stable production tail.

### Forced mixed commits and loop rates

New commits include no-op commits and exclude exact retries. The mixed phase
excludes initial entity seeding. The loop-rate numerator counts load commits,
mixed commits, retries and queries; its clock also includes validation and
instrumentation. It is not an ingestion-only rate.

| Case | Mixed new commits per iteration | p50, µs | p99, µs | Action-loop operations/s |
| --- | --- | --- | --- | --- |
| corrections-base | 112 | 323.784 / 290.051 / 266.798 | 726.506 / 470.668 / 400.537 | 4208 / 4843 / 5090 |
| append-base | 112 | 305.891 / 273.030 / 272.308 | 471.400 / 486.071 / 431.325 | 4176 / 4746 / 4935 |
| retractions-base | 112 | 308.787 / 305.372 / 262.640 | 560.447 / 456.793 / 410.617 | 4537 / 4696 / 5294 |
| hot-base | 112 | 303.086 / 289.701 / 279.913 | 536.491 / 718.191 / 456.542 | 4748 / 4683 / 5260 |
| corrections-history | 496 | 290.942 / 233.495 / 255.416 | 792.948 / 566.445 / 703.782 | 3521 / 4483 / 4055 |
| corrections-entities | 64 | 305.360 / 240.970 / 243.384 | 896.804 / 396.570 / 617.001 | 4552 / 5597 / 5531 |
| corrections-reads | 112 | 262.789 / 243.934 / 228.385 | 977.919 / 524.867 / 616.586 | 9179 / 10416 / 11005 |
| corrections-width | 112 | 283.589 / 233.255 / 214.190 | 552.450 / 1097.663 / 357.817 | 4852 / 5635 / 6438 |
| corrections-seed | 112 | 263.812 / 245.383 / 222.145 | 684.998 / 394.673 / 387.062 | 4910 / 5493 / 6015 |
| corrections-repeat | 112 | 261.899 / 241.350 / 215.362 | 460.867 / 439.600 / 371.964 | 4762 / 5755 / 6226 |

### Query service observations

The base and history cases have 13 Get, Scan, Select and FilterScan samples and
12 Sum samples per iteration. The read-count variant has 52 Get and 51 of every
other operator. Queries include historical and head cutoffs. Each cell is the
per-iteration p50 in microseconds; these cached heap-state queries do not measure
cold storage access.

| Operator | corrections-base | corrections-history | corrections-reads |
| --- | --- | --- | --- |
| GET | 7.714 / 2.736 / 2.915 | 7.534 / 3.065 / 2.665 | 1.603 / 0.992 / 1.322 |
| SCAN | 9.498 / 4.589 / 6.282 | 10.680 / 5.129 / 5.279 | 3.036 / 2.224 / 2.064 |
| SELECT | 21.120 / 7.634 / 8.416 | 26.299 / 9.969 / 10.470 | 7.583 / 4.809 / 5.050 |
| FILTER_SCAN | 10.199 / 5.199 / 5.160 | 11.181 / 5.330 / 5.410 | 2.945 / 2.304 / 2.044 |
| SUM | 22.783 / 11.832 / 9.387 | 27.953 / 13.054 / 12.904 | 6.752 / 4.247 / 4.559 |

### Recovery and maintenance

Each operation below has one sample per measured iteration, shown in
milliseconds. The checkpoint covers the full original history: its reopen has no
journal tail to apply. These observations do not demonstrate a checkpoint speedup
or isolate pure replay from validation and startup synchronization.

| Case | Journal writable reopen, ms | Checkpoint writable reopen, ms | Checkpoint publication, ms | Pinned-reader compaction publication, ms |
| --- | --- | --- | --- | --- |
| corrections-base | 7.218 / 3.239 / 2.966 | 9.548 / 5.910 / 4.104 | 7.725 / 6.249 / 5.127 | 9.355 / 8.342 / 5.308 |
| corrections-history | 52.277 / 26.258 / 27.494 | 26.663 / 34.964 / 32.353 | 13.143 / 8.804 / 12.219 | 11.213 / 9.587 / 12.733 |
| corrections-reads | 7.128 / 3.777 / 4.169 | 9.676 / 5.026 / 5.159 | 7.502 / 6.019 / 5.009 | 8.606 / 6.860 / 5.606 |
| corrections-repeat | 6.746 / 4.216 / 3.957 | 12.703 / 5.797 / 4.279 | 7.152 / 5.302 / 4.235 | 9.191 / 7.966 / 4.979 |

### Resource observations

All new-commit CPU/allocation samples in these cells were available. CPU and
allocation are executing-platform-thread counters, including their documented
instrumentation scope. Per-commit figures divide each mixed group's total by
that group's count; they are not latency percentiles. Heap is the maximum of
recorded before/after gauges across the iteration, including uncollected garbage.

| Case | CPU µs per mixed new commit | Allocated bytes per mixed new commit | Checkpoint reopen allocated MiB | Max sampled used heap MiB |
| --- | --- | --- | --- | --- |
| corrections-base | 105.622 / 73.800 / 72.196 | 16769 / 16769 / 16705 | 2.302 / 2.298 / 2.298 | 26.140 / 11.546 / 20.546 |
| corrections-history | 157.335 / 95.397 / 119.607 | 52073 / 51792 / 51793 | 25.874 / 25.874 / 25.873 | 101.671 / 133.084 / 150.084 |
| corrections-reads | 101.644 / 75.418 / 70.820 | 16769 / 16769 / 16705 | 2.302 / 2.302 / 2.298 | 28.140 / 13.640 / 22.640 |
| corrections-repeat | 100.368 / 80.093 / 68.990 | 16769 / 16769 / 16705 | 2.345 / 2.302 / 2.298 | 27.140 / 11.515 / 20.515 |

### Retained storage and process accounting

Logical lengths and encoded history bytes were identical across each case's
three measured repetitions. Allocation cells list all three observations because
the filesystem can attribute different blocks to equal-length files. The process
write-byte ratio includes the complete instrumented lifecycle and is constant
across these three repetitions; it is not engine or device write amplification.

| Case | Encoded journal bytes | Retained logical bytes | Attributed allocated bytes | Process write bytes / journal byte |
| --- | --- | --- | --- | --- |
| corrections-base | 10306 | 32898 | 69632 / 69632 / 69632 | 57.629 |
| append-base | 10432 | 33276 | 69632 / 69632 / 69632 | 56.933 |
| retractions-base | 9882 | 31626 | 69632 / 69632 / 69632 | 60.101 |
| hot-base | 10256 | 32748 | 69632 / 69632 / 69632 | 57.910 |
| corrections-history | 41794 | 131634 | 237568 / 233472 / 233472 | 55.765 |
| corrections-entities | 10490 | 33450 | 69632 / 69632 / 69632 | 56.618 |
| corrections-reads | 10306 | 32898 | 69632 / 69632 / 69632 | 57.629 |
| corrections-width | 10306 | 32898 | 69632 / 69632 / 69632 | 57.629 |
| corrections-seed | 10320 | 32940 | 69632 / 69632 / 69632 | 57.550 |
| corrections-repeat | 10306 | 32898 | 69632 / 69632 / 69632 | 57.629 |

All thirty measured process `read_bytes` deltas were zero; character reads were
nonzero. This does not mean the engine performed no reads. It neither isolates
queries nor establishes cold-cache behavior. Operation-attributed file read/write
counts and amplification are unmeasured by this ordinary timing dataset. The
separate [attributed evaluation](linux-attributed-observations.md) now supplies
bounded returned-adapter-byte measurements with its own raw evidence and coverage.

### Findings and next experiments

Increasing history from 128 to 512 commits raised mixed new-commit allocation
from about 16.7 kB to 51.8–52.1 kB per commit and checkpoint-reopen allocation from
about 2.3 MiB to 25.9 MiB. This is a useful allocation/reconstruction signal, even
though new-commit p50 did not rise consistently. The
[commit preparation](https://github.com/MeridianDB/meridiandb/blob/fa0a26df9455e1d30f4c5bdb873a22436d52514f/src/main/java/org/meridiandb/engine/InMemoryEngine.java#L62)
copies growing history/request maps, and recovery reconstructs complete historical state; this
is a plausible source of the extra allocation, not an isolated causal profile.
Measure that work before selecting a storage layout or native I/O accelerator.

The identical final control had commit p50 values 16.8–19.3% below the starting
case despite matching source, inputs and recorded environment. One warmup did not
remove run-order variation. Small apparent profile/width differences cannot be
treated as reliable gains. More reads increased the loop's operations/s partly
because its numerator contains more short heap queries; that is not increased
write throughput.

The separate [attributed evaluation](linux-attributed-observations.md) now
distinguishes repeated integrity reads, original append bytes and maintenance
writes while preserving force/ownership semantics. Next, profile the history-depth
allocation and reconstruction signals and compare one
bounded change against the same corpus and acknowledgement. Controlled device,
cold-cache, sustained-arrival and production qualification remain separate work.
