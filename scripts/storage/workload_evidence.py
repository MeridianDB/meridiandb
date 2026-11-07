"""Strict admission of settled, run-bound developer workload evidence.

This module does not launch processes. Recorded validation recomputes declarations
using supplied historical context without touching original paths or the current
platform. Live validation additionally reobserves files after the supervisor has
settled the worker/group and checked source stability. Neither mode authenticates
a hostile writer or qualifies a storage device.
"""

from collections import Counter, defaultdict
import hashlib
from itertools import islice
import json
import math
import os
from pathlib import Path, PurePosixPath
import platform
import re
import stat

from attributed_workload import AttributionError, MODE as ATTRIBUTED_MODE, request_budget, validate_attribution


MAX_DOCUMENT_BYTES = 16 * 1024 * 1024
MAX_DEPTH = 32
MAX_NODES = 500_000
MAX_STRING_UNITS = 4096
MAX_RETAINED_BYTES = 128 * 1024 * 1024
MAX_FILES = 1024
MAX_HEAP_BYTES = 256 * 1024 * 1024
MAX_JOURNAL_BYTES = 16 * 1024 * 1024
MAX_IO_REASON_UNITS = 1024
MAX_REFERENCE_WORK = 32_000_000
MAX_ITERATION_ACTIONS = 20_000
DIMENSION_FIELDS = ("entities", "commits", "queries", "intervalWidth")
DEFAULT_DIMENSIONS = dict(zip(DIMENSION_FIELDS, (16, 128, 64, 8)))
LONG_MAX = (1 << 63) - 1
LONG_MIN = -(1 << 63)
PHASES = ("LOAD", "MIXED", "MAINTENANCE")
KINDS = ("CREATE", "COMMIT", "RETRY", "GET", "SCAN", "SELECT", "FILTER_SCAN", "SUM",
         "JOURNAL_REOPEN", "SEGMENT_WRITE", "CHECKPOINT_PUBLISH", "COMPACTION_PLAN",
         "COMPACTION_PUBLISH", "CHECKPOINT_REOPEN")
IO_FIELDS = ("rchar", "wchar", "syscr", "syscw", "read_bytes", "write_bytes", "cancelled_write_bytes")
INTENTS = ("SEED", "APPEND", "CORRECTION", "RETRACTION", "REPEAT", "RETRY")
READ_COUNTS = {"GET": 13, "SCAN": 13, "SELECT": 13, "FILTER_SCAN": 13, "SUM": 12}
PROFILES = {
    "APPEND": (0, 0, 0, 5, 0, 50),
    "CORRECTIONS": (70, 10, 10, 10, 10, 75),
    "RETRACTIONS": (20, 60, 10, 10, 10, 50),
    "HOT_ENTITY": (60, 10, 10, 20, 95, 80),
}
SPEC_FIELDS = ("seed", "entities", "commits", "queries", "intervalWidth", "correctionPercent",
               "retractionPercent", "repeatPercent", "retryPercent", "hotEntityPercent", "historicalReadPercent")
SAMPLE_FIELDS = {"phase", "kind", "elapsedNanos", "threadCpuNanos", "threadAllocatedBytes",
                 "heapUsedBeforeBytes", "heapUsedAfterBytes"}
SUMMARY_FIELDS = {"phase", "kind", "count", "totalNanos", "minNanos", "p50Nanos", "p95Nanos",
                  "p99Nanos", "maxNanos", "serviceOperationsPerSecond", "threadCpuNanos",
                  "threadCpuSamples", "threadAllocatedBytes", "threadAllocatedSamples", "maxSampledHeapUsedBytes"}
ITERATION_FIELDS = {"index", "warmup", "directory", "namespacePreparationNanos", "actionLoopNanos",
                    "actionServiceNanos", "actionValidationNanos", "actionOtherNanos",
                    "actionLoopOperationsPerSecond", "totalValidationNanos", "validationTasks",
                    "sourceSegments", "replacementSegments", "pinnedReadersDuringPublication",
                    "namedStorage", "samples", "summaries"}
WORKLOAD_FIELDS = {"attempts", "commits", "retries", "writeOperations", "reads", "noOpCommits",
                   "putOperations", "retractOperations", "noOpPuts", "noOpRetractions", "putsReplacingPresent",
                   "retractionsRemovingPresent", "geometricOverlaps", "entityTouches", "scheduledIntents",
                   "readOperators", "historicalReads", "headReads", "pointHits", "pointMisses", "emptyResults",
                   "nonemptyResults"}
RESULT_FIELDS = {"schemaVersion", "corpusVersion", "spec", "config", "fingerprint", "historyFingerprint",
                 "encodedHistoryBytes", "workload", "runtime", "runRoot", "preparationNanos", "iterations", "gaps"}
