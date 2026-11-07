---
title: Attributed local workload core
description: Execute a separate instrumented local lifecycle with bounded operation and auxiliary I/O scopes, immutable raw counters and original-history byte denominators.
---

# Attributed local workload core

The test-only attributed runner observes local adapter work during the same checked
create, commit, query, recovery and checkpoint lifecycle as the
[local measurement core](local-measurements.md). Its result explicitly identifies
instrumented execution and keeps operation counters separate from setup,
validation and teardown. The original [Linux observations](linux-observations.md)
remain unchanged.

This is an in-process Java developer interface. Source-bound serialized admission,
command-line supervision, named-ratio reports and evaluated attributed Linux
results remain planned. [ADR 0046](../decisions/0046-attributed-local-lifecycle.md)
defines that boundary; the [counter guide](local-io-counters.md) defines the native
transfer and force counters.

## Execute an instrumented lifecycle

Compile the test sources, then call from Java developer code:

```java
var corpus = WorkloadCorpus.generate(WorkloadCorpus.Profile.CORRECTIONS.spec(42));
var result = LocalAttributedWorkload.run(
        scratchParent, corpus, new LocalWorkloadRunner.Config(0, 1));
```

`scratchParent` is an existing trusted, stable, durably named real directory.
Every warmup and measured iteration creates a fresh database directory and
preserves it afterward. Linux is the qualification target; explicitly unavailable
Windows directory synchronization remains a capability limit.

The returned schema-one result has mode
`CALLER_THREAD_LOCAL_IO_INSTRUMENTED`, the checked lifecycle result, an ordered
scope list and logical byte denominators. Its nested timing samples come from
this instrumented execution. Counter updates occur during native calls; capture
setup, snapshot validation and trace recording occur outside operation service
clocks. These timings must not be substituted into the original observational
dataset as if instrumentation were absent.

Run focused correctness checks from the repository root:

```bash
./mvnw -B '-Dtest=LocalIoObservationTest,LocalWorkloadIoTest,LocalAttributedWorkloadTest,LocalWorkloadRunnerTest' test
```

## Scope and coverage

Every scope records its ordinal, iteration, category, optional operation phase/kind,
call outcome and immutable eight-role/fourteen-counter snapshot. Preparation uses
iteration `-1`; each fresh database has its own nonnegative iteration index.
Operation entries match the lifecycle's service samples in order. Auxiliary scopes
have no operation kind, keeping their work out of commit/query numerators.

| Category | Observed work | Coverage |
| --- | --- | --- |
| Preparation | Independent oracle and encoded input preparation before database creation | Local adapter boundary; these tasks use memory |
| Namespace | Control-owner preflight and three explicit parent/name directory forces | Local adapter transfers and force outcomes |
| Operation | Create, commit/retry, each query kind, writable reopen, segment write, checkpoint/compaction calls | Local adapter work, including engine-required integrity reads |
| Owner acquisition / prefix verification | Maintenance store/journal opens and actual owned-journal prefix preparation | Local adapter work outside operation samples |
| Reader acquisition | Old and replacement checkpoint readers | Local adapter work outside publication samples |
| Validation | Expected-answer checks, recovery sweeps and lazy old/new history reads | Local adapter work outside operation samples |
| External hash | Raw closed-journal stream and SHA-256 validation | External file I/O excluded from adapter counters |
| File inventory | Final regular-file names and logical lengths | Metadata I/O excluded from byte counters |
| Teardown | Database, store, journal and reader close | Hooked adapter work only; no invented close/metadata bytes |

Zero adapter counters for external hashing or file inventory do not establish
zero filesystem work. Each category carries explicit coverage. All categories
exclude unhooked metadata, locks and other threads; none measures physical device
traffic. Serialization/export and supervisor process telemetry occur outside this
Java lifecycle and have no fabricated captured sample.

## Logical denominators

Before native execution, the runner computes original journal-header bytes and
encoded frame bytes from non-retry source records. Seed and mixed frames remain
separate; their sum plus the header must equal the corpus's encoded history length.
Original commit, exact retry and query counts also remain distinct.

An append ratio uses new frame bytes, excluding the header and retries. A named
recovery/lifecycle ratio can use the complete encoded original history when its
scope is stated. These are logical source lengths, independent of observed native
counters and final file sizes. The core exports denominators; it does not yet
produce amplification ratios. Future reporting must retain zero-denominator N/A
and separate retries or heap-only queries rather than inventing a ratio.

## Bounds and failure evidence

At most 2,048 scopes are admitted across the complete run. Before creating any
namespace, admission derives the successful trace inventory from actions,
source-segment count and repetitions, and reserves additional failure/teardown
slots. This is stricter than the ordinary runner's 20,000-action cap. Large
configurations can therefore be refused even when their ordinary timing run fits.
The cap bounds retained trace shape, not exact heap, disk space or execution time.

Snapshots validate fixed dimensions, nonnegative counters and return/failure/byte
coherence. Overflow, invalid roles/results or native failures remain inspectable
in failed evidence and cannot yield an accepted result. Missing capture data are
explicitly absent, with their observation failure, rather than replaced by zeros.

Capacity is checked before calls. If observation fails after an acquisition
returns an owner, the runner closes that owner before propagating failure and
retains cleanup errors as suppressed evidence. Teardown still closes an existing
owner when capture setup is unavailable. A native close that throws is not retried
by this fallback. Cleanup outside capture is marked incomplete.

A successful result requires the full operation/auxiliary scope inventory and
all independent temporal, original-request, persisted-history and pinned-reader
checks. A failed run retains available raw scopes and its created namespace;
it returns no successful result or ratio. This supplies correctness evidence for
the instrumentation, with evaluated amplification and production qualification
remaining separate work.
