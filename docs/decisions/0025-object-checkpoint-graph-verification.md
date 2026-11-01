---
title: "ADR 0025: Object checkpoint graph verification"
description: Compose exact immutable graph validation and bounded actual-journal comparisons before introducing durable remote publication state.
---

# ADR 0025: Object checkpoint graph verification

Date: October 31, 2026. Status: read-only graph/owned-prefix verification
implemented, experimental; [durable intent](../development/local-s3-publication-state.md)
and [verified conditional publication](../development/s3-checkpoint-publication.md)
are now implemented separately. Managed S3 startup and writable recovery remain planned.

## Context

The [head format](../architecture/object-checkpoint-protocol.md) binds declarations
and exact adjacent metadata. The [S3 primitive](../development/s3-checkpoint-heads.md)
performs bounded conditional byte operations. Neither establishes that referenced
history exists or matches the actual owned journal. Two internally valid graphs
can share lineage while representing divergent histories.

Per-object wire limits also do not bound a complete operation. A pair may repeat
dependencies; a malicious segment can advertise more records than its manifest
range. Actual journal records can be larger than an untrusted prefix declaration.

## Decision

Add JDK-only `ObjectCheckpointGraphs` over the existing immutable-object interface
and owned `LocalJournal`. Bind independent namespace/publisher configuration and
actual journal lineage. Resolve namespace-scoped complete manifest/segment images,
then compare reconstructed history with bounded actual journal bytes. Verify both
members of an adjacent pair, preserving independent revision/generation/cutoff.

Reserve manifest bytes/fetches, actual-prefix bytes and record work for the entire
operation before fetching. Bind both manifests, then reserve all segment work
before dependency reads. Count repeated dependencies and both graph/journal record
work. Use the declared prefix length as the owner's read cap. Define explicit
prototype maxima in the [graph guide](../development/object-checkpoint-graphs.md),
distinct from format limits or performance claims.

Extend segment decoding with an independently expected header range, checked
before codec body hashing and record allocation/decoding. Share the existing checkpoint
verification algorithm through a package-private decoded-segment source, retaining
the legacy raw-source path and its error behavior. Fetch/decode each segment once
per graph; keep all evidence private until the complete operation succeeds and
readers close.

Pure compaction and protected local inventory/removal now use the same early
expected-range decode after reserving their aggregate record work. A CRC-valid
header that disagrees with its manifest fails before body work, deliberately
changing bounded-path failure precedence while preserving the public raw verifier
and ordinary local checkpoint publication/load. See [bounded consumer admission](../architecture/checkpoint-manifest-format.md#bounded-consumer-range-admission).

Return immutable metadata and encoded-work usage, with explicit graph/limit/
actual-prefix failures. Own no caller resource and expose no selected-head token,
lease, force receipt, publication permission or removal API. Actual owner I/O and
corruption retain poisoning; caller policy exhaustion does not poison a healthy
journal.

The separate [history-loading extension](0030-bounded-pinned-object-history.md)
now materializes original records from one pinned head during the same complete
pass. Existing metadata-only operations retain their resource contract; a loaded
history does not establish current remote selection or recover an engine.

## Evidence and alternatives

Independent divergent histories and exact budget/read-boundary cases test the
distinction between graph consistency and actual history. Real local adapters and
the optional SDK/HTTP consumer exercise the same API. Existing manifest, segment,
temporal, ownership, cleanup and durability suites retain their prior contracts.
No wire format, runtime dependency or local acknowledgement change is required.

Trusting only lineage or prefix coordinates would accept incompatible histories.
Decoding all records before checking a manifest's expected range would defeat its
record reservation. Fetching/decoding twice would add repeated work without new
identity evidence. Silently sharing a budget-exempt dependency cache would obscure
the operation's costs. Initial journal recovery, exact heap/CPU accounting and
repeated independent invocations remain outside these per-operation counters.

Durable expected/pending state and complete conditional publication are implemented
by separate consumers. A successful graph check alone cannot reconcile a still-in-flight PUT,
prove current selection or detect every unseen cloned writer without distributed
admission. Preserve the existing single-placement and no-external-overwrite
assumptions rather than implying that this verifier provides fencing.
