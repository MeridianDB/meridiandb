---
title: "ADR 0022: Optional S3 object access"
description: Package bounded S3 byte access separately from MeridianDB core, with conditional writes, explicit SDK resource limits and real HTTP protocol tests.
---

# ADR 0022: Optional S3 object access

Status: accepted; byte adapter and controlled protocol checks implemented,
experimental. Live-provider qualification and remote checkpoint publication remain
open. Checked October 31, 2026.

## Context

The [immutable-object contract](../development/immutable-objects.md) has a concrete
segment-codec consumer and a JDK-only local adapter. Another implementation can
reuse that contract without replacing local commit authority or checkpoint
selection. An SDK integration is now a separate deliverable, satisfying the
condition for additional packaging in [ADR 0004](0004-java-build-foundation.md).

## Decision

Keep the root `meridiandb-core` JAR and its local build intact. Put the optional
`meridiandb-s3` JAR in `adapters/s3/`, with an independent Maven POM and a dependency
on core. Core has no dependency on the adapter or SDK. Install core locally before
verifying the adapter; CI runs both on Linux and Windows. Attach only the abstract
object conformance suite as a test-only core classifier, so both implementations
execute the same assertions without copying their expectations.

Use the maintained AWS SDK for Java v2 and its synchronous URLConnection transport.
Exclude the SDK's default Apache 5 and Netty transports. Pin the researched SDK
version in the adapter POM; the [build guide](../development/build.md) owns current
artifact research. Keep the Python site build independent.

`S3ObjectStore` owns its SDK and HTTP clients. It delegates credential resolution
without transferring ownership of the supplied credential provider: the SDK may
otherwise close a shared refreshable provider with its client. Configure explicit
operation/attempt, connection and socket timeouts and one SDK attempt. Disable
chunked upload framing and supply a precalculated SHA-256 transfer checksum for
the known bounded body.

Create with `If-None-Match: *`. Only `412 PreconditionFailed` permits the shared
explicit-reuse path; `409 ConditionalRequestConflict` remains a failure. A lost
response leaves the write outcome uncertain. A later explicit retry either
creates the missing object or verifies the entire existing expected image. GET
absence requires `404 NoSuchKey`; access denied and a missing bucket remain failures.

Consume GET inside the SDK response transformer, validate the complete response,
observe cleanup, then verify SHA-256 before returning a heap reader. Bound error
and control bodies before SDK XML unmarshalling; reject nonempty successful PUT
bodies. The [S3 guide](../development/s3-objects.md) owns exact limits, API examples,
failure classifications and fixture coverage.

## Evidence and limits

The real SDK executes signed conditional PUT and complete GET requests against a
controlled loopback HTTP fixture. Shared object assertions, concurrent creation,
lost responses, changed objects, malformed replies, body limits, timeouts and
native cleanup faults have explicit tests. The fixture does not authenticate
signatures or model a cloud service's durability, authorization, TLS, versioning
or infrastructure behavior. This is protocol conformance evidence, not a claim
that every S3-compatible service is qualified.

This choice follows S3's [conditional-write semantics](https://docs.aws.amazon.com/AmazonS3/latest/userguide/conditional-writes.html)
and [GET error semantics](https://docs.aws.amazon.com/AmazonS3/latest/API/API_GetObject.html).
The SDK's [timeout guide](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/timeouts.html),
[retry guide](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/retry-strategy.html)
and [checksum guide](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/s3-checksums.html)
support explicit configuration. MeridianDB's body caps, packaging and complete
readback requirements are project decisions rather than provider guarantees.

## Alternatives and consequences

A mandatory SDK dependency would burden ordinary local embedding. A profile that
changes the contents of the core JAR would give one coordinate different runtime
requirements. A full reactor/source relocation would change all existing build
paths without helping this small integration; retain two explicit builds for now.
Revisit a shared parent/reactor when more deliverables justify it.

The adapter deliberately transfers whole bounded images. Physical range proofs,
asynchronous transfer and high-throughput tuning need separate consumers and
measurements. Conditional object creation grants no distributed writer authority,
checkpoint selection, remote recovery, reclamation or stronger acknowledgement.
