"""Pure admission of complete recorded POSIX recovery comparison worker evidence."""

import hashlib
import json
from pathlib import PurePosixPath
import re

from recovery_corpus import METADATA_FIELDS, validate_corpus
from recovery_images import MAX_RAW_BYTES, SHAPES, validate_images
from recovery_operations import validate_operations


MAX_DOCUMENT_BYTES = 16 * 1024 * 1024
MAX_DEPTH = 32
MAX_NODES = 500_000
MAX_TEXT = 4096
LONG_MAX = (1 << 63) - 1
LONG_MIN = -(1 << 63)
PROTOCOL = "MERIDIANDB_RECOVERY_COMPARISON"
CONTROL = "RECOVERY_UNINSTRUMENTED"
CAPTURED = "CALLER_THREAD_RECOVERY_IO_INSTRUMENTED"
SCOPE = "Recorded recovery worker consistency"
PROFILES = {
    "APPEND": (0, 0, 0, 5, 0, 50),
    "CORRECTIONS": (70, 10, 10, 10, 10, 75),
    "RETRACTIONS": (20, 60, 10, 10, 10, 50),
    "HOT_ENTITY": (60, 10, 10, 20, 95, 80),
}
PERCENTAGES = ("correctionPercent", "retractionPercent", "repeatPercent", "retryPercent",
               "hotEntityPercent", "historicalReadPercent")
REQUEST_FIELDS = {"schemaVersion", "runId", "source", "mode", "profile", "seed",
                  "spec", "config", "limits", "scratch", "output"}
ENVELOPE_FIELDS = {"schemaVersion", "protocol", "mode", "request", "workerPid", "status", "error",
                   "failedRunRoot", "sourceHistory", "continuationFrameHex", "fixtureImages", "result"}
RESULT_FIELDS = {"schemaVersion", "mode", "spec", "config", "limits",
                 "runtime", "runRoot", "observations", "gaps", *METADATA_FIELDS}
RUNTIME_FIELDS = {"javaVersion", "javaRuntimeVersion", "javaVendor", "osName", "osVersion",
                  "architecture", "availableProcessors", "maxHeapBytes", "fileStoreType"}
ROW_FIELDS = {"iteration", "warmup", "strategyOrdinal", "shape", "access", "strategy", "directory",
              "fixture", "operations", "validationNanos", "teardownNanos",
              "closedJournalBytes", "closedJournalSha256"}


class RecoveryEvidenceError(ValueError):
    """Malformed, out-of-scope or contradictory recovery worker evidence."""


def _require(condition, message):
    if not condition:
        raise RecoveryEvidenceError(message)


def _integer(value, name, low=0, high=LONG_MAX):
    _require(type(value) is int and low <= value <= high, f"Invalid integer: {name}")
    return value


def _fields(value, keys, name):
    _require(type(value) is dict and len(value) == len(keys) and value.keys() == keys,
             f"Wrong fields: {name}")


def _text(value, name, limit=MAX_TEXT):
    _require(type(value) is str and 0 < len(value) <= limit
             and len(value.encode("utf-16-be", "surrogatepass")) <= 2 * limit
             and all(ord(c) >= 32 and ord(c) != 127 and not 0xD800 <= ord(c) <= 0xDFFF for c in value),
             f"Invalid text: {name}")
    return value


def _exact(actual, expected):
    if type(actual) is not type(expected):
        return False
    if type(expected) is dict:
        return actual.keys() == expected.keys() and all(_exact(actual[k], v) for k, v in expected.items())
    if type(expected) is list:
        return len(actual) == len(expected) and all(_exact(a, b) for a, b in zip(actual, expected))
    return actual == expected


def _lexical_limits(text):
    # Count containers, keys and values before allocating the decoded tree.
    # The JSON decoder below checks the complete grammar.
    depth = nodes = string_units = 0
    quoted = escaped = scalar = False
    for char in text:
        if quoted:
            string_units += 1
            _require(string_units <= 2 * MAX_RAW_BYTES + 1, "JSON string token exceeds admission")
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == '"':
                quoted = False
            continue
        if char == '"':
            quoted, scalar, string_units = True, False, 0
            nodes += 1
        elif char in "[{":
            depth += 1
            nodes += 1
            scalar = False
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


def _raw_limit(path):
    if path == ("sourceHistory", "headerHex"):
        return 2 * 65556
    if path == ("continuationFrameHex",) or len(path) == 3 and path[:2] == ("sourceHistory", "framesHex"):
        return 8192
    if len(path) == 4 and path[0] == "fixtureImages" and type(path[1]) is int and path[2] == "files":
        return 2 * MAX_RAW_BYTES
    return None


