"""Independent deterministic v1 corpus and realized-summary reconstruction.

Only the bounded generated corpus is checked here. No Java implementation,
filesystem, worker envelope, recovery matrix, native counter or process evidence
is imported or admitted. Historical values are resolved from original writes;
there is no retained table of complete states at every commit.
"""

import hashlib
import re
import struct


LONG_MIN = -(1 << 63)
LONG_MAX = (1 << 63) - 1
MAX_REFERENCE_WORK = 32_000_000
MAX_ENCODED_BYTES = 16 * 1024 * 1024
MAX_ACTIONS = 2 * 4096 + 1024
MAX_FRAME_BYTES = 4096
WRITE_SALT = 0x13579BDF2468ACE0
READ_SALT = 0x275A3C691DBF804E
RETRY_SALT = 0x691FE5D7320CAB84
SPEC_FIELDS = ("seed", "entities", "commits", "queries", "intervalWidth", "correctionPercent",
               "retractionPercent", "repeatPercent", "retryPercent", "hotEntityPercent", "historicalReadPercent")
INTENTS = ("SEED", "APPEND", "CORRECTION", "RETRACTION", "REPEAT", "RETRY")
READ_OPERATORS = ("GET", "SCAN", "SELECT", "FILTER_SCAN", "SUM")
PREDICATES = ("EQ", "NE", "LT", "LE", "GT", "GE")
SUMMARY_FIELDS = ("attempts", "commits", "retries", "writeOperations", "reads", "noOpCommits",
                  "putOperations", "retractOperations", "noOpPuts", "noOpRetractions",
                  "putsReplacingPresent", "retractionsRemovingPresent", "geometricOverlaps",
                  "entityTouches", "scheduledIntents", "readOperators", "historicalReads", "headReads",
                  "pointHits", "pointMisses", "emptyResults", "nonemptyResults")
METADATA_FIELDS = ("corpusVersion", "fingerprint", "historyFingerprint", "encodedHistoryBytes", "workload")
_READ_COUNTS = {"reads", "historicalReads", "headReads", "pointHits", "pointMisses", "emptyResults", "nonemptyResults"}
_MAP_FIELDS = {"entityTouches", "scheduledIntents", "readOperators"}


class CorpusError(ValueError):
    """Invalid, excessive or inconsistent generated-corpus input or metadata."""


def _require(condition, message):
    if not condition:
        raise CorpusError(message)


def _integer(value, name, low=0, high=LONG_MAX):
    _require(type(value) is int and low <= value <= high, f"Invalid integer: {name}")
    return value


def _fields(value, expected, name):
    _require(type(value) is dict and len(value) == len(expected)
             and all(type(key) is str for key in value) and value.keys() == set(expected),
             f"Wrong fields: {name}")


def _admit(spec):
    _fields(spec, SPEC_FIELDS, "spec")
    _integer(spec["seed"], "seed", LONG_MIN)
    entities = _integer(spec["entities"], "entities", 1, 1024)
    commits = _integer(spec["commits"], "commits", entities + 1, 4096)
    queries = _integer(spec["queries"], "queries", 0, 1024)
    width = _integer(spec["intervalWidth"], "intervalWidth", 1, 16)
    for name in SPEC_FIELDS[5:]:
        _integer(spec[name], name, 0, 100)
    _require(spec["correctionPercent"] + spec["retractionPercent"] + spec["repeatPercent"] <= 100,
             "Write intent percentages exceed 100")
    work = commits * commits * (width + 1) + commits * queries * (width + min(entities, 16) + 2)
    _require(work <= MAX_REFERENCE_WORK, "Corpus reference-work admission exceeded")
    _require(2 * commits + queries <= MAX_ACTIONS, "Corpus action admission exceeded")
    # Generated identities have these maximum lengths, and every generated window
    # is finite. A put is the larger operation. Bound both original and retry bytes
    # before generating; the exact totals are checked as frames are emitted too.
    maximum_frame = 20 + 8 + 4 + 2 * len(f"r-{commits}") + 4 + 1 + 4 + 2 * len(f"e-{entities - 1}") + 1 + 16 + 8
    _require(42 + commits * maximum_frame <= MAX_ENCODED_BYTES
             and (2 * commits - entities) * maximum_frame <= MAX_ENCODED_BYTES,
             "Corpus encoded-byte admission exceeded")
    return {name: spec[name] for name in SPEC_FIELDS}


