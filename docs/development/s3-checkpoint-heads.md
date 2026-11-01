---
title: Conditional S3 head access
description: Read bounded checkpoint-head bytes with their opaque ETag and issue exact conditional S3 writes without implicit retry, rebasing or checkpoint publication.
---

# Conditional S3 head access

`S3CheckpointHeads` supplies conditional head-byte operations in the optional
`meridiandb-s3` artifact. It is separate from `S3ObjectStore`'s
[immutable-object API](s3-objects.md). Both use the maintained AWS SDK, explicit
URLConnection transport, body guards, one SDK attempt and bounded timeouts.
Core and ordinary local embedding still require no external runtime dependency.

The [head format](../architecture/object-checkpoint-protocol.md) is implemented
in core. This adapter adds GET and conditional PUT; a complete checkpoint
publisher with graph/actual-journal verification, durable pending intent and
uncertain-result reconciliation is implemented separately by
[S3CheckpointPublisher](s3-checkpoint-publication.md). Transaction
acknowledgement, engine recovery, reader protection and reclamation are unchanged.
Separate JDK-only [graph verification](object-checkpoint-graphs.md) now checks
complete referenced history and actual journal prefixes; it does not compose
those checks with conditional selection or durable intent. The separate
[publication-state codec](s3-publication-state.md) represents exact expected/pending
metadata and pure transitions. [Durable local installation](local-s3-publication-state.md)
is now available separately. [Verified preparation](s3-publication-preparation.md)
composes actual scope/graph/owner checks and durable pending state without sending
a selection PUT. The [verified publisher](s3-checkpoint-publication.md) now adds
conditional submission and durable exact-candidate reconciliation.
[ADR 0024](../decisions/0024-conditional-s3-head-access.md) records this boundary.

## Build and configuration

Run the same two builds from the repository root:

```bash
./mvnw -B install
```

```bash
./mvnw -B -f adapters/s3/pom.xml verify
```

