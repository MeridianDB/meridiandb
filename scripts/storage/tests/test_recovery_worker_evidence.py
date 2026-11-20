"""Recorded worker admission with complete native fixtures and independent caller bindings."""

import copy
import gzip
import hashlib
import json
from pathlib import Path
import sys
import unittest
from unittest import mock


STORAGE = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(STORAGE))
import recovery_worker_evidence as evidence


CONTROL = "RECOVERY_UNINSTRUMENTED"
CAPTURED = "CALLER_THREAD_RECOVERY_IO_INSTRUMENTED"
SHAPES = ("JOURNAL_ONLY", "GENESIS", "CHECKPOINT_TAIL", "COMPACTED_CHECKPOINT_TAIL")
FIXTURES = Path(__file__).with_name("fixtures")
PID = 4242
LONG_MAX = (1 << 63) - 1
CHECKED_FIELDS = {"scope", "workerSha256", "workerBytes", "request", "workerPid", "runtimeBindings",
                  "images", "corpus", "result", "denominators", "cohortOrders"}
RUNTIME = {"javaVersion": "25.0.2", "javaRuntimeVersion": "25.0.2+10-LTS-69", "javaVendor": "Oracle Corporation",
           "osName": "Mac OS X", "osVersion": "27.2", "architecture": "aarch64", "availableProcessors": 16,
           "maxHeapBytes": 268435456, "fileStoreType": "apfs"}

# Complete Java producer outputs, not fabricated outer envelopes. The first three
# came from .local-qa/recovery-corpus/mdb-0111/root/local-native-{control,captured,
# maximum-history}/worker.json; balanced came from
# .local-qa/recovery-worker/mdb-0112/root/local-native-captured-balanced/worker.json.
# Only run/source/PID and POSIX scratch/output/root/home labels were normalized.
# Original binary images, corpus facts, row order, clocks, JVM declarations and
# native counters are retained. These captures establish recorded consistency,
# not current files/processes, source authenticity, device traffic or timing gains.
# Original uncompressed source SHA-256 values, respectively:
# be09416672a776286936b4b15fd2df87e08d2fbabfb89618e7739e5827d1fbcd
# 95d9dfdaa41719d6d2489a632d368265a78e3857a01de9037dc7725705c24915
# 28ff4f4b13a3983598433422e3d9a563c16e8daebc0940a2158f7d00159003cb
# e88e80de25ef15bf278427e330149844741319f61611b7c4ff0f03348850b3fa
FIXTURE_HASHES = {
    "control": "536836194e2429a3c53c776433cd32d1d9914adb9c295c21671021d7eeac5cfa",
    "captured": "e227e01aa8694c2841dc467f7776600a07195cedf1d516f3cf896cc837325938",
    "maximum": "0738d67dc97f5b08b169b9a1e129b25f314e56cae7bcb8d1d6a39b640cd2a2c4",
    "balanced": "a2db1a06721b2944280082467653fbdfc25d854850ff3f44cf200efbc006aa35",
}


def fixture(name="control"):
    with gzip.open(FIXTURES / f"recovery-worker-{name}-v1.json.gz", "rb") as stream:
        return stream.read()


def runtime(name="control"):
    return dict(RUNTIME, osVersion="27.2.1") if name == "balanced" else dict(RUNTIME)


def request(name="control"):
    maximum = name == "maximum"
    percentages = (0, 0, 0, 5, 0, 50) if maximum else (70, 10, 10, 10, 10, 75)
    spec = dict(seed=42, entities=1 if maximum else 2, commits=496 if maximum else 7,
                queries=2 if maximum else 5, intervalWidth=1 if maximum else 2)
    spec.update(zip(("correctionPercent", "retractionPercent", "repeatPercent", "retryPercent",
                     "hotEntityPercent", "historicalReadPercent"), percentages))
    home = f"/recorded/recovery-worker/{name}"
    return dict(schemaVersion=1, runId={"control": "1", "captured": "2", "maximum": "3", "balanced": "4"}[name] * 32,
                source="a" * 40, mode=CONTROL if name == "control" else CAPTURED,
                profile="APPEND" if maximum else "CORRECTIONS", seed=42, spec=spec,
                config=dict(warmups=1 if name == "balanced" else 0, repetitions=2 if name == "balanced" else 1),
                limits=dict(maxObjectBytes=67108864, maxJournalBytes=67108864, maxRecordWork=262144),
                scratch=home + "/fixtures", output=home + "/worker.json")


