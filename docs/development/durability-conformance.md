---
title: Local durability conformance
description: Reproduce MeridianDB's process-exit, lost-acknowledgement, checkpoint and seeded writable-reopen checks, with explicit filesystem and power-loss limits.
---

# Local durability conformance

The experimental [local database](local-database.md) is checked through real
writable creation, transactions and reopen. Child JVMs terminate abruptly at
specific production boundaries using `Runtime.halt`; separate parent processes
then acquire the released owners and verify exact recovered state. These checks
exercise process failure while the operating system remains running.

## Commit and startup boundaries

Each commit-exit history begins with an acknowledged transaction affecting two
entities. A second transaction corrects both entities over the same valid-time interval.
Recovery checks whole-transaction results, original request positions, historical
point/scan/sum answers and retry behavior.

| Exit point | Required result in this process-exit experiment |
| --- | --- |
| Before invoking append | Only acknowledged commit one; retry creates commit two |
| After complete append, before force | Complete commit two is recovered from surviving OS state; retry returns its original position |
| After journal force, before query publication | Complete commit two is recovered even though the writer never published it |
| After query publication, before return | Complete commit two is recovered despite the lost acknowledgement |
| After `transact` returns and the child reports acknowledgement | Acknowledged commit two survives reopen |

No recovered case may expose half of the correction or apply a retry twice.
Conflicting request-ID reuse fails without advancing the head. Before append,
absence is established by the controlled boundary; a general append exception
still has the conservative uncertain-outcome contract.

Writable reopen is terminated after hidden reconstruction and after startup
synchronization. Both preserve the previously acknowledged history. Read-only
reconstruction has its own exit check. Fresh creation is also terminated at both
hidden-recovery boundaries: the selected genesis remains recoverable at sequence
zero, with its original database identity.

An additional test constructs a **torn image** by appending a strict partial frame
after closing the owning database. Strict reopen rejects it and preserves its
bytes. This is an injected malformed storage image, not a process killed midway
through the production append loop. The journal's separate short-write, failure
and truncation tests cover those byte/I/O boundaries.

## Checkpoint boundaries

The checkpoint matrix terminates actual initialization after journal creation,
dependency synchronization, candidate synchronization, manifest rename and final
directory synchronization. Before rename there is no selected genesis; pending
candidates remain unselected evidence. After rename the complete genesis is
selected. Existing complete journal history remains recoverable in either case;
creation cannot overwrite the retained files.

A second matrix starts from two acknowledged commits, writes a segment covering
commit one and publishes generation two, retaining commit two in the journal tail.
It terminates at each of the four publication boundaries:

| Exit point | Selected checkpoint after process exit | Recovered database head |
| --- | --- | --- |
| Dependencies synchronized | Genesis, cutoff zero | Commit two |
| Candidate forced and closed | Genesis, cutoff zero; pending candidate ignored | Commit two |
| Manifest renamed | Generation two, cutoff one | Commit two |
| Final directory synchronized | Generation two, cutoff one | Commit two |

Selection verifies the complete graph and actual journal prefix. Recovery retains
both commits and their historical answers in every row. Visibility of a renamed
file before the final force is an observed process-exit result, not proof that its
name would survive device failure. No case adopts pending files, removes old
objects or authorizes reclamation.

## Temporal histories through writable reopen

`LocalDurableFixtureTest` executes the unchanged five canonical histories in two
shapes: genesis plus journal, and a checkpoint after commit one with later commits
retained in the journal tail. Four histories have a nonempty tail; the single-commit
coordinate-limit history has none.
All 84 literal expectations run in each shape, including invalid input/cutoffs,
corrections, retractions, no-ops and retries. Actual transactions determine write
acceptance; the test-only reference cannot replace a production write. Every
successful new write and identical retry is followed by close and writable reopen.
Final sweeps check every historical cutoff, query form and original request ID.

`LocalDurablePropertiesTest` reuses the 16 persisted seeds and performs 32 new
transactions per seed: **512 new durable commits**, plus identical and conflicting
retries. Each accepted new write and replay is followed by writable reopen.
A checkpoint covers commits 1–8 while the journal already contains commits 9–16;
later commits continue through that checkpoint-plus-tail shape. Pinned views retain
their original answers while the writer advances and become invalid after close.

A forward dense-cell model checks point, clipped scan and exact aggregate results
independently of interval-splitting logic. The separate backward-history reference
also checks unbounded scans and extreme coordinates. Guaranteed repeated-value
no-ops, `Long.MIN_VALUE`/`Long.MAX_VALUE` values and sums beyond signed 64-bit range
supplement randomized intervals and retractions. Assertion diagnostics retain the
seed and transaction history; the canonical source and seed corpus stay unchanged.

## Reproduce the checks

From the repository root with JDK 25:

```bash
./mvnw -B -Dtest=LocalDatabaseCrashTest,LocalCheckpointCrashTest,LocalBoundaryTest,LocalDurableFixtureTest,LocalDurablePropertiesTest test
```

Reproduce one saved or reported random seed:

```bash
./mvnw -B -Dtest=LocalDurablePropertiesTest -Dmeridiandb.seed=42 test
```

Run the full suite, including injected I/O failures and concurrent-reader/writer
checks, with `./mvnw -B verify`. Child processes have bounded timeouts and write
logs outside the database namespace. Package-private per-instance probes observe
real I/O boundaries; public factories install no probe. There is no global fault
switch or public fault-injection API.

## Evidence and limits

The local run on October 28, 2026 used macOS on arm64 with working directory
open/force, atomic rename and local locks. Capability-dependent tests may skip
only on `DirectorySyncUnsupportedException`; a CI pass on such a provider is not
evidence that its durable protocol ran. Other failures remain test failures.

The acknowledged-write contract depends on an already durably named trusted
local directory and a filesystem, provider and device that honor the declared
file and directory synchronization. Abrupt JVM exit leaves the OS cache alive.
These tests do not simulate power loss, reordered or lost unsynchronized writes,
controller failure, remote filesystems or adversarial path replacement. In
particular, the append-before-force row does not promise machine-failure durability.

Primary sources checked October 28, 2026 support this distinction:

- JDK [FileChannel.force](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/channels/FileChannel.html#force(boolean))
  scopes its guarantees to local storage and covered changes.
- RocksDB [synchronous writes](https://github.com/facebook/rocksdb/wiki/Basic-Operations#synchronous-writes)
  distinguishes OS-buffered writes from storage synchronization.
- SQLite [crash testing](https://www.sqlite.org/testing.html#crash_testing) and
  [atomic-commit testing](https://www.sqlite.org/atomiccommit.html#testing_atomic_commit_behavior)
  describe simulated damage and reordered unsynchronized writes beyond terminating
  a child process. MeridianDB does not yet provide that filesystem simulation.

No throughput, bounded-total-memory, automatic checkpoint, compaction or
reclamation result follows from conformance. Recovery retains and reconstructs
full history; see the [local database costs](local-database.md#cost-and-verification).
