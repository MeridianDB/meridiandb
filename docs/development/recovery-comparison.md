---
title: Recovery comparison core
description: Compare ordinary and explicit checkpoint-history recovery on fresh equivalent local histories, with separate raw clocks, native work, access modes and storage shapes.
---

# Recovery comparison core

`LocalRecoveryComparison` is test-only developer infrastructure for comparing
ordinary local recovery with explicit checkpoint-history reuse. It prepares the
same original journal and selected objects for each strategy, checks independent
answers and retains raw operation samples. It changes no production open mode or
existing workload result format. [ADR 0050](../decisions/0050-recovery-comparison-core.md)
records the design.

This synchronous core has no command-line supervisor, source-bound serialized
protocol or evaluated performance result. The existing
[ordinary and attributed workload tools](supervised-workloads.md) retain their
current schemas and observations. Peak and retained memory remain separate
diagnostic work; before/after heap samples establish neither.

## Run developer code

After compiling test sources, Java developer code can call:

```java
var corpus = WorkloadCorpus.generate(
        WorkloadCorpus.Profile.CORRECTIONS.spec(42));
var config = new LocalRecoveryComparison.Config(1, 2);
var result = LocalRecoveryComparison.run(
        scratchParent, corpus, config,
        LocalRecoveryComparison.MeasurementMode.UNINSTRUMENTED,
        LocalCheckpointHistory.Limits.DEFAULT);
```

Imports are `org.meridiandb.workload.WorkloadCorpus`,
`org.meridiandb.workload.LocalRecoveryComparison` and
`org.meridiandb.storage.manifest.LocalCheckpointHistory`. The helpers are outside
both shipped JARs. `scratchParent` is an existing trusted, stable, durably named
real directory on the filesystem being examined. Run correctness checks from the
repository root:

```bash
./mvnw -B \
  '-Dtest=LocalRecoveryComparisonTest,UninstrumentedRecoveryComparisonTest' \
  test
```

Every invocation creates a new `mdb-recovery-` root. Every strategy, access mode,
storage shape and warmup/measured repetition has its own database directory.
Files are retained after success or failure; no old database is reused or swept.

## Select a measurement mode

The four-argument `run` and existing probe overload keep instrumented behavior.
The explicit overload places `MeasurementMode` before limits, preserving existing
nullable probe calls. [ADR 0051](../decisions/0051-uninstrumented-recovery-controls.md)
records the control boundary.

| Mode | Native counters |
| --- | --- |
| Captured | Complete adapter snapshots; coverage `ADAPTERS_ONLY` |
| Control | Absent (`null`); coverage `NOT_CAPTURED` |

`INSTRUMENTED` selects captured execution; `UNINSTRUMENTED` selects controls.
Result identifiers are `CALLER_THREAD_RECOVERY_IO_INSTRUMENTED` and
`RECOVERY_UNINSTRUMENTED`, respectively.

Uninstrumented execution creates no native capture or counter export. It still
uses the recorder, JVM probes and existing inactive adapter hooks. A bounded
read-only caller-capture guard runs before filesystem effects, before each
measurement, at the actual call boundary and after instrumentation. The call
boundary guard is included in the service clock. This is a control with native
capture disabled, not a claim of zero measurement overhead.

An ambient capture on the calling thread rejects control admission without
closing, resetting or exporting that caller-owned capture. Captures on other
threads remain independent. A probe that leaves caller capture active at a guarded boundary rejects the run;
acquired database owners are still cleaned up. Result construction rejects unknown mode identifiers or samples whose counter
coverage disagrees with the selected mode. Missing counters never imply zero I/O.
Keep modes and cohorts separate when interpreting raw results.

## Equivalent histories

Each repetition covers sixteen opens: two recovery strategies, two access modes
and four storage shapes.

| Shape | Selected history | Complete journal |
| --- | --- | --- |
| Journal only | No selected manifest | All original commits |
| Genesis | Explicit checkpoint at zero | All original commits |
| Checkpoint tail | First half of commits, split into bounded source segments | All original commits, including the suffix |
| Compacted checkpoint tail | Same prefix repacked through actual compaction publication | Same complete journal; old manifests and segments retained |

A one-segment source may be reused without a smaller layout. The compacted shape
records actual compaction publication; it does not require a size reduction.

Read-only and writable modes use their actual public factories. History reuse
passes explicit [checkpoint-acquisition limits](local-checkpoint-history.md).
Both strategies receive identical initial file names and SHA-256 values within
each shape/access pair, including control-file presence and retained old objects.
The result binds selected cutoff, prefix length/hash, selected manifest/segment
bytes and segment count separately from the full journal identity.

Source records, segment images, compaction plans, continuation identity and
independent oracle answers are prepared before filesystem effects. Setup writes
and synchronizes the complete journal/name and selected objects outside operation
clocks. A production capability probe precedes raw directory forces. Original
corpus version, schedule/history fingerprints and encoded history remain unchanged.

## Ordering and admission