RUNTIME_FIELDS = {"javaVersion", "javaRuntimeVersion", "javaVendor", "osName", "osVersion", "architecture",
                  "availableProcessors", "maxHeapBytes", "fileStoreType"}
ENVELOPE_FIELDS = {"schemaVersion", "runId", "source", "profile", "seed", "workerPid", "status", "error", "io", "result"}
REQUEST_FIELDS = {"runId", "source", "profile", "seed", "warmups", "repetitions"}
SCALED_REQUEST_FIELDS = REQUEST_FIELDS | {"schemaVersion", "dimensions"}
ATTRIBUTED_REQUEST_FIELDS = SCALED_REQUEST_FIELDS | {"mode"}
CONTROL_NAMES = {".meridiandb-checkpoints.lock", ".meridiandb-readers.lock"}
MANIFEST_NAME = re.compile(r"checkpoint-([0-9]{19})-([0-9a-f]{64})\.manifest")
SEGMENT_NAME = re.compile(r"([0-9a-f]{64})\.segment")


class EvidenceError(ValueError):
    """Incomplete, contradictory, excessive or out-of-scope evidence."""


def _require(condition, message):
    if not condition:
        raise EvidenceError(message)


def _integer(value, name, low=0, high=LONG_MAX):
    _require(type(value) is int and low <= value <= high, f"Invalid integer: {name}")
    return value