def encode(document):
    return (json.dumps(document, separators=(",", ":"), ensure_ascii=True, allow_nan=False) + "\n").encode()


def zero_snapshot():
    return dict(schemaVersion=1, overflow=False, invalidResult=False, counts=[[0] * 14 for _ in range(8)])


class RecoveryWorkerDocumentTests(unittest.TestCase):
    def test_parser_preserves_utf8_escapes_signed_long_extremes_and_fresh_objects(self):
        raw = b'{"text":"snowman \\u2603 and \\ud83d\\ude00; [\\\"{}]", "low":-9223372036854775808,"high":9223372036854775807}'
        expected = {"text": 'snowman \u2603 and \U0001f600; ["{}]', "low": -(1 << 63), "high": LONG_MAX}
        parsed = evidence.parse_document(raw)
        self.assertEqual(expected, parsed)
        parsed.clear()
        self.assertEqual(expected, evidence.parse_document(raw))

    def test_parser_refuses_duplicate_decoded_keys_bad_utf8_nonnumeric_types_and_malformed_json(self):
        invalid = (b'', b'\xff', b'{', b'[]', b'null', b'{"x":1,"x":2}',
                   b'{"x":1,"\\u0078":2}', b'{"n":{"x":1,"x":2}}',
                   b'{"x":NaN}', b'{"x":Infinity}', b'{"x":-Infinity}', b'{"x":1e999}',
                   b'{"x":1.0}', b'{"x":9223372036854775808}', b'{"x":-9223372036854775809}',
                   b'{"x":999999999999999999999999999999999999999}', b'{"x":01}',
                   b'{"x":"\\ud800"}', b'{"x":"\\u0000"}', b'{"x":"\\u007f"}')
        for raw in invalid:
            with self.subTest(raw=raw), self.assertRaises(ValueError):
                evidence.parse_document(raw)
        for raw in (None, "{}", bytearray(b"{}"), memoryview(b"{}")):
            with self.subTest(type=type(raw)), self.assertRaises(ValueError):
                evidence.parse_document(raw)

    def test_byte_depth_node_and_utf16_text_boundaries_are_enforced(self):
        raw = b'{"x":1}'
        with mock.patch.object(evidence, "MAX_DOCUMENT_BYTES", len(raw)):
            self.assertEqual({"x": 1}, evidence.parse_document(raw))
        with mock.patch.object(evidence, "MAX_DOCUMENT_BYTES", len(raw) - 1), self.assertRaises(ValueError):
            evidence.parse_document(raw)
        with mock.patch.object(evidence, "MAX_NODES", 3):
            evidence.parse_document(raw)
            with self.assertRaises(ValueError): evidence.parse_document(b'{"x":[1]}')
        with mock.patch.object(evidence, "MAX_DEPTH", 2):
            evidence.parse_document(b'{"x":[1]}')
            with self.assertRaises(ValueError): evidence.parse_document(b'{"x":[[1]]}')
        evidence.parse_document(encode({"x": "a" * 4096}))
        evidence.parse_document(encode({"x": "\U0001f600" * 2048}))
        for value in ("a" * 4097, "\U0001f600" * 2049):
            with self.assertRaises(ValueError): evidence.parse_document(encode({"x": value}))

    def test_lexical_depth_nodes_and_extreme_tokens_refuse_before_json_materialization(self):
        cases = ((b'{"x":' + b'[' * 10000 + b'0' + b']' * 10000 + b'}', {"MAX_DEPTH": 32}),
                 (b'{"x":[0,0,0]}', {"MAX_NODES": 4}),
                 (b'{"x":"aaaaaaaa"}', {"MAX_RAW_BYTES": 2}))
        for raw, constants in cases:
            with self.subTest(constants=constants), mock.patch.multiple(evidence, **constants), \
                    mock.patch.object(evidence.json, "loads") as decoder:
                with self.assertRaises(ValueError): evidence.parse_document(raw)
                decoder.assert_not_called()

    def test_long_lowercase_raw_images_are_admitted_only_at_binary_field_locations(self):
        raw = fixture("maximum")
        document = evidence.parse_document(raw)
        long_image = max((value for supplied in document["fixtureImages"] for value in supplied["files"].values()), key=len)
        self.assertEqual(40484, len(long_image))
        ordinary = copy.deepcopy(document); ordinary["result"]["gaps"] = [long_image]
        with self.assertRaises(ValueError): evidence.parse_document(encode(ordinary))
        for document in ({"sourceHistory": {"framesHex": ["00" * 4097]}},
                         {"sourceHistory": {"headerHex": "00" * 65557}},
                         {"continuationFrameHex": "00" * 4097},
                         {"fixtureImages": [{"files": {"image.segment": "GG" * 2300}}]},
                         {"ordinary": "00" * 2049}):
            with self.subTest(keys=list(document)), self.assertRaises(ValueError):
                evidence.parse_document(encode(document))
        # Syntax parsing alone does not certify that these short bytes are valid images.
        self.assertEqual({"sourceHistory": {"framesHex": ["00" * 4096]}},
                         evidence.parse_document(encode({"sourceHistory": {"framesHex": ["00" * 4096]}})))


