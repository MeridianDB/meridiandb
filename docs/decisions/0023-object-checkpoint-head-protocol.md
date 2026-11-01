---
title: "ADR 0023: Object checkpoint head protocol"
description: Separate a compact versioned head and pure metadata checks from conditional object access, owned-journal verification and remote publication.
---

# ADR 0023: Object checkpoint head protocol

Date: October 31, 2026. Status: format and pure metadata checks implemented,
experimental. Conditional head access is implemented separately under
[ADR 0024](0024-conditional-s3-head-access.md); verified publication is implemented
separately under [ADR 0029](0029-verified-s3-checkpoint-publication.md).

## Context

The optional S3 adapter implements bounded immutable bytes. Selecting a complete
checkpoint additionally needs a mutable pointer, exact predecessor evidence,
actual journal-prefix checks and explicit uncertain-result reconciliation.
The existing immutable manifest already has independent physical-generation and
temporal-prefix fields. A provider's ETag supplies a different kind of identity.

## Decision

Define a fixed 256-byte v1 head in the JDK-only core. It binds configured namespace
and publisher UUIDs, one stable publication UUID, remote revision, canonical
journal-header SHA-256, exact immutable manifest identity/length, prefix metadata
and a complete predecessor-head reference. The
[protocol specification](../architecture/object-checkpoint-protocol.md) owns exact
offsets, limits, names, transitions and required future publication/failure behavior.

Hash the exact existing journal header rather than carrying its entire database
identifier. That preserves the full 32,768 UTF-16-code-unit domain, including
unpaired surrogates, with a small fixed allocation. A checksum footer protects the
payload; SHA-256 of the complete image supplies its separate content identity.
Neither value is a signature or a provider comparison token.

Keep remote revision distinct from manifest generation and temporal cutoff. A
first remote head may point at a manifest produced after many local generations;
it need not rewrite that manifest. Require exact adjacent revision and predecessor
identity, unchanged bindings, a distinct adjacent publication identity and
noncontradictory prefix coordinates. A higher cutoff/longer image cannot itself
prove extension of the previous history.

Expose pure binding checks with precise scope. `bindManifest` verifies the whole
encoded manifest and matching declarations, without resolving its segments.
`requireBinding` compares independent expectations. `requireSuccessor` validates
adjacent metadata, without observing a remote selection or an actual journal.
Retain the existing manifest/segment wire formats and engine acknowledgement.

Specify conditional head I/O and full verified publication separately. A future
adapter must pair complete head bytes and a strong ETag from one GET. A publisher
must establish complete graph/actual owned-journal agreement and durable pending
intent before CAS, preserve original intent/preconditions across uncertainty,
and fail closed on observed conflicts or corrupt selected state. No fallback or
reclamation follows an uncertain result. One exclusive local publisher placement
remains an explicit assumption; copied UUIDs and local locks provide no distributed
fencing.

## Evidence and alternatives

Checked October 31, 2026. S3 documents atomic single-key updates and
[strong object consistency](https://docs.aws.amazon.com/AmazonS3/latest/userguide/Welcome.html#ConsistencyModel),
[conditional writes](https://docs.aws.amazon.com/AmazonS3/latest/userguide/conditional-writes.html)
and [version/delete-marker behavior](https://docs.aws.amazon.com/AmazonS3/latest/userguide/versioning-workflows.html).
[RFC 9110](https://www.rfc-editor.org/rfc/rfc9110.html#name-if-match)
defines strong ETag comparison for `If-Match`. Cross-key graph verification,
publication identity, durable pending intent and fail-closed handling are
MeridianDB's protocol choices, not guarantees supplied by a successful CAS.

A variable text head would add parsing/normalization work without a current
consumer for extensible metadata. A small cap containing the full lineage string
would silently restrict existing identifiers. One counter for head and manifest
generation would force unnecessary manifest reconstruction. A stateless CAS
helper would leave lost replies, copied writers and rollback ambiguities hidden.

The fixed format and independent vectors make the declaration boundary auditable.
Future versions require an explicit versioned codec/transition decision. The
optional conditional adapter and real SDK/HTTP checks are implemented under
ADR 0024; full journal/graph publication remains required before a verified
remote checkpoint publisher is available.
