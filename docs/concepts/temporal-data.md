---
description: Understand valid time and system time through a retroactive salary correction, and see how temporal queries differ from events and snapshots.
---

# Temporal data

MeridianDB explores how to preserve both when a fact applies and when the
database learned it. These examples describe the working temporal contract,
implemented by the memory engine and an experimental local storage/write protocol.
Process crash/retry conformance checks local recovery; a stable API remains planned.

## Two time dimensions

**Valid time** describes when an asserted fact applies in the domain. A salary
change can take effect on February 1 even if it is entered several weeks later.
The application supplies the domain meaning of that date or interval.

**System time** describes the database's history of committed knowledge. A later
correction creates a new view of the past while preserving the earlier view.
The database must define how its committed transaction order maps to historical
queries. The [initial contract](../architecture/temporal-contract.md) uses
ordered commit positions; wall-clock timestamps alone are not that ordering
contract.

A bitemporal question identifies both dimensions: “What salary applied on
February 15, using the knowledge committed by February 20?”

## A correction received in March

Suppose a record committed on January 5 says an employee's annual salary is
70,000, effective January 1. On March 10, a correction says the salary became
75,000 on February 1. Assume there are no other changes.

Both questions below use the **same valid date**. Only the knowledge cutoff
changes. For this illustration, assume the application has mapped each calendar
cutoff to the relevant committed prefix. The initial engine contract takes an
explicit commit cutoff; it does not supply calendar-time lookup.

| Valid date | Knowledge cutoff | Expected annual salary | Explanation |
| --- | --- | ---: | --- |
| February 15 | February 20 | 70,000 | The correction has not been recorded. |
| February 15 | March 11 | 75,000 | The March correction applies from February 1. |

After the correction, a query for January 15 should still return 70,000. The
correction changes only the applicable portion of the valid-time history. A
historical query using the earlier knowledge cutoff should still return the
earlier answer; replacing that answer would discard system-time history.

## Interval boundaries

The initial working contract uses **half-open valid-time intervals**: include the
start and exclude the end. Under its range-replacement rule, this example's
unbounded correction produces:

| Valid interval | Annual salary |
| --- | ---: |
| `[2026-01-01, 2026-02-01)` | 70,000 |
| `[2026-02-01, unbounded)` | 75,000 |

February 1 belongs to the second interval, so adjacent intervals do not both
claim that boundary. Dates here abbreviate midnight UTC. The
[contract](../architecture/temporal-contract.md) defines microsecond coordinates,
unbounded endpoints, overlap ordering and query results. It remains a working
specification, now exercised by a [test-only reference model](../development/reference-model.md)
and boundary checks, alongside the [embedded engine](../development/embedded-engine.md).
The [local database](../development/local-database.md) recovers this history and
forces new records before atomic query publication. Its protocol remains
experimental, with process-exit/retry checks and no stable schema/API commitment.

## Events and snapshots answer different questions

An **event history** records observations or messages received. An event may have
its own timestamp, arrive late or arrive more than once. Turning it into temporal
state requires explicit interpretation and retry rules; an event timestamp is
not automatically either a valid-time boundary or a database commit time.

A **physical snapshot** captures a storage state for a particular purpose, such
as reading or recovery. A sequence of snapshots does not by itself define which
facts applied in February or preserve every previously committed interpretation.
Snapshot and compaction design must retain the historical answers the temporal
contract promises.

The [worked histories](../architecture/temporal-contract.md#worked-history-and-workload-expectations)
cover bounded corrections, retractions, overlaps, duplicate requests and stable
read views with exact expected answers. See the
[architecture overview](../architecture/overview.md) for the route from that
specification to an independently tested implementation.