def _check_text_tree(value, path=()):
    _require(len(path) <= MAX_DEPTH, "JSON depth admission exceeded")
    if type(value) is dict:
        for key, item in value.items():
            _text(key, "JSON object key")
            _check_text_tree(item, path + (key,))
    elif type(value) is list:
        for index, item in enumerate(value):
            _check_text_tree(item, path + (index,))
    elif type(value) is str:
        raw_limit = _raw_limit(path)
        if raw_limit is None:
            _text(value, "JSON string")
        else:
            _require(len(value) <= raw_limit and len(value) % 2 == 0 and re.fullmatch(r"[0-9a-f]*", value) is not None,
                     "Invalid bounded lowercase image string")


def parse_document(data):
    """Decode bounded UTF-8 JSON with long strings only at binary image positions."""
    _require(type(data) is bytes and 0 < len(data) <= MAX_DOCUMENT_BYTES, "JSON byte admission exceeded")
    try:
        text = data.decode("utf-8")
    except UnicodeDecodeError as error:
        raise RecoveryEvidenceError("Evidence must be UTF-8 JSON") from error
    _lexical_limits(text)

    def unique(pairs):
        result = {}
        for key, value in pairs:
            _require(key not in result, "Duplicate JSON evidence key")
            result[key] = value
        return result

    def number(raw):
        _require(len(raw.lstrip("-")) <= 19, "JSON integer exceeds signed-long admission")
        return _integer(int(raw), "JSON integer", LONG_MIN)

    def unsupported_number(_):
        raise RecoveryEvidenceError("Recovery worker numeric values must be signed integers")

    try:
        value = json.loads(text, object_pairs_hook=unique, parse_int=number,
                           parse_float=unsupported_number, parse_constant=unsupported_number)
    except (json.JSONDecodeError, RecursionError) as error:
        raise RecoveryEvidenceError("Malformed JSON recovery worker evidence") from error
    _require(type(value) is dict, "Worker evidence must be a JSON object")
    _check_text_tree(value)
    return value


def _path(value, name):
    _text(value, name)
    path = PurePosixPath(value)
    _require(value.startswith("/") and not value.startswith("//") and str(path) == value
             and ".." not in path.parts, f"Require normalized absolute POSIX path: {name}")
    return path


def _request(expected):
    _fields(expected, REQUEST_FIELDS, "expected request")
    _integer(expected["schemaVersion"], "request schema", 1, 1)
    for key, size in (("source", 40), ("runId", 32)):
        _require(re.fullmatch(rf"[0-9a-f]{{{size}}}", _text(expected[key], key, size)) is not None,
                 f"Invalid expected {key}")
    _require(type(expected["mode"]) is str and expected["mode"] in (CONTROL, CAPTURED),
             "Unknown recovery measurement mode")
    profile = _text(expected["profile"], "profile")
    _require(profile in PROFILES, "Unknown corpus profile")
    seed = _integer(expected["seed"], "seed", LONG_MIN)
    spec = expected["spec"]
    _require(type(spec) is dict and type(spec.get("seed")) is int and spec["seed"] == seed
             and all(type(spec.get(k)) is int and spec[k] == n for k, n in zip(PERCENTAGES, PROFILES[profile])),
             "Expected seed/spec or profile/spec differs")
    # The image consumer owns the complete spec, limits and matrix work admission.
    scratch, output = (_path(expected[name], name) for name in ("scratch", "output"))
    _require(scratch != output and scratch != scratch.parent and output.parent == scratch.parent
             and re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,127}", output.name) is not None
             and output.name != "worker.partial.json", "Worker output must be a distinct safe sibling")
    return scratch


def _runtime(value, expected):
    _fields(value, RUNTIME_FIELDS, "worker runtime")
    _require(type(expected) is dict and 0 < len(expected) <= len(RUNTIME_FIELDS)
             and expected.keys() <= RUNTIME_FIELDS, "Require independent runtime expectations")
    for name in RUNTIME_FIELDS - {"availableProcessors", "maxHeapBytes"}:
        _text(value[name], name, 512)
    _require(re.fullmatch(r"25(?:[.+-].+)?", value["javaVersion"]) is not None, "Worker must use Java 25")
    _integer(value["availableProcessors"], "available processors", 1, (1 << 31) - 1)
    _integer(value["maxHeapBytes"], "maximum JVM heap", 1)
    _require(all(_exact(value[k], expected[k]) for k in expected), "Runtime differs from independent expectations")
    return sorted(expected)


