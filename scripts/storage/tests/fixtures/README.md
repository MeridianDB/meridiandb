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

## Recovery worker fixtures

These gzip fixtures preserve complete Java worker output for recorded-evidence
tests. Run ID, source, PID and paths have synthetic bindings. Recorded runtime,
clocks, original images and adapter counts come from macOS/APFS worker runs.
The captures exercise parser compatibility. Fresh CI separately checks worker
output on Linux.

| Fixture | Mode and scope | Uncompressed size | Gzip SHA-256 |
| --- | --- | ---: | --- |
| `recovery-worker-balanced-v1.json.gz` | Captured, one warmup and two measured iterations | 139,797 | `10cc3d282e9b9a10bfe724994bf11d9f05eaedc6b547bc6df9a4e17a018a92ea` |
| `recovery-worker-captured-v1.json.gz` | Captured, one measured iteration | 54,377 | `281f6cc20f848ad03a3cd8639fafb373af73f122e8b98823b32718b77fe27b5a` |
| `recovery-worker-control-v1.json.gz` | Control, one measured iteration | 41,930 | `336694f33e62ba467ae18411cc0c966481e7f2552147a8bef4fcfdb2f70b577a` |
| `recovery-worker-maximum-v1.json.gz` | Captured, 496 original commits | 281,287 | `116f479d9f94ed66286b0db4fdf161826064455eefe8dd031d7cdaae64d6774f` |
