---
title: "ADR 0026: Bounded S3 publication state"
description: Represent exact target, expected observation and pending conditional intent in the optional artifact before adding durable local storage.
---

# ADR 0026: Bounded S3 publication state

Date: October 31, 2026. Status: bounded representation and pure transitions
implemented, experimental. [ADR 0027](0027-durable-local-s3-publication-state.md)
adds durable local installation; [ADR 0029](0029-verified-s3-checkpoint-publication.md)
adds conditional publication and durable reconciliation.

## Context

The conditional head API can preserve exact request bytes and preconditions, and
the graph verifier can compare complete history with the actual journal. Neither
stores the local expected/pending evidence needed after process exit or a lost
reply. An old-head read cannot establish that an earlier request finished without
effect, so rebuilding a different intent or adopting a new token is unsafe.

## Decision

Place S3-specific state/intent values and a pure codec in the optional artifact.
Reuse existing head images, bindings and strong quoted tokens. Persist one exact
target with original endpoint spelling, signing-region ID and bucket; exclude
credentials and clients. Endpoint/region text have explicit ASCII byte caps,
without introducing an AWS-region catalog or narrowing the core lineage domain.

Use one bounded binary snapshot with a positive independent local generation,
target/bindings, optional complete expected observation and optional exact pending
candidate/operation. Derive the precondition from expected state rather than
storing an independently mutable duplicate. A 4 KiB cap covers all current legal
combinations. Versioned framing, exact lengths/flags and a SHA-256 footer reject
malformed or contradictory images; the separate complete reference includes the
footer. The [format guide](../development/s3-publication-state.md) owns field
offsets, limits, APIs and failure classes.

Expose pure fresh, prepare and exact-candidate-observation recognition operations.
Reject another prepare while pending, scope/binding changes, different candidate
bytes and local-generation overflow. Preserve the original publication identity,
bytes and quoted token. No generic reset/adopt/clear/rebase operation resolves
missing/old head observations, errors or uncertainty. Exact lexical endpoint
checks intentionally preserve a stricter retry scope than URI value equivalence.

Pure decoded state declares metadata. It proves no observed request, prior
transition history, graph verification, supported ownership, force or durable
installation. Recognition of the exact candidate observation likewise requires
the complete publisher to establish its provenance and graph/actual-prefix facts.
Durable local ownership and installation are implemented separately under
[ADR 0027](0027-durable-local-s3-publication-state.md), with failure poisoning,
damaged-newest handling and process-exit/reopen tests.

## Evidence and alternatives

Independent binary vectors and field/state mutations verify framing, whole-image
identity, nested head/token checks and transitions. Existing actual-SDK tests retain
their lost-response, old-head and later-in-flight-effect cases. The format cannot
replace those protocol facts or detect every unseen copied publisher/rollback.
No dependency, core format, local acknowledgement or engine recovery change is
required by this decision.

Keeping these S3-specific fields in core would introduce provider concepts into
ordinary embedding. A free-form text document would need additional normalization
and parsing policy for exact retry bytes. Independently editable token/operation
fields could contradict the expected observation. A reset or implicit adoption
helper would hide unresolved intent. Keep the representation small and explicit,
then use the separately verified local durability protocol before claiming
durable publication state.
