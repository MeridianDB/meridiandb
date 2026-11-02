---
title: Remote writable recovery conformance
description: Verify managed S3 checkpoint recovery, forced local writes, historical answers and retry identity through canonical fixtures, persisted seeds and publication interruptions.
---

# Remote writable recovery conformance

The experimental [managed S3 writable mode](s3-database-writes.md) is exercised
through actual SDK requests to a bounded loopback HTTP fixture and actual local
metadata, checkpoint and journal owners. The suite combines unchanged temporal
expectations with publication, recovery and new forced writes. It preserves the
existing [temporal contract](../architecture/temporal-contract.md) and local
acknowledgement protocol. [ADR 0035](../decisions/0035-remote-writable-recovery-conformance.md)
records the verification boundary.

These checks establish behavior in the supported test placement. They do not
qualify a live S3-compatible service, device power loss, distributed writer fencing,
replication, remote reclamation or throughput. The full matching local journal
remains necessary even when the remote checkpoint covers its entire history.

## Canonical and seeded histories

`S3DatabaseWritableConformanceTest` adds 40 invocations:

- All five original temporal histories and three query histories run through
  genesis, split and full checkpoint shapes: 24 history/shape cases. Each shape
  retains all 174 literal steps, for 522 literal steps across the three shapes.
  Neither the fixture parser nor the independent reference model is copied or
  rewritten for the adapter.
- All 16 persisted seeds run three independent 32-commit workflows, for 1,536
  actual new commits. Original request construction, random draws, historical
  cutoffs and retry draws remain unchanged. Each workflow checks all 33 final
  historical snapshots against the separate reference and integer-cell model.

Every write, exact retry, conflict and observed read uses an actual managed
writable open. Checkpoints are installed through SDK immutable-object access,
verified preparation and conditional publication before recovery; repeated reads
reuse the selected checkpoint. The checkpoint cutoff is zero, half the existing
history or the full existing history. New transactions then extend only the local
journal. Original no-ops retain their identities and commit positions.

Checks include point/interval/aggregate results, all predicate operators, absence,
extreme values, deterministic entity ordering, original request lookup and immutable
pinned views. Historical results agree with ordinary local recovery as a separate
baseline. Exact retries and conflicts append no records. Raw journal frames are
compared only after owners close. Local transactions and queries make no SDK call
after startup and leave the remote selection and every local publication-state
image unchanged.

## Publication and recovery boundaries

`S3DatabasePublicationConformanceTest` adds ten invocations:

| Case | Required observation |
| --- | --- |
| Timed-out conditional PUT held before its effect | Durable pending blocks managed recovery before another GET, both before and after the late effect; explicit reconciliation selects the exact candidate without a second PUT |
| Process exit at `WRITE_FINISHED` or `CANDIDATE_VERIFIED` | Reopen retains exact pending evidence; reconcile before managed writable startup |
| Process exit at `STATE_SYNCED` | Validated local reopen already has the exact expected result and no pending intent |
| Clean tail loss with checkpoint cutoffs 0, 1 and 3 | A known minimum rejects missing coverage; default zero exposes only valid surviving history, or rejects when the checkpoint itself exceeds that history |
| Missing complete journal with full remote checkpoint | Startup fails without recreating the journal or inventing transactions |
| Failed online head read | Startup fails without falling back to local history or mutating either authority |
| Expected GET captured before a later selection | The open retains its original paired image/token; later local writes do not refresh it, and the next startup rejects the changed selection |

After each reconciled or durably completed publication interruption, actual managed
recovery checks historical answers, retries every original request, commits a new
transaction, preserves an older view and reopens to retry the new request exactly.
Remote head bytes/token and publication-state images do not change during those
local writes. Child exits occur in the publisher while the parent retains the HTTP
fixture; processes, gates and worker lifetimes are bounded.

The existing [managed writable checks](s3-database-writes.md) separately cover
startup synchronization, append/force/publication uncertainty, poisoned views,
retained owners, reentrant calls, nonblocking snapshot capture and write/close
serialization. Six managed process stops distinguish recovery, synchronization,
append, force, query publication and acknowledged return. Existing managed-admission
and graph checks cover foreign scope/lineage, changed token/body, missing or corrupt
objects, divergent prefixes, malformed tails, limits and failure cleanup. The full
corpus supplements those focused tests rather than replacing their evidence.

## Coverage and durability limits

A selected checkpoint covers cutoff C. Later acknowledged commits depend on the
local journal until a separate publication covers them. A known minimum H can
reject a surviving journal ending before H; it does not authenticate its bytes.
With default zero, a cleanly lost suffix that was never checkpointed cannot be
inferred. Recovery reports the surviving head and absent lost request identities;
future cutoffs fail. A checkpoint beyond that surviving head also fails. No missing
records are reconstructed from remote segments.

Managed startup remains online. An already opened instance uses reconstructed
local state; a successful historical head observation is no continuing lease or
proof that the selection remains current. This protocol supplies neither remote
transaction acknowledgement nor cross-host fencing. Native file/directory force
assumptions, supported placement and uncertain-close quarantine remain unchanged.

## Run and inspect

From the repository root, install the core and its test-support artifact first:

```bash
./mvnw -B install
```

Then run the complete adapter suite:

```bash
./mvnw -B -f adapters/s3/pom.xml verify
```

The core has 1,309 invocations; the optional adapter has 762, including 108 pure
[coordination state/codec checks](../architecture/s3-coordination-state.md) and 58
[guard checks](s3-coordination-guard.md). The 50 corpus/
integration cases need supported native directory synchronization and skip only
that explicitly unavailable capability. Pure admission checks remain independent
of native placement. Use `mvnw.cmd` on Windows.

For one persisted seed plus the unchanged canonical cases:

```bash
./mvnw -B -f adapters/s3/pom.xml -Dtest=S3DatabaseWritableConformanceTest -Dmeridiandb.seed=42 test
```

The shared test classifier contains only the object contract, unchanged fixture/
reference classes and their nested classes, authoritative JSON contracts and seed
resource. Jackson is test-only in both builds. Test classes, fault bridges, SDK
dependencies and JSON parsing stay outside the production core JAR. The optional
production JAR contains no conformance helper.

The corpus fixture retains explicit caps of 32,768 requests, 512 objects and 2 MiB
of stored bytes; per-object/request bodies remain capped at 4 KiB. Existing focused
fixtures retain their smaller defaults. These are test-workflow limits, not database
limits or workload measurements. Graph verification, full journal replay, metadata
and journal synchronization, retained heap and each commit's local force remain
separate costs.