def _signed(value, bits):
    value &= (1 << bits) - 1
    return value - (1 << bits) if value & (1 << (bits - 1)) else value


class _Random48:
    """The specified java.util.Random 48-bit stream, including signed-word rules."""

    def __init__(self, seed):
        self.state = (seed ^ 0x5DEECE66D) & ((1 << 48) - 1)

    def _next(self, bits):
        self.state = (self.state * 0x5DEECE66D + 0xB) & ((1 << 48) - 1)
        return self.state >> (48 - bits)

    def next_int(self, bound):
        _integer(bound, "random bound", 1, (1 << 31) - 1)
        if bound & (bound - 1) == 0:
            return (bound * self._next(31)) >> 31
        while True:
            bits = self._next(31)
            value = bits % bound
            # Java tests a signed int addition. Values at/above 2^31 wrap negative.
            if bits - value + bound - 1 < (1 << 31):
                return value

    def next_long(self):
        high = _signed(self._next(32), 32)
        low = _signed(self._next(32), 32)
        return _signed((high << 32) + low, 64)


def _crc_table():
    result = []
    for value in range(256):
        for _ in range(8):
            value = (value >> 1) ^ (0x82F63B78 if value & 1 else 0)
        result.append(value)
    return tuple(result)


_CRC_TABLE = _crc_table()


def _crc32c(data):
    value = 0xFFFFFFFF
    for byte in data:
        value = _CRC_TABLE[(value ^ byte) & 0xFF] ^ (value >> 8)
    return value ^ 0xFFFFFFFF