Use `mvnw.cmd` on Windows. Core verification installs the core JAR and its small
shared test classifier locally; the optional build verifies both immutable and
conditional-head APIs. The optional build runs **472 tests**: 46 immutable-object
cases, 43 conditional-head SDK/API cases, 27 token/observation/native-stream cases,
four [graph-consumer SDK/HTTP cases](object-checkpoint-graphs.md) and 65 pure
[publication-state codec/transition cases](s3-publication-state.md), plus 86
[local state API/fault/process cases](local-s3-publication-state.md) and 53
[verified preparation cases](s3-publication-preparation.md) and 78
[verified publication cases](s3-checkpoint-publication.md), plus seven
[pinned-history SDK/HTTP cases](object-checkpoint-graphs.md#load-pinned-history) and
63 [managed recovery cases](s3-database.md).
Native local cases explicitly skip unsupported placement; pure and injected checks
remain independent of those capabilities.
Core runs its separate 1,272 tests. No cloud service or account is used by these tests.

Construct `S3CheckpointHeads` with an explicit endpoint origin, signing region,
existing bucket, caller-owned credentials and a `Binding` containing the expected
namespace UUID, publisher UUID and canonical journal-header content ID. These
expectations must come from independent managed state; repeating values read from
an untrusted head establishes no independent binding. The UUIDs identify placement
but provide no distributed writer admission.

Endpoint/bucket restrictions and timeout defaults are the same as for the
immutable S3 adapter. `S3ObjectStore.Timeouts` can override operation/attempt,
connection and socket limits. Each head adapter owns its separate SDK/HTTP client
and serializes calls with close. Credentials remain caller-owned, even when shared
with other adapters. Observations contain immutable heap metadata and survive
adapter close; their `image()` accessor returns an independent copy.

The only managed key is `S3CheckpointHeads.headKey(namespaceId)`, encoded as
`ns-<32-lowercase-hex-UUID>-current.head`. The adapter has no arbitrary-key mutable
write, listing, deletion, bucket setup or unconditional replacement API.

## Operations and saved observations

| Operation | Behavior |
| --- | --- |
| `read()` | One complete GET; return `Optional<S3HeadObservation>` after body/response cleanup, token checks, full head decoding and configured binding checks |
| `createIfAbsent(intentImage)` | Validate an exact revision-1 image, snapshot it and issue one signed `If-None-Match: *` PUT |
| `replace(previous, intentImage)` | Validate target scope, both bindings and the exact adjacent transition, then issue one signed PUT using `previous.token()` as `If-Match` |
| `S3HeadObservation.restore(endpoint, bucket, image, token)` | Reconstruct a bounded immutable saved precondition; it performs no request and proves no prior observation or current selection |

An observation returned by `read()` pairs the **complete bytes and opaque token
from the same GET response**. It includes the original endpoint URI, bucket,
derived key, decoded head and complete SHA-256 reference. No separate HEAD request or token inference
from the content hash occurs. Replacement rejects a different endpoint/bucket/key
or binding before any request. Scope equality uses the configured URI; callers
must retain that scope when saving/restoring observations rather than silently
substituting another endpoint alias.

For example, with independently configured application variables:

```java
try (var heads = new S3CheckpointHeads(endpoint, region, bucket, credentials, binding)) {
    var observed = heads.read().orElseThrow();
    // candidateImage must already belong to a verified, durably recorded publication intent.
    var writtenReference = heads.replace(observed, candidateImage);
}
```

The import is `org.meridiandb.storage.s3.S3CheckpointHeads`. This fragment shows
the primitive's call shape, not a complete publisher. Its successful return is a
conditional byte-write receipt; it validates no referenced segment or actual local
journal prefix. A missing head observation alone does not permit genesis after a
namespace was initialized. The complete publisher must enforce its own durable
expected/pending state and graph/owner-journal protocol before calling either PUT.

## Token policy

`S3HeadToken` preserves one strong opaque **ASCII** ETag, including quotes, with a
maximum of **1,024 bytes**. ASCII makes its byte and Java-character counts equal.
This deliberately supports a narrower domain than RFC 9110's allowed `obs-text`.

The empty quoted tag `""`, quoted comma `"a,b"`, quoted asterisk `"*"` and a literal
backslash inside the quoted token are valid. A backslash is opaque data, not an
escape for an interior quote. Weak tags, unquoted wildcard/list syntax, interior
quotes, leading/trailing whitespace, controls, DEL, non-ASCII and oversized tokens
are rejected. Preserve the original quoted value for retries; never replace it
with SHA-256, a version ID or a newly observed token for an old intent.

GET requires exactly one SDK-visible ETag value and one canonical visible
`Content-Length: 256`, with status 200 and no range response. Both values are
checked before an observation escapes. The body is consumed and explicitly closed
inside the SDK operation/attempt timer; only then is the complete head decoded
and its configured binding checked. No partial body or native stream is returned.

The URLConnection/SDK stack normalizes response headers before the adapter sees
them. Repeated values in its exposed header list are rejected, but differently
cased raw duplicate field names can collapse into one value during SDK copying.
These checks therefore establish **SDK-visible header validation**, not universal
raw-wire duplicate detection. A supported provider must emit unambiguous framing
and a single strong validator. The underlying transport parses headers and applies
HTTP message framing first; the adapter cannot bound raw header allocation or
prove facts about bytes outside that framed response.

## Response and failure classes

Only a decoded `404 NoSuchKey` becomes an empty GET result. Access denied, a missing
bucket, malformed headers/body/head and configured binding disagreement remain
failures. An initialized publisher must retain its expected-state evidence even
when the primitive observes a missing key.

Writes require status 200 with an empty response body. Signed binary requests
carry exact 256-byte length and a precalculated SHA-256 transfer checksum; AWS
chunked upload framing is disabled. The existing transport guard bounds
error/non-GET bodies to 64 KiB before SDK XML parsing and observes native cleanup.
It rejects nonempty successful PUT bodies and preserves primary/cleanup failures.

`S3HeadWriteException` describes the response observed by this request:

| `code()` | Observation |
| --- | --- |
| `PRECONDITION_FAILED` | Decoded 412 `PreconditionFailed` |
| `CONCURRENT_OPERATION` | Decoded 409 `ConditionalRequestConflict` |
| `MISSING_KEY` | Replacement receives decoded 404 `NoSuchKey` |
| `SERVICE_FAILURE` | Other decoded service error, including access denied or missing bucket |
| `UNCERTAIN` | Transport/timeout/guard failure or unexpected successful status/body |

`statusCode()` records the response status when available, or zero otherwise. A body
guard can fail before SDK error unmarshalling, leaving no trusted decoded status.
Error categories do not prove the stored outcome of an earlier in-flight request;
a service failure can itself follow a storage effect. Invalid local arguments,
tokens, head images or transitions fail before HTTP. Untrusted read failures
expose no observation.

There is one SDK attempt. Neither write performs a refresh GET, automatic retry,
fallback, rebasing, intent regeneration or reconciliation. The caller must retain
the exact original bytes, publication identity, target scope and precondition.
The [publication protocol](../architecture/object-checkpoint-protocol.md#failure-and-retry-outcomes)
defines the later publisher's duties. In particular, rereading the predecessor
after a timeout cannot prove that the original request has finished without effect.

## Verified behavior and limits

Controlled loopback HTTP tests execute the actual SDK's serialization, signing,
transport and unmarshalling. A separate head fixture maintains atomic conditional
updates and changing opaque tokens. It preserves the existing immutable fixture's
constant-ETag cases, which test content-hash independence.

Coverage includes conditional genesis/replacement races, stale tokens, bytes/token
pairing while another writer changes the head, captured absence, lost replies
before/after effects, exact saved-precondition retries, status distinctions,
malformed framing/tokens/head bytes, guard/body limits, operation timeouts, target/
binding/transition preflight, snapshot and credential lifetime. Focused native
stream checks supplement protocol tests with close, abort, stalled/extra-byte and
suppression faults. A deterministic request gate also proves the late-effect
case: a PUT times out, a GET still sees the predecessor, then the original held
request takes effect after release, with no retry.

A deliberate `A → B → A` rollback restores the original bytes and token. A stale
conditional replacement can then succeed. This is a tested limitation of the
primitive: a valid quoted ETag is not a fencing token or permanent version identity.
The future publisher must compare durable expected/pending state and retain the
exclusive-placement assumptions; even that cannot detect every unseen copied
writer without distributed admission.

The fixture checks SigV4-shaped headers, not signature authenticity. It stores
bytes in heap and models no TLS, service authorization, durable cloud storage,
versioning, cross-region behavior, replication or throughput. No live provider has
been qualified. Body caps limit adapter-controlled buffers; caller-retained
observations and SDK/JVM-managed resources remain additional costs.
