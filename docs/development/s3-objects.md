---
title: Optional S3 immutable objects
description: Build and use MeridianDB's optional S3 byte adapter with conditional creation, complete SHA-256 verification and controlled SDK/HTTP conformance tests.
---

# Optional S3 immutable objects

Further S3 development is deferred while [Linux local storage](../architecture/linux-storage.md)
is qualified and measured. Existing code, tests and experimental contracts remain
available; incomplete coordination/reclamation is not a prerequisite for local work.

`org.meridiandb.storage.s3.S3ObjectStore` implements the
[immutable-object contract](immutable-objects.md) using the AWS SDK for Java v2.
It is a separate experimental `org.meridiandb:meridiandb-s3:0.1.0-SNAPSHOT` artifact.
The core JAR and ordinary local embedding retain no external runtime dependency.
No library is published.

This adapter creates and reads byte objects in an existing bucket. Separate APIs
provide [verified checkpoint publication](s3-checkpoint-publication.md) and
[core pinned read-only recovery](object-checkpoint-recovery.md). Optional
[managed S3 read recovery](s3-database.md) admits durable expected selection.
Managed S3 writable recovery is implemented separately; protected remote object deletion remains planned; the local journal and commit acknowledgement protocol are unchanged.
[ADR 0022](../decisions/0022-optional-s3-object-access.md) records the packaging and
provider-library choice. The [checkpoint-head format](../architecture/object-checkpoint-protocol.md)
and pure metadata checks are available in core. A separate
[conditional head API](s3-checkpoint-heads.md) adds bounded GET/conditional PUT;
this byte adapter remains immutable-only. The JDK-only
[graph verifier](object-checkpoint-graphs.md) can consume this byte API for complete
manifest/segment and actual-journal comparisons; it performs no head mutation.

## Build and verify

Use the repository's Java 25 baseline. From the repository root, first verify and
install core and its small test-support JAR into the local Maven repository:

```bash
./mvnw -B install
```

Then verify the optional adapter:

```bash
./mvnw -B -f adapters/s3/pom.xml verify
```

On Windows, use `mvnw.cmd` for each command. The second build produces
`adapters/s3/target/meridiandb-s3-0.1.0-SNAPSHOT.jar`; it is not a shaded bundle.
An embedding application needs this artifact, core and the adapter's transitive
SDK dependencies. The SDK's default Apache 5/Netty HTTP clients and optional CRT
runtime are absent from this integration. The explicit URLConnection transport
uses the JDK HTTP implementation and JVM-managed connection pooling.