def _matrix(result, request, scratch, images):
    root = _path(result["runRoot"], "run root")
    _require(root.parent == scratch and re.fullmatch(r"mdb-recovery-[A-Za-z0-9_-]{1,128}", root.name) is not None,
             "Run root must be an owned child of the recorded scratch path")
    warmups = request["config"]["warmups"]
    repetitions = request["config"]["repetitions"]
    rows = result["observations"]
    _require(type(rows) is list and len(rows) == 16 * (warmups + repetitions),
             "Incomplete recovery observation matrix")
    denominators = []
    orders = {"warmup": [], "measured": []}
    index = 0
    for iteration in range(warmups + repetitions):
        warmup = iteration < warmups
        order_index = iteration if warmup else iteration - warmups
        order = ["ORDINARY", "HISTORY_REUSE"]
        if order_index % 2:
            order.reverse()
        orders["warmup" if warmup else "measured"].append(order)
        for shape, fixture in zip(SHAPES, images["fixtures"]):
            initial = {key: value for key, value in fixture.items() if key != "shape"}
            for access in ("READ_ONLY", "WRITABLE"):
                for ordinal, strategy in enumerate(order):
                    row = rows[index]
                    index += 1
                    _fields(row, ROW_FIELDS, "observation")
                    home = str(root / f"{iteration}-{shape}-{access}-{strategy}")
                    coordinates = dict(iteration=iteration, warmup=warmup, shape=shape, access=access,
                                       strategyOrdinal=ordinal, strategy=strategy, directory=home)
                    _require(all(_exact(row[k], v) for k, v in coordinates.items()),
                             f"Wrong recovery observation order or path at row {index - 1}")
                    _require(_exact(row["fixture"], initial), "Observation differs from independent initial images")
                    writable = access == "WRITABLE"
                    _require(_exact(row["closedJournalBytes"], images["continuedJournalBytes" if writable else "encodedHistoryBytes"])
                             and _exact(row["closedJournalSha256"], images["continuedJournalSha256" if writable else "historyFingerprint"]),
                             "Closed journal differs from independent reconstruction")
                    _integer(row["validationNanos"], "validation clock")
                    _integer(row["teardownNanos"], "teardown clock")
                    row["operations"] = validate_operations(
                        row["operations"], mode=request["mode"], shape=shape, access=access, strategy=strategy,
                        max_heap=result["runtime"]["maxHeapBytes"], images=images, fixture=fixture)
                    denominators.append(dict(iteration=iteration, warmup=warmup, shape=shape, access=access,
                                             strategy=strategy, strategyOrdinal=ordinal,
                                             originalJournalBytes=images["encodedHistoryBytes"],
                                             selectedPrefixBytes=fixture["prefixBytes"],
                                             selectedManifestBytes=fixture["selectedManifestBytes"],
                                             selectedSegmentBytes=fixture["selectedSegmentBytes"],
                                             continuationFrameBytes=images["continuationFrameBytes"] if writable else None))
    return denominators, orders


def validate_worker(data, expected_request, *, worker_pid, expected_runtime):
    """Admit a complete recorded result against independent caller expectations.

    Supply the exact expected request, observed PID and available runtime facts.
    The returned binding names identify which runtime fields were compared. This
    function interprets recorded paths lexically and performs no file or process I/O.
    """
    scratch = _request(expected_request)
    _integer(worker_pid, "expected worker PID", 1)
    document = parse_document(data)
    _fields(document, ENVELOPE_FIELDS, "worker envelope")
    _integer(document["schemaVersion"], "worker schema", 1, 1)
    mode = expected_request["mode"]
    _require(document["protocol"] == PROTOCOL and document["mode"] == mode
             and _exact(document["workerPid"], worker_pid) and document["status"] == "success"
             and document["error"] is None and document["failedRunRoot"] is None,
             "Worker envelope is not the requested complete success")
    request = document["request"]
    _require(_exact(request, expected_request), "Wrong worker request binding")
    result = document["result"]
    _fields(result, RESULT_FIELDS, "worker result")
    _integer(result["schemaVersion"], "result schema", 1, 1)
    _require(result["mode"] == mode and all(_exact(result[k], request[k]) for k in ("spec", "config", "limits")),
             "Worker result differs from expected mode or input")
    runtime_bindings = _runtime(result["runtime"], expected_runtime)
    gaps = result["gaps"]
    _require(type(gaps) is list and 1 <= len(gaps) <= 32, "Invalid recorded scope notes")
    _require(len({_text(gap, "scope note", 2048) for gap in gaps}) == len(gaps), "Duplicate scope note")
    images = validate_images(document["sourceHistory"], document["continuationFrameHex"], document["fixtureImages"],
                             request["spec"], request["config"], request["limits"])
    corpus = validate_corpus(document["sourceHistory"], {k: result[k] for k in METADATA_FIELDS}, request["spec"])
    denominators, orders = _matrix(result, request, scratch, images)
    return dict(scope=SCOPE, workerSha256=hashlib.sha256(data).hexdigest(), workerBytes=len(data),
                request=request, workerPid=worker_pid, runtimeBindings=runtime_bindings,
                images=images, corpus=corpus, result=result, denominators=denominators, cohortOrders=orders)