def _text(value, name, limit=MAX_STRING_UNITS, pattern=None):
    _require(type(value) is str and bool(value.strip()) and len(value) <= limit, f"Invalid text: {name}")
    _require(len(value.encode("utf-16-be", "surrogatepass")) // 2 <= limit, f"Text exceeds bound: {name}")
    if pattern is not None:
        _require(re.fullmatch(pattern, value) is not None, f"Invalid identity: {name}")
    return value


def _object(value, keys, name):
    _require(type(value) is dict and set(value) == set(keys), f"Wrong object fields: {name}")
    return value


def _list(value, name, low=0, high=MAX_NODES):
    _require(type(value) is list and low <= len(value) <= high, f"Invalid list: {name}")
    return value


def validate_dimensions(dimensions, warmups, repetitions):
    """Admit a scaled v1 corpus and conservative total work before storage effects."""
    _object(dimensions, DIMENSION_FIELDS, "dimensions")
    warmups = _integer(warmups, "warmups", 0, 2)
    repetitions = _integer(repetitions, "repetitions", 1, 5)
    entities = _integer(dimensions["entities"], "entities", 1, 1024)
    commits = _integer(dimensions["commits"], "commits", entities + 1, 4096)
    queries = _integer(dimensions["queries"], "queries", 0, 1024)
    width = _integer(dimensions["intervalWidth"], "intervalWidth", 1, 16)
    work = commits * commits * (width + 1) + commits * queries * (width + min(entities, 16) + 2)
    _require(work <= MAX_REFERENCE_WORK, "Workload reference-work admission exceeded")
    _require((2 * commits + queries) * (warmups + repetitions) <= MAX_ITERATION_ACTIONS,
             "Conservative iteration action admission exceeded")
    return dict(zip(DIMENSION_FIELDS, (entities, commits, queries, width)))


def validate_request(request):
    """Validate exact legacy/scaled request bindings and return checked dimensions.

    Legacy six-field requests imply envelope schema 1 and the original defaults.
    Scaled eight-field requests explicitly select schema 2. Schema 3 adds an
    explicit attributed mode; the inner corpus/measurement result remains v1.
    """
    scaled = type(request) is dict and "schemaVersion" in request
    attributed = scaled and request.get("schemaVersion") == 3
    _object(request, ATTRIBUTED_REQUEST_FIELDS if attributed else SCALED_REQUEST_FIELDS if scaled else REQUEST_FIELDS, "request")
    _text(request["runId"], "runId", 32, r"[0-9a-f]{32}")
    _text(request["source"], "source", 40, r"[0-9a-f]{40}")
    _require(type(request["profile"]) is str and request["profile"] in PROFILES, "Unknown profile")
    _integer(request["seed"], "seed", LONG_MIN)
    warmups = _integer(request["warmups"], "warmups", 0, 2)
    repetitions = _integer(request["repetitions"], "repetitions", 1, 5)
    if scaled:
        _integer(request["schemaVersion"], "scaled request schema", 3 if attributed else 2, 3 if attributed else 2)
        dimensions = validate_dimensions(request["dimensions"], warmups, repetitions)
        if attributed:
            _require(request["mode"] == ATTRIBUTED_MODE, "Wrong attributed request mode")
            try:
                request_budget(request["profile"], request["seed"], dimensions, warmups, repetitions)
            except AttributionError as error:
                raise EvidenceError(str(error)) from error
        return dimensions
    return dict(DEFAULT_DIMENSIONS)


def _read_counts(queries):
    return {kind: queries // 5 + (index < queries % 5) for index, kind in enumerate(READ_COUNTS)}


def _source_segments(commits):
    chunk = min(16, (commits + 1) // 2)
    return (commits + chunk - 1) // chunk


def _structure(document):
    pending = [(document, 0)]
    nodes = 0
    while pending:
        value, depth = pending.pop()
        nodes += 1
        _require(depth <= MAX_DEPTH and nodes <= MAX_NODES, "JSON structure admission exceeded")
        if type(value) is dict:
            for key, item in value.items():
                _require(type(key) is str, "JSON object key must be text")
                pending.append((key, depth + 1))
                pending.append((item, depth + 1))
        elif type(value) is list:
            pending.extend((item, depth + 1) for item in value)
        elif type(value) is str:
            _require(len(value) <= MAX_STRING_UNITS and
                     len(value.encode("utf-16-be", "surrogatepass")) // 2 <= MAX_STRING_UNITS,
                     "JSON string admission exceeded")
        elif type(value) is int:
            _integer(value, "JSON integer", LONG_MIN)
        elif type(value) is float:
            _require(math.isfinite(value), "Nonfinite JSON number")
        else:
            _require(value is None or type(value) is bool, "Unsupported JSON value")


def parse_document(data: bytes) -> dict:
    """Decode bounded UTF-8 JSON, refusing ambiguous keys and excessive structure."""
    _require(type(data) is bytes and 0 < len(data) <= MAX_DOCUMENT_BYTES, "JSON byte admission exceeded")
    try:
        text = data.decode("utf-8")
    except UnicodeDecodeError as error:
        raise EvidenceError("Evidence must be UTF-8 JSON") from error
    # Bound nesting before the decoder allocates a deeply recursive structure.
    depth = nodes = string_source_units = 0
    quoted = escaped = False
    scalar = False
    for char in text:
        if quoted:
            string_source_units += 1
            _require(string_source_units <= 6 * MAX_STRING_UNITS + 1, "JSON string admission exceeded")
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == '"':
                quoted = False
        elif char == '"':
            quoted = True
            scalar = False
            string_source_units = 0
            nodes += 1
        elif char in "[{":
            depth += 1
            scalar = False
            nodes += 1
            _require(depth <= MAX_DEPTH + 1, "JSON depth admission exceeded")
        elif char in "]}":
            depth -= 1
            scalar = False
            _require(depth >= 0, "Malformed JSON nesting")
        elif char in ",: \t\r\n":
            scalar = False
        elif not scalar:
            scalar = True
            nodes += 1
        _require(nodes <= MAX_NODES, "JSON node admission exceeded")

    def unique(pairs):
        result = {}
        for key, value in pairs:
            _require(key not in result, "Duplicate JSON evidence key")
            result[key] = value
        return result

    def number(raw):
        _require(len(raw.lstrip("-")) <= 19, "JSON integer exceeds signed-long admission")
        return _integer(int(raw), "JSON integer", LONG_MIN)

    def constant(_raw):
        raise EvidenceError("Nonfinite JSON evidence constant")

    try:
        document = json.loads(text, object_pairs_hook=unique, parse_int=number, parse_constant=constant)
    except (ValueError, RecursionError) as error:
        if isinstance(error, EvidenceError):
            raise
        raise EvidenceError("Malformed JSON evidence") from error
    _require(type(document) is dict, "Worker evidence must be an object")
    _structure(document)
    return document


def _rate(value, count, nanos, name):
    if nanos == 0:
        _require(value is None, f"Zero-time rate must be unavailable: {name}")
    else:
        _require(type(value) in (int, float) and math.isfinite(value) and value >= 0, f"Invalid rate: {name}")
        _require(math.isclose(value, count * 1_000_000_000.0 / nanos, rel_tol=1e-12, abs_tol=1e-12),
                 f"Rate differs from its denominator: {name}")


def _counts(value, keys, name, maximum=LONG_MAX):
    _object(value, keys, name)
    for key, count in value.items():
        _integer(count, f"{name}.{key}", high=maximum)
    return value


def _workload(value, profile, dimensions):
    _object(value, WORKLOAD_FIELDS, "workload")
    entities, commits, queries, _width = (dimensions[k] for k in DIMENSION_FIELDS)
    mixed = commits - entities
    read_counts = _read_counts(queries)
    for key in WORKLOAD_FIELDS - {"entityTouches", "scheduledIntents", "readOperators"}:
        _integer(value[key], f"workload.{key}", high=2 * commits - entities + queries)
    w = value
    _require(w["commits"] == w["writeOperations"] == commits and w["reads"] == queries, "Wrong workload dimensions")
    _require(w["retries"] <= mixed and w["attempts"] == commits + w["retries"], "Wrong request/retry counts")
    _require(w["putOperations"] >= entities and w["putOperations"] + w["retractOperations"] == commits, "Wrong write-kind counts")
    _require(w["noOpCommits"] == w["noOpPuts"] + w["noOpRetractions"], "Wrong no-op counts")
    _require(w["noOpPuts"] <= w["putsReplacingPresent"] <= w["putOperations"], "Wrong present-put counts")
    _require(w["noOpRetractions"] + w["retractionsRemovingPresent"] == w["retractOperations"], "Wrong retraction effects")
    _require(w["putsReplacingPresent"] + w["retractionsRemovingPresent"] <= w["geometricOverlaps"] <= mixed,
             "Wrong geometric-overlap counts")
    touches = _counts(w["entityTouches"], {f"e-{i}" for i in range(entities)}, "entityTouches", mixed + 1)
    _require(all(touches.values()) and sum(touches.values()) == commits, "Wrong entity-touch counts")
    intents = _counts(w["scheduledIntents"], INTENTS, "scheduledIntents", max(entities, mixed))
    _require(intents["SEED"] == entities and intents["RETRY"] == w["retries"]
             and all(intents[k] <= mixed for k in ("APPEND", "CORRECTION", "RETRACTION", "REPEAT"))
             and sum(intents.values()) == w["attempts"], "Wrong intent counts")
    operators = _counts(w["readOperators"], READ_COUNTS, "readOperators", queries)
    _require(operators == read_counts, "Wrong generated query mix")
    _require(w["historicalReads"] + w["headReads"] == queries and w["pointHits"] + w["pointMisses"] == read_counts["GET"]
             and w["emptyResults"] + w["nonemptyResults"] == queries, "Wrong read-result denominators")
    _require(w["pointMisses"] <= w["emptyResults"] and w["pointHits"] <= w["nonemptyResults"], "Wrong point-result counts")
    if profile == "APPEND":
        _require(intents["APPEND"] == mixed and all(intents[k] == 0 for k in ("CORRECTION", "RETRACTION", "REPEAT"))
                 and w["retractOperations"] == w["noOpCommits"] == w["geometricOverlaps"] == 0,
                 "Append profile contains non-append effects")
    return w["attempts"] + queries


def _samples_and_summaries(iteration, workload, max_heap, dimensions, source_segments):
    samples = _list(iteration["samples"], "samples", 1, 100_000)
    groups = defaultdict(list)
    for sample in samples:
        _object(sample, SAMPLE_FIELDS, "sample")
        _require(sample["phase"] in PHASES and sample["kind"] in KINDS, "Unknown sample phase/kind")
        for key in ("elapsedNanos", "heapUsedBeforeBytes", "heapUsedAfterBytes"):
            _integer(sample[key], f"sample.{key}")
        _require(sample["heapUsedBeforeBytes"] <= max_heap and sample["heapUsedAfterBytes"] <= max_heap,
                 "Heap sample exceeds actual worker heap limit")
        for key in ("threadCpuNanos", "threadAllocatedBytes"):
            if sample[key] is not None:
                _integer(sample[key], f"sample.{key}")
        groups[(sample["phase"], sample["kind"])].append(sample)
    entities, commits, queries = (dimensions[k] for k in ("entities", "commits", "queries"))
    wanted = {("LOAD", "CREATE"): 1, ("LOAD", "COMMIT"): entities, ("MIXED", "COMMIT"): commits - entities,
              **{("MIXED", k): v for k, v in _read_counts(queries).items() if v},
              **{("MAINTENANCE", k): 1 for k in ("JOURNAL_REOPEN", "CHECKPOINT_PUBLISH", "COMPACTION_PLAN",
                                                 "COMPACTION_PUBLISH", "CHECKPOINT_REOPEN")},
              ("MAINTENANCE", "SEGMENT_WRITE"): source_segments}
    if workload["retries"]:
        wanted[("MIXED", "RETRY")] = workload["retries"]
    _require({key: len(value) for key, value in groups.items()} == wanted, "Wrong operation inventory")
    actions = workload["attempts"] + workload["reads"]
    order = [(s["phase"], s["kind"]) for s in samples]
    _require(order[:entities + 1] == [("LOAD", "CREATE")] + [("LOAD", "COMMIT")] * entities, "Wrong setup sample order")
    _require(all(phase == "MIXED" for phase, _kind in order[entities + 1:1 + actions]), "Wrong mixed sample order")
    tail = ["JOURNAL_REOPEN"] + ["SEGMENT_WRITE"] * source_segments + ["CHECKPOINT_PUBLISH", "COMPACTION_PLAN", "COMPACTION_PUBLISH", "CHECKPOINT_REOPEN"]
    _require(order[1 + actions:] == [("MAINTENANCE", kind) for kind in tail], "Wrong maintenance sample order")
    summaries = _list(iteration["summaries"], "summaries", 1, len(PHASES) * len(KINDS))
    expected_order = sorted(groups, key=lambda k: (PHASES.index(k[0]), KINDS.index(k[1])))
    _require(len(summaries) == len(expected_order), "Wrong summary inventory")
    for summary, key in zip(summaries, expected_order):
        _object(summary, SUMMARY_FIELDS, "summary")
        _require((summary["phase"], summary["kind"]) == key, "Wrong or duplicate summary group")
        observed = groups[key]
        elapsed = sorted(s["elapsedNanos"] for s in observed)
        total = _integer(sum(elapsed), "summary total")
        expected = {"count": len(observed), "totalNanos": total, "minNanos": elapsed[0], "maxNanos": elapsed[-1],
                    "maxSampledHeapUsedBytes": max(max(s["heapUsedBeforeBytes"], s["heapUsedAfterBytes"]) for s in observed)}
        for percent in (50, 95, 99):
            expected[f"p{percent}Nanos"] = elapsed[(percent * len(elapsed) + 99) // 100 - 1]
        for counter, availability in (("threadCpuNanos", "threadCpuSamples"), ("threadAllocatedBytes", "threadAllocatedSamples")):
            values = [s[counter] for s in observed if s[counter] is not None]
            expected[availability] = len(values)
            expected[counter] = _integer(sum(values), f"summary {counter}") if len(values) == len(observed) else None
        for name, wanted_value in expected.items():
            if wanted_value is None:
                _require(summary[name] is None, f"Unavailable summary counter was fabricated: {name}")
            else:
                _integer(summary[name], f"summary.{name}")
                _require(summary[name] == wanted_value, f"Summary differs from raw samples: {name}")
        _rate(summary["serviceOperationsPerSecond"], len(observed), total, "service rate")
    service = sum(s["elapsedNanos"] for s in samples if s["phase"] != "MAINTENANCE" and s["kind"] != "CREATE")
    _require(iteration["actionServiceNanos"] == service, "Action service differs from raw samples")


def _directory(path, name):
    try:
        _require(path.is_absolute() and not path.is_symlink() and stat.S_ISDIR(path.lstat().st_mode)
                 and path.resolve(strict=True) == path, f"Noncanonical or nonreal directory: {name}")
    except (OSError, ValueError, UnicodeError) as error:
        if isinstance(error, EvidenceError):
            raise
        raise EvidenceError(f"Cannot inspect directory: {name}") from error
    return path


def _path(value, name):
    value = _text(value, name)
    _require(not any(ord(char) < 32 or ord(char) == 127 for char in value), f"Control character in path: {name}")
    path = Path(value)
    _require(path.is_absolute() and str(path) == value and ".." not in path.parts, f"Noncanonical path: {name}")
    return path


def _recorded_path(value, name):
    """Canonical Linux path syntax only; existence and native identity are unknown."""
    value = _text(value, name)
    _require(not any(ord(char) < 32 or ord(char) == 127 for char in value), f"Control character in path: {name}")
    path = PurePosixPath(value)
    _require(path.is_absolute() and path.anchor == "/" and str(path) == value and ".." not in path.parts,
             f"Noncanonical recorded path: {name}")
    return path


def _entries(path, maximum):
    with os.scandir(path) as entries:
        result = [Path(entry.path) for entry in islice(entries, maximum + 1)]
    _require(len(result) <= maximum, "Directory entry admission exceeded")
    return result


def _file_declaration(named, history_bytes, source_segments):
    """Check declared names/lengths, without observing files or trusting their bytes."""
    _object(named, {"fileLengths", "totalBytes"}, "namedStorage")
    lengths = named["fileLengths"]
    file_count = source_segments + 7
    _require(type(lengths) is dict and len(lengths) == file_count, "Wrong retained file inventory")
    for key, value in lengths.items():
        _text(key, "filename", 128)
        _require(PurePosixPath(key).name == key and key not in (".", ".."), "Unsafe file name")
        _integer(value, "file length", high=MAX_RETAINED_BYTES)
    manifests = [MANIFEST_NAME.fullmatch(name) for name in lengths if MANIFEST_NAME.fullmatch(name)]
    segments = [name for name in lengths if SEGMENT_NAME.fullmatch(name)]
    _require(len(manifests) == 3 and sorted(int(m[1]) for m in manifests) == [1, 2, 3]
             and len(segments) == source_segments + 1 and set(lengths) == CONTROL_NAMES | {"transactions.journal"}
             | {m[0] for m in manifests} | set(segments), "Wrong retained object names")
    _require(all(lengths[name] == 0 for name in CONTROL_NAMES)
             and lengths["transactions.journal"] == history_bytes
             and all(lengths[name] > 0 for name in segments + [m[0] for m in manifests]), "Wrong retained object lengths")
    _integer(named["totalBytes"], "named total", high=MAX_RETAINED_BYTES)
    _require(named["totalBytes"] == sum(lengths.values()), "Named total differs from files")
    return {"fileLengths": dict(sorted(lengths.items())), "logicalBytes": named["totalBytes"]}


def _files(home, named, history_hash, history_bytes, seen, device, source_segments):
    declared = _file_declaration(named, history_bytes, source_segments)
    lengths = declared["fileLengths"]
    file_count = source_segments + 7
    try:
        actual = _entries(home, file_count)
        _require(len(actual) == len(lengths) and {p.name for p in actual} == set(lengths), "On-disk inventory differs")
        files = []
        for path in sorted(actual):
            item = path.lstat()
            _require(stat.S_ISREG(item.st_mode) and item.st_nlink == 1, "Nonregular or multiply-linked workload file")
            _require(item.st_dev == device, "Workload file escaped the profiled filesystem")
            inode = (item.st_dev, item.st_ino)
            _require(inode not in seen, "Duplicate workload inode")
            seen.add(inode)
            _require(item.st_size == lengths[path.name], "Observed file length differs")
            _require(hasattr(item, "st_blocks"), "Allocated-block accounting unavailable")
            allocated = _integer(item.st_blocks, "st_blocks") * 512
            _require(allocated <= MAX_RETAINED_BYTES, "Allocated file bound exceeded")
            if path.name == "transactions.journal":
                digest = hashlib.sha256()
                count = 0
                with path.open("rb") as source:
                    while block := source.read(8192):
                        count += len(block)
                        _require(count <= MAX_JOURNAL_BYTES, "Journal hash byte admission exceeded")
                        digest.update(block)
                _require(count == history_bytes and digest.hexdigest() == history_hash, "Observed journal identity differs")
            files.append({"name": path.name, "logicalBytes": item.st_size, "attributedAllocatedBytes": allocated})
    except (OSError, ValueError, UnicodeError) as error:
        if isinstance(error, EvidenceError):
            raise
        raise EvidenceError("Cannot inspect retained workload files") from error
    return {"files": files, "logicalBytes": sum(f["logicalBytes"] for f in files),
            "attributedAllocatedBytes": sum(f["attributedAllocatedBytes"] for f in files)}


def _io(observation, index, warmup, directory, denominator):
    _object(observation, {"index", "warmup", "directory", "before", "after", "delta", "unavailable"}, "io")
    _integer(observation["index"], "I/O index")
    _require(type(observation["warmup"]) is bool and observation["index"] == index
             and observation["warmup"] == warmup and observation["directory"] == directory, "Wrong I/O iteration binding")
    for name in ("before", "after", "delta"):
        if observation[name] is not None:
            _counts(observation[name], IO_FIELDS, f"io.{name}")
    first, last, delta, reason = (observation[k] for k in ("before", "after", "delta", "unavailable"))
    if first is None:
        _text(reason, "I/O unavailability", MAX_IO_REASON_UNITS)
        prefix = "Before snapshot unavailable: "
        _require(reason.startswith(prefix) and reason[len(prefix):].strip() and delta is None,
                 "Wrong missing-before I/O evidence")
    elif last is None:
        _text(reason, "I/O unavailability", MAX_IO_REASON_UNITS)
        prefix = "After snapshot unavailable: "
        _require(reason.startswith(prefix) and reason[len(prefix):].strip() and delta is None,
                 "Wrong missing-after I/O evidence")
    else:
        reset = next((key for key in IO_FIELDS if last[key] < first[key]), None)
        if reset:
            _require(delta is None and reason == f"Nonmonotonic process I/O counter: {reset}", "Wrong reset I/O evidence")
        else:
            _require(reason is None and delta == {key: last[key] - first[key] for key in IO_FIELDS}, "Wrong I/O deltas")
    return {"delta": None if delta is None else dict(delta), "unavailable": reason,
            "processReadBytesPerEncodedJournalByte": None if delta is None else delta["read_bytes"] / denominator,
            "processWriteBytesPerEncodedJournalByte": None if delta is None else delta["write_bytes"] / denominator}


def validate_recorded_worker(doc, request: dict, worker_pid: int, stores: Path, mount: dict,
                             kernel: str, architecture: str) -> dict:
    """Purely recompute saved success declarations in their recorded Linux context.

    Paths are checked lexically as Linux paths and need not exist. Returned file
    lengths and fingerprints remain declarations; this function does not verify
    bytes, filesystem identity, allocation or the execution that produced them.
    """
    _structure(doc)
    dimensions = validate_request(request)
    scaled = "schemaVersion" in request
    attributed = request.get("schemaVersion") == 3
    warmups, repetitions = request["warmups"], request["repetitions"]
    source_segments = _source_segments(dimensions["commits"])
    _integer(worker_pid, "worker PID", 1)
    expected_fields = ENVELOPE_FIELDS | ({"dimensions"} if scaled else set())
    if attributed:
        expected_fields |= {"mode", "attribution"}
    _object(doc, expected_fields, "worker envelope")
    version = request.get("schemaVersion", 1)
    _integer(doc["schemaVersion"], "worker schema", version, version)
    if attributed:
        _require(doc["mode"] == ATTRIBUTED_MODE, "Wrong attributed worker mode")
    if scaled:
        actual_dimensions = _object(doc["dimensions"], DIMENSION_FIELDS, "worker dimensions")
        for key, expected in dimensions.items():
            _integer(actual_dimensions[key], f"worker dimensions.{key}")
            _require(actual_dimensions[key] == expected, "Wrong worker dimension binding")
    for key in ("runId", "source", "profile", "seed"):
        _require(type(doc[key]) is type(request[key]) and doc[key] == request[key], f"Wrong worker request binding: {key}")
    _integer(doc["workerPid"], "worker PID", 1)
    _require(doc["workerPid"] == worker_pid and doc["status"] == "success" and doc["error"] is None,
             "Worker is not the requested complete success")
    result = _object(doc["result"], RESULT_FIELDS, "result")
    for name in ("schemaVersion", "corpusVersion"):
        _integer(result[name], name, 1, 1)
    spec = _object(result["spec"], SPEC_FIELDS, "spec")
    expected_spec = dict(zip(SPEC_FIELDS, (request["seed"], *(dimensions[k] for k in DIMENSION_FIELDS),
                                          *PROFILES[request["profile"]])))
    for key, expected in expected_spec.items():
        _integer(spec[key], f"spec.{key}", LONG_MIN if key == "seed" else 0)
        _require(spec[key] == expected, "Wrong requested workload specification")
    config = _object(result["config"], {"warmups", "repetitions"}, "config")
    for key in config:
        _integer(config[key], f"config.{key}")
        _require(config[key] == request[key], "Wrong repetition configuration")
    for key in ("fingerprint", "historyFingerprint"):
        _text(result[key], key, 64, r"[0-9a-f]{64}")
    encoded = _integer(result["encodedHistoryBytes"], "encodedHistoryBytes", 1, MAX_JOURNAL_BYTES)
    _integer(result["preparationNanos"], "preparationNanos")
    action_count = _workload(result["workload"], request["profile"], dimensions)
    runtime = _object(result["runtime"], RUNTIME_FIELDS, "runtime")
    for key in RUNTIME_FIELDS - {"availableProcessors", "maxHeapBytes"}:
        _text(runtime[key], f"runtime.{key}", 512)
    _require(runtime["javaVersion"] == "25" or runtime["javaVersion"].startswith("25."), "Worker must use Java 25")
    _text(kernel, "recorded kernel", 512)
    _text(architecture, "recorded architecture", 512)
    aliases = {"amd64": "x86_64", "x86_64": "x86_64", "arm64": "aarch64", "aarch64": "aarch64"}
    _require(runtime["osName"] == "Linux" and runtime["osVersion"] == kernel
             and aliases.get(runtime["architecture"].lower(), runtime["architecture"].lower())
             == aliases.get(architecture.lower(), architecture.lower()), "Wrong worker OS/runtime placement")
    _require(type(mount) is dict and runtime["fileStoreType"] == mount.get("fileSystem"), "Wrong worker filesystem")
    _integer(runtime["availableProcessors"], "availableProcessors", 1, 1_000_000)
    _integer(runtime["maxHeapBytes"], "maxHeapBytes", 1, MAX_HEAP_BYTES)
    gaps = _list(result["gaps"], "gaps", 1, 32)
    for gap in gaps:
        _text(gap, "gap", 2048)
    _require(len(gaps) == len(set(gaps)), "Duplicate gap text")
    stores = _recorded_path(str(stores), "stores")
    root = _recorded_path(result["runRoot"], "runRoot")
    _require(root.parent == stores and root.name.startswith("mdb-workload-"), "Worker run escaped the fresh stores root")
    iterations = _list(result["iterations"], "iterations", warmups + repetitions, warmups + repetitions)
    io = _list(doc["io"], "io", len(iterations), len(iterations))
    accepted = []
    total_logical = total_files = 0
    for index, iteration in enumerate(iterations):
        _object(iteration, ITERATION_FIELDS, "iteration")
        _integer(iteration["index"], "iteration index")
        _require(iteration["index"] == index and type(iteration["warmup"]) is bool
                 and iteration["warmup"] == (index < warmups), "Wrong iteration index/warmup")
        home = _recorded_path(iteration["directory"], "iteration directory")
        _require(home == root / f"iteration-{index}", "Wrong iteration path")
        for key in ("namespacePreparationNanos", "actionLoopNanos", "actionServiceNanos", "actionValidationNanos",
                    "actionOtherNanos", "totalValidationNanos", "validationTasks", "sourceSegments",
                    "replacementSegments", "pinnedReadersDuringPublication"):
            _integer(iteration[key], key)
        _require(iteration["sourceSegments"] == source_segments and iteration["replacementSegments"] == 1
                 and iteration["pinnedReadersDuringPublication"] == 1
                 and iteration["validationTasks"] == action_count + source_segments + 8,
                 "Wrong checked lifecycle counts")
        _require(iteration["actionLoopNanos"] == iteration["actionServiceNanos"] + iteration["actionValidationNanos"]
                 + iteration["actionOtherNanos"] and iteration["totalValidationNanos"] >= iteration["actionValidationNanos"],
                 "Incoherent loop/validation timing")
        _rate(iteration["actionLoopOperationsPerSecond"], action_count, iteration["actionLoopNanos"], "action-loop rate")
        _samples_and_summaries(iteration, result["workload"], runtime["maxHeapBytes"], dimensions, source_segments)
        declared = _file_declaration(iteration["namedStorage"], encoded, source_segments)
        total_files += len(declared["fileLengths"])
        total_logical += declared["logicalBytes"]
        _require(total_files <= MAX_FILES and total_logical <= MAX_RETAINED_BYTES,
                 "Retained file/byte admission exceeded")
        accepted.append({"index": index, "warmup": iteration["warmup"], "directory": str(home),
                         "fileLengths": declared["fileLengths"], "logicalBytes": declared["logicalBytes"],
                         "processIo": _io(io[index], index, iteration["warmup"], str(home), encoded)})
    checked = {"encodedHistoryBytes": encoded, "historyFingerprint": result["historyFingerprint"],
            "sourceSegments": source_segments, "retainedFileCount": total_files,
            "totalLogicalBytes": total_logical, "iterations": accepted}
    if attributed:
        try:
            checked["attribution"] = validate_attribution(doc["attribution"], result)
        except AttributionError as error:
            raise EvidenceError(str(error)) from error
    return checked


def validate_worker(doc, request: dict, worker_pid: int, stores: Path, mount: dict) -> dict:
    """Validate success declarations, then reobserve the complete live file graph."""
    recorded = validate_recorded_worker(doc, request, worker_pid, stores, mount,
                                        platform.release(), platform.machine())
    stores = _directory(Path(stores), "stores")
    device = stores.lstat().st_dev
    root = _directory(_path(recorded["iterations"][0]["directory"], "iteration directory").parent, "runRoot")
    _require(root.parent == stores and root.lstat().st_dev == device,
             "Run root escaped the profiled filesystem")
    try:
        _require(_entries(stores, 1) == [root], "Fresh stores root contains unexpected entries")
        _require({p.name for p in _entries(root, len(recorded["iterations"]))}
                 == {f"iteration-{i}" for i in range(len(recorded["iterations"]))}, "Unexpected run directory entries")
    except OSError as error:
        raise EvidenceError("Cannot inspect live run topology") from error
    accepted = []
    seen = set()
    total_logical = total_allocated = total_files = 0
    for iteration in recorded["iterations"]:
        home = _directory(_path(iteration["directory"], "iteration directory"), "iteration directory")
        _require(home.lstat().st_dev == device, "Iteration escaped the profiled filesystem")
        named = {"fileLengths": iteration["fileLengths"], "totalBytes": iteration["logicalBytes"]}
        observed = _files(home, named, recorded["historyFingerprint"], recorded["encodedHistoryBytes"], seen,
                          device, recorded["sourceSegments"])
        total_files += len(observed["files"])
        total_logical += observed["logicalBytes"]
        total_allocated += observed["attributedAllocatedBytes"]
        _require(total_files <= MAX_FILES and total_logical <= MAX_RETAINED_BYTES and total_allocated <= MAX_RETAINED_BYTES,
                 "Retained file/byte admission exceeded")
        observed.update({"index": iteration["index"], "warmup": iteration["warmup"], "directory": str(home),
                         "processIo": iteration["processIo"]})
        accepted.append(observed)
    _require(total_files == recorded["retainedFileCount"] and total_logical == recorded["totalLogicalBytes"],
             "Live inventory differs from recorded declarations")
    checked = {"schemaVersion": 1, "retainedFileCount": total_files, "totalLogicalBytes": total_logical,
            "totalAttributedAllocatedBytes": total_allocated, "iterations": accepted,
            "limits": ["Allocated bytes are per-file st_blocks*512 attribution, not unique physical extents or reclaimable space",
                       "File totals exclude directory metadata, build outputs and supervisor logs",
                       "Process I/O covers each complete iteration, including checks, JVM activity and proc reads; not only timed calls",
                       "Process read/write ratios use encoded journal bytes; they are not device amplification or durability evidence",
                       "cancelled_write_bytes is retained separately and is not subtracted to invent net device traffic"]}
    if "attribution" in recorded:
        checked["attribution"] = recorded["attribution"]
    return checked
