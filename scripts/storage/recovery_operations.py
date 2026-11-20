"""Bounded operation/sample/native-counter checks for the recovery worker.

The caller supplies independently admitted image facts and their selected fixture.
These checks cover this worker's fresh, successful caller-thread operation scopes;
they do not authenticate execution, infer device traffic or compare timing gains.
Native transfer counts may vary with short and zero returns. Returned bytes, EOF
probes and force outcomes follow the fixed adapter paths independently of those
transfer counts. No legacy workload-admission contract is changed here.
"""


LONG_MAX = (1 << 63) - 1
MAX_RAW_BYTES = 2 * 1024 * 1024
CAPTURED = "CALLER_THREAD_RECOVERY_IO_INSTRUMENTED"
CONTROL = "RECOVERY_UNINSTRUMENTED"
SHAPES = ("JOURNAL_ONLY", "GENESIS", "CHECKPOINT_TAIL", "COMPACTED_CHECKPOINT_TAIL")
ROLES = ("JOURNAL", "SEGMENT", "MANIFEST", "CONTROL", "DIRECTORY", "TAIL_EVIDENCE", "OBJECT", "UNKNOWN")
METRICS = ("READ_ATTEMPTS", "READ_RETURNS", "READ_BYTES", "READ_ZERO", "READ_EOF", "READ_FAILURES",
           "WRITE_ATTEMPTS", "WRITE_RETURNS", "WRITE_BYTES", "WRITE_ZERO", "WRITE_FAILURES",
           "FORCE_ATTEMPTS", "FORCE_SUCCESSES", "FORCE_FAILURES")
OPERATION_FIELDS = {"operation", "measurement", "coverage", "counters"}
SAMPLE_FIELDS = ("phase", "kind", "elapsedNanos", "threadCpuNanos", "threadAllocatedBytes",
                 "heapUsedBeforeBytes", "heapUsedAfterBytes")
FIXTURE_FIELDS = {"shape", "checkpointSequence", "prefixBytes", "prefixSha256", "selectedManifestBytes",
                  "selectedSegmentBytes", "selectedSegments", "initialFileSha256"}


class OperationError(ValueError):
    """Incomplete, contradictory or unsupported recovery operation evidence."""


def _require(condition, message):
    if not condition:
        raise OperationError(message)


def _integer(value, name, low=0, high=LONG_MAX):
    _require(type(value) is int and low <= value <= high, f"Invalid integer: {name}")
    return value


def _fields(value, expected, name):
    _require(type(value) is dict and len(value) == len(expected)
             and all(type(key) is str for key in value) and value.keys() == set(expected),
             f"Wrong fields: {name}")


def _sum(values):
    return _integer(sum(values), "counter aggregate")


def _sample(value, kind, max_heap):
    _fields(value, SAMPLE_FIELDS, "measurement sample")
    _require(type(value["phase"]) is str and value["phase"] == "MAINTENANCE"
             and type(value["kind"]) is str and value["kind"] == kind,
             "Operation phase/kind differs from the recovery scope")
    _integer(value["elapsedNanos"], "elapsedNanos")
    for name in ("threadCpuNanos", "threadAllocatedBytes"):
        if value[name] is not None:
            _integer(value[name], name)
    for name in ("heapUsedBeforeBytes", "heapUsedAfterBytes"):
        _integer(value[name], name, 0, max_heap)
    # Resource probes bracket a wider interval than the call clock. Allocation is
    # cumulative work, not retained heap. Neither admits a tighter inequality here.
    return {name: value[name] for name in SAMPLE_FIELDS}