def _text(value):
    encoded = value.encode("utf-16-be", "surrogatepass")
    return struct.pack(">i", len(encoded) // 2) + encoded


def _header():
    data = b"MRDNJNL\0" + struct.pack(">i", 1) + _text("workload-v1")
    return data + struct.pack(">I", _crc32c(data))


def _frame(sequence, operation):
    entity, start, end, value = operation
    kind = 2 if value is None else 1
    payload = struct.pack(">q", sequence) + _text(f"r-{sequence}") + struct.pack(">iB", 1, kind)
    payload += _text(f"e-{entity}") + b"\x03" + struct.pack(">qq", start, end)
    if value is not None:
        payload += struct.pack(">q", value)
    prefix = b"MTXN" + struct.pack(">ii", 1, len(payload))
    return prefix + struct.pack(">I", _crc32c(prefix)) + payload + struct.pack(">I", _crc32c(payload))


def _choose_entity(random, spec):
    return 0 if random.next_int(100) < spec["hotEntityPercent"] else random.next_int(spec["entities"])


def _query(random, spec, frontier, index):
    selected = _choose_entity(random, spec)
    entity = f"absent-{index}" if index % 7 == 0 else f"e-{selected}"
    width = spec["intervalWidth"]
    if index % 3 == 0:
        point = random.next_int(width)
    elif index % 3 == 1:
        point = frontier[selected] - width + random.next_int(width)
    else:
        point = frontier[selected] + width
    entities = tuple(f"e-{(selected + i) % spec['entities']}" for i in range(min(spec["entities"], 16) - 1, -1, -1))
    entities += ("absent-selection",)
    choice = (index // 5) % 5
    operand = LONG_MIN if choice == 0 else LONG_MAX if choice == 1 else index % 3 - 1
    return index % 5, entity, point, point + width, entities, PREDICATES[index % 6], operand


def _encode_query(query):
    kind, entity, point, end, entities, predicate, operand = query
    encoded = bytes((kind + 1,))
    if kind in (0, 1, 3):
        encoded += _text(entity)
    else:
        encoded += struct.pack(">i", len(entities)) + b"".join(_text(item) for item in entities)
    if kind in (1, 3):
        encoded += b"\x01" + struct.pack(">q", point) + b"\x01" + struct.pack(">q", end)
    else:
        encoded += struct.pack(">q", point)
    if kind in (2, 3):
        encoded += _text(predicate) + struct.pack(">q", operand)
    return encoded


def _point(histories, entity, cutoff, point):
    for sequence, start, end, value in reversed(histories.get(entity, ())):
        if sequence <= cutoff and start <= point < end:
            return value
    return None


def _matches(value, operator, operand):
    if value is None:
        return False
    if operator == "EQ":
        return value == operand
    if operator == "NE":
        return value != operand
    if operator == "LT":
        return value < operand
    if operator == "LE":
        return value <= operand
    if operator == "GT":
        return value > operand
    return value >= operand


def _read_classification(histories, cutoff, query):
    kind, entity, point, end, entities, predicate, operand = query
    if kind == 0:
        present = _point(histories, entity, cutoff, point) is not None
        return not present, present
    if kind in (1, 3):
        for coordinate in range(point, end):
            value = _point(histories, entity, cutoff, coordinate)
            if value is not None and (kind == 1 or _matches(value, predicate, operand)):
                return False, None
        return True, None
    values = [_point(histories, item, cutoff, point) for item in entities]
    present = [value for value in values if value is not None]
    if kind == 2:
        return not any(_matches(value, predicate, operand) for value in present), None
    # Python integers preserve the exact BigInteger aggregate, including sums that
    # exceed signed long or cancel to zero. Emptiness depends on count, not sum.
    aggregate = (len(present), sum(present))
    return aggregate[0] == 0, None


def _new_summary():
    result = {name: 0 for name in SUMMARY_FIELDS if name not in _MAP_FIELDS}
    result["entityTouches"] = {}
    result["scheduledIntents"] = {name: 0 for name in INTENTS}
    result["readOperators"] = {name: 0 for name in READ_OPERATORS}
    return result


def _record_write(summary, histories, sequence, operation):
    entity, start, end, value = operation
    name = f"e-{entity}"
    history = histories.setdefault(name, [])
    before = [_point(histories, name, sequence - 1, point) for point in range(start, end)]
    no_op = all(prior == value for prior in before)
    present = any(prior is not None for prior in before)
    summary["commits"] += 1
    summary["writeOperations"] += 1
    summary["noOpCommits"] += no_op
    if value is None:
        summary["retractOperations"] += 1
        summary["noOpRetractions"] += no_op
        summary["retractionsRemovingPresent"] += present
    else:
        summary["putOperations"] += 1
        summary["noOpPuts"] += no_op
        summary["putsReplacingPresent"] += present
    summary["geometricOverlaps"] += any(old_start < end and start < old_end for _, old_start, old_end, _ in history)
    summary["entityTouches"][name] = summary["entityTouches"].get(name, 0) + 1
    history.append((sequence, start, end, value))


def _retry_count(spec):
    random = _Random48(spec["seed"] ^ RETRY_SALT)
    result = 0
    for sequence in range(spec["entities"] + 1, spec["commits"] + 1):
        if random.next_int(100) < spec["retryPercent"]:
            random.next_int(sequence)
            result += 1
    return result


def prepare_corpus(spec):
    """Return fresh v1 source bytes, schedule identities and realized summary.

    This accepts the corpus's own bounds, without imposing recovery-matrix limits.
    Integer-unit windows make dense comparisons exact for the generated finite
    intervals. Only original per-entity writes are retained for historical lookup.
    """
    spec = _admit(spec)
    entities, commits, queries, width = (spec[name] for name in SPEC_FIELDS[1:5])
    retries_expected = _retry_count(spec)
    action_count = commits + retries_expected + queries
    _require(action_count <= 2 * commits + queries, "Generated action count exceeds admission")
    schedule = hashlib.sha256()
    schedule.update(_text("meridiandb-workload") + struct.pack(">i", 1) + struct.pack(">q", spec["seed"]))
    for name in SPEC_FIELDS[1:]:
        schedule.update(struct.pack(">i", spec[name]))
    schedule.update(struct.pack(">i", action_count))
    header = _header()
    history_hash = hashlib.sha256(header)
    encoded_bytes, attempt_bytes = len(header), 0
    frames = []
    summary = _new_summary()
    histories = {}
    primary = [[None] * width for _ in range(entities)]
    frontier = [width] * entities
    writes = _Random48(spec["seed"] ^ WRITE_SALT)
    reads = _Random48(spec["seed"] ^ READ_SALT)
    retries = _Random48(spec["seed"] ^ RETRY_SALT)
    emitted = 0
    operation = None
    for sequence in range(1, commits + 1):
        seed = sequence <= entities
        choice = -1 if seed else writes.next_int(100)
        intent = ("SEED" if seed else "REPEAT" if choice < spec["repeatPercent"] else
                  "RETRACTION" if choice < spec["repeatPercent"] + spec["retractionPercent"] else
                  "CORRECTION" if choice < spec["repeatPercent"] + spec["retractionPercent"] + spec["correctionPercent"] else "APPEND")
        if intent != "REPEAT":
            entity = sequence - 1 if seed else _choose_entity(writes, spec)
            value = LONG_MIN if sequence % 17 == 1 else LONG_MAX if sequence % 17 == 0 else writes.next_long()
            if seed:
                operation = (entity, 0, width, value)
                primary[entity][:] = [value] * width
            elif intent == "APPEND":
                start = frontier[entity]
                frontier[entity] += width
                operation = (entity, start, frontier[entity], value)
            else:
                start = writes.next_int(width)
                visited = 0
                while visited < width and primary[entity][start] is None:
                    start = (start + 1) % width
                    visited += 1
                if visited == width:
                    operation = (entity, 0, width, value)
                    primary[entity][:] = [value] * width
                else:
                    end = start + 1 + writes.next_int(width - start)
                    stored = None if intent == "RETRACTION" else value
                    operation = (entity, start, end, stored)
                    primary[entity][start:end] = [stored] * (end - start)
        frame = _frame(sequence, operation)
        frames.append(frame)
        encoded_bytes += len(frame)
        attempt_bytes += len(frame)
        _require(encoded_bytes <= MAX_ENCODED_BYTES and attempt_bytes <= MAX_ENCODED_BYTES,
                 "Generated encoded bytes exceed admission")
        history_hash.update(frame)
        schedule.update(b"\x01" + _text("SEED" if seed else "MIXED") + _text(intent) + struct.pack(">i", len(frame)) + frame)
        summary["attempts"] += 1
        summary["scheduledIntents"][intent] += 1
        _record_write(summary, histories, sequence, operation)
        if not seed:
            if retries.next_int(100) < spec["retryPercent"]:
                prior = frames[retries.next_int(sequence)]
                attempt_bytes += len(prior)
                _require(attempt_bytes <= MAX_ENCODED_BYTES, "Generated retry bytes exceed admission")
                schedule.update(b"\x01" + _text("MIXED") + _text("RETRY") + struct.pack(">i", len(prior)) + prior)
                summary["attempts"] += 1
                summary["retries"] += 1
                summary["scheduledIntents"]["RETRY"] += 1
            due = (sequence - entities) * queries // (commits - entities)
            while emitted < due:
                cutoff = reads.next_int(sequence) if reads.next_int(100) < spec["historicalReadPercent"] else sequence
                query = _query(reads, spec, frontier, emitted)
                schedule.update(b"\x02" + struct.pack(">q", cutoff) + _encode_query(query))
                empty, point_hit = _read_classification(histories, cutoff, query)
                summary["reads"] += 1
                summary["readOperators"][READ_OPERATORS[query[0]]] += 1
                summary["historicalReads" if cutoff < sequence else "headReads"] += 1
                summary["emptyResults" if empty else "nonemptyResults"] += 1
                if point_hit is not None:
                    summary["pointHits" if point_hit else "pointMisses"] += 1
                emitted += 1
    _require(emitted == queries and summary["retries"] == retries_expected,
             "Generated schedule differs from admitted counts")
    summary["entityTouches"] = dict(sorted(summary["entityTouches"].items()))
    summary = {name: summary[name] for name in SUMMARY_FIELDS}
    return dict(corpusVersion=1, spec=spec, fingerprint=schedule.hexdigest(), historyFingerprint=history_hash.hexdigest(),
                encodedHistoryBytes=encoded_bytes, sourceHistory=dict(headerHex=header.hex(), framesHex=[frame.hex() for frame in frames]),
                workload=summary)


def _source_admission(source, spec):
    _fields(source, {"headerHex", "framesHex"}, "sourceHistory")
    _require(type(source["framesHex"]) is list and len(source["framesHex"]) == spec["commits"],
             "Wrong original source frame inventory")
    total = 0
    for name, values, maximum in (("header", (source["headerHex"],), 65556),
                                  ("frame", source["framesHex"], MAX_FRAME_BYTES)):
        for value in values:
            _require(type(value) is str and 0 < len(value) <= 2 * maximum and len(value) % 2 == 0
                     and re.fullmatch(r"[0-9a-f]+", value) is not None, f"Invalid bounded lowercase source {name}")
            total += len(value) // 2
            _require(total <= MAX_ENCODED_BYTES, "Source encoded-byte admission exceeded")


def _summary_admission(summary, spec):
    _fields(summary, SUMMARY_FIELDS, "workload")
    commits, entities, queries = spec["commits"], spec["entities"], spec["queries"]
    for name in SUMMARY_FIELDS:
        if name in _MAP_FIELDS:
            continue
        high = queries if name in _READ_COUNTS else commits - entities if name == "retries" else 2 * commits - entities if name == "attempts" else commits
        _integer(summary[name], name, 0, high)
    touches = summary["entityTouches"]
    _fields(touches, {f"e-{index}" for index in range(entities)}, "entityTouches")
    for count in touches.values():
        _integer(count, "entity touch count", 0, commits)
    _fields(summary["scheduledIntents"], INTENTS, "scheduledIntents")
    for name, count in summary["scheduledIntents"].items():
        high = entities if name == "SEED" else commits - entities
        _integer(count, "scheduled intent count", 0, high)
    _fields(summary["readOperators"], READ_OPERATORS, "readOperators")
    for count in summary["readOperators"].values():
        _integer(count, "read operator count", 0, queries)


def validate_corpus(source_history, metadata, spec):
    """Strictly bind supplied source and five-field metadata to a fresh v1 corpus.

    All supplied shapes, scalar types, maps and hexadecimal budgets are checked
    before regeneration. The result contains derived metadata only and shares no
    mutable values with the supplied structures. No partial summary is returned.
    """
    admitted = _admit(spec)
    _source_admission(source_history, admitted)
    _fields(metadata, METADATA_FIELDS, "corpus metadata")
    _integer(metadata["corpusVersion"], "corpusVersion", 1, 1)
    _integer(metadata["encodedHistoryBytes"], "encodedHistoryBytes", 0, MAX_ENCODED_BYTES)
    for name in ("fingerprint", "historyFingerprint"):
        value = metadata[name]
        _require(type(value) is str and re.fullmatch(r"[0-9a-f]{64}", value) is not None,
                 f"Invalid corpus digest: {name}")
    _summary_admission(metadata["workload"], admitted)
    generated = prepare_corpus(admitted)
    _require(source_history == generated["sourceHistory"], "Original source differs from regenerated v1 history")
    derived = {name: generated[name] for name in METADATA_FIELDS}
    _require(metadata == derived, "Corpus metadata differs from independently prepared v1 summary")
    return derived
