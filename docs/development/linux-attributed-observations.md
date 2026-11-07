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

## Observed results — November 7, 2026

All ten cases passed: thirty fresh stores, ten warmups, twenty measured iterations
and 4,032 original commits across all stores. Source
`b79a0366fca194dd56f326cd3f0952a31598a958` was clean. Every case recorded the same
compiled-class fingerprint, runtime and mount/cache context. Matching metadata
does not prove an unchanged physical host. Eight cases with unchanged requested
inputs reproduce the corresponding earlier ordinary content/history fingerprints;
history/query contrasts intentionally use the smaller dimensions above.

Placement was Linux 7.0.0-1017-azure, x86_64, ext4, Eclipse Adoptium
25.0.5+8-LTS, four available processors and a 256 MiB worker heap. Recorded mount
options included `data=writeback`, `nobarrier`, `commit=30`, `discard`,
`errors=remount-ro`, `relatime` and `rw`; one option was unreported. Device,
controller/cache and block properties remained unknown. This is an exploratory
placement, without production qualification.

The [durable raw evidence](https://github.com/MeridianDB/meridiandb/tree/main/evidence/linux-attributed-observations/2026-11-07)
retains exact requests, receipts, worker counters/original-history bytes and logs,
with original/gzip lengths and SHA-256 hashes. After downloading, all ten saved
JSON analyses reproduce their CI bytes without original Linux directories;
Markdown readback also passes. Each warmup and measured repetition remains
separately accessible. Raw sums were also checked directly against the matrix
columns, independently of the derived group totals.

### Operation totals

Within each case, the complete attributed counter matrices and derived groups
were identical across its warmup and both measured repetitions. Each figure below
therefore applies separately to each repetition; figures are not averaged. The
final control also reproduced the base operation totals. `H` is the complete
original journal image, including its header. Read/write normalizations cover
operation scopes only; auxiliary and unavailable work appear separately below.

| Case | H bytes | Operation read bytes | Operation write bytes | Read/H | Write/H | Successful forces |
| --- | --- | --- | --- | --- | --- | --- |
| corrections-base | 10,306 | 137,612 | 32,898 | 13.353 | 3.192 | 174 |
| append-base | 10,432 | 139,250 | 33,276 | 13.348 | 3.190 | 174 |
| retractions-base | 9,882 | 132,100 | 31,626 | 13.368 | 3.200 | 174 |
| hot-base | 10,256 | 136,962 | 32,748 | 13.354 | 3.193 | 174 |
| corrections-history | 15,564 | 207,166 | 49,384 | 13.311 | 3.173 | 246 |
| corrections-entities | 10,490 | 140,004 | 33,450 | 13.346 | 3.189 | 174 |
| corrections-reads | 10,306 | 137,612 | 32,898 | 13.353 | 3.192 | 174 |
| corrections-width | 10,306 | 137,612 | 32,898 | 13.353 | 3.192 | 174 |
| corrections-seed | 10,320 | 137,794 | 32,940 | 13.352 | 3.192 | 174 |
| corrections-repeat | 10,306 | 137,612 | 32,898 | 13.353 | 3.192 | 174 |

The exact integer numerator/denominator pairs are retained in JSON; displayed
quotients are rounded. These lifecycle normalizations include one segment-write,
checkpoint and compaction cycle. They do not describe steady ingestion or device
amplification. Force counts do not estimate force latency or metadata write bytes.

### Base operation breakdown

The base seed commits write 1,274 bytes and mixed new commits write 8,990 bytes,
exactly their original frame lengths. Each performs one successful journal force
per new commit, including no-op commits. Neither group reads adapter file bytes.
Exact retries and all five query operators perform zero captured adapter work;
their zero byte denominators remain N/A. Pure compaction planning also has zero
adapter work, but its explicitly named full-history normalization is 0/H.

| Operation | Read bytes | Write bytes | Successful forces | Read ratio (basis) | Write ratio (basis) |
| --- | --- | --- | --- | --- | --- |
| Create | 322 | 196 | 10 | 0.031 (H) | 0.019 (H) |
| Load commits (16) | 0 | 1,274 | 16 | 0.000 (seed frames) | 1.000 (seed frames) |
| Mixed new commits (112) | 0 | 8,990 | 112 | 0.000 (mixed frames) | 1.000 (mixed frames) |
| Exact retries (13) | 0 | 0 | 0 | N/A | N/A |
| All query operators (64 calls) | 0 | 0 | 0 | N/A | N/A |
| Journal writable reopen | 20,808 | 0 | 5 | 2.019 (H) | 0.000 (H) |
| Segment writes (8) | 0 | 11,240 | 8 | 0.000 (H) | 1.091 (H) |
| Checkpoint publication | 21,742 | 602 | 12 | 2.110 (H) | 0.058 (H) |
| Pure compaction plan | 0 | 0 | 0 | 0.000 (H) | 0.000 (H) |
| Compaction publication | 42,840 | 10,596 | 6 | 4.157 (H) | 1.028 (H) |
| Checkpoint writable reopen | 51,900 | 0 | 5 | 5.036 (H) | 0.000 (H) |

The ratio columns show the quotient and name its denominator: for example,
checkpoint reopen is 51,900/10,306 = 5.036 returned read bytes per original
history byte. Create is normalized by the final history for a common scale; it
does not read that future history. Segment framing, manifests and repeated
integrity reads explain why lifecycle figures differ from append frame bytes.

The raw role matrix makes repeated work visible. Checkpoint writable reopen reads
30,918 journal bytes (3H), 20,772 segment bytes and 210 manifest bytes. Compaction
publication reads 20,612 journal bytes (2H), 21,626 segment bytes and 602 manifest
bytes. These are existing verification/reconstruction paths, including required
integrity work; the figures do not establish that a check is redundant or removable.

### Auxiliary and unavailable work

Base preparation has zero adapter reads/writes/forces. The following figures
apply to each base measured repetition and are excluded from operation ratios:

| Auxiliary scope | Read bytes | Write bytes | Successful forces |
| --- | --- | --- | --- |
| Namespace | 0 | 0 | 4 |
| Owner acquisition | 10,306 | 0 | 1 |
| Prefix verification | 10,306 | 0 | 0 |
| Reader acquisition | 43,050 | 0 | 3 |
| Validation | 21,626 | 0 | 0 |
| Teardown | 0 | 0 | 0 |

Operations plus these captured auxiliary scopes total 222,900 read bytes, 32,898
write bytes and 182 successful forces. Auxiliary reads contribute 85,288 bytes,
about 38.3% of that captured read total. External hash/inventory remain excluded,
and export/process telemetry/supervisor validation remain uncaptured with
unavailable counters. Neither total includes an estimate for their I/O.

Every measured `/proc` `read_bytes` delta was zero while adapter reads were
positive. Process/kernel accounting and adapter returned bytes answer different
questions. Cached file reads can still involve repeated application verification;
these results supply no cold-cache or physical-device read measure.

### Findings and next experiment

Increasing commits from 128 to 192 raises H from 10,306 to 15,564 and operation
read bytes from 137,612 to 207,166. Returned-read normalization remains close to
13.3; the small ratio reduction reflects the changed image/overhead mix rather
than an optimization. Doubling queries leaves write history and operation byte
totals unchanged because these queries use reconstructed heap state. Doubling
interval width changes values in fixed-width fields without changing encoded
length or the observed byte totals. Four profiles and a second seed retain
similar normalized file work without identical temporal effects.

The repeated control has mixed-commit p50 values 241.349/204.274 µs versus the
starting 297.501/268.719 µs, despite identical file-work totals and inputs. This
18.9%/24.0% difference illustrates continuing timing/run-order variation; it is
not a performance improvement. Do not compare these instrumented times to the
earlier ordinary timing dataset.

The bounded baseline now combines ordinary latency/resource observations with
separately instrumented operation file work. A useful next experiment is to profile
full-history reconstruction and compare bounded reuse of verified segment images
within one recovery acquisition, preserving complete object integrity, actual
journal-prefix binding, ownership, history and acknowledgement. Measure returned
bytes, allocation, latency and memory together before selecting a layout or cache.
The current evidence does not justify native acceleration or a universal format
advantage. Controlled-device, cold-cache, sustained-arrival and production work
retain their separate qualification requirements.
