"""Independent recorded-image checks for the test-only recovery comparison protocol.

No Java codec, filesystem, source/process assertion, outer result, schedule/oracle,
or performance claim is admitted here. Input and returned structures are separate.
"""

import hashlib
import re

MAX_RAW_BYTES = 2 * 1024 * 1024
MAX_CONTINUATION_BYTES = 4096
MAX_FRAME_BYTES = 4096
MAX_DECODE_RECORDS = 4096
LONG_MIN = -(1 << 63)
LONG_MAX = (1 << 63) - 1
SHAPES = ("JOURNAL_ONLY", "GENESIS", "CHECKPOINT_TAIL", "COMPACTED_CHECKPOINT_TAIL")
LOCK = ".meridiandb-checkpoints.lock"
JOURNAL = "transactions.journal"
MANIFEST_NAME = re.compile(r"checkpoint-([0-9]{19})-([0-9a-f]{64})\.manifest")
SEGMENT_NAME = re.compile(r"([0-9a-f]{64})\.segment")
SPEC_FIELDS = {"seed", "entities", "commits", "queries", "intervalWidth", "correctionPercent",
               "retractionPercent", "repeatPercent", "retryPercent", "hotEntityPercent", "historicalReadPercent"}


class RecoveryImageError(ValueError):
    """Incomplete, contradictory, excessive or out-of-scope recovery images."""


def _require(condition, message):
    if not condition:
        raise RecoveryImageError(message)


def _integer(value, name, low=0, high=LONG_MAX):
    _require(type(value) is int and low <= value <= high, f"Invalid integer: {name}")
    return value


def _fields(value, expected, name):
    _require(type(value) is dict and value.keys() == expected, f"Wrong fields: {name}")


def _digest(data):
    return hashlib.sha256(data).digest()


def _sha(data):
    return hashlib.sha256(data).hexdigest()


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


class _Reader:
    def __init__(self, data):
        _require(type(data) is bytes and len(data) <= MAX_RAW_BYTES + MAX_CONTINUATION_BYTES,
                 "Binary image byte admission exceeded")
        self.data = data
        self.offset = 0

    def take(self, length):
        _require(0 <= length <= len(self.data) - self.offset, "Truncated binary image")
        first = self.offset
        self.offset += length
        return self.data[first:self.offset]

    def number(self, count, signed=True):
        return int.from_bytes(self.take(count), "big", signed=signed)

    def string(self):
        length = self.number(4)
        _integer(length, "UTF-16 identity units", 1, 32768)
        return self.take(length * 2).decode("utf-16-be", "surrogatepass")

    def done(self):
        _require(self.offset == len(self.data), "Trailing binary image bytes")


def _decode_header(data):
    _require(type(data) is bytes and 22 <= len(data) <= 65556 and len(data) % 2 == 0,
             "Invalid lineage header byte length")
    reader = _Reader(data)
    _require(reader.take(8) == b"MRDNJNL\0", "Wrong journal header magic")
    _require(reader.number(4) == 1, "Unsupported journal header version")
    lineage = reader.string()
    checksum = reader.number(4, False)
    reader.done()
    _require(checksum == _crc32c(data[:-4]), "Journal header CRC32C mismatch")
    return lineage


def _decode_frame(data):
    _require(type(data) is bytes and 20 <= len(data) <= MAX_FRAME_BYTES, "Record image byte admission exceeded")
    reader = _Reader(data)
    _require(reader.take(4) == b"MTXN", "Wrong journal record magic")
    _require(reader.number(4) == 1, "Unsupported journal record version")
    length = reader.number(4)
    _integer(length, "record payload bytes", 26, MAX_FRAME_BYTES - 20)
    _require(reader.number(4, False) == _crc32c(data[:12]), "Journal prefix CRC32C mismatch")
    payload = reader.take(length)
    _require(reader.number(4, False) == _crc32c(payload), "Journal payload CRC32C mismatch")
    reader.done()
    body = _Reader(payload)
    sequence = _integer(body.number(8), "journal sequence", 1)
    request = body.string()
    _require(body.number(4) == 1, "Recovery corpus requires exactly one operation")
    kind = body.number(1, False)
    _require(kind in (1, 2), "Unknown journal operation")
    entity = body.string()
    flags = body.number(1, False)
    _require(flags & ~3 == 0, "Unknown interval flags")
    start = body.number(8) if flags & 1 else None
    end = body.number(8) if flags & 2 else None
    value = body.number(8) if kind == 1 else None
    body.done()
    _require(start is None or end is None or start < end, "Invalid interval endpoints")
    return dict(sequence=sequence, request=request, kind=kind, entity=entity, start=start, end=end, value=value)