class RecoveryWorkerEvidenceTests(unittest.TestCase):
    def validate(self, name="control", document=None, expected=None, observed_pid=PID, bindings=None):
        return evidence.validate_worker(fixture(name) if document is None else encode(document),
                                        request(name) if expected is None else expected, worker_pid=observed_pid,
                                        expected_runtime=runtime(name) if bindings is None else bindings)

    def reject(self, name="control", document=None, expected=None, observed_pid=PID, bindings=None):
        expected = request(name) if expected is None else expected
        bindings = runtime(name) if bindings is None else bindings
        before = copy.deepcopy((document, expected, bindings))
        with self.assertRaises(ValueError):
            self.validate(name, document, expected, observed_pid, bindings)
        self.assertEqual(before, (document, expected, bindings))

    def test_complete_native_modes_bind_exact_caller_identities_hashes_and_derived_denominators(self):
        checked = {}
        for name in ("control", "captured"):
            with self.subTest(mode=name):
                raw = fixture(name)
                self.assertEqual(FIXTURE_HASHES[name], hashlib.sha256(raw).hexdigest())
                result = self.validate(name)
                self.assertEqual(CHECKED_FIELDS, set(result))
                self.assertEqual("Recorded recovery worker consistency", result["scope"])
                self.assertEqual(FIXTURE_HASHES[name], result["workerSha256"])
                self.assertEqual(len(raw), result["workerBytes"])
                self.assertEqual(PID, result["workerPid"])
                self.assertEqual(request(name), result["request"])
                self.assertEqual(sorted(RUNTIME), result["runtimeBindings"])
                self.assertEqual({"warmup": [], "measured": [["ORDINARY", "HISTORY_REUSE"]]}, result["cohortOrders"])
                self.assertEqual(16, len(result["result"]["observations"]))
                self.assertEqual("4969d6422ed6d8d63ce33353ceb6b98b0abce78f7916a55897b88c3abf45d491", result["corpus"]["fingerprint"])
                self.assertEqual("cd39d3afb4b025f0189d81d9c6a32584d7cbc3423e15b5e09eca84967a54800b", result["images"]["historyFingerprint"])
                self.assertEqual(588, result["images"]["encodedHistoryBytes"])
                self.assertEqual(156, result["images"]["continuationFrameBytes"])
                selected = {"JOURNAL_ONLY": (0, 0, 0), "GENESIS": (42, 154, 0),
                            "CHECKPOINT_TAIL": (276, 322, 600), "COMPACTED_CHECKPOINT_TAIL": (276, 210, 356)}
                denominators = []
                for shape in SHAPES:
                    for access in ("READ_ONLY", "WRITABLE"):
                        for ordinal, strategy in enumerate(("ORDINARY", "HISTORY_REUSE")):
                            prefix, manifest, segment = selected[shape]
                            denominators.append(dict(iteration=0, warmup=False, shape=shape, access=access,
                                                     strategy=strategy, strategyOrdinal=ordinal, originalJournalBytes=588,
                                                     selectedPrefixBytes=prefix, selectedManifestBytes=manifest,
                                                     selectedSegmentBytes=segment,
                                                     continuationFrameBytes=156 if access == "WRITABLE" else None))
                self.assertEqual(denominators, result["denominators"])
                checked[name] = result
        self.assertEqual(checked["control"]["images"], checked["captured"]["images"])
        self.assertEqual(checked["control"]["corpus"], checked["captured"]["corpus"])
        self.assertEqual(checked["control"]["denominators"], checked["captured"]["denominators"])

    def test_real_maximum_history_preserves_long_raw_object_strings_and_exact_40000_matrix_boundary(self):
        raw = fixture("maximum")
        self.assertEqual(FIXTURE_HASHES["maximum"], hashlib.sha256(raw).hexdigest())
        result = self.validate("maximum")
        self.assertEqual(496, result["images"]["commits"])
        self.assertEqual(40498, result["images"]["encodedHistoryBytes"])
        self.assertEqual(40000, 16 * (2 * (2 * 496 + 2) + 496 + 16))
        self.assertEqual(16, len(result["denominators"]))
        self.assertEqual("fd904ff79ae07f30c2a2744f39c2b4595f1a95193c639984c1786c0f545e9a4a", result["corpus"]["fingerprint"])

    def test_real_warmup_resets_measured_ab_ba_order_and_retains_all_forty_eight_cohorts(self):
        self.assertEqual(FIXTURE_HASHES["balanced"], hashlib.sha256(fixture("balanced")).hexdigest())
        result = self.validate("balanced")
        self.assertEqual({"warmup": [["ORDINARY", "HISTORY_REUSE"]],
                          "measured": [["ORDINARY", "HISTORY_REUSE"], ["HISTORY_REUSE", "ORDINARY"]]}, result["cohortOrders"])
        rows = result["result"]["observations"]
        self.assertEqual(48, len(rows))
        self.assertEqual(48, len(result["denominators"]))
        for iteration, expected in ((0, "ORDINARY"), (1, "ORDINARY"), (2, "HISTORY_REUSE")):
            self.assertEqual(iteration, rows[16 * iteration]["iteration"])
            self.assertEqual(iteration == 0, rows[16 * iteration]["warmup"])
            self.assertEqual(expected, rows[16 * iteration]["strategy"])

    def test_runtime_binding_subset_is_explicit_and_all_runtime_fields_still_require_valid_types(self):
        result = self.validate(bindings={"architecture": "aarch64", "javaVersion": "25.0.2"})
        self.assertEqual(["architecture", "javaVersion"], result["runtimeBindings"])
        for bindings in ({}, {"unknown": "value"}, {"javaVersion": "25.0.3"}, {"availableProcessors": True},
                         {"maxHeapBytes": float(RUNTIME["maxHeapBytes"])}, {"osVersion": "stale"}):
            self.reject(bindings=bindings)
        for field, invalid in (("javaVersion", "24.0.2"), ("availableProcessors", 0), ("availableProcessors", True),
                               ("maxHeapBytes", 0), ("maxHeapBytes", 1.0), ("fileStoreType", "")):
            document = json.loads(fixture()); document["result"]["runtime"][field] = invalid
            self.reject(document=document, bindings={"architecture": "aarch64"})

    def test_every_outer_and_nested_contract_rejects_missing_or_extra_fields(self):
        baseline = json.loads(fixture("captured"))
        locations = ((), ("request",), ("result",), ("result", "runtime"),
                     ("result", "observations", 0), ("result", "observations", 0, "operations", 0),
                     ("result", "observations", 0, "operations", 0, "measurement"),
                     ("result", "observations", 0, "operations", 0, "counters"))
        for path in locations:
            current = baseline
            for key in path: current = current[key]
            for omitted in current:
                document = copy.deepcopy(baseline); target = document
                for key in path: target = target[key]
                del target[omitted]
                with self.subTest(path=path, omitted=omitted): self.reject("captured", document)
            document = copy.deepcopy(baseline); target = document
            for key in path: target = target[key]
            target["unknown"] = None
            with self.subTest(path=path, extra=True): self.reject("captured", document)

    def test_stale_run_source_pid_mode_status_or_partial_envelope_cannot_be_accepted(self):
        mutations = (("request", "source", "b" * 40), ("request", "runId", "0" * 32),
                     ("request", "seed", 43), (None, "workerPid", PID + 1), (None, "workerPid", True),
                     (None, "schemaVersion", True), (None, "schemaVersion", 2),
                     (None, "protocol", "OTHER_PROTOCOL"), (None, "mode", CAPTURED),
                     (None, "status", "failed"), (None, "status", "unsupported"),
                     (None, "error", {"type": "IOException", "message": "failure"}),
                     (None, "failedRunRoot", "/recorded/retained"), ("result", "mode", CAPTURED),
                     ("result", "corpusVersion", 2), ("result", "schemaVersion", True))
        for parent, field, value in mutations:
            document = json.loads(fixture()); (document if parent is None else document[parent])[field] = value
            with self.subTest(parent=parent, field=field): self.reject(document=document)
        self.reject(observed_pid=PID + 1)
        self.reject(observed_pid=True)
        expected = request(); expected["source"] = "b" * 40
        self.reject(expected=expected)
        expected = request(); expected["runId"] = "9" * 32
        self.reject(expected=expected)

    def test_expected_request_itself_requires_exact_typed_fields_profile_scope_and_complete_work_admission(self):
        for omitted in request():
            expected = request(); del expected[omitted]
            self.reject(expected=expected)
        expected = request(); expected["unknown"] = 1
        self.reject(expected=expected)
        for section, field, invalid in ((None, "schemaVersion", True), (None, "mode", "UNINSTRUMENTED"),
                                        (None, "profile", "APPEND"), (None, "seed", True),
                                        ("spec", "commits", True), ("spec", "seed", 43),
                                        ("spec", "correctionPercent", 71), ("config", "warmups", False),
                                        ("config", "repetitions", 6), ("limits", "maxRecordWork", True),
                                        ("limits", "maxObjectBytes", -1)):
            expected = request(); target = expected if section is None else expected[section]; target[field] = invalid
            document = json.loads(fixture()); document["request"] = copy.deepcopy(expected)
            if section in ("spec", "config", "limits"): document["result"][section] = copy.deepcopy(expected[section])
            with self.subTest(section=section, field=field): self.reject(document=document, expected=expected)
        expected = request("maximum"); expected["spec"]["queries"] = 3
        document = json.loads(fixture("maximum")); document["request"] = copy.deepcopy(expected)
        document["result"]["spec"] = copy.deepcopy(expected["spec"])
        self.reject("maximum", document, expected)

    def test_nested_boolean_and_float_aliases_do_not_count_as_integer_metadata(self):
        paths = (("result", "observations", 0, "iteration"), ("result", "observations", 0, "strategyOrdinal"),
                 ("result", "observations", 0, "validationNanos"), ("result", "observations", 0, "teardownNanos"),
                 ("result", "observations", 4, "fixture", "checkpointSequence"),
                 ("result", "observations", 0, "fixture", "selectedSegments"),
                 ("result", "observations", 0, "operations", 0, "measurement", "elapsedNanos"),
                 ("result", "observations", 0, "operations", 0, "measurement", "threadCpuNanos"))
        for path in paths:
            document = json.loads(fixture()); target = document
            for key in path[:-1]: target = target[key]
            target[path[-1]] = False
            with self.subTest(path=path): self.reject(document=document)
        for path in (("result", "observations", 0, "warmup"), ("result", "observations", 0, "closedJournalBytes"),
                     ("result", "workload", "pointHits")):
            document = json.loads(fixture()); target = document
            for key in path[:-1]: target = target[key]
            target[path[-1]] = float(target[path[-1]])
            self.reject(document=document)

    def test_complete_matrix_inventory_coordinates_and_pair_order_are_required(self):
        for change in (lambda rows: rows.pop(), lambda rows: rows.append(copy.deepcopy(rows[0])),
                       lambda rows: rows.__setitem__(1, copy.deepcopy(rows[0])),
                       lambda rows: rows.__setitem__(slice(0, 2), list(reversed(rows[:2])))):
            document = json.loads(fixture()); change(document["result"]["observations"]); self.reject(document=document)
        for field, value in (("iteration", 1), ("warmup", True), ("strategyOrdinal", 1), ("shape", "GENESIS"),
                             ("access", "WRITABLE"), ("strategy", "HISTORY_REUSE")):
            document = json.loads(fixture()); document["result"]["observations"][0][field] = value
            self.reject(document=document)
        document = json.loads(fixture("balanced")); rows = document["result"]["observations"]
        # An incorrectly continued warmup counter would make first measured order BA.
        for pair in range(16, 32, 2):
            rows[pair:pair + 2] = list(reversed(rows[pair:pair + 2]))
            rows[pair]["strategyOrdinal"], rows[pair + 1]["strategyOrdinal"] = 0, 1
        self.reject("balanced", document)

    def test_posix_paths_are_lexical_normalized_owned_and_keep_output_a_safe_sibling(self):
        for field, invalid in (("scratch", "relative"), ("scratch", "//recorded/fixtures"),
                               ("scratch", "/recorded//fixtures"), ("scratch", "/recorded/./fixtures"),
                               ("scratch", "/recorded/../fixtures"), ("scratch", "/"),
                               ("output", "/other/worker.json"), ("output", request()["scratch"]),
                               ("output", "/recorded/recovery-worker/control/worker.partial.json"),
                               ("output", "/recorded/recovery-worker/control/.hidden")):
            expected = request(); expected[field] = invalid
            document = json.loads(fixture()); document["request"] = copy.deepcopy(expected)
            with self.subTest(field=field, invalid=invalid): self.reject(document=document, expected=expected)
        for invalid in ("relative", "/else/mdb-recovery-other", request()["scratch"] + "/run", request()["scratch"] + "/mdb-recovery-",
                        request()["scratch"] + "/mdb-recovery-" + "x" * 129,
                        request()["scratch"] + "/mdb-recovery-1/.."):
            document = json.loads(fixture()); document["result"]["runRoot"] = invalid
            self.reject(document=document)
        document = json.loads(fixture()); document["result"]["observations"][0]["directory"] += "/other"
        self.reject(document=document)

    def test_initial_fixture_and_closed_journal_claims_cannot_forge_derived_denominators(self):
        for field, value in (("checkpointSequence", 0), ("prefixBytes", 1), ("selectedManifestBytes", 1),
                             ("selectedSegmentBytes", 1), ("selectedSegments", 1), ("prefixSha256", "0" * 64)):
            document = json.loads(fixture()); document["result"]["observations"][0]["fixture"][field] = value
            self.reject(document=document)
        document = json.loads(fixture())
        document["result"]["observations"][0]["fixture"]["initialFileSha256"]["transactions.journal"] = "0" * 64
        self.reject(document=document)
        for field, invalid in (("closedJournalBytes", 587), ("closedJournalSha256", "0" * 64), ("closedJournalSha256", "A" * 64)):
            document = json.loads(fixture()); document["result"]["observations"][0][field] = invalid
            self.reject(document=document)
        for place in (None, "result"):
            document = json.loads(fixture()); (document if place is None else document[place])["denominators"] = []
            self.reject(document=document)

    def test_corpus_and_raw_image_mutations_fail_even_when_outer_request_matches(self):
        for field, value in (("fingerprint", "0" * 64), ("historyFingerprint", "0" * 64), ("encodedHistoryBytes", 589)):
            document = json.loads(fixture()); document["result"][field] = value
            self.reject(document=document)
        document = json.loads(fixture()); document["result"]["workload"]["pointHits"] += 1
        self.reject(document=document)
        document = json.loads(fixture()); document["sourceHistory"]["framesHex"][0] = document["sourceHistory"]["framesHex"][1]
        self.reject(document=document)
        document = json.loads(fixture()); document["continuationFrameHex"] = document["sourceHistory"]["framesHex"][0]
        self.reject(document=document)
        document = json.loads(fixture()); document["fixtureImages"].reverse()
        self.reject(document=document)

    def test_operations_require_complete_order_and_mode_consistent_absent_or_captured_counters(self):
        document = json.loads(fixture()); document["result"]["observations"][0]["operations"][0]["counters"] = zero_snapshot()
        self.reject(document=document)
        for field, value in (("coverage", "NOT_CAPTURED"), ("counters", None)):
            document = json.loads(fixture("captured")); document["result"]["observations"][0]["operations"][0][field] = value
            self.reject("captured", document)
        for change in (lambda operations: operations.pop(), lambda operations: operations.reverse(),
                       lambda operations: operations.__setitem__(1, copy.deepcopy(operations[0]))):
            document = json.loads(fixture()); change(document["result"]["observations"][2]["operations"])
            self.reject(document=document)
        for field, value in (("phase", "LOAD"), ("kind", "CHECKPOINT_REOPEN"), ("elapsedNanos", -1),
                             ("threadAllocatedBytes", -1), ("heapUsedBeforeBytes", RUNTIME["maxHeapBytes"] + 1)):
            document = json.loads(fixture()); document["result"]["observations"][0]["operations"][0]["measurement"][field] = value
            self.reject(document=document)

    def test_coherent_but_wrong_native_role_bytes_forces_retries_eof_or_scope_are_rejected(self):
        for mutation in ("journal-bytes", "force", "segment-bytes", "retry-read", "continuation-write", "unknown", "eof"):
            document = json.loads(fixture("captured")); rows = document["result"]["observations"]
            if mutation == "journal-bytes": rows[0]["operations"][0]["counters"]["counts"][0][2] += 1
            elif mutation == "force":
                counter = rows[0]["operations"][0]["counters"]["counts"][0]; counter[11] += 1; counter[12] += 1
            elif mutation == "segment-bytes": rows[8]["operations"][0]["counters"]["counts"][1][2] += 1
            elif mutation == "retry-read": rows[2]["operations"][1]["counters"]["counts"][0][:3] = [1, 1, 1]
            elif mutation == "continuation-write": rows[2]["operations"][2]["counters"]["counts"][0][8] -= 1
            elif mutation == "unknown": rows[0]["operations"][0]["counters"]["counts"][7][:3] = [1, 1, 1]
            else:
                counter = rows[0]["operations"][0]["counters"]["counts"][0]; counter[0] += 1; counter[1] += 1; counter[4] += 1
            with self.subTest(mutation=mutation): self.reject("captured", document)

    def test_native_counter_types_dimensions_coherence_overflow_and_failed_calls_reject(self):
        for field, invalid in (("schemaVersion", True), ("overflow", True), ("invalidResult", True),
                               ("overflow", 0), ("invalidResult", 0)):
            document = json.loads(fixture("captured")); document["result"]["observations"][0]["operations"][0]["counters"][field] = invalid
            self.reject("captured", document)
        for mutation in ("roles", "metrics", "bool", "negative", "attempts", "failure", "force-failure", "overflow-sum"):
            document = json.loads(fixture("captured")); counts = document["result"]["observations"][0]["operations"][0]["counters"]["counts"]
            if mutation == "roles": counts.pop()
            elif mutation == "metrics": counts[0].pop()
            elif mutation == "bool": counts[0][3] = False
            elif mutation == "negative": counts[0][2] = -1
            elif mutation == "attempts": counts[0][0] += 1
            elif mutation == "failure": counts[0][0] += 1; counts[0][5] += 1
            elif mutation == "force-failure": counts[4][11] += 1; counts[4][13] += 1
            else:
                counts[0][:3] = [LONG_MAX, LONG_MAX, LONG_MAX]
                counts[1][:3] = [1, 1, 1]
            with self.subTest(mutation=mutation): self.reject("captured", document)

    def test_short_and_zero_native_progress_and_optional_probe_scope_remain_valid(self):
        document = json.loads(fixture("captured")); rows = document["result"]["observations"]
        read = rows[0]["operations"][0]["counters"]["counts"][0]
        read[0] += 2; read[1] += 2; read[3] += 1  # One additional positive short return and one zero return.
        write = rows[2]["operations"][2]["counters"]["counts"][0]
        write[6] += 1; write[7] += 1; write[9] += 1
        sample = rows[0]["operations"][0]["measurement"]
        sample["threadCpuNanos"] = sample["elapsedNanos"] + 1
        sample["threadAllocatedBytes"] = 2 * RUNTIME["maxHeapBytes"]
        sample["heapUsedBeforeBytes"], sample["heapUsedAfterBytes"] = 0, RUNTIME["maxHeapBytes"]
        rows[1]["operations"][0]["measurement"]["threadCpuNanos"] = None
        rows[1]["operations"][0]["measurement"]["threadAllocatedBytes"] = None
        checked = self.validate("captured", document)
        self.assertEqual(document["result"]["observations"], checked["result"]["observations"])

    def test_scope_notes_are_bounded_distinct_nonempty_and_returned_graph_is_fresh(self):
        for gaps in ([], "scope", [None], [""], ["same", "same"], ["x" * 2049], [str(index) for index in range(33)]):
            document = json.loads(fixture()); document["result"]["gaps"] = gaps
            self.reject(document=document)
        expected, bindings = request(), runtime()
        before = copy.deepcopy((expected, bindings))
        checked = self.validate(expected=expected, bindings=bindings)
        checked["request"]["spec"].clear()
        checked["result"]["observations"][0]["fixture"]["initialFileSha256"].clear()
        checked["result"]["observations"][0]["operations"][0]["measurement"].clear()
        checked["corpus"]["workload"]["entityTouches"].clear()
        checked["denominators"][0]["originalJournalBytes"] = 0
        checked["cohortOrders"]["measured"][0].clear()
        self.assertEqual(before, (expected, bindings))
        fresh = self.validate(expected=expected, bindings=bindings)
        self.assertEqual(588, fresh["denominators"][0]["originalJournalBytes"])
        self.assertEqual(["ORDINARY", "HISTORY_REUSE"], fresh["cohortOrders"]["measured"][0])
        self.assertEqual(request(), fresh["request"])


if __name__ == "__main__":
    unittest.main()
