---
title: Local operation measurement core
description: Measure checked local commits, pinned queries, recovery and checkpoint compaction with fresh repetitions, explicit service clocks and scoped JVM resource observations.
---

# Local operation measurement core

The test-only in-process runner measures synchronous local operations using the
[deterministic workload corpus](workload-corpus.md). It checks independent expected
answers, original request positions and exact persisted history before returning
typed results. It provides measurement infrastructure;
[bounded Linux observations](linux-observations.md) report its current behavior.
No controlled-device or production performance result is established.

[ADR 0042](../decisions/0042-local-operation-measurements.md) defines this boundary.
The [Linux supervisor](supervised-workloads.md) adds a command-line runner, serialized
result admission, source/class/mount bindings and scoped allocated-block/process-I/O
accounting. Controlled baseline evaluation remains separate. The current core API
is developer support, outside both core and the attached contract-support JAR.

## Execute a bounded local experiment

With test sources compiled, Java developer code can call:

```java
var corpus = WorkloadCorpus.generate(WorkloadCorpus.Profile.CORRECTIONS.spec(42));
var config = new LocalWorkloadRunner.Config(1, 3);
var result = LocalWorkloadRunner.run(scratchParent, corpus, config);
```

`scratchParent` must be an existing trusted stable, durably named real directory on the filesystem
being examined. The runner creates a fresh child and a distinct database directory
for every warmup and measured repetition. It preserves all output and never sweeps
or reuses a previous database. Retrying a request within a corpus retains its
original commit; repeating the experiment starts new history in another directory.

Before timed creation, an untimed checkpoint-store preflight checks the production
directory capability and creates the allowed empty control file. After closing
that owner, directory forces run from the database home through the run root to
the scratch parent, establishing the new child names. `namespacePreparationNanos`
records this preflight/force work separately. The timed create therefore reopens
an existing preflighted control file; its first creation/probe cost is excluded.

Defaults are one warmup and three measured repetitions. Admission allows zero to
two warmups, one to five measured repetitions, and at most 20,000 corpus actions
across all iterations, in addition to the corpus bounds. Samples and file inventory
are bounded per iteration. This bounds admitted work; it is not a native-call
deadline, exact heap budget or reserved-disk-space guarantee. Larger runs and a
process supervisor need their own explicit environment and output limits.

Oracle answers, original records and split segment images are prepared once before
operation timers or database creation. Each warmup runs the same complete lifecycle
on its own store; its `warmup` flag separates it from measured repetitions. The
runner performs no forced GC, cache eviction or telemetry enablement. JVM warmup
does not create a cold-disk experiment; OS cache state remains uncontrolled.

Run its correctness checks from the repository root:

```bash
./mvnw -B '-Dtest=LocalWorkloadRunnerTest,WorkloadMeasurementsTest' test
```

## Operation and ownership boundaries

| Operation | Timed work |
| --- | --- |
| Create | Local database creation, genesis publication and startup ownership after untimed directory/control preflight |
| Load / mixed commit | One existing forced local `transact` call through acknowledgement; seed and mixed phases are separate |
| Exact retry | One original request replay; counted separately from new commits and new no-ops |
| Get / scan / select / filter scan / sum | Open one numeric-cutoff read view, execute the query and close the view |
| Journal reopen | Acquire actual writable owners, verify genesis and replay the journal, including existing startup synchronization |
| Segment write | Encode, create, force and close one original-history segment |
| Checkpoint publication | Verify the actual journal/dependencies, force them and publish the generation-two selection |
| Compaction plan | Pure history compaction over prepared in-memory segment images; separate from native publication |
| Compaction publication | Verify/install/force replacement objects and publish generation three, with one old reader pinned |
| Checkpoint reopen | Acquire writable owners, verify the selected checkpoint graph and reconstruct state plus the journal tail |

Expected-answer comparisons and recovery sweeps run outside each operation's wall
clock. Database close, checkpoint-owner acquisition, prefix preparation, selected
reader acquisition, final raw-journal hashing and file inventory are also outside
those operation clocks. Reopen samples measure open/startup, not the subsequent
close or correctness sweep. Compaction planning samples are pure supplied-image
measurements rather than engine I/O measurements.

