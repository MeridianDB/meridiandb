---
title: Deterministic temporal workload corpus
description: Generate bounded reproducible temporal actions, pinned historical queries and independent expected answers before measuring MeridianDB's local engine.
---

# Deterministic temporal workload corpus

The test-only workload corpus generates repeatable temporal actions and prepares
their expected answers before engine execution. It supports append, correction,
retraction and hot-entity scenarios. This is correctness infrastructure for future
local measurements; it reports no latency, throughput or comparative performance.

[ADR 0041](../decisions/0041-deterministic-workload-corpus.md) explains the boundary.
The existing [canonical fixtures](reference-model.md) remain small exact semantic
checks. The generated corpus adds configurable histories without replacing those
fixtures or changing the storage formats and acknowledgement contract.

## Generate and prepare

Developer support lives in `src/test/java/org/meridiandb/workload/`:

```java
var spec = WorkloadCorpus.Profile.CORRECTIONS.spec(42);
var corpus = WorkloadCorpus.generate(spec);
var prepared = WorkloadOracle.prepare(corpus);
```

`WorkloadCorpus` and `WorkloadOracle` use the existing temporal types. They remain
outside both the production core JAR and the attached contract-support classifier.
They are not a released library API, command-line benchmark or timing harness.
After compiling tests, run their checks from the repository root:

```bash
./mvnw -B -Dtest=WorkloadCorpusTest,WorkloadOracleTest,WorkloadConformanceTest test
```

Preparation validates declared commit sequences, retry identity/payload and
scheduled read availability against the independent reference model. It exposes
immutable outcomes and summary maps only after the entire schedule validates.
Engine execution uses the separate [local measurement core](local-measurements.md).
It finishes generation and expected-answer preparation outside operation timers
and uses a fresh database for each repetition so previous request IDs do not turn
writes into retries.

## Profiles and action order

All four defaults use 16 entities, 128 new commits including 16 seed commits,
64 reads and an interval width of eight integer microseconds. These are bounded
development datasets, not representative production scale.

| Profile | Correction / retraction / repeat intent | Retry probability | Hot-entity probability | Historical-read probability |
| --- | --- | --- | --- | --- |
| `APPEND` | 0% / 0% / 0% | 5% | 0% | 50% |
| `CORRECTIONS` | 70% / 10% / 10% | 10% | 10% | 75% |
| `RETRACTIONS` | 20% / 60% / 10% | 10% | 10% | 50% |
| `HOT_ENTITY` | 60% / 10% / 10% | 20% | 95% | 80% |

The generated schedule seeds each entity over `[0, width)`, then interleaves
mixed writes, optional exact retries and the requested reads. Seed commits have
no retries or reads. Append intents fill consecutive disjoint half-open windows
per entity. Corrections and retractions target the original window; when it is
entirely absent, the generator restores it with a put. Repeat intents copy the
previous original operation under a new request ID. Exact retries reuse an older
original request and its sequence, adding no committed history.

Intent probabilities apply to mixed new commits, with the remaining probability
assigned to append. Retry probability allows at most one retry per mixed commit.
A hot-entity choice selects entity zero; the uniform fallback can also select it,
and repeats inherit the previous entity. Therefore configured percentages are
not observed effect or entity-share guarantees. Caller-constructed literal corpora
must also begin with the configured number of seed submissions before mixed
submissions or retries; seed stage and intent must agree. Literal reads can be
interleaved during setup. Effect-intent labels remain metadata rather than proof
of a correction/removal effect or of generator provenance.

Reads rotate through `Get`, `Scan`, `Select`, `FilterScan` and `Sum`. Each stores
an explicit numeric system cutoff available at its scheduled action. Historical
selection uses an earlier cutoff, including genesis; otherwise the action pins
the then-current sequence. It never resolves `Head` again during execution.
Queries include absent identities, present/past/future valid-time locations,
all six numeric predicates and signed-long extremes. Sums preserve exact
`BigInteger` results. Selection order follows the existing UTF-16 contract.

## Report observed behavior with denominators

`WorkloadOracle.Summary` counts the actual prepared history and results:

| Counter | Interpretation |
| --- | --- |
| Attempts / commits / retries | Every submission / new request commits / exact replay attempts; attempts = commits + retries |
| Write operations | Original committed operations, including new no-ops; v1 has one per commit |
| Scheduled intents | Labels across attempts, including seed and retry; use mixed new commits as the intent-probability denominator |
| Entity touches | Original operations per identity in UTF-16 order; shows realized skew and per-entity history depth, excluding retries |
| No-op puts / retractions | New commits that leave resolved state unchanged within their operation window |
| Puts replacing present / retractions removing present | Operations whose window previously contained state; replacing-present puts can also be no-ops |
| Geometric overlaps | Original operations intersecting any earlier operation on the same entity, including absent retractions; adjacency is excluded |
| Historical / head reads | Earlier versus then-current numeric cutoffs; their sum is all reads |
| Read operators / point hits and misses | Counts per operator; point-hit denominator is `Get` reads only |
| Empty / nonempty results | All reads; an aggregate with count one and sum zero is nonempty |