def _decode_segment(data, header, frames, first, last, budget):
    """Require expected header/range before body hashing or record decoding."""
    reader = _Reader(data)
    _require(reader.take(8) == b"MRDNSEG\0", "Wrong segment magic")
    _require(reader.number(4) == 1, "Unsupported segment version")
    lineage_bytes = reader.number(4)
    _integer(lineage_bytes, "segment lineage bytes", 22, 65556)
    _require(lineage_bytes % 2 == 0, "Odd segment lineage length")
    nested = reader.take(lineage_bytes)
    _decode_header(nested)
    _require(nested == header, "Segment lineage differs from original header")
    actual_first, actual_last, count, body_bytes = reader.number(8), reader.number(8), reader.number(4), reader.number(8)
    checksum_offset = reader.offset
    _require(reader.number(4, False) == _crc32c(data[:checksum_offset]), "Segment header CRC32C mismatch")
    _require(1 <= first <= last <= len(frames) and actual_first == first and actual_last == last,
             "Segment range differs from expected original history")
    _require(count == last - first + 1 and 1 <= count <= 65536, "Segment record count differs")
    expected_bytes = sum(len(frame) for frame in frames[first - 1:last])
    _require(body_bytes == expected_bytes and reader.offset + body_bytes + 32 == len(data),
             "Segment complete/body byte length differs")
    _require(budget[0] + count <= MAX_DECODE_RECORDS, "Aggregate decoded record admission exceeded")
    body = reader.take(body_bytes)
    _require(reader.take(32) == _digest(data[:-32]), "Segment integrity footer mismatch")
    reader.done()
    _require(body == b"".join(frames[first - 1:last]), "Segment body differs from exact original frames")
    # Decode every nested frame independently too; equality alone is not a codec check.
    cursor = 0
    requests = set()
    for sequence in range(first, last + 1):
        length = len(frames[sequence - 1])
        record = _decode_frame(body[cursor:cursor + length])
        cursor += length
        _require(record["sequence"] == sequence and record["request"] not in requests,
                 "Invalid segment sequence/request history")
        requests.add(record["request"])
    budget[0] += count
    return dict(first=first, last=last, bytes=len(data), sha256=_sha(data))


def _decode_manifest(data, header, frames):
    _require(type(data) is bytes and len(data) <= 1024 * 1024, "Manifest byte admission exceeded")
    reader = _Reader(data)
    _require(reader.take(8) == b"MRDNMAN\0", "Wrong manifest magic")
    _require(reader.number(4) == 1, "Unsupported manifest version")
    lineage_bytes = reader.number(4)
    _integer(lineage_bytes, "manifest lineage bytes", 22, 65556)
    _require(lineage_bytes % 2 == 0, "Odd manifest lineage length")
    nested = reader.take(lineage_bytes)
    _decode_header(nested)
    _require(nested == header, "Manifest lineage differs from original header")
    generation = _integer(reader.number(8), "manifest generation", 1)
    cutoff = _integer(reader.number(8), "manifest cutoff", 0, len(frames))
    prefix_bytes = _integer(reader.number(8), "manifest prefix bytes", len(header))
    prefix_hash = reader.take(32).hex()
    references = _integer(reader.number(4), "manifest references", 0, 4096)
    checksum_offset = reader.offset
    _require(reader.number(4, False) == _crc32c(data[:checksum_offset]), "Manifest header CRC32C mismatch")
    _require(reader.offset + references * 56 + 32 == len(data), "Manifest complete byte length differs")
    _require((cutoff == 0) == (references == 0), "Genesis/reference count differs")
    prefix = header + b"".join(frames[:cutoff])
    _require(prefix_bytes == len(prefix) and prefix_hash == _sha(prefix), "Manifest original prefix differs")
    refs = []
    names = set()
    next_sequence = 1
    for _ in range(references):
        identity = reader.take(32).hex()
        byte_length = _integer(reader.number(8), "segment reference bytes", 1, 32 * 1024 * 1024)
        first, last = reader.number(8), reader.number(8)
        _require(identity not in names and first == next_sequence and first <= last <= cutoff
                 and last - first + 1 <= 65536, "Invalid manifest reference coverage")
        names.add(identity)
        next_sequence = last + 1
        refs.append(dict(id=identity, bytes=byte_length, first=first, last=last))
    _require(next_sequence == cutoff + 1, "Manifest does not cover the complete selected prefix")
    _require(reader.take(32) == _digest(data[:-32]), "Manifest integrity footer mismatch")
    reader.done()
    return dict(generation=generation, cutoff=cutoff, prefixBytes=prefix_bytes, prefixSha256=prefix_hash,
                references=refs, bytes=len(data), sha256=_sha(data))