The database closes before another store/journal owner opens. The selected old
reader remains alive through compaction publication; afterward both old and new
readers must return the original records at the same journal prefix. The final
writable reopen checks every prepared historical query and original request
position. Raw journal hashing happens only after every native owner closes.
All three generations and obsolete segments remain named; the runner performs
no reclamation. There is one synchronous execution thread and one deliberately
pinned reader during compaction, with no asynchronous index/merge backlog metric.

## Interpret clocks and counters

Every raw sample retains its load/mixed/maintenance phase and operation kind.
Within each iteration and group, summaries report count, total, minimum,
nearest-rank p50/p95/p99 and maximum wall nanoseconds. Small groups, such as one
reopen, yield descriptive observations rather than a stable tail-latency estimate.
Raw observations remain available; warmup and measured samples are not pooled.

| Observation | Meaning and limit |
| --- | --- |
| Operation wall time | `nanoTime` directly brackets the call; oracle comparisons and counter snapshots are outside |
| Service operations/second | Count divided by summed call wall time; excludes gaps and does not establish sustained offered throughput |
| Action-loop operations/second | All load and mixed actions divided by their loop wall time, including validation, counters and bookkeeping; excludes create/maintenance |
| Validation time | Explicit successful validation tasks, including recovery sweeps; the action-loop subset is reported separately |
| Other action-loop time | Loop wall minus service and validation intervals; retains probe/recording/dispatch overhead rather than subtracting an estimated calibration |
| Thread CPU | Optional current-platform-thread CPU delta, excluding other threads and GC-worker CPU; nanosecond precision does not imply that accuracy |
| Thread allocation | Optional approximate cumulative heap-allocation delta for that platform thread, including surrounding counter instrumentation |
| Heap used | Before/after process-heap gauges, including uncollected garbage; sampled maximum is neither true peak, retained heap nor RSS |

CPU/allocation snapshots bracket a slightly wider instrumented interval than the
direct wall clock. Disabled, unsupported, virtual-thread or decreasing/reset
counters yield `null`, with availability counts in each summary. A full-group
counter total exists only when every sample has a usable delta. No counter is
silently enabled. Heap may decrease after GC and is not treated as a monotonic
allocation counter. A zero-resolution total yields no fabricated infinite rate.
No arrival schedule or queue delay is measured, so service latency is not
end-to-end arrival latency.

Primary Java 25 specifications checked **November 2, 2026**:
[thread CPU](https://docs.oracle.com/en/java/javase/25/docs/api/java.management/java/lang/management/ThreadMXBean.html),
[allocation extension](https://docs.oracle.com/en/java/javase/25/docs/api/jdk.management/com/sun/management/ThreadMXBean.html)
and [heap memory](https://docs.oracle.com/en/java/javase/25/docs/api/java.management/java/lang/management/MemoryMXBean.html).

## Evidence, storage and failures

Typed schema version one binds the complete corpus specification, both content
fingerprints, observed workload metadata, configuration, warmup flags and actual
Java/OS/architecture/filesystem type, processor count and maximum JVM heap. It
does not yet bind a source revision or complete Linux deployment profile.

`encodedHistoryBytes` remains the exact logical journal image length.
`NamedStorage` records regular-file names and logical lengths after all owners
close, including old generations, obsolete segments and control files. Their sum
is neither allocated storage, freed blocks, physical device traffic nor read/write
amplification. Those counters remain explicit gaps until measured with their own
scope and denominators.

A result returns only after every iteration and verification succeeds. Ordinary
I/O, temporal disagreement or telemetry/clock failure fails the run and preserves
its directory. If instrumentation fails after create/reopen has returned a native
owner, that resource is closed and cleanup failures remain suppressed evidence.
No successful operation sample is created for a failed call. Directory-sync
unsupported outcomes remain distinct from ordinary I/O; Linux qualification
requires all required native checks, while Windows keeps its basic compatibility
role. The separate [supervisor](supervised-workloads.md) handles process timeouts
and partial/stale serialized-result admission. Inner-core gaps describe standalone
use; the supervisor adds bindings and accounting without changing their scope.

The 26 new checks cover literal timing summaries, unavailable/reset telemetry,
clock/cleanup failures, bounds/immutability, all four actual local profiles and
fresh warmup/repetition behavior. These complement the existing corpus and
[Linux qualification](linux-storage-qualification.md) checks. Hosted CI establishes
correct execution on its observed placement; it is not controlled performance or
production-device qualification.
