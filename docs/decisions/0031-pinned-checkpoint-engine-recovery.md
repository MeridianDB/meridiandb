---
title: "ADR 0031: Pinned checkpoint engine recovery"
description: Reuse private temporal reconstruction with a factory-owned local journal, same-pass verified object history, a frozen tail and explicit read-only ownership and coverage limits.
---

# ADR 0031: Pinned checkpoint engine recovery

Date: October 31, 2026. Status: implemented, experimental read-only core recovery;
[managed S3 read recovery](0032-managed-s3-read-recovery.md) is implemented separately.
[Core pinned writes](0033-pinned-checkpoint-writes.md) are now implemented separately;
managed S3 writable recovery remains planned.

## Context

Verified immutable checkpoint records are insufficient to expose a temporal engine.
Recovery must bind them to the actual local owner, validate the full journal,
apply the prefix once and restore only its suffix. The selected checkpoint cutoff
may be older than available local history. A cleanly lost later suffix is not
discoverable from an earlier checkpoint alone.

## Decision

Add concrete `LocalDatabase.openFromObjectCheckpoint` factories using a caller's
explicit pinned head and immutable-object adapter. Acquire the existing local
checkpoint store and journal privately; retain both owners until database close.
Freeze observed journal head H, check an optional independent minimum, then load
history against that same journal. Caller-owned adapters and externally supplied
History values do not become storage-ownership authority.

Reuse the private reconstruction algorithm from [ADR 0013](0013-local-database-recovery.md):
restore original checkpoint transactions once, validate the full journal through H
and apply only its contiguous suffix. Keep original lineage, no-op transactions,
exact retry payloads, request lookup and all historical temporal snapshots.
Expose no engine/view before complete success. Independently clean up every owned
resource on failure, preserving the primary cause and suppressed cleanup failures.

The object adapter remains caller-owned. There is no public arbitrary backend or
restore callback. This explicit mode does not read local checkpoint selection or
the provider's current-head key; it is not automatic fallback from a failed local
open. Invalid pinned evidence fails even when another valid checkpoint exists.
Normal local modes retain their contracts. Read-only transactions remain rejected;
the separate [writable pinned mode](0033-pinned-checkpoint-writes.md) now adds
startup synchronization and the existing forced commit protocol.

The [recovery guide](../development/object-checkpoint-recovery.md) owns APIs,
admission order, minimum coverage, supported placement, lifetime and cost limits.
The factory requires the complete matching local journal, including the prefix.
It does not reconstruct a lost log or supply replicated durability.

## Evidence and alternatives

The design uses [ADR 0030](0030-bounded-pinned-object-history.md)'s recorded primary
recovery comparisons and same-pass loader. Its XTDB/Lucene sources were checked
November 1, 2026 at 01:46:44 UTC (October 31, 21:46:44 EDT); this decision reuses
that evidence rather than claiming a new external behavior check.

Independent canonical/query and seeded dense comparisons verify the core factory,
while targeted failures exercise actual owner release, strict suffix evidence,
coverage admission and private startup. These are MeridianDB implementation checks,
not another database's guarantees or device-power-loss qualification.

Accepting an arbitrary History as a completed recovery certificate would detach
its earlier prefix observation from the current journal owner. Borrowing a writable
journal without a transfer/serialization contract would permit append/close races.
Rebuilding directly from the whole log alone would not exercise checkpoint-prefix
reconstruction. Owning concrete startup and using the existing private replay path
keeps those boundaries explicit.

The minimum sequence can reject known insufficient coverage, but cannot identify
unknown lost transactions or authenticate the content of a later tail. Current-head
admission, unresolved publication intent and actual S3 client scope remain part of
the separate managed startup integration. Retained objects grant no remote cleanup
authority, and graph budgets remain distinct from complete engine-recovery costs.
