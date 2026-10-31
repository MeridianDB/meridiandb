---
title: Persistence and replication boundaries
description: MeridianDB's local-first bitemporal library design separates commit authority, immutable history, checkpoint publication, search visibility and optional replication.
---

# Persistence and replication boundaries

MeridianDB's design centers on an embeddable Java bitemporal database with its own
local persistence. Applications can use the experimental local engine without
Kafka, an object-storage service or an application server. Additional storage
implementations and replication are planned extensions. They must preserve the
same temporal answers and declare their own durability and coordination guarantees.

[ADR 0020](../decisions/0020-persistence-and-replication-boundaries.md) records this
direction. The interfaces described here are responsibilities to implement and
test, rather than a stable Java service-provider API.

## Bitemporal history is the core contract

The [temporal contract](temporal-contract.md) distinguishes when a fact applies
from which committed knowledge a reader uses. A retroactive correction changes
later knowledge about an earlier valid interval while preserving earlier answers.
The current system-time coordinate is a database-local commit position; mapping
calendar timestamps to knowledge cutoffs remains unspecified. Event timestamps,
storage generations and search refreshes do not assign that order.

Every backend must preserve complete transactions, database lineage, original
request identities and all supported historical cutoffs. Physical compaction may
repack history, while physical reclamation removes only objects proven obsolete
under the applicable reader and retained-root protocol. Neither operation changes
logical history retention.

## Separate the responsibilities

| Responsibility | Current local implementation | Extension requirement |
| --- | --- | --- |
| Commit authority | One serialized writer and exclusive local ownership | Define ordering and acknowledgement separately from object writes; distributed leadership needs its own protocol |
| Ordered durable history | Append/force journal with exact transaction frames | Preserve lineage, complete order and request identity when transporting or replicating history |
| Immutable history objects | Content-identified segment files with complete bounded validation | Provide immutable creation and bounded reads with independently checked identity and length |
| Checkpoint selection | Verified manifest dependencies, actual journal-prefix binding, ordered forces and atomic rename | Publish only a complete graph through an explicit backend-specific selection protocol |
| Temporal queries and indexes | Eager queries against one pinned committed cutoff | Derived indexes must resolve the same temporal state and expose any lag explicitly |
| Reader protection and reclamation | Cooperative cross-process local barrier and verified retained roots | Protect every supported reader and publisher before retiring metadata or dependencies |

Keep these boundaries small. Introduce an interface with a concrete consumer and
conformance tests. Sharing immutable bytes or verification code does not require
making a filesystem rename look like an object-store write. Local journal
ownership remains explicit while immutable-object and checkpoint implementations
are added. An optional integration's SDK must not become a requirement for local
embedding.

## Immutable-object access

An object adapter needs precise creation, read and failure behavior:

- Bind a key to a complete immutable image with an independent content identifier
  and expected encoded length. Reusing an existing object requires validating its
  bytes; matching a filename or provider token is insufficient.
- Bound input, allocation and response bytes. Specify exact range boundaries,
  overflow checks, short reads, unexpected full responses and changed-object
  behavior for range access. A verified slice alone cannot establish a whole-object
  checksum or expose a partially validated history segment.
- Distinguish absent objects, create conflicts, corrupt content, unsupported
  capabilities and transport failures. A failed response may follow a successful
  remote write; reconcile the exact intended immutable identity before retrying.
- Treat provider ETags and conditional-update tokens as opaque preconditions,
  separately from MeridianDB's whole-object SHA-256 identifiers.

The existing [segment format](immutable-segment-format.md) requires complete-object
verification before records are returned. Range-read support does not relax that
format. Another block-integrity scheme would require its own format decision.

## Object storage and checkpoints

An object-store checkpoint is planned as an optional companion to the local
journal. First upload or verify all immutable dependencies, then publish immutable
manifest bytes and conditionally select the complete generation. The exact head
representation, predecessor binding and recovery protocol require an implementation
decision and failure tests. Listing objects is not by itself a commit protocol.

