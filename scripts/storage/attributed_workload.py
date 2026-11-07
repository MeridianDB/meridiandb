"""Pure admission and named ratios for recorded caller-thread adapter work.

The base workload validator owns the legacy timing/runtime/file declarations.
This module checks the additional attributed contract, original history bytes and
scope/counter arithmetic. It neither observes files nor authenticates execution.
"""

from collections import Counter
import hashlib
import math
import re


MODE = "CALLER_THREAD_LOCAL_IO_INSTRUMENTED"
MAX_SCOPES = 2048
MAX_HISTORY_BYTES = 16 * 1024 * 1024
MAX_HEX_UNITS = 4096
MAX_NODES = 500_000
LONG_MAX = (1 << 63) - 1
LONG_MIN = -(1 << 63)
RETRY_SALT = 0x691FE5D7320CAB84
ROLES = ("JOURNAL", "SEGMENT", "MANIFEST", "CONTROL", "DIRECTORY", "TAIL_EVIDENCE", "OBJECT", "UNKNOWN")
METRICS = ("READ_ATTEMPTS", "READ_RETURNS", "READ_BYTES", "READ_ZERO", "READ_EOF", "READ_FAILURES",
           "WRITE_ATTEMPTS", "WRITE_RETURNS", "WRITE_BYTES", "WRITE_ZERO", "WRITE_FAILURES",
           "FORCE_ATTEMPTS", "FORCE_SUCCESSES", "FORCE_FAILURES")
CATEGORIES = ("PREPARATION", "NAMESPACE", "OPERATION", "OWNER_ACQUISITION", "PREFIX_VERIFICATION",
              "READER_ACQUISITION", "VALIDATION", "EXTERNAL_HASH", "FILE_INVENTORY", "TEARDOWN")
EXCLUDED = {"EXTERNAL_HASH", "FILE_INVENTORY"}
PHASES = ("LOAD", "MIXED", "MAINTENANCE")
KINDS = ("CREATE", "COMMIT", "RETRY", "GET", "SCAN", "SELECT", "FILTER_SCAN", "SUM",
         "JOURNAL_REOPEN", "SEGMENT_WRITE", "CHECKPOINT_PUBLISH", "COMPACTION_PLAN",
         "COMPACTION_PUBLISH", "CHECKPOINT_REOPEN")
QUERIES = ("GET", "SCAN", "SELECT", "FILTER_SCAN", "SUM")
RETRY_PERCENT = {"APPEND": 5, "CORRECTIONS": 10, "RETRACTIONS": 10, "HOT_ENTITY": 20}
DIMENSIONS = ("entities", "commits", "queries", "intervalWidth")
PERCENTAGES = ("correctionPercent", "retractionPercent", "repeatPercent", "retryPercent",
               "hotEntityPercent", "historicalReadPercent")
SPEC_FIELDS = {"seed", *DIMENSIONS, *PERCENTAGES}
ENTRY_FIELDS = {"ordinal", "iteration", "category", "phase", "kind", "callReturned", "counters",
                "operationFailureType", "observationFailureType", "cleanupOutsideCapture"}
DENOMINATORS = {"headerBytes", "seedFrameBytes", "mixedFrameBytes", "encodedHistoryBytes",
                "newCommits", "exactRetries", "queries"}


class AttributionError(ValueError):
    """Incomplete, inconsistent, excessive or unsupported attributed evidence."""


def _require(condition, message):
    if not condition:
        raise AttributionError(message)


def _integer(value, name, low=0, high=LONG_MAX):
    _require(type(value) is int and low <= value <= high, f"Invalid integer: {name}")
    return value


def _fields(value, expected, name):
    _require(type(value) is dict and set(value) == set(expected), f"Wrong fields: {name}")
    return value


