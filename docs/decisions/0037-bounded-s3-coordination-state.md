---
title: "ADR 0037: Bounded S3 coordination state"
description: Separate canonical immutable root and exact-attempt declarations from native ownership, durable receipts and actual SDK completion provenance.
---

# ADR 0037: Bounded S3 coordination state

Date: November 1, 2026. Status: pure state/codec implemented; concrete durable
coordination and participating SDK sessions remain unimplemented.

## Context

The accepted [remote reclamation protocol](../architecture/remote-reclamation.md)
requires complete root accounting and individual mutation hazards before any
remote removal can be safe. Existing publication metadata describes logical
expected/pending selection; it cannot prove that every earlier physical attempt
has finished. Native admission, state encoding and terminal response provenance
need independently testable boundaries.

## Decision

Implement an immutable optional-artifact state and canonical versioned codec
before native or SDK integration. Bind exact target, namespace, publisher, lineage
and placement. Store complete bounded head/manifest declarations, derived typed
dependencies, explicit retention and permanent retirement tombstones. Reject
duplicate identities, inconsistent shared references and noncanonical ordering.

Identify each attempt by placement, contiguous ordinal and nonce. Preserve its
exact operation/key/content, original opaque condition and original head identity
where replacement requires it. Permit one unresolved attempt, freeze catalog
changes while pending and settle only the exact original attempt. Retain a
matching last completion/watermark; refuse gaps and arithmetic exhaustion.
Reserve both intent and completion generation increments before beginning.

Bound whole images, root images/counts and aggregate nested references before
allocation. Fixed schema caps guarantee completion image growth fits. Use SHA-256
integrity plus a distinct whole-image reference, independent literal byte/hash
vectors and checksum-valid malformed-input tests. The
[format specification](../architecture/s3-coordination-state.md) owns exact fields,
limits, transition behavior and verification evidence.

Keep transition methods package-private. Public immutable declarations, a fresh
factory or valid decoding provide no native lease, durable installation, complete
liveness, rollback detection or actual terminal evidence. Status 200/204/412 values
are schema declarations; the future effect owner must qualify the original
request's complete response. The future ledger must preserve full immutable
intent/completion generations rather than replacing history with this watermark.

## Alternatives and consequences

Reusing logical publication reconciliation would conflate selected state with
physical-attempt completion. A generic mutable map or arbitrary keys would weaken
typed dependency and exact-condition checks. Unbounded manifests or tombstone
purging would make capacity and historical validation harder to establish. The
small explicit caps instead refuse growth; future reclamation of control history
requires its own evidence-preserving design.

The separate pure model makes byte and transition failures testable without
filesystem capability or cloud service assumptions. It also deliberately lacks
authority: actual selected/retained-root verification, permanent native ownership,
durable intent before dispatch, qualified same-attempt completion and process
hazard conformance still need concrete implementations. Existing raw clients
remain outside coordinated mode. There is no remote deletion API, admission,
provider qualification or temporal-semantic change in this decision.