def _matrix(value):
    _fields(value, {"schemaVersion", "overflow", "invalidResult", "counts"}, "native counter snapshot")
    _integer(value["schemaVersion"], "native counter schema", 1, 1)
    _require(value["overflow"] is False and value["invalidResult"] is False,
             "Incomplete native counter snapshot")
    rows = value["counts"]
    _require(type(rows) is list and len(rows) == len(ROLES), "Wrong native role inventory")
    checked = []
    for row in rows:
        _require(type(row) is list and len(row) == len(METRICS), "Wrong native metric inventory")
        for count in row:
            _integer(count, "native counter")
        counter = dict(zip(METRICS, row))
        for operation in ("READ", "WRITE"):
            _require(counter[f"{operation}_ATTEMPTS"] == _sum((counter[f"{operation}_RETURNS"], counter[f"{operation}_FAILURES"])),
                     "Native attempts differ from returned/throwing calls")
            _require(counter[f"{operation}_FAILURES"] == 0,
                     "Throwing native calls have unknown additional effects")
        _require(counter["FORCE_ATTEMPTS"] == _sum((counter["FORCE_SUCCESSES"], counter["FORCE_FAILURES"]))
                 and counter["FORCE_FAILURES"] == 0, "Incomplete native force outcomes")
        empty_reads = _sum((counter["READ_ZERO"], counter["READ_EOF"]))
        _require(empty_reads <= counter["READ_RETURNS"] and counter["WRITE_ZERO"] <= counter["WRITE_RETURNS"],
                 "Native empty returns exceed completed calls")
        for operation, positive in (("READ", counter["READ_RETURNS"] - empty_reads),
                                    ("WRITE", counter["WRITE_RETURNS"] - counter["WRITE_ZERO"])):
            amount = counter[f"{operation}_BYTES"]
            _require(amount == 0 if positive == 0 else amount >= positive,
                     "Native bytes differ from positive returned calls")
        checked.append(list(row))
    _require(not any(checked[-1]), "Unknown native role invalidates coverage")
    for column in range(len(METRICS)):
        _sum(row[column] for row in checked)
    return dict(schemaVersion=1, overflow=False, invalidResult=False, counts=checked)


def _context(mode, shape, access, strategy, max_heap, images, fixture):
    _require(type(mode) is str and mode in (CAPTURED, CONTROL), "Unsupported recovery measurement mode")
    _require(type(shape) is str and shape in SHAPES, "Unsupported recovery storage shape")
    _require(type(access) is str and access in ("READ_ONLY", "WRITABLE"), "Unsupported recovery access")
    _require(type(strategy) is str and strategy in ("ORDINARY", "HISTORY_REUSE"), "Unsupported recovery strategy")
    _integer(max_heap, "maximum heap", 1)
    _require(type(images) is dict, "Require derived recovery image facts")
    commits = _integer(images.get("commits"), "derived original commits", 2, 4096)
    history_bytes = _integer(images.get("encodedHistoryBytes"), "derived history bytes", 42, MAX_RAW_BYTES)
    header_bytes = _integer(images.get("headerBytes"), "derived header bytes", 22, 65556)
    continuation_bytes = _integer(images.get("continuationFrameBytes"), "derived continuation bytes", 1, 4096)
    _require(header_bytes <= history_bytes, "Derived header exceeds original history")
    _fields(fixture, FIXTURE_FIELDS, "derived fixture")
    _require(type(fixture["shape"]) is str and fixture["shape"] == shape, "Derived fixture shape differs")
    fixtures = images.get("fixtures")
    _require(type(fixtures) is list and len(fixtures) == len(SHAPES)
             and type(fixtures[SHAPES.index(shape)]) is dict and fixtures[SHAPES.index(shape)] == fixture,
             "Fixture differs from independently derived image facts")
    prefix = _integer(fixture["prefixBytes"], "derived prefix bytes", 0, history_bytes)
    manifest = _integer(fixture["selectedManifestBytes"], "derived manifest bytes", 0, 1024 * 1024)
    segments = _integer(fixture["selectedSegmentBytes"], "derived segment bytes", 0, MAX_RAW_BYTES)
    segment_count = _integer(fixture["selectedSegments"], "derived selected segments", 0, 16)
    cutoff = fixture["checkpointSequence"]
    if shape == "JOURNAL_ONLY":
        _require(cutoff is None and prefix == manifest == segments == segment_count == 0,
                 "Journal-only fixture declares selected checkpoint work")
    else:
        _integer(cutoff, "derived checkpoint cutoff", 0, commits)
        _require(manifest > 0, "Selected checkpoint has no manifest bytes")
        if shape == "GENESIS":
            _require(cutoff == 0 and prefix == header_bytes and segments == segment_count == 0,
                     "Genesis fixture declares non-genesis work")
        else:
            _require(cutoff == commits // 2 and prefix > header_bytes and segments > 0 and segment_count > 0
                     and (shape != "COMPACTED_CHECKPOINT_TAIL" or segment_count == 1),
                     "Selected checkpoint work differs from the comparison layout")
    return commits, history_bytes, continuation_bytes