Defaults are one warmup and two measured repetitions. Configuration allows zero
to two warmups and one to five measured repetitions. Strategy order alternates
ordinary/reuse then reuse/ordinary independently within the warmup and measured
sequences. Warmups do not shift the first measured order. An even measured count
balances AB and BA for every shape/access cohort; odd counts remain explicitly
unbalanced. Shape/access order is fixed and recorded; JVM compilation and OS cache
are uncontrolled. Fresh files do not establish cold-storage conditions.

Admission checks the whole matrix before path effects:

- At most 40,000 conservative validation units: opens multiplied by twice the
  corpus action count, commit count and sixteen lifecycle/checksum checks.
- At most 32,000,000 squared-commit units: opens multiplied by `(commits + 1)²`.
- At most 256 MiB of aggregate initial logical fixture images, conservatively
  counting reused compacted images twice.
- Every selected shape must fit the supplied acquisition byte/record limits.

The existing corpus limits also apply. These are admitted-work bounds, not a
native-call deadline, exact heap limit or reserved-disk guarantee. A legal
repetition count can still exceed aggregate admission for a larger corpus.
Raw fixture reads are bounded; unexpected extra bytes or entries fail the run.

## Operation and resource scopes

Each observation records its repetition, warmup flag, actual strategy ordinal,
shape/access/strategy, directory and initial image identities. It contains a raw
open sample and a native snapshot only in instrumented mode. Writable observations additionally contain
separate samples for an original retry, one forced new commit and its exact retry.
The new commit puts value 17 over unbounded valid time for a fresh deterministic
entity and request ID. Validation checks the new-head value and new request
position before timing its retry; historical answers remain unchanged. Read-only observations
perform no continuation writes.

Instrumented service clocks bracket the API call; control clocks additionally
include the bounded call-boundary capture guard. Executing-thread CPU/allocation
and before/after used-heap gauges follow the existing
[measurement semantics](local-measurements.md#interpret-clocks-and-counters).
In instrumented mode, native adapter counters bracket the recorder/probe interval
around each call. They retain returned bytes, force successes and coverage checks
rather than estimating device traffic; timings include active capture-hook work.
Control samples have no counter snapshot, so these work totals are unavailable.
Neither mode establishes device traffic or a performance gain. Reader-admission work is an intentional difference;
writable journal/name startup and forced acknowledgement remain intact.

Outside operation clocks/counters, validation checks every available cutoff
position, every independent pinned query and original request position in the
corpus, retry/continuation identity, and the exact closed-journal length/hash.
Validation includes the final raw journal check after owner close. Database close
has its own duration. Setup, compaction publication and fixture inventory are
excluded from these durations; compaction and reclamation service times are not
measured here. No timing summary pools warmups,
strategies, modes or shapes.

## Results and failures

Typed result version one contains corpus/configuration/limit/runtime identities
and immutable raw observations. Mode and per-operation coverage must agree.
It is a separate developer type, not a new wire schema. Optional CPU/allocation stays unavailable when the JVM cannot supply a
valid delta. Used heap includes uncollected garbage and is neither peak, retained
heap nor RSS; no forced GC or telemetry enablement occurs. Prepared corpus, oracle
and graph inputs also remain live during the matrix and contribute to these
gauges.

Results return only after every scenario, oracle check, exact final journal check
and owner cleanup succeeds. A returned database is closed if later instrumentation
or export fails, including runtime and Error paths. I/O/runtime run failures report
the retained root through `RunFailedException`; Errors propagate with files retained.
No partial successful comparison is returned and no fallback hides a rejected
checkpoint. Source supervision, bounded serialized admission, memory diagnostics
and an adoption/rejection evaluation remain required before a performance decision.


## Correctness coverage

The original ten instrumented checks cover the complete sixteen-open matrix, equivalent initial images,
selected versus full-journal identities, raw immutability, measured ordering,
pre-effect admission and runtime/Error cleanup. A four-commit literal fixture
uses a 354-byte complete journal and a 198-byte selected prefix. Open read bytes
are checked separately from setup, validation, continuation and teardown:

| Shape | Ordinary | History reuse |
| --- | --- | --- |
| Journal only | 708 | 708 |
| Genesis | 904 | 904 |
| Checkpoint tail | 1,972 | 1,572 |
| Compacted checkpoint tail | 1,672 | 1,394 |

These figures apply to both access modes for this fixture. Writable startup has
three force successes without reader admission; ordinary selected-checkpoint
recovery has two additional admission forces. Read-only counts are one and three
respectively. The continuation writes exactly one 156-byte frame and forces the
journal once; both retry calls perform no counted native work. Independently
decoded final bytes and fresh ordinary reopen verify the new-head value and
previous-cutoff absence. These are correctness vectors, not performance results.


Sixteen additional checks cover the native-capture-disabled matrix, exact final
bytes and state-changing continuation, cross-mode fixture/order equivalence,
synthetic call-clock boundaries with large out-of-clock snapshot work, nullable
resource counters and Runtime/Error cleanup in both modes. Constructor checks
reject unknown or mismatched coverage. Caller capture tests verify admission
rejection and continued caller ownership, including activation by a probe. A
bounded 64-thread saturation check holds every other-thread capture slot while
controls succeed, establishing that controls never request capture admission.
These are correctness checks; they establish no latency or memory advantage.
