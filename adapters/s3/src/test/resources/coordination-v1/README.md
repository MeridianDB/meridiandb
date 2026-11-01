# Coordination v1 literal vectors

These frozen hexadecimal images were assembled independently with Python
`struct.pack` big-endian fields and `hashlib.sha256`, without invoking
`S3CoordinationStateCodec`. The head and manifest inputs reuse the pre-existing
literal vectors in `S3PublicationStateCodecTest` and `ManifestCodecTest`.
`S3CoordinationStateCodecTest` owns the expected full-image lengths and hashes;
changing the production encoder alone must not rewrite these expectations.

The target is `http://localhost:9000`, `us-east-1`, `bucket`. Namespace, publisher
and placement UUIDs are respectively `(0, 1)`, `(0, 2)` and `(0, 3)`; the first
attempt nonce is `(0, 4)`. Lineage is the full hash of the journal header for
database `db`. `head-one` selects `manifest-one`; `head-two` is its exact adjacent
successor selecting `manifest-two`. Full head IDs determine catalog order.

| Image | Construction |
| --- | --- |
| `fresh` | Generation 1, no roots or attempts |
| `registered` | Generation 2, root one registered, no attempts |
| `pending` | Generation 3, root one and ordinal 1 create of its immutable head-intent key |
| `completed` | Generation 4, the same ordinal 1 attempt completed with status 200 |
| `replacement` | Generation 6, roots one/two, prior completion, ordinal 2 pending head replacement with nonce `(0, 5)` |

Replacement preserves the exact original head reference and the opaque tag
`"orig,opaque\tag"`, including quotes. Every image follows the documented v1
field order and appends SHA-256 of its preceding bytes. The independently expected
object reference then hashes the whole image, including that footer. Hex text
line endings are not part of the encoded image; fixtures accept LF and CRLF.

These vectors contain declarations, not durable receipts or remote evidence.
