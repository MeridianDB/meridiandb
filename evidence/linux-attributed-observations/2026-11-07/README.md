# November 7, 2026 attributed Linux file-work observations

Immutable evidence from clean source
`b79a0366fca194dd56f326cd3f0952a31598a958` and
[Linux evaluation 42387103000](https://github.com/MeridianDB/meridiandb/actions/runs/42387103000).
The [public evaluation](../../../docs/development/linux-attributed-observations.md)
defines the protocol, returned-adapter-byte ratios and coverage limits. This is
hosted observational evidence, without controlled-device or production qualification.

`manifest.json` records ten ordered cases, exact requests/run IDs/fingerprints,
analysis SHA-256, original artifact identity and original/gzip lengths/hashes.
Fifty gzip files total 450,633 bytes, with zero gzip timestamps. They retain exact
request/report/worker bytes and both process logs beyond temporary CI retention.
Keep these bytes and manifest unchanged; later observations use a separate dataset.
The ordinary timing dataset under `evidence/linux-observations/` is separate.

Verify and decompress from the repository root into ignored local scratch:

```bash
python3 - <<'PY'
import gzip
import hashlib
import json
from pathlib import Path

source = Path('evidence/linux-attributed-observations/2026-11-07')
target = Path('.local-qa/linux-attributed-observations-2026-11-07')
manifest = json.loads((source / 'manifest.json').read_text())
for case in manifest['cases']:
    destination = target / case['name']
    destination.mkdir(parents=True, exist_ok=True)
    for name, expected in case['files'].items():
        packed = (source / case['name'] / (name + '.gz')).read_bytes()
        assert len(packed) == expected['gzipBytes']
        assert hashlib.sha256(packed).hexdigest() == expected['gzipSha256']
        data = gzip.decompress(packed)
        assert len(data) == expected['bytes']
        assert hashlib.sha256(data).hexdigest() == expected['sha256']
        (destination / name).write_bytes(data)
print('Verified and decompressed ten attributed observations')
PY
```

Recompute one report without its original Linux paths:

```bash
python3 scripts/storage/summarize_workload.py \
  --run-dir .local-qa/linux-attributed-observations-2026-11-07/corrections-base \
  --expect-source b79a0366fca194dd56f326cd3f0952a31598a958 \
  --format markdown
```

Select JSON output and compare its SHA-256 with the case's `analysisSha256` for
byte-for-byte analysis verification. All ten JSON analyses reproduce their CI
bytes; Markdown readback also passes. The recorded source contains the same saved
reader implementation. Repeating native execution requires Linux, a clean checkout
of that source and its `scripts/storage/attributed-evaluation.tsv`.

Readback validates recorded consistency; it does not authenticate the producer or
reobserve the original machine. Preserve recorded runtime, cache unknowns and
coverage gaps rather than replacing them with current-host metadata.