The immutable adapter's 46 tests comprise nine shared object-contract cases, 29 actual
SDK/HTTP protocol and integration cases, and eight focused native-stream/guard
fault checks. The optional build also runs 70 [conditional-head cases](s3-checkpoint-heads.md),
four [complete graph-consumer cases](object-checkpoint-graphs.md) and 65 pure
[publication-state cases](s3-publication-state.md), plus 86
[durable local state cases](local-s3-publication-state.md) and 53
[verified preparation cases](s3-publication-preparation.md) and 78
[verified publication cases](s3-checkpoint-publication.md), plus seven [pinned-history SDK/HTTP cases](object-checkpoint-graphs.md#load-pinned-history) and 63 [managed read recovery cases](s3-database.md), plus 74
[managed writable cases](s3-database-writes.md), plus 40 corpus and ten
[publication/recovery conformance cases](s3-recovery-conformance.md), plus 108 [coordination state/codec cases](../architecture/s3-coordination-state.md), plus 58 [native coordination guard cases](s3-coordination-guard.md), for 762 optional tests
in total. The 108 state/codec cases are pure checks; durable enrollment/ledger and
SDK participation remain unimplemented. Native local cases report explicit placement capability skips separately.
Core's separate 1,583 tests and fourteen runnable examples still
verify local behavior. Linux and Windows CI run both builds and those examples.
The fixture binds only `127.0.0.1`, uses fixed test credentials and needs no service
emulator, Docker daemon, cloud account or provisioned bucket.

## Configure an embedding application

Supply an existing general-purpose bucket, explicit HTTP(S) endpoint origin,
signing region and credential provider. The current adapter uses path-style
requests and accepts bucket names of 3–63 lowercase letters, digits and hyphens.
Names containing dots, directory buckets, access-point ARNs and endpoint path
prefixes are outside this adapter's supported configuration.

For example, application code with the adapter on its Maven classpath can use a
credential provider it owns and an endpoint it has configured:

```java
try (var store = new S3ObjectStore(endpoint, region, bucket, credentials)) {
    var key = new ObjectKey("history.segment");
    var result = store.createOrVerify(key, encodedSegment);
    try (var object = store.read(key, result.reference())) {
        byte[] completeImage = object.bytes();
    }
}
```

The imports are `org.meridiandb.storage.s3.S3ObjectStore` and
`org.meridiandb.storage.object.ObjectKey`. The application supplies the variables;
this fragment is not a cloud setup command. Use a service-appropriate HTTPS
endpoint and application-managed credentials for a real provider. The provider
object remains caller-owned and may be shared; store close does not close it.
Applications may provide a logging binding for the SDK; the adapter does not impose
one on embedding hosts.

The store owns and closes its SDK and transport clients. Calls serialize with
store close, including the compound `createOrVerify` operation. Each successful
reader contains only heap bytes and remains usable after store close. Different
store instances can race on the same key; the provider must implement the
conditional creation contract atomically. This design does not promise parallel
throughput within one store instance.

## Immutable writes and explicit reconciliation

Input validation and the 32 MiB limit precede a request. The adapter snapshots the
input and sends one binary PUT with an exact length, a precalculated SHA-256
transfer checksum and signed `If-None-Match: *`. Chunked AWS upload framing is
disabled. Creation requires a successful `200` response with no body. A transfer
checksum helps the provider validate received bytes; it does not make an ETag a
content ID or verify a subsequently stored image.

| Observed result | Adapter behavior |
| --- | --- |
| `200`, empty PUT response | Return expected whole-object reference as created |
| `412 PreconditionFailed` | Raise `ObjectAlreadyExistsException`; explicit `createOrVerify` performs a complete expected-length/hash readback |
| `409 ConditionalRequestConflict` | Raise I/O failure; it proves no existing object to reuse |
| Lost reply, timeout, other service/transport error or unexpected successful body | Raise I/O failure; creation may already have taken effect |
| Same key, different expected content on readback | Raise `ObjectIntegrityException`; preserve stored bytes |

The SDK is configured for **one attempt**. After an uncertain creation, retry
explicitly with `createOrVerify` using the same key and bytes. If the first request
took effect, the retry verifies the whole existing image and returns `created=false`.
If it did not, the retry can create it. Reuse proves bytes, not an earlier
acknowledgement, checkpoint publication or durability barrier.

Use a controlled namespace whose participants preserve immutable names: external
overwrite, deletion, lifecycle expiration and version/delete-marker manipulation
can invalidate that assumption. S3 conditional creation checks the current object;
it is not a permanent reservation after deletion. The adapter creates no bucket
policy, cross-node writer lease or distributed admission protocol.

## Complete reads, response limits and cleanup

GET sends no `Range`. Before allocating the expected object buffer, require status
`200`, no `Content-Range` and an exact `Content-Length` matching the independent
`ObjectReference`. Reject absent, changed, short, overlong, chunked-without-length
or partial responses. Read the complete bounded body, check EOF and explicitly
close before returning bytes from the SDK response transformer. Then verify the
full SHA-256 and return a `VerifiedObject`. An opaque provider ETag is never used
as that identity. `readRange` remains a copy from the verified whole heap image;
no physical HTTP range integrity is claimed.

Only `404 NoSuchKey` becomes `NoSuchFileException`. A `403` may hide object
existence, and `404 NoSuchBucket` identifies a different failure; neither is
translated into absence or safe reuse.

Before SDK XML parsing, an HTTP-client guard limits every error/non-GET body to
64 KiB and closes the actual native response. Oversized responses fail after at
most one extra probe byte. Any nonempty successful PUT body is rejected. On read
or guard failure, abort the stream and preserve primary/cleanup exceptions;
cleanup failure exposes no reader. Supplemental stream-fault tests exercise
close, abort, stalled reads and overlong bytes that a well-framed HTTP message
would not normally expose.

Default SDK operation and attempt timeouts are two minutes, connection timeout
five seconds and socket timeout fifteen seconds. `S3ObjectStore.Timeouts` accepts
explicit durations between one millisecond and ten minutes. Whole-body GET
consumption stays inside the SDK operation/attempt timer; the fixture verifies
that timer with a socket timeout longer than the operation timeout. These are SDK
and transport limits, not a hard realtime guarantee for JVM scheduling, DNS or an
arbitrary caller-supplied credential callback.

The body caps bound adapter-controlled transfer buffers. The underlying transport
parses headers before this guard, and HTTP framing defines EOF; this is not a raw
socket/header allocation bound or proof about bytes outside the response message.
Input/SDK/reader copies, concurrent clients, retained results and decoded history
add to aggregate heap use. URLConnection's global connection pool is JVM-managed;
store close releases its clients and response resources, not every JVM connection.

## Verified capabilities and fixture limits

The controlled HTTP fixture executes real SDK signing, serialization, transport
and unmarshalling. It checks conditional signed request shape, exact binary bodies
and complete GETs. It exercises:

- shared local/provider object expectations, independent opens and reader lifetimes;
- two-client conditional creation races and unchanged winning bytes;
- lost PUT replies before and after effects, verified same-content retry and
  rejection of conflicting content;
- missing keys versus access denied/missing buckets, and `409` versus `412`;
- unchanged opaque ETags with changed content, unexpected `206`/range headers,
  missing/wrong/oversized lengths, truncated bodies and transport failures;
- 32 MiB upload/read, preflight rejection above the limit, oversized error and
  unexpected successful PUT bodies, body-transfer timeouts and credential ownership;
- exact original segment transactions through `SegmentObjects`, without selecting
  a checkpoint or exposing partial history.

The fixture stores bytes in heap and checks SigV4-shaped authorization headers;
it does not authenticate signatures. It does not model TLS, credential refresh
services, provider authorization, versioning, regions/redirects, multipart upload,
service durability, failure-domain replication or throughput. No live service has
been qualified. A provider deployment needs its own capability/conformance and
operational evidence before relying on this experimental byte adapter.
