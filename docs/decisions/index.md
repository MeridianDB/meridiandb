---
description: Follow MeridianDB's decisions on Java and Maven, documentation tooling and initial temporal semantics, with rationale and alternatives.
---

# Architectural decisions

Decision records explain a choice, its alternatives and the evidence that could
change it. They preserve the reasoning without treating an early hypothesis as a
permanent commitment.

| Record | Status | Scope |
| --- | --- | --- |
| [0001: Language strategy](0001-language-strategy.md) | Initial working decision | Java as the initial default; conditions for considering other languages |
| [0002: Documentation toolchain](0002-documentation-toolchain.md) | Working decision | Zensical, MkDocs configuration, initial hosting and portability |
| [0003: Initial temporal semantics](0003-initial-temporal-semantics.md) | Working specification; reference and memory engine implemented | Whole-value intervals, ordered commits, read views and request identity |
| [0004: Java build foundation](0004-java-build-foundation.md) | Adopted for the experimental artifact | Java 25, pinned stable Maven toolchain, version research and verification |
| [0005: Reference model](0005-reference-model.md) | Implemented in test support | Immutable logical types, history-search oracle and independent dense-state checks |
| [0006: In-memory engine](0006-in-memory-engine.md) | Implemented; experimental | Immutable interval snapshots, serialized publication, pinned views and explicit close behavior |
| [0007: Journal format](0007-journal-format.md) | Pure codec implemented | Versioned bounded framing, exact identity, checksums and strict incomplete/corrupt input handling |
| [0008: Local journal](0008-local-journal.md) | Implemented; experimental | Exclusive local writer, append/force cutoffs, bounded replay and explicit evidence-preserving tail repair |
| [0009: Immutable history segments](0009-immutable-segment-format.md) | Pure codec implemented | Exact transaction ranges, bounded immutable objects, integrity footer and full-object identifiers |
| [0010: Local immutable segment I/O](0010-local-segment-io.md) | Implemented; experimental | Create-only local files, bounded expected-ID verification and heap-owned reader lifetimes |
| [0011: Checkpoint manifest representation](0011-checkpoint-manifest-format.md) | Codec and consistency verifier implemented | Bounded generation metadata, complete segment references and reconstructed journal-prefix binding |
| [0012: Local checkpoint publication](0012-local-checkpoint-publication.md) | Implemented; experimental | Actual owner-journal verification, immutable generation selection, ordered forces and atomic rename |
| [0013: Local database recovery](0013-local-database-recovery.md) | Read recovery implemented | Stored lineage, complete checkpoint-plus-tail replay, historical reads and request lookup |
| [0014: Durable local commit publication](0014-durable-commit-publication.md) | Forced-write protocol and process crash/retry conformance implemented | Private preparation, startup synchronization, append/force before publication and explicit uncertainty |
| [0015: Temporal query operators](0015-temporal-query-operators.md) | Implemented; experimental | Signed-value predicates, explicit entity selection and filtered intervals over one pinned snapshot |
| [0016: History-preserving compaction](0016-history-preserving-compaction.md) | Pure repacking/publication implemented; protected removal separate | Retain all transactions and request IDs; bound repacking and separate publication from protected physical reclamation |
| [0017: Live checkpoint reader protection](0017-live-reader-protection.md) | Shared barrier implemented; protected removal separate | Independent lazy-reader lifetime, cross-process exclusion and fail-closed cleanup |
| [0018: Verified reclamation inventory](0018-reclamation-inventory.md) | Inventory implemented; protected removal separate | Explicit retained roots, complete owner-bound verification, conservative diagnostic handling and bounded candidate accounting |
| [0019: Protected physical reclamation](0019-protected-physical-reclamation.md) | Local removal implemented; integrated conformance remains separate | Recompute under both owners, force live roots, retire metadata before dependencies and preserve interrupted orphans |

The current project has no stable engine API or storage-format compatibility promise. Read the
[architecture overview](../architecture/overview.md) for the open design questions.

When a material decision changes, update its current status and retain the dated
reasoning. Keep the README and related technical pages consistent with
the decision rather than copying detailed policy into each page.
