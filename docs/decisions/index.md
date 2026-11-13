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
| [0019: Protected physical reclamation](0019-protected-physical-reclamation.md) | Local removal and integrated local conformance implemented | Recompute under both owners, force live roots, retire metadata before dependencies and preserve interrupted orphans |
| [0020: Persistence and replication boundaries](0020-persistence-and-replication-boundaries.md) | Architecture direction adopted; managed read recovery implemented separately; managed S3 writable recovery implemented separately; replication planned | Local-first bitemporal library, distinct commit/object/checkpoint responsibilities and optional replication |
| [0021: Bounded immutable-object access](0021-bounded-immutable-object-access.md) | Interface, local adapter and segment consumer implemented; experimental | Exact whole-image identity/length, create-only writes, explicit reuse and verified heap ranges |
| [0022: Optional S3 object access](0022-optional-s3-object-access.md) | Byte adapter and controlled SDK/HTTP checks implemented; experimental | Separate optional artifact, conditional creation, complete verified reads and explicit response/ownership limits |
| [0023: Object checkpoint head protocol](0023-object-checkpoint-head-protocol.md) | Format and pure checks implemented; publisher separate | Fixed head identity, independent revision, predecessor/binding checks and specified conditional publication |
| [0024: Conditional S3 head access](0024-conditional-s3-head-access.md) | Conditional primitive implemented; publisher separate | Same-response opaque tokens, scoped exact intents, one-attempt writes and explicit uncertainty/native-header limits |
| [0025: Object checkpoint graph verification](0025-object-checkpoint-graph-verification.md) | Implemented, experimental | Bounded full graph and actual-prefix verification, separate from selection and durable intent |
| [0026: Bounded S3 publication state](0026-bounded-s3-publication-state.md) | Format and pure transitions implemented; local installation separate | Exact target/expected/pending snapshots without local I/O or selection proof |
| [0027: Durable local S3 publication state](0027-durable-local-s3-publication-state.md) | Implemented, experimental | Exclusive local ownership, synchronized immutable generations and fail-closed exact-state reopen |
| [0028: Verified S3 publication preparation](0028-verified-s3-publication-preparation.md) | Preparation implemented; selection separate | Actual client scope, shared graph/owner checks, barriers, immutable intent and exact durable pending state |
| [0029: Verified S3 checkpoint publication](0029-verified-s3-checkpoint-publication.md) | Implemented, experimental | Original-precondition submission, shared pre/post graph budget and durable exact-candidate reconciliation |
| [0030: Bounded pinned object history](0030-bounded-pinned-object-history.md) | Implemented, experimental | Same-pass immutable original records after complete graph and actual owned-prefix verification; no engine or current-head proof |
| [0031: Pinned checkpoint engine recovery](0031-pinned-checkpoint-engine-recovery.md) | Implemented, experimental read-only core mode | Factory-owned local journal, verified pinned prefix and exact tail reconstruction; managed S3 startup and writable integration remain separate |
| [0032: Managed S3 read recovery](0032-managed-s3-read-recovery.md) | Implemented, experimental optional read-only mode | Exact durable expected state, fresh scoped head observation and privately owned core recovery |
| [0033: Writes after pinned checkpoint recovery](0033-pinned-checkpoint-writes.md) | Implemented, experimental core mode | Actual journal/name startup barriers, forced local writes and original retry identity after explicit pinned recovery |
| [0034: Managed S3 writable recovery](0034-managed-s3-writable-recovery.md) | Implemented, experimental optional mode; conformance verified separately | Exact managed admission, core startup barriers and forced local transactions with concurrent committed-state capture |
| [0035: Remote writable recovery conformance](0035-remote-writable-recovery-conformance.md) | Implemented, experimental verification | Unchanged SDK canonical/seeded histories, publication interruptions, exact retry continuation and local coverage limits |
| [0036: Coordinated remote reclamation](0036-coordinated-remote-reclamation.md) | Accepted protocol; pure declarations implemented separately; durable enrollment/SDK integration pending | Single-placement admission, complete root catalog, durable per-attempt hazards and verified ordered conditional removal |
| [0037: Bounded S3 coordination state](0037-bounded-s3-coordination-state.md) | Pure state/codec implemented; durable coordination pending | Bounded immutable root catalog, exact individual attempts and canonical bytes without native or SDK provenance |
| [0038: Permanent S3 coordination guard](0038-permanent-s3-coordination-guard.md) | Internal native primitive implemented; ledger/enrollment pending | Bounded sole-channel shared/exclusive leases, permanent identity and uncertain-close quarantine |
| [0039: Linux local storage priority](0039-linux-local-storage-priority.md) | Adopted direction; qualification and layouts planned | Linux production target, basic Windows compatibility, deferred S3 expansion and measured temporal-format/I/O experiments |
| [0040: Linux qualification evidence](0040-linux-qualification-evidence.md) | Development harness implemented; production qualification open | Same-filesystem fixtures, exact inventory, fresh native/runtime/result evidence and bounded failure cleanup |
| [0041: Deterministic workload corpus](0041-deterministic-workload-corpus.md) | Test-only corpus implemented; operation measurements separate | Bounded seeded actions, explicit historical cutoffs, independent answers, realized metadata and separate schedule/history fingerprints |
| [0042: Local operation measurements](0042-local-operation-measurements.md) | In-process core implemented; supervision separate | Fresh checked lifecycles, direct call clocks, separate validation/loop overhead and scoped optional JVM telemetry |
| [0043: Supervised workload evidence](0043-supervised-workload-evidence.md) | Harness implemented; bounded ordinary/attributed observations available | Bounded private process groups, clean source/class bindings, strict JSON admission and scoped allocated/process-I/O accounting |
| [0044: Recorded workload reports](0044-recorded-workload-reports.md) | Reporting implemented; bounded ordinary/attributed observations available | Recomputed saved evidence, bound producer receipts and separate per-iteration metrics without native reobservation |
| [0045: Internal local I/O counters](0045-local-io-counters.md) | Counters and reporting implemented; bounded Linux observations available | Completed caller-thread adapter bytes and force counters with explicit uncertainty and bounded internal capture |
| [0046: Attributed local lifecycle](0046-attributed-local-lifecycle.md) | Attribution and reporting implemented; bounded Linux observations available | Explicit operation/auxiliary scopes, logical-history denominators and bounded incomplete evidence without changing ordinary result schemas |
| [0047: Attributed workload evidence](0047-attributed-workload-evidence.md) | Serialized attribution and reporting implemented; bounded Linux observations available | Explicit mode/source bindings, independent original-history decoding and raw-counter/ratio recomputation with bounded live/saved admission |
| [0048: Bounded local checkpoint history](0048-bounded-local-checkpoint-history.md) | Opt-in acquisition and explicit recovery implemented; evaluation pending | One verified local segment pass, private original records and actual-prefix binding under per-call encoded-work budgets |
| [0049: Explicit checkpoint history recovery](0049-explicit-checkpoint-history-recovery.md) | Explicit experimental factories implemented; evaluation pending | Private same-pass checkpoint replay, transient-list release and unchanged journal verification/startup acknowledgement |
| [0050: Recovery comparison core](0050-recovery-comparison-core.md) | Test-only synchronous core implemented; supervision/evaluation pending | Fresh equivalent recovery cohorts, bounded aggregate work, raw operation/native scopes and independent checks |

| [0051: Uninstrumented recovery controls](0051-uninstrumented-recovery-controls.md) | Explicit test-only controls implemented; evaluation pending | Native capture disabled with read-only caller admission, absent counters and equivalent recovery contracts |

The current project has no stable engine API or storage-format compatibility promise. Read the
[architecture overview](../architecture/overview.md) for the open design questions.

When a material decision changes, update its current status and retain the dated
reasoning. Keep the README and related technical pages consistent with
the decision rather than copying detailed policy into each page.