def _admit_request(spec, config, limits):
    _fields(spec, SPEC_FIELDS, "spec")
    _fields(config, {"warmups", "repetitions"}, "config")
    _fields(limits, {"maxObjectBytes", "maxJournalBytes", "maxRecordWork"}, "limits")
    _integer(spec["seed"], "seed", LONG_MIN)
    entities = _integer(spec["entities"], "entities", 1, 1024)
    commits = _integer(spec["commits"], "commits", entities + 1, 4096)
    queries = _integer(spec["queries"], "queries", 0, 1024)
    width = _integer(spec["intervalWidth"], "intervalWidth", 1, 16)
    for field in SPEC_FIELDS - {"seed", "entities", "commits", "queries", "intervalWidth"}:
        _integer(spec[field], field, 0, 100)
    _require(spec["correctionPercent"] + spec["retractionPercent"] + spec["repeatPercent"] <= 100,
             "Write intent percentages exceed 100")
    warmups = _integer(config["warmups"], "warmups", 0, 2)
    repetitions = _integer(config["repetitions"], "repetitions", 1, 5)
    opens = 16 * (warmups + repetitions)
    _require(commits * commits * (width + 1) + commits * queries * (width + min(entities, 16) + 2) <= 32_000_000,
             "Corpus reference-work admission exceeded")
    _require(opens * (2 * (2 * commits + queries) + commits + 16) <= 40_000
             and opens * (commits + 1) ** 2 <= 32_000_000, "Comparison aggregate work admission exceeded")
    _integer(limits["maxObjectBytes"], "maxObjectBytes", 0, 64 * 1024 * 1024)
    _integer(limits["maxJournalBytes"], "maxJournalBytes", 0, 64 * 1024 * 1024)
    _integer(limits["maxRecordWork"], "maxRecordWork", 0, 262144)
    cutoff = commits // 2
    chunk = min(16, max(1, cutoff // 2))
    ranges = [(first, min(first + chunk - 1, cutoff)) for first in range(1, cutoff + 1, chunk)]
    return entities, commits, width, cutoff, ranges


def _hex_admission(value, name, maximum, allow_empty=False):
    _require(type(value) is str and (allow_empty or bool(value)) and len(value) <= maximum * 2
             and len(value) % 2 == 0 and re.fullmatch(r"[0-9a-f]*", value) is not None,
             f"Invalid bounded lowercase hexadecimal: {name}")
    return len(value) // 2


def _preflight(source, continuation, fixtures, spec, config, limits):
    admitted = _admit_request(spec, config, limits)
    _, commits, _, cutoff, ranges = admitted
    decoded_work = commits + 1 + cutoff * (1 if len(ranges) == 1 else 2)
    _require(decoded_work <= MAX_DECODE_RECORDS, "Aggregate decoded record admission exceeded")
    _fields(source, {"headerHex", "framesHex"}, "sourceHistory")
    _require(type(source["framesHex"]) is list and len(source["framesHex"]) == commits,
             "Original frame inventory differs from admitted commits")
    total = _hex_admission(source["headerHex"], "original header", 65556)
    for frame in source["framesHex"]:
        total += _hex_admission(frame, "original frame", MAX_FRAME_BYTES)
        _require(total <= MAX_RAW_BYTES, "Aggregate raw fixture byte admission exceeded")
    _hex_admission(continuation, "continuation frame", MAX_CONTINUATION_BYTES)
    _require(type(fixtures) is list and len(fixtures) == 4, "Require exactly four ordered storage shapes")
    for shape, supplied in zip(SHAPES, fixtures):
        _fields(supplied, {"shape", "files"}, "fixtureImages")
        _require(supplied["shape"] == shape, "Wrong, duplicate or reordered storage shape")
        files = supplied["files"]
        count = {"JOURNAL_ONLY": 1, "GENESIS": 2, "CHECKPOINT_TAIL": 2 + len(ranges),
                 "COMPACTED_CHECKPOINT_TAIL": 3 + len(ranges) + (len(ranges) > 1)}[shape]
        _require(type(files) is dict and len(files) == count and LOCK in files, "Initial nonjournal inventory differs")
        for name, image in files.items():
            _require(type(name) is str and (name == LOCK or MANIFEST_NAME.fullmatch(name) or SEGMENT_NAME.fullmatch(name)),
                     "Invalid initial fixture filename")
            total += _hex_admission(image, "initial fixture image", MAX_RAW_BYTES, name == LOCK)
            _require(total <= MAX_RAW_BYTES, "Aggregate raw fixture byte admission exceeded")
    # The fixed matrix always selects this prefix and its source graph. Admission
    # can use raw lengths without decoding or hashing any image.
    prefix_bytes = len(source["headerHex"]) // 2 + sum(len(frame) // 2 for frame in source["framesHex"][:cutoff])
    _require(2 * cutoff <= limits["maxRecordWork"] and prefix_bytes <= limits["maxJournalBytes"],
             "Selected prefix acquisition limits exceeded before decoding")
    tail_files = fixtures[2]["files"]
    for index, supplied in enumerate(fixtures[1:], 1):
        files = supplied["files"]
        if index < 3:
            selected_bytes = sum(len(image) // 2 for name, image in files.items() if name != LOCK)
            _require(selected_bytes <= limits["maxObjectBytes"], "Selected object acquisition limit exceeded before decoding")
        else:
            manifests = [name for name in files if (match := MANIFEST_NAME.fullmatch(name)) and int(match[1]) == 2]
            segments = [name for name in files if SEGMENT_NAME.fullmatch(name)
                        and (len(ranges) == 1 or name not in tail_files)]
            if len(manifests) == len(segments) == 1:
                _require(sum(len(files[name]) // 2 for name in manifests + segments) <= limits["maxObjectBytes"],
                         "Selected compacted acquisition limit exceeded before decoding")
    return admitted, total


def validate_images(source_history, continuation_hex, fixture_images, spec, config, limits):
    """Check only recorded binary-image consistency; perform no filesystem/process work.

    Spec/configuration/limits must be supplied from the caller's expected request.
    Full outer request binding, schedule/oracle identity and result/matrix/native
    counters are separate admission stages. Keep input structures unchanged during
    the call. No partial summary is returned.
    """
    admitted, raw_bytes = _preflight(source_history, continuation_hex, fixture_images, spec, config, limits)
    entities, commits, width, cutoff, ranges = admitted
    header = bytes.fromhex(source_history["headerHex"])
    _require(_decode_header(header) == "workload-v1", "Wrong recovery corpus lineage")
    frames = [bytes.fromhex(frame) for frame in source_history["framesHex"]]
    budget = [0]
    for sequence, frame in enumerate(frames, 1):
        record = _decode_frame(frame)
        _require(record["sequence"] == sequence and record["request"] == f"r-{sequence}",
                 "Wrong original sequence/request identity")
        match = re.fullmatch(r"e-(0|[1-9][0-9]*)", record["entity"])
        _require(match is not None and int(match[1]) < entities, "Wrong original entity identity")
        start, end = record["start"], record["end"]
        _require(start is not None and end is not None and 0 <= start < end <= commits * width and end - start <= width,
                 "Original interval exceeds finite corpus bounds")
        if start >= width:
            _require(record["kind"] == 1 and start % width == 0 and end - start == width,
                     "Original append interval differs")
        else:
            _require(end <= width, "Original primary interval crosses append region")
        if sequence <= entities:
            _require(record["kind"] == 1 and record["entity"] == f"e-{sequence - 1}" and start == 0 and end == width,
                     "Original seed operation differs")
        budget[0] += 1
    continuation = bytes.fromhex(continuation_hex)
    _require(_decode_frame(continuation) == dict(sequence=commits + 1, request="recovery-continuation-0", kind=1,
             entity="recovery-continuation-entity-0", start=None, end=None, value=17),
             "Continuation is not the exact fresh unbounded Put17")
    budget[0] += 1
    original = header + b"".join(frames)
    history_hash = _sha(original)
    prefix = header + b"".join(frames[:cutoff])
    prefix_hash = _sha(prefix)
    summaries = []
    source_files = None
    segment_cache = {}
    for shape, supplied in zip(SHAPES, fixture_images):
        images = {name: bytes.fromhex(image) for name, image in supplied["files"].items()}
        _require(images[LOCK] == b"", "Initial checkpoint lock is not empty")
        hashes = {name: _sha(image) for name, image in images.items()}
        hashes[JOURNAL] = history_hash
        manifests = {}
        segment_names = set()
        for name, image in images.items():
            manifest_name = MANIFEST_NAME.fullmatch(name)
            segment_name = SEGMENT_NAME.fullmatch(name)
            if manifest_name:
                _require(hashes[name] == manifest_name[2], "Manifest filename full-object ID mismatch")
                manifest = _decode_manifest(image, header, frames)
                generation = int(manifest_name[1])
                _require(generation == manifest["generation"] and generation not in manifests,
                         "Manifest filename generation differs or is duplicated")
                manifests[generation] = manifest
            elif segment_name:
                _require(hashes[name] == segment_name[1], "Segment filename full-object ID mismatch")
                segment_names.add(name)
        expected_generations = {"JOURNAL_ONLY": set(), "GENESIS": {1}, "CHECKPOINT_TAIL": {1},
                                "COMPACTED_CHECKPOINT_TAIL": {1, 2}}[shape]
        _require(set(manifests) == expected_generations, "Initial manifest generation inventory differs")
        referenced = set()
        for generation, manifest in manifests.items():
            expected_cutoff = 0 if shape == "GENESIS" else cutoff
            _require(manifest["cutoff"] == expected_cutoff, "Manifest selected cutoff differs")
            expected_ranges = [] if shape == "GENESIS" else [(1, cutoff)] if generation == 2 else ranges
            refs = manifest["references"]
            _require([(ref["first"], ref["last"]) for ref in refs] == expected_ranges,
                     "Manifest source/compacted layout differs")
            _require(manifest["bytes"] + sum(ref["bytes"] for ref in refs) <= limits["maxObjectBytes"]
                     and manifest["prefixBytes"] <= limits["maxJournalBytes"]
                     and 2 * manifest["cutoff"] <= limits["maxRecordWork"],
                     "Selected checkpoint acquisition limits exceeded before dependency decoding")
            for ref in refs:
                name = ref["id"] + ".segment"
                _require(name in images and len(images[name]) == ref["bytes"], "Missing or wrong-length dependency")
                referenced.add(name)
                cached = segment_cache.get(name)
                if cached is not None:
                    _require(cached[0] == images[name] and cached[1]["first"] == ref["first"]
                             and cached[1]["last"] == ref["last"], "Reused segment image/range differs")
                else:
                    decoded = _decode_segment(images[name], header, frames, ref["first"], ref["last"], budget)
                    segment_cache[name] = (images[name], decoded)
        _require(referenced == segment_names, "Unreferenced or missing retained segment image")
        if shape == "CHECKPOINT_TAIL":
            source_files = images
        if shape == "COMPACTED_CHECKPOINT_TAIL":
            _require(source_files is not None and all(images.get(name) == image for name, image in source_files.items()),
                     "Compacted fixture does not retain exact source objects")
        selected = manifests[max(manifests)] if manifests else None
        selected_bytes = 0 if selected is None else sum(ref["bytes"] for ref in selected["references"])
        if selected is not None:
            _require(selected["bytes"] + selected_bytes <= limits["maxObjectBytes"]
                     and selected["prefixBytes"] <= limits["maxJournalBytes"]
                     and 2 * selected["cutoff"] <= limits["maxRecordWork"], "Selected checkpoint acquisition limits exceeded")
        summaries.append(dict(shape=shape, checkpointSequence=None if selected is None else selected["cutoff"],
                prefixBytes=0 if selected is None else selected["prefixBytes"],
                prefixSha256=None if selected is None else selected["prefixSha256"],
                selectedManifestBytes=0 if selected is None else selected["bytes"],
                selectedSegmentBytes=selected_bytes, selectedSegments=0 if selected is None else len(selected["references"]),
                initialFileSha256=dict(sorted(hashes.items()))))
    _require(budget[0] <= MAX_DECODE_RECORDS, "Aggregate decoded record admission exceeded")
    return dict(schemaVersion=1, scope="RECORDED_RECOVERY_IMAGES", lineage="workload-v1", commits=commits,
                headerBytes=len(header), encodedHistoryBytes=len(original), historyFingerprint=history_hash,
                checkpointSequence=cutoff, checkpointPrefixBytes=len(prefix), checkpointPrefixSha256=prefix_hash,
                continuationFrameBytes=len(continuation), continuationFrameSha256=_sha(continuation),
                continuedJournalBytes=len(original) + len(continuation), continuedJournalSha256=_sha(original + continuation),
                rawFixtureBytes=raw_bytes, decodedRecordWork=budget[0], fixtures=summaries)
