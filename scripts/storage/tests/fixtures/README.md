# Attributed worker fixtures

These compressed schema-three worker fixtures preserve actual native serialization
and counter output for parser/report regression tests. Recorded paths, PID and Linux
host metadata are synthetic. They are not performance observations, producer
attestations or Linux qualification evidence. Fresh native execution is checked
separately by Java tests and the supervised Linux CI runs.

Both use APPEND, seed 42, E1/W1 and warmup 0/repetition 1. The tiny fixture has
C2/Q5, 52 scopes and 198 original-history bytes. The maximum fixture has C100/Q879,
2,016 successful scopes within the 2,048-scope reservation, and 8,026 history bytes.
Tests independently check source framing/checksums/hash, raw scope inventories,
mode/source bindings and ratio arithmetic, then exercise the full JSON/report caps.

| File | Uncompressed bytes / SHA-256 | Gzip bytes / SHA-256 |
| --- | --- | --- |
| `attributed-tiny-v3.json.gz` | 39,832 / `719973db48522ac82ed84327332b697cdfed690eb637d04e45a7440051acb89a` | 4,772 / `7977914d432d0fe1a85498566b04090a64802d3675adb5747c742cbd1613ee32` |
| `attributed-max-v3.json.gz` | 1,222,369 / `6ea2db907f0e385cef08c668115f07b4b6ed14e78771801a35cd2fff9a87ef25` | 26,768 / `4c79cbb403e2cb7708f02dc9cd0cbe9c817749122620157296341ef864a4a066` |

Gzip timestamps are fixed at zero. Preserve these bytes when changing validators;
make an explicit fixture/version decision if a producer contract changes. Readback
requires only Python's standard library; the tests never execute a recorded command
or open a path declared inside these fixtures.
