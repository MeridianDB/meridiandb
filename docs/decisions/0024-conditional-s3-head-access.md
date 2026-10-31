---
title: "ADR 0024: Conditional S3 head access"
description: Keep same-response head observations and exact conditional byte writes separate from verified checkpoint publication, with bounded tokens and explicit uncertainty.
---

# ADR 0024: Conditional S3 head access

Date: October 31, 2026. Status: optional head-access primitive implemented and
protocol-tested, experimental; verified checkpoint publication remains planned.

## Context

The core [head format](../architecture/object-checkpoint-protocol.md) defines exact
identity, predecessor and metadata transitions. The immutable S3 byte API cannot
replace a mutable selection record. Conditional provider access needs its own
interface and evidence without implying full graph, journal or writer verification.

## Decision

Add `S3CheckpointHeads` to the existing optional SDK artifact. Keep `S3ObjectStore`
immutable-only. Share package-private client configuration and complete-body
cleanup helpers; each public adapter owns a separate SDK/transport client and
preserves caller ownership of supplied credentials. Core gains no SDK dependency.

Bind one configured namespace/publisher/lineage to its protocol key. Read exact
head bytes and a strong opaque token from a single GET, then validate cleanup,
head decoding and independent bindings before returning an immutable observation.
Preserve endpoint/bucket/key scope with saved observations. Restoring saved metadata
does not authenticate its provenance or establish current selection.

Expose only create-if-absent and replace-if-observed-token writes. Preflight exact
head bytes and bindings; replacement also checks its complete predecessor and
adjacent transition. Preserve the original precondition, signed binary bytes and
checksum for one SDK attempt, without refresh, retry or rebase. Return a byte-write
reference, not a graph/prefix/publication receipt. Typed service/uncertain failures
record observations without resolving an earlier in-flight intent.

Use a single quoted strong ASCII ETag capped at 1,024 bytes including quotes.
Preserve empty quoted tags and literal comma/asterisk/backslash content. ASCII is
a deliberately narrower transport policy than RFC `obs-text`; reject ambiguity
before a PUT. Require one SDK-visible tag and canonical fixed length after a GET.
Document URLConnection/SDK header normalization: case-distinct raw duplicate
fields can collapse before adapter inspection. Keep that diagnostic limitation
explicit instead of claiming raw-wire header validation.

The [head-access guide](../development/s3-checkpoint-heads.md) owns operations,
failure classes, exact limits, verified cases and fixture boundaries. The wider
publication protocol still requires complete graph/actual-journal agreement and
durable expected/pending intent before selection.

## Evidence and alternatives

Checked October 31, 2026: actual selected SDK source and current
[PutObject API](https://docs.aws.amazon.com/AmazonS3/latest/API/API_PutObject.html),
[conditional-write semantics](https://docs.aws.amazon.com/AmazonS3/latest/userguide/conditional-writes.html),
[GET semantics](https://docs.aws.amazon.com/AmazonS3/latest/API/API_GetObject.html)
and [RFC 9110 ETags](https://www.rfc-editor.org/rfc/rfc9110.html#name-etag).
Fresh artifact evidence is recorded in the [build guide](../development/build.md).
Token limits, exact scope checks and the separation of primitives from publication
are MeridianDB decisions rather than additional provider guarantees.

A controlled changing-token fixture exercises real SDK requests and conditional
outcomes. Existing immutable byte tests remain unchanged. Native stream checks
observe cleanup faults; a real URLConnection probe confirms the case-folding
limitation. A deliberate rollback case demonstrates that an old token can recur
and permit a stale write, so the API makes no distributed-fencing claim.

Putting mutable operations on the immutable byte interface would blur lifetimes
and obligations. Deriving provider tokens from content IDs would impose an invalid
ETag assumption. A convenience retry/rebase wrapper would hide unresolved intent
and could advance the wrong predecessor. Implement the narrow primitive first;
complete the durable verified publisher separately before claiming remote
checkpoint installation, recovery or reclamation.