def _native_scope(snapshot, operation, shape, access, strategy, commits, history_bytes, continuation_bytes, fixture):
    read_bytes, write_bytes, eof, minimum_positive_reads, forces = {}, {}, {}, {}, {}
    if operation == "OPEN":
        selected = shape != "JOURNAL_ONLY"
        reader_admission = selected and strategy == "ORDINARY"
        segment_passes = 2 if strategy == "ORDINARY" else 1
        read_bytes = dict(JOURNAL=2 * history_bytes + fixture["prefixBytes"],
                          SEGMENT=segment_passes * fixture["selectedSegmentBytes"],
                          MANIFEST=fixture["selectedManifestBytes"])
        eof = dict(SEGMENT=segment_passes * fixture["selectedSegments"], MANIFEST=int(selected))
        # Each full journal scan reads its header in two pieces and each frame
        # prefix/body separately. Selected-prefix verification repeats that work.
        prefix_reads = 2 * (fixture["checkpointSequence"] + 1) if selected else 0
        minimum_positive_reads = dict(eof, JOURNAL=4 * (commits + 1) + prefix_reads)
        writable = access == "WRITABLE"
        forces = dict(JOURNAL=int(writable), CONTROL=int(reader_admission),
                      DIRECTORY=1 + int(writable) + int(reader_admission))
    elif operation == "CONTINUATION":
        write_bytes = dict(JOURNAL=continuation_bytes)
        forces = dict(JOURNAL=1)
    # Both exact retry operations return the already acknowledged result without
    # reaching any adapter transfer/force boundary. Their input-frame size is not
    # counted as native file work, and JVM allocation is validated separately.
    for role, row in zip(ROLES, snapshot["counts"]):
        counter = dict(zip(METRICS, row))
        _require(counter["READ_BYTES"] == read_bytes.get(role, 0) and counter["WRITE_BYTES"] == write_bytes.get(role, 0),
                 f"Native returned bytes differ for {operation}/{role}")
        _require(counter["READ_EOF"] == eof.get(role, 0), f"Native EOF probes differ for {operation}/{role}")
        _require(counter["FORCE_SUCCESSES"] == forces.get(role, 0), f"Native force outcomes differ for {operation}/{role}")
        positive_reads = counter["READ_RETURNS"] - counter["READ_ZERO"] - counter["READ_EOF"]
        positive_writes = counter["WRITE_RETURNS"] - counter["WRITE_ZERO"]
        _require(positive_reads >= minimum_positive_reads.get(role, 0), "Native positive reads omit required journal/object work")
        # The actual adapters reject the sixteenth consecutive zero return. A
        # successful loop can therefore have at most fifteen before each positive
        # completion or object EOF. Short completions remain otherwise unpinned.
        _require(counter["READ_ZERO"] <= 15 * (positive_reads + counter["READ_EOF"])
                 and counter["WRITE_ZERO"] <= 15 * positive_writes,
                 "Native zero returns exceed the successful adapter progress bound")


def validate_operations(operations, *, mode, shape, access, strategy, max_heap, images, fixture):
    """Return fresh validated raw operations for one already bound observation.

    The caller owns request, image, fixture and runtime admission. Samples retain
    unavailable optional counters and the exact supplied native matrix. No native
    bytes are inferred for controls, and no timing/allocation/device claim is made.
    """
    commits, history_bytes, continuation_bytes = _context(mode, shape, access, strategy, max_heap, images, fixture)
    expected = ("OPEN",) if access == "READ_ONLY" else ("OPEN", "ORIGINAL_RETRY", "CONTINUATION", "CONTINUATION_RETRY")
    _require(type(operations) is list and len(operations) == len(expected), "Wrong recovery operation inventory")
    checked = []
    for value, operation in zip(operations, expected):
        _fields(value, OPERATION_FIELDS, "operation sample")
        _require(type(value["operation"]) is str and value["operation"] == operation, "Recovery operation order differs")
        if operation == "OPEN":
            kind = "JOURNAL_REOPEN" if shape == "JOURNAL_ONLY" else "CHECKPOINT_REOPEN"
        else:
            kind = "COMMIT" if operation == "CONTINUATION" else "RETRY"
        sample = _sample(value["measurement"], kind, max_heap)
        coverage = "NOT_CAPTURED" if mode == CONTROL else "ADAPTERS_ONLY"
        _require(type(value["coverage"]) is str and value["coverage"] == coverage, "Native coverage differs from recovery mode")
        if mode == CONTROL:
            _require(value["counters"] is None, "Uncaptured operations require absent native counters")
            counters = None
        else:
            counters = _matrix(value["counters"])
            _native_scope(counters, operation, shape, access, strategy, commits, history_bytes, continuation_bytes, fixture)
        checked.append(dict(operation=operation, measurement=sample, coverage=coverage, counters=counters))
    return checked