def _text(value, name, maximum=4096):
    _require(type(value) is str and bool(value.strip()) and len(value) <= maximum
             and len(value.encode("utf-16-be", "surrogatepass")) // 2 <= maximum, f"Invalid text: {name}")
    return value


def _bounded_tree(value):
    pending = [(value, 0)]
    count = 0
    while pending:
        item, depth = pending.pop()
        count += 1
        _require(depth <= 32 and count <= MAX_NODES, "Attribution structure admission exceeded")
        if type(item) is dict:
            for key, child in item.items():
                _require(type(key) is str, "Attribution keys must be strings")
                pending.append((key, depth + 1))
                pending.append((child, depth + 1))
        elif type(item) is list:
            pending.extend((child, depth + 1) for child in item)
        elif type(item) is str:
            _require(len(item) <= 4096 and len(item.encode("utf-16-be", "surrogatepass")) // 2 <= 4096,
                     "Attribution string admission exceeded")
        elif type(item) is int:
            _integer(item, "attribution scalar", LONG_MIN)
        elif type(item) is float:
            _require(math.isfinite(item), "Nonfinite attribution scalar")
        else:
            _require(item is None or type(item) is bool, "Unsupported attribution value")


def _checked_dimensions(dimensions, warmups, repetitions):
    _fields(dimensions, DIMENSIONS, "dimensions")
    e = _integer(dimensions["entities"], "entities", 1, 1024)
    c = _integer(dimensions["commits"], "commits", e + 1, 4096)
    q = _integer(dimensions["queries"], "queries", 0, 1024)
    w = _integer(dimensions["intervalWidth"], "intervalWidth", 1, 16)
    _integer(warmups, "warmups", 0, 2)
    _integer(repetitions, "repetitions", 1, 5)
    _require(c * c * (w + 1) + c * q * (w + min(e, 16) + 2) <= 32_000_000,
             "Reference-work admission exceeded")
    _require((2 * c + q) * (warmups + repetitions) <= 20_000, "Conservative action admission exceeded")
    return e, c, q, w


class _JavaRandom:
    def __init__(self, seed):
        self.state = (seed ^ 0x5DEECE66D) & ((1 << 48) - 1)

    def _next(self, bits):
        self.state = (self.state * 0x5DEECE66D + 0xB) & ((1 << 48) - 1)
        return self.state >> (48 - bits)

    def next_int(self, bound):
        _integer(bound, "Random bound", 1, (1 << 31) - 1)
        if bound & (bound - 1) == 0:
            return (bound * self._next(31)) >> 31
        while True:
            bits = self._next(31)
            value = bits % bound
            # Java evaluates this addition as signed int, rejecting its negative wrap.
            if bits - value + bound - 1 < (1 << 31):
                return value


def _source_segments(commits):
    chunk = min(16, (commits + 1) // 2)
    return (commits + chunk - 1) // chunk


def _actions(seed, entities, commits, queries, retry_percent):
    random = _JavaRandom(seed ^ RETRY_SALT)
    actions = []
    emitted = retries = 0
    for sequence in range(1, commits + 1):
        phase = "LOAD" if sequence <= entities else "MIXED"
        actions.append((phase, "COMMIT"))
        if sequence > entities:
            if random.next_int(100) < retry_percent:
                random.next_int(sequence)  # The prior-request draw changes every later retry decision.
                retries += 1
                actions.append(("MIXED", "RETRY"))
            due = (sequence - entities) * queries // (commits - entities)
            while emitted < due:
                actions.append(("MIXED", QUERIES[emitted % 5]))
                emitted += 1
    return actions, retries


def _budget(actions, segments, iterations):
    capacity = 2 + (2 * actions + 2 * segments + 64) * iterations
    _require(capacity <= MAX_SCOPES, "Attributed scope admission exceeded")
    return capacity


def request_budget(profile, seed, dimensions, warmups, repetitions):
    """Reproduce the retry stream and admit the exact conservative trace reservation."""
    _require(type(profile) is str and profile in RETRY_PERCENT, "Unknown attributed profile")
    _integer(seed, "seed", LONG_MIN)
    entities, commits, queries, _ = _checked_dimensions(dimensions, warmups, repetitions)
    actions, _ = _actions(seed, entities, commits, queries, RETRY_PERCENT[profile])
    return _budget(len(actions), _source_segments(commits), warmups + repetitions)


def _crc32c(data):
    crc = 0xFFFFFFFF
    for byte in data:
        crc ^= byte
        for _ in range(8):
            crc = (crc >> 1) ^ (0x82F63B78 if crc & 1 else 0)
    return crc ^ 0xFFFFFFFF


class _Reader:
    def __init__(self, data):
        self.data = data
        self.offset = 0

    def take(self, count):
        _require(count >= 0 and count <= len(self.data) - self.offset, "Truncated journal payload")
        start = self.offset
        self.offset += count
        return self.data[start:self.offset]

    def number(self, count, signed=False):
        return int.from_bytes(self.take(count), "big", signed=signed)

    def string(self):
        units = self.number(4, True)
        _integer(units, "journal UTF-16 units", 1, 32768)
        return self.take(units * 2).decode("utf-16-be", "surrogatepass")

    def done(self):
        _require(self.offset == len(self.data), "Trailing bytes in journal image")


def _header(data):
    reader = _Reader(data)
    _require(reader.take(8) == b"MRDNJNL\0", "Wrong journal header magic")
    _require(reader.number(4) == 1, "Unsupported journal header version")
    lineage = reader.string()
    checksum = reader.number(4)
    reader.done()
    _require(checksum == _crc32c(data[:-4]), "Journal header CRC32C mismatch")
    return lineage


def _frame(data):
    reader = _Reader(data)
    _require(reader.take(4) == b"MTXN", "Wrong journal record magic")
    _require(reader.number(4) == 1, "Unsupported journal record version")
    length = reader.number(4, True)
    _integer(length, "record payload bytes", 26, 8 * 1024 * 1024)
    _require(reader.number(4) == _crc32c(data[:12]), "Journal record prefix CRC32C mismatch")
    payload = reader.take(length)
    _require(reader.number(4) == _crc32c(payload), "Journal record payload CRC32C mismatch")
    reader.done()
    body = _Reader(payload)
    sequence = body.number(8, True)
    _integer(sequence, "journal sequence", 1)
    request = body.string()
    _require(body.number(4, True) == 1, "Attributed v1 source requires one operation")
    kind = body.number(1)
    _require(kind in (1, 2), "Unknown journal operation kind")
    entity = body.string()
    flags = body.number(1)
    _require(flags & ~3 == 0, "Unknown journal endpoint flags")
    start = body.number(8, True) if flags & 1 else None
    end = body.number(8, True) if flags & 2 else None
    value = body.number(8, True) if kind == 1 else None
    body.done()
    _require(start is None or end is None or start < end, "Invalid journal interval")
    return {"sequence": sequence, "request": request, "kind": kind, "entity": entity,
            "start": start, "end": end, "value": value}


def _hex(value, name):
    _text(value, name, MAX_HEX_UNITS)
    _require(len(value) % 2 == 0 and re.fullmatch(r"[0-9a-f]+", value) is not None,
             f"Noncanonical hexadecimal: {name}")
    return bytes.fromhex(value)


def _history(source, result, spec, retries):
    _fields(source, {"headerHex", "framesHex"}, "sourceHistory")
    entities, commits, queries, width = (spec[key] for key in DIMENSIONS)
    frames = source["framesHex"]
    _require(type(frames) is list and len(frames) == commits, "Wrong original frame inventory")
    header = _hex(source["headerHex"], "source header")
    _require(_header(header) == "workload-v1", "Wrong source history lineage")
    _require(sum(len(frame) if type(frame) is str else MAX_HISTORY_BYTES * 2 + 1 for frame in frames)
             + len(source["headerHex"]) <= MAX_HISTORY_BYTES * 2, "Source history byte admission exceeded")
    digest = hashlib.sha256(header)
    seed_bytes = mixed_bytes = 0
    touches = Counter()
    puts = retracts = 0
    for index, supplied in enumerate(frames, 1):
        frame = _hex(supplied, "source frame")
        record = _frame(frame)
        _require(record["sequence"] == index and record["request"] == f"r-{index}", "Wrong source sequence/request identity")
        match = re.fullmatch(r"e-(0|[1-9][0-9]*)", record["entity"])
        _require(match is not None and int(match[1]) < entities, "Wrong source entity identity")
        start, end = record["start"], record["end"]
        _require(start is not None and end is not None and 0 <= start < end <= commits * width
                 and end - start <= width, "Source interval exceeds finite corpus bounds")
        if start >= width:
            _require(record["kind"] == 1 and start % width == 0 and end - start == width,
                     "Source append window differs from corpus bounds")
        else:
            _require(end <= width, "Source primary window crosses the append boundary")
        if index <= entities:
            _require(record["kind"] == 1 and record["entity"] == f"e-{index - 1}" and start == 0 and end == width,
                     "Wrong seed source operation")
            seed_bytes += len(frame)
        else:
            mixed_bytes += len(frame)
        touches[record["entity"]] += 1
        puts += record["kind"] == 1
        retracts += record["kind"] == 2
        digest.update(frame)
    total = len(header) + seed_bytes + mixed_bytes
    _integer(result.get("encodedHistoryBytes"), "encodedHistoryBytes", 1, MAX_HISTORY_BYTES)
    _text(result.get("historyFingerprint"), "historyFingerprint", 64)
    _require(total == result["encodedHistoryBytes"] and digest.hexdigest() == result["historyFingerprint"],
             "Source history identity differs from workload result")
    workload = result.get("workload")
    _require(type(workload) is dict, "Missing observed workload metadata")
    for key, expected in (("commits", commits), ("reads", queries), ("retries", retries),
                          ("attempts", commits + retries), ("writeOperations", commits),
                          ("putOperations", puts), ("retractOperations", retracts)):
        _integer(workload.get(key), f"workload.{key}")
        _require(workload[key] == expected, "Observed workload differs from source history/schedule")
    _require(type(workload.get("entityTouches")) is dict and set(workload["entityTouches"]) == set(touches),
             "Observed entity inventory differs from source")
    for entity, count in touches.items():
        _integer(workload["entityTouches"][entity], "entity touch count")
        _require(workload["entityTouches"][entity] == count, "Observed entity touches differ from source")
    return {"headerBytes": len(header), "seedFrameBytes": seed_bytes, "mixedFrameBytes": mixed_bytes,
            "encodedHistoryBytes": total, "newCommits": commits, "exactRetries": retries, "queries": queries}


def _sum(values):
    total = sum(values)
    return _integer(total, "aggregate counter")


def _matrix(value):
    _fields(value, {"schemaVersion", "overflow", "invalidResult", "counts"}, "counter snapshot")
    _integer(value["schemaVersion"], "counter schema", 1, 1)
    _require(value["overflow"] is False and value["invalidResult"] is False, "Incomplete counter snapshot")
    rows = value["counts"]
    _require(type(rows) is list and len(rows) == 8, "Wrong counter role inventory")
    checked = []
    for row in rows:
        _require(type(row) is list and len(row) == 14, "Wrong raw metric inventory")
        for item in row:
            _integer(item, "raw counter")
        c = dict(zip(METRICS, row))
        for operation in ("READ", "WRITE"):
            _require(c[f"{operation}_ATTEMPTS"] == _sum((c[f"{operation}_RETURNS"], c[f"{operation}_FAILURES"])),
                     "Attempts differ from returns/failures")
            _require(c[f"{operation}_FAILURES"] == 0, "Throwing native calls have unknown additional effects")
        _require(c["FORCE_ATTEMPTS"] == _sum((c["FORCE_SUCCESSES"], c["FORCE_FAILURES"]))
                 and c["FORCE_FAILURES"] == 0, "Incomplete force outcomes")
        empty = _sum((c["READ_ZERO"], c["READ_EOF"]))
        _require(empty <= c["READ_RETURNS"] and c["WRITE_ZERO"] <= c["WRITE_RETURNS"], "Empty results exceed returns")
        for operation, positive in (("READ", c["READ_RETURNS"] - empty),
                                    ("WRITE", c["WRITE_RETURNS"] - c["WRITE_ZERO"])):
            amount = c[f"{operation}_BYTES"]
            _require(amount == 0 if positive == 0 else amount >= positive, "Bytes differ from positive returned calls")
        checked.append(list(row))
    _require(not any(checked[-1]), "Unknown-role counters invalidate coverage")
    for index in range(14):
        _sum(row[index] for row in checked)
    return checked


def _zero():
    return [[0] * 14 for _ in ROLES]


def _add(target, source):
    for row in range(8):
        for column in range(14):
            target[row][column] = _sum((target[row][column], source[row][column]))
    for column in range(14):
        _sum(row[column] for row in target)


def _totals(matrix):
    return {"counters": {role: dict(zip(METRICS, row)) for role, row in zip(ROLES, matrix)},
            "totalReadBytes": _sum(row[2] for row in matrix),
            "totalWriteBytes": _sum(row[8] for row in matrix),
            "totalForceSuccesses": _sum(row[12] for row in matrix)}


def _ratio(numerator, denominator):
    return {"numerator": numerator, "denominator": denominator,
            "value": None if denominator == 0 else numerator / denominator}


def _ratios(matrix, denominator, label):
    return {f"fileReadBytesPer{label}": _ratio(_sum(row[2] for row in matrix), denominator),
            f"fileWriteBytesPer{label}": _ratio(_sum(row[8] for row in matrix), denominator)}


def _scope_inventory(actions, segments):
    expected = []

    def scope(category, phase=None, kind=None):
        expected.append((category, phase, kind))

    scope("NAMESPACE")
    scope("TEARDOWN")
    for _ in range(3):
        scope("NAMESPACE")
    scope("OPERATION", "LOAD", "CREATE")
    for phase, kind in actions:
        scope("OPERATION", phase, kind)
        scope("VALIDATION")
    scope("VALIDATION")
    scope("TEARDOWN")
    scope("OPERATION", "MAINTENANCE", "JOURNAL_REOPEN")
    scope("VALIDATION")
    scope("TEARDOWN")
    scope("OWNER_ACQUISITION")
    scope("OWNER_ACQUISITION")
    scope("PREFIX_VERIFICATION")
    scope("VALIDATION")
    for _ in range(segments):
        scope("OPERATION", "MAINTENANCE", "SEGMENT_WRITE")
        scope("VALIDATION")
    scope("OPERATION", "MAINTENANCE", "CHECKPOINT_PUBLISH")
    scope("READER_ACQUISITION")
    scope("VALIDATION")
    scope("OPERATION", "MAINTENANCE", "COMPACTION_PLAN")
    scope("VALIDATION")
    scope("OPERATION", "MAINTENANCE", "COMPACTION_PUBLISH")
    scope("READER_ACQUISITION")
    scope("VALIDATION")
    for _ in range(4):
        scope("TEARDOWN")
    scope("OPERATION", "MAINTENANCE", "CHECKPOINT_REOPEN")
    scope("VALIDATION")
    scope("TEARDOWN")
    scope("EXTERNAL_HASH")
    scope("FILE_INVENTORY")
    return expected


def validate_attribution(payload, result):
    """Recompute attributed declarations after base workload evidence admission.

    The result is the existing admitted LocalWorkloadRunner schema-one record.
    Source bytes establish logical denominators; counters remain recorded native
    outcomes. Auxiliary work never enters operation numerators.
    """
    _bounded_tree(payload)
    _bounded_tree(result)
    _fields(payload, {"schemaVersion", "mode", "scopes", "denominators", "gaps", "sourceHistory"}, "attribution")
    _integer(payload["schemaVersion"], "attribution schema", 1, 1)
    _require(payload["mode"] == MODE, "Unsupported instrumentation mode")
    _require(type(result) is dict, "Missing base workload result")
    spec = _fields(result.get("spec"), SPEC_FIELDS, "spec")
    config = _fields(result.get("config"), {"warmups", "repetitions"}, "config")
    e, c, q, _ = _checked_dimensions({key: spec[key] for key in DIMENSIONS}, **config)
    seed = _integer(spec["seed"], "seed", LONG_MIN)
    for key in PERCENTAGES:
        _integer(spec[key], key, 0, 100)
    _require(sum(spec[key] for key in ("correctionPercent", "retractionPercent", "repeatPercent")) <= 100,
             "Intent percentages exceed 100")
    actions, retries = _actions(seed, e, c, q, spec["retryPercent"])
    segments = _source_segments(c)
    iterations = config["warmups"] + config["repetitions"]
    _budget(len(actions), segments, iterations)
    declared = _fields(payload["denominators"], DENOMINATORS, "denominators")
    expected = _history(payload["sourceHistory"], result, spec, retries)
    for key, value in expected.items():
        _integer(declared[key], f"denominators.{key}")
        _require(declared[key] == value, "Denominators differ from original source bytes/schedule")
    gaps = payload["gaps"]
    _require(type(gaps) is list and 1 <= len(gaps) <= 32, "Invalid instrumentation gaps")
    for gap in gaps:
        _text(gap, "gap", 2048)
    _require(len(set(gaps)) == len(gaps), "Duplicate instrumentation gaps")
    original = result.get("iterations")
    _require(type(original) is list and len(original) == iterations, "Wrong base iteration inventory")
    scopes = payload["scopes"]
    inventory = _scope_inventory(actions, segments)
    _require(type(scopes) is list and len(scopes) == 2 + len(inventory) * iterations <= MAX_SCOPES,
             "Wrong full scope inventory")
    expected_scopes = [(-1, "PREPARATION", None, None)] * 2 + [
        (index, *scope) for index in range(iterations) for scope in inventory]
    matrices = []
    for ordinal, (entry, binding) in enumerate(zip(scopes, expected_scopes)):
        _fields(entry, ENTRY_FIELDS, "scope")
        _integer(entry["ordinal"], "scope ordinal")
        _integer(entry["iteration"], "scope iteration", -1)
        _require(entry["ordinal"] == ordinal and (entry["iteration"], entry["category"], entry["phase"], entry["kind"]) == binding,
                 "Scope order/binding differs from actual lifecycle")
        _require(entry["callReturned"] is True and entry["cleanupOutsideCapture"] is False
                 and entry["operationFailureType"] is None and entry["observationFailureType"] is None,
                 "Incomplete scope outcome")
        matrix = _matrix(entry["counters"])
        if entry["category"] in EXCLUDED:
            _require(not any(any(row) for row in matrix), "Excluded external work cannot carry adapter counters")
        matrices.append(matrix)
    captured_all = _zero()
    for matrix in matrices:
        _add(captured_all, matrix)
    preparation = _zero()
    for matrix in matrices[:2]:
        _add(preparation, matrix)
    summaries = []
    cursor = 2
    operation_order = [scope[1:] for scope in inventory if scope[0] == "OPERATION"]
    for index, iteration in enumerate(original):
        _require(type(iteration) is dict, "Invalid base iteration")
        _integer(iteration.get("index"), "base iteration index")
        _require(iteration["index"] == index and type(iteration.get("warmup")) is bool
                 and iteration["warmup"] == (index < config["warmups"]), "Wrong base iteration binding")
        _integer(iteration.get("sourceSegments"), "sourceSegments", 2)
        _require(iteration["sourceSegments"] == segments, "Wrong source segment inventory")
        samples = iteration.get("samples")
        _require(type(samples) is list and len(samples) == len(operation_order), "Wrong base operation sample inventory")
        for sample, binding in zip(samples, operation_order):
            _require(type(sample) is dict and (sample.get("phase"), sample.get("kind")) == binding,
                     "Base sample order differs from generated lifecycle")
        groups, auxiliaries = {}, {}
        group_counts, auxiliary_counts = Counter(), Counter()
        operation_total, captured_total = _zero(), _zero()
        for entry, matrix in zip(scopes[cursor:cursor + len(inventory)], matrices[cursor:cursor + len(inventory)]):
            _add(captured_total, matrix)
            if entry["category"] == "OPERATION":
                key = (entry["phase"], entry["kind"])
                _add(groups.setdefault(key, _zero()), matrix)
                _add(operation_total, matrix)
                group_counts[key] += 1
            else:
                key = entry["category"]
                _add(auxiliaries.setdefault(key, _zero()), matrix)
                auxiliary_counts[key] += 1
        operation_groups = []
        for phase, kind in sorted(groups, key=lambda key: (PHASES.index(key[0]), KINDS.index(key[1]))):
            matrix = groups[(phase, kind)]
            if kind == "COMMIT":
                denominator = expected["seedFrameBytes" if phase == "LOAD" else "mixedFrameBytes"]
                label = "OriginalFrameByte"
            elif kind == "RETRY" or kind in QUERIES:
                denominator, label = 0, "OriginalFrameByte"
            else:
                denominator, label = expected["encodedHistoryBytes"], "OriginalHistoryByte"
            operation_groups.append({"phase": phase, "kind": kind, "scopeCount": group_counts[(phase, kind)],
                                     **_totals(matrix), "ratios": _ratios(matrix, denominator, label)})
        summaries.append({"index": index, "warmup": iteration["warmup"], "operationGroups": operation_groups,
                          "auxiliaryGroups": [{"category": category,
                                               "coverage": "EXCLUDED_EXTERNAL_IO" if category in EXCLUDED else "ADAPTERS_ONLY",
                                               "scopeCount": auxiliary_counts[category], **_totals(auxiliaries[category])}
                                              for category in CATEGORIES if category in auxiliaries],
                          "operationTotals": {**_totals(operation_total),
                                              "ratios": _ratios(operation_total, expected["encodedHistoryBytes"], "OriginalHistoryByte")},
                          "capturedTotals": _totals(captured_total)})
        cursor += len(inventory)
    return {"schemaVersion": 1, "mode": MODE, "denominators": dict(expected),
            "preparationTotals": {"scopeCount": 2, "coverage": "ADAPTERS_ONLY", **_totals(preparation)},
            "uncapturedWork": [{"category": category, "coverage": "EXCLUDED_EXTERNAL_IO", "counters": None}
                               for category in ("EXPORT", "PROCESS_TELEMETRY", "SUPERVISOR_VALIDATION")],
            "iterations": summaries,
            "limits": ["Recorded caller-thread adapter counters, not authenticated execution or device traffic",
                       "Operation numerators exclude setup, validation, hash, inventory, teardown and export work",
                       "Excluded hash/inventory zero counters do not establish zero external I/O; export is outside this lifecycle",
                       "Original-frame denominators exclude retries; zero denominators are unavailable, never zero amplification",
                       "Original-history denominators include its exact lineage header and every original frame",
                       "Warmup and measured repetitions remain separate; instrumented timings do not replace observational latency"]}
