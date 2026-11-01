---
title: "ADR 0036: Coordinated remote reclamation"
description: Stage remote reclamation behind single-placement namespace admission, durable per-attempt hazards, verified roots and exact ordered conditional removal.
---

# ADR 0036: Coordinated remote reclamation

Date: November 1, 2026. Status: accepted design; coordinated admission, inventory,
conditional deletion and integrated reclamation remain unimplemented.
[ADR 0037](0037-bounded-s3-coordination-state.md) adds pure bounded declarations
without native or SDK integration.

## Context

The experimental S3 adapter implements immutable object access, verified publication
and managed temporal recovery. Its current clients have no remote-reader admission
or deletion interface. Local store locks and an expected head observation cannot
protect remote reclamation against independent readers, publishers or delayed
requests. Removing physical checkpoint representations must preserve all logical
history, the full journal and selected/retained roots.

## Decision

Adopt the [coordinated remote reclamation protocol](../architecture/remote-reclamation.md)
before adding delete effects. The first supported implementation targets a fresh
cooperating namespace in a never-versioned general-purpose bucket, with one
canonical local placement on one host. Keep existing unguarded APIs separate;
no automatic enrollment, legacy migration or cross-host fencing is implied.

Shared admission protects participating reader, uploader and publisher lifetimes;
exclusive maintenance protects root observation, verification and removal. Use a
permanent native guard inode with durable binding/activity generations beside it.
Preserve common identity reservation, shared-lock capability and uncertain-close
rules; expose no unguarded delegate through the coordinated path.

Durably record every potentially mutating attempt before sending it, including
immutable PUTs and head-intent uploads. Reserve completion capacity first. Only
that attempt's validated terminal result may supply its durable completion.
Unknown attempts remain quarantined across close/restart; an absent key, later
retry success or logical publication reconciliation cannot settle an earlier
attempt. A validated completion already installed before a local failure remains
recognizable on reopen. The initial protocol has no automatic unknown-attempt
clearing mechanism.

Under exclusive admission and clean activity/publication state, verify current and
explicit retained/backup roots and bounded obsolete candidates against complete
graphs and the actual owned journal. Register all roots before remote use and
include every nonretired root's dependencies in the authoritative live set; an
unknown manifest's preserved bytes alone cannot protect its graph. Uncertain root
coverage defers deletion. Pair full body identity and opaque tag from
one GET. Recompute at execution; retire obsolete head metadata, then manifests,
then unused segments through exact original-condition requests. Keep unknown,
orphan and diagnostic objects and stop with durable quarantine on uncertainty.

Implement and verify admission/hazards, inventory, conditional SDK deletion and
ordered integration as separate required boundaries. A protocol, preview, delete
primitive or universal deferral alone does not establish reclamation. Qualify
actual normal removal, concurrent readers/publication, late effects, process
interruption and canonical/seeded engine continuation before offering the mode.

## Evidence and alternatives

AWS documents general-purpose `If-Match` deletion and distinguishes unversioned
removal from versioned markers. Its timeout guidance supplies no certificate that
an unknown operation cannot take effect later. These facts and the protocol's
explicit deductions are linked and dated in the [protocol](../architecture/remote-reclamation.md).
The primary check started November 1 at 19:12:05 UTC /14:12:05 EST; exact SDK
2.57.8 sources expose the required conditional header. Maven metadata/compatibility
were freshly checked, with no selected-version change.

A raw key sweep cannot establish root liveness. Conditional deletion alone cannot
exclude readers or delayed mutations. Deleting into absence then recreating the
same content/tag may let an older unknown delete remove a newly live dependency;
the protocol therefore refuses reuse while that individual attempt is unresolved.
TTL/PID assumptions, client close and a fresh GET do not supply the missing fence.

Local coordination makes a bounded initial mode possible under explicit placement
assumptions; it does not extend to arbitrary hosts or copied directories. External
coordination, version-aware reclamation, legacy migration, unknown-attempt recovery
and throughput optimization remain separate decisions. No runtime API, wire-format
change, bucket setting or deletion is implemented by this decision.