Effect counters overlap and must not be added as exclusive categories. Geometric
overlap is distinct from replacing visible data. For example, these seed-42
defaults produce the following **dataset metadata**, with 128 commits and 64
reads in every row:

| Profile | Attempts | Retries | New no-op commits | Geometric overlaps | Entity-zero touches |
| --- | --- | --- | --- | --- | --- |
| `APPEND` | 133 | 5 | 0 | 0 | 12 |
| `CORRECTIONS` | 141 | 13 | 15 | 99 | 25 |
| `RETRACTIONS` | 141 | 13 | 11 | 100 | 19 |
| `HOT_ENTITY` | 149 | 21 | 14 | 90 | 107 |

The hot-entity share here is 107/128 original operations, including setup. It is
not the configured 95% selection probability. No wall-clock timing is involved.

## Reproduction and identity

Version 1 uses three separate `java.util.Random` streams for writes, reads and
retries. Changing read count or retry probability preserves original write
history. Record the full specification and version, not just a seed.
Java specifies this generator's algorithm, but its state is only 48 bits: seeds
differing by `2^48` can produce identical actions. `Spec.initialRandomState(salt)`
exposes the effective state for each public stream salt. This is reproducibility,
not statistical independence or cryptographic randomness. The Java 25
[Random specification](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/Random.html)
was checked November 2, 2026.

Two streaming SHA-256 fingerprints serve different purposes:

- `fingerprint()` binds the version, complete specification and ordered actions,
  including stage/intent labels, exact journal frames, explicit read cutoffs and
  typed query fields. Text uses length-prefixed UTF-16 code units; numeric fields
  and bound-presence tags have explicit binary encodings.
- `historyFingerprint()` hashes the actual journal header and original committed
  frames, excluding retries and queries. `encodedHistoryBytes()` is that exact
  logical journal length. It measures neither allocated blocks nor device writes,
  read/write amplification or reclaimable space.

The tiny golden specification `(7, 1, 4, 5, 2, 100, 0, 0, 100, 100, 100)` has
354 journal bytes and these identities:

```text
schedule: 39bd170644930733aef2da90da5ed3d9c1d42bf48c37a42052d19f57e52f2af0
history:  cdbb2a26ffde6b13937bf196fa3fe77630c6b65922696e67073d37050be7a211
```

Its literal test checks operation values/windows, retry sequences, historical
cutoffs and expected answers as well as the hashes. A change to corpus semantics
or fingerprint encoding requires an explicit version/golden compatibility decision.
The fingerprints identify inputs; they do not authenticate execution or hardware.

## Bounds and conformance

Specification admission requires 1–1,024 entities, more commits than entities and
at most 4,096 commits, 0–1,024 reads and finite interval width 1–16. Every percentage
is 0–100 and correction + retraction + repeat is at most 100. With `C` commits,
`Q` reads, `E` entities and `W` interval width, the reference-work admission is:

```text
C*C*(W+1) + C*Q*(W+min(E,16)+2) <= 32,000,000
```

This conservative admission estimate is not a heap or execution-time guarantee.
Together with dimension limits it bounds full-history preparation. Actions are
capped at `2*C+Q`; identities at 128 UTF-16 units; original and attempted encoded
frames each at 16 MiB; operation/scan windows at the configured finite width; and
selection/aggregate fanout at `min(E,16)+1`. Prepared results are bounded by that
fanout or the finite scan window. Caller-built actions are checked before oracle
preparation. V1 excludes unbounded intervals and multi-operation transactions;
their existing contract tests remain authoritative.

The 36 workload checks combine literal answers and metadata, independent backward
reference and forward dense-state comparisons, actual memory execution and four
real local writable/reopen profiles. Local tests reopen after each 16 new commits,
recheck pinned reads and retry lookup, then compare closed journal bytes with the
history fingerprint. Capability-dependent local skips stay separate from pure
checks; the [Linux qualification gate](linux-storage-qualification.md) requires
every invocation with zero skips.

The in-process [measurement core](local-measurements.md) now exercises repeated
fresh-store writes, queries, recovery and checkpoint/compaction with explicit
clocks and scoped JVM counters. Supervised evidence, physical storage/I/O accounting,
controlled measurements and actual Linux baseline evaluation remain subsequent
work under the [storage measurement plan](../architecture/linux-storage.md#qualification-and-measurement-order).
