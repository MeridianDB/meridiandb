# November 7, 2026 Linux observations

Immutable technical evidence from source
`fa0a26df9455e1d30f4c5bdb873a22436d52514f` and
[Linux observation run 42377033037](https://github.com/MeridianDB/meridiandb/actions/runs/42377033037).
The [public evaluation](../../../docs/development/linux-observations.md) describes
the protocol, results and limits. This is hosted observational evidence, not
controlled-device performance or production qualification.

`manifest.json` records all ten ordered cases, exact run/request/fingerprints,
analysis SHA-256, original and gzip lengths/hashes, and original artifact identity.
Each case retains exact request/report/worker bytes and both process logs as gzip
with zero timestamps. Fifty gzip files total 311,945 bytes. These copies outlive
the temporary CI artifact. Preserve existing bytes and manifest; future observations
belong in a separate dated dataset.

From the repository root, verify and decompress into ignored local scratch:

```bash
python3 - <<'PY'
import gzip
import hashlib
import json
from pathlib import Path

source = Path('evidence/linux-observations/2026-11-07')
target = Path('.local-qa/linux-observations-2026-11-07')
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
print('Verified and decompressed ten saved observations')
PY
```

Recompute one report without its original Linux paths:

```bash
python3 scripts/storage/summarize_workload.py \
  --run-dir .local-qa/linux-observations-2026-11-07/corrections-base \
  --expect-source fa0a26df9455e1d30f4c5bdb873a22436d52514f \
  --format markdown
```

For byte-for-byte analysis verification, select JSON output and compare its SHA-256
with that case's `analysisSha256`. The observation source contains the same report
implementation. Repeating execution requires Linux and a clean checkout of that
source, using its `scripts/storage/evaluation.tsv` and the documented supervisor.
Current-host metadata must not replace the original recorded context when reading
saved observations. Hash consistency is not producer authentication.