Amazon S3 documents strong object consistency and conditional writes using
`If-None-Match` and `If-Match`. These are useful building blocks, but updates to
different keys are not a single atomic transaction. A different S3-compatible
service must establish the capabilities actually used. Sources checked
October 31, 2026: [consistency model](https://docs.aws.amazon.com/AmazonS3/latest/userguide/Welcome.html#ConsistencyModel),
[conditional writes](https://docs.aws.amazon.com/AmazonS3/latest/userguide/conditional-writes.html).

Conditional checkpoint selection does not establish database leadership. Two
hosts with separate local journals could acknowledge incompatible histories even
if only one later selects its checkpoint. The initial remote-storage work retains
one exclusively owned local writer and journal; it provides no automatic writer
takeover or cross-host concurrent writing. Unsupported access modes must be
rejected, rather than inferred safe from an object API.

Copying a local store also copies its lineage and may create a divergent journal
tail. Lineage equality alone therefore proves no exclusive namespace ownership.
The remote protocol must distinguish enforceable checks from the deployment's
exclusive-placement assumption, refuse observed prefix or predecessor conflicts,
and protect cleanup under the same supported ownership domain. Safe cross-host
admission requires additional coordination before it can be offered.

Durability remains explicit. Local acknowledgement protects the committed local
journal under its declared filesystem assumptions. A remote checkpoint covers only
its verified prefix. Losing the entire local journal can lose an acknowledged tail
that has not reached remote storage; object storage does not silently upgrade the
acknowledgement to replicated durability. Recovery requires the exact journal
lineage and prefix binding, followed by its contiguous suffix. A remote graph
alone supplies neither a missing suffix nor evidence of earlier acknowledgement.

Remote reclamation needs its own admission and retained-root protocol. A local
file lock protects cooperating local processes; it does not protect a reader on
another host. Preserve remote objects until the implemented protocol proves
exclusive cleanup and complete reader, publisher and backup coverage. Retain
unknown objects and uncertain publication evidence conservatively.

## Optional replication

The first replication direction is one authoritative writer with read-only
followers. Transfer original ordered transactions, retaining their lineage,
positions, request identities and payloads. Bootstrap from a verified checkpoint
at a known cutoff, then replay the exact contiguous journal suffix. A follower
must not create new system-time positions from its own clock or arrival order.

The protocol must define reconnect, duplicate delivery, conflicting payloads,
gaps, bounded lag/backpressure, durable follower progress and checkpoint retention
before replication is usable. Segment transfer can accelerate bootstrap; receiving
segments does not establish a transaction's commit authority.

| Acknowledgement mode | Required meaning |
| --- | --- |
| Local durable | The authoritative local journal completed its force protocol; no replica guarantee |
| Asynchronously replicated | Local durable success with separately reported follower progress; an unsynchronized follower cannot promise preservation of every acknowledged write after promotion |
| Quorum committed | A defined replicated-log protocol establishes durable commitment and safe leadership changes; copying to several peers is insufficient |

Replication and automatic failover are separate stages. Evaluate a maintained
consensus implementation before adopting quorum commitment or automatic leadership
changes, including fencing, membership and crash/retry behavior. Raft separates
leader election, log replication and safety; it is design evidence rather than a
selected dependency. [Raft paper](https://raft.github.io/raft.pdf), checked
October 31, 2026. No replication mode or network service is implemented today.

## Search visibility

The current durable writer forces history before publishing query state and
acknowledging success. Future asynchronous indexing must expose both the durable
commit cutoff and the indexed cutoff. Define an explicit wait-for-cutoff operation
or a complete fallback before promising reads of newly committed knowledge.

A historical predicate must run against the requested valid-time and knowledge
state. Filtering today's values can miss earlier matching values or revive
superseded assertions. Index design must cover corrections, retractions and
interval boundaries against the independent reference. Near-real-time visibility
is an eventual measurable target, separate from the two temporal dimensions;
there are no MeridianDB latency or throughput results yet.

See the [comparable-system research](storage-research.md#persistence-formats-and-temporal-semantics)
for the distinct roles of ClickHouse, Hive, Elasticsearch and XTDB, and the
[roadmap](../roadmap.md) for implemented and planned capabilities.
