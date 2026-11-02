---
title: "ADR 0038: Permanent S3 coordination guard"
description: Share one bounded native control channel, reject changed identities and retain uncertain cleanup without equating a guard lease with durable namespace admission.
---

# ADR 0038: Permanent S3 coordination guard

Date: November 1, 2026. Status: internal native primitive implemented; durable
ledger, enrollment and participating SDK composition remain unimplemented.

## Context

The [accepted reclamation protocol](../architecture/remote-reclamation.md) requires
shared participant lifetimes and exclusive maintenance in one canonical local
placement. The [pure state codec](../architecture/s3-coordination-state.md) has no
native ownership. JVM file locks alone also cannot represent multiple logical
readers: locks belong to the whole JVM, and another same-file channel close may
release existing native locks.

## Decision

Add a package-private optional-artifact guard for an existing empty permanent
regular nonsymlink control file. Resolve its real parent, require a default-provider
nonnull file key and reject changed or aliased registered identity before another
native open. Preserve common local-adapter file reservations. Trust stable names
and cooperating participants; make no copied-placement or cross-host fencing claim.

Use one native channel per canonical identity in a loaded adapter copy/JVM.
Bound simultaneous placements and shared references. Reuse actual shared locks;
defer incompatible local requests without duplicate opens. Use nonblocking native
shared/exclusive acquisition across processes and reject silent shared-to-exclusive
conversion. Validation and force use the same channel and serialized monitor;
no borrowed native delegate is exposed.

Invalidate access before final cleanup and independently attempt release and
close, preserving primary/suppressed errors. Remove entries only after known
successful channel close. Any thrown close remains quarantined even if the native
wrapper reports closed. Refuse no-op retry as cleanup evidence. Unexpected
unregistered overlap retains the duplicate channel/reservation until process exit.

The [guard guide](../development/s3-coordination-guard.md) owns exact bounds,
capability/failure behavior, native test evidence and dated JDK primary links.
The guard performs no creation or directory synchronization. Fresh creation,
exact target/placement binding, immutable durable generations, complete receipt
history and unknown-attempt reopen remain separate ledger obligations. Existing
S3 clients remain outside coordinated mode.

## Alternatives and consequences

A process-local reference count without a native lock cannot exclude cooperating
processes. A native channel per reader can lose other leases through same-file
close effects. Renaming or deleting the locked name can split identity. Boolean
path equality or `isOpen() == false` after failure cannot provide the missing
ownership or cleanup evidence.

The internal guard makes native exclusion independently testable before durable
metadata and SDK composition. Its conservative quarantine can require process
restart after a local close failure. Restart clears no durable remote hazard.
File keys and real paths remain observations under stable-placement assumptions;
capacity refusal and unsupported capabilities stay explicit. No public admission,
remote mutation, reclamation permission or temporal-semantic change follows.
