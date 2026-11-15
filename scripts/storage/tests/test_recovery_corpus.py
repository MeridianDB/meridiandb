"""Independent corpus goldens and behavioral boundaries; no runtime Java oracle."""

import copy
import gzip
import hashlib
import importlib.util
import json
from pathlib import Path
import struct
import sys
import unittest
from unittest import mock


STORAGE = Path(__file__).resolve().parents[1]
REPO = STORAGE.parents[1]
_SPEC = importlib.util.spec_from_file_location("recovery_corpus_test_target", STORAGE / "recovery_corpus.py")
corpus = importlib.util.module_from_spec(_SPEC)
sys.modules[_SPEC.name] = corpus
_SPEC.loader.exec_module(corpus)

METADATA_FIELDS = {"corpusVersion", "fingerprint", "historyFingerprint", "encodedHistoryBytes", "workload"}
PREPARED_FIELDS = METADATA_FIELDS | {"spec", "sourceHistory"}
INTENTS = ("SEED", "APPEND", "CORRECTION", "RETRACTION", "REPEAT", "RETRY")
OPERATORS = ("GET", "SCAN", "SELECT", "FILTER_SCAN", "SUM")
WORKLOAD_FIELDS = {"attempts", "commits", "retries", "writeOperations", "reads", "noOpCommits", "putOperations",
                   "retractOperations", "noOpPuts", "noOpRetractions", "putsReplacingPresent", "retractionsRemovingPresent",
                   "geometricOverlaps", "entityTouches", "scheduledIntents", "readOperators", "historicalReads", "headReads",
                   "pointHits", "pointMisses", "emptyResults", "nonemptyResults"}

HEADER_HEX = "4d52444e4a4e4c00000000010000000b0077006f0072006b006c006f00610064002d007600318b36712d"
TINY_FRAMES = [
    "4d54584e000000010000003a82addfa70000000000000001000000030072002d00310000000101000000030065002d0030"
    "030000000000000000000000000000000180000000000000004ff325ac",
    "4d54584e000000010000003a82addfa70000000000000002000000030072002d00320000000101000000030065002d0030"
    "03000000000000000100000000000000027c77a3f562907c9e5d0e1c89",
]
TINY_HISTORY = "f36a0d6a3c1133af7b5e170ee55fedc2d543252c33a4c0420e42fa12e5947f55"
TINY_FINGERPRINT = "8c665b701f4f29e5a2c796732e91b19ba78e8e56d1ad78732acf6909cab59b8d"


def spec(seed=42, entities=1, commits=2, queries=0, width=1, correction=0, retraction=0, repeat=0,
         retry=0, hot=0, historical=50):
    return {"seed": seed, "entities": entities, "commits": commits, "queries": queries, "intervalWidth": width,
            "correctionPercent": correction, "retractionPercent": retraction, "repeatPercent": repeat,
            "retryPercent": retry, "hotEntityPercent": hot, "historicalReadPercent": historical}


def summary(**changes):
    value = {"attempts": 2, "commits": 2, "retries": 0, "writeOperations": 2, "reads": 0,
             "noOpCommits": 0, "putOperations": 2, "retractOperations": 0, "noOpPuts": 0,
             "noOpRetractions": 0, "putsReplacingPresent": 0, "retractionsRemovingPresent": 0,
             "geometricOverlaps": 0, "entityTouches": {"e-0": 2},
             "scheduledIntents": {intent: 1 if intent in ("SEED", "APPEND") else 0 for intent in INTENTS},
             "readOperators": dict.fromkeys(OPERATORS, 0), "historicalReads": 0, "headReads": 0,
             "pointHits": 0, "pointMisses": 0, "emptyResults": 0, "nonemptyResults": 0}
    value.update(changes)
    return value


def tiny_source():
    return {"headerHex": HEADER_HEX, "framesHex": list(TINY_FRAMES)}


def tiny_metadata():
    return {"corpusVersion": 1, "fingerprint": TINY_FINGERPRINT, "historyFingerprint": TINY_HISTORY,
            "encodedHistoryBytes": 198, "workload": summary()}


def metadata(prepared):
    return {field: copy.deepcopy(prepared[field]) for field in METADATA_FIELDS}


def crc32c(data):
    value = 0xFFFFFFFF
    for byte in data:
        value ^= byte
        for _ in range(8):
            value = (value >> 1) ^ (0x82F63B78 if value & 1 else 0)
    return value ^ 0xFFFFFFFF


def text(value):
    image = value.encode("utf-16-be", "surrogatepass")
    return struct.pack(">i", len(image) // 2) + image


def finite_put(sequence, start, end, value):
    """Only encode an explicitly specified literal record; generate no workload or RNG draws."""
    payload = struct.pack(">q", sequence) + text(f"r-{sequence}") + struct.pack(">i", 1)
    payload += b"\x01" + text("e-0") + b"\x03" + struct.pack(">qqq", start, end, value)
    prefix = b"MTXN" + struct.pack(">ii", 1, len(payload))
    return prefix + struct.pack(">I", crc32c(prefix)) + payload + struct.pack(">I", crc32c(payload))


def tiny_schedule_fingerprint(frames):
    """Serialize the two hand-enumerated submit actions, independently of the Python producer."""
    encoded = text("meridiandb-workload") + struct.pack(">iq", 1, 42)
    encoded += struct.pack(">10i", 1, 2, 0, 1, 0, 0, 0, 0, 0, 50) + struct.pack(">i", 2)
    for stage, intent, frame in (("SEED", "SEED", frames[0]), ("MIXED", "APPEND", frames[1])):
        encoded += b"\x01" + text(stage) + text(intent) + struct.pack(">i", len(frame)) + frame
    return hashlib.sha256(encoded).hexdigest()


class RecoveryCorpusTests(unittest.TestCase):
    def reject_validate(self, source, value, expected_spec):
        before = copy.deepcopy((source, value, expected_spec))
        with self.assertRaises(corpus.CorpusError):
            corpus.validate_corpus(source, value, expected_spec)
        self.assertEqual(before, (source, value, expected_spec))

    def test_hand_derived_two_commit_append_has_literal_frames_fingerprints_and_complete_zero_read_summary(self):
        # Seed42 write stream consumes three next(31) draws 480384172/1634333949/2078046530;
        # nextLong words2088215541/1653636254 give the second value8968817457247583390.
        self.assertEqual(0xE3069283, crc32c(b"123456789"))
        expected_frames = [finite_put(1, 0, 1, -(1 << 63)), finite_put(2, 1, 2, 8968817457247583390)]
        self.assertEqual(TINY_FRAMES, [frame.hex() for frame in expected_frames])
        self.assertEqual(TINY_FINGERPRINT, tiny_schedule_fingerprint(expected_frames))
        self.assertEqual(TINY_HISTORY, hashlib.sha256(bytes.fromhex(HEADER_HEX) + b"".join(expected_frames)).hexdigest())
        prepared = corpus.prepare_corpus(spec())
        self.assertEqual(PREPARED_FIELDS, set(prepared))
        self.assertEqual(spec(), prepared["spec"])
        self.assertEqual(tiny_source(), prepared["sourceHistory"])
        self.assertEqual(tiny_metadata(), metadata(prepared))
        self.assertEqual(WORKLOAD_FIELDS, set(prepared["workload"]))
        self.assertEqual(tiny_metadata(), corpus.validate_corpus(tiny_source(), tiny_metadata(), spec()))

    def test_existing_java_tiny_golden_pins_corrections_retries_all_query_operators_and_empty_results(self):
        expected_spec = spec(7, 1, 4, 5, 2, correction=100, retry=100, hot=100, historical=100)
        prepared = corpus.prepare_corpus(expected_spec)
        self.assertEqual("39bd170644930733aef2da90da5ed3d9c1d42bf48c37a42052d19f57e52f2af0", prepared["fingerprint"])
        self.assertEqual("cdbb2a26ffde6b13937bf196fa3fe77630c6b65922696e67073d37050be7a211", prepared["historyFingerprint"])
        self.assertEqual(354, prepared["encodedHistoryBytes"])
        values = (-(1 << 63), -2131460139842329618, 8463413780889623691, -8675119527219200843)
        windows = ((0, 2), (1, 2), (0, 1), (1, 2))
        expected_frames = [finite_put(index + 1, *window, value).hex()
                           for index, (window, value) in enumerate(zip(windows, values))]
        self.assertEqual({"headerHex": HEADER_HEX, "framesHex": expected_frames}, prepared["sourceHistory"])
        expected = summary(attempts=7, commits=4, retries=3, writeOperations=4, reads=5, putOperations=4,
                           putsReplacingPresent=3, geometricOverlaps=3, entityTouches={"e-0": 4},
                           scheduledIntents={"SEED": 1, "APPEND": 0, "CORRECTION": 3, "RETRACTION": 0, "REPEAT": 0, "RETRY": 3},
                           readOperators=dict.fromkeys(OPERATORS, 1), historicalReads=5, pointMisses=1,
                           emptyResults=4, nonemptyResults=1)
        self.assertEqual(expected, prepared["workload"])
        self.assertEqual(metadata(prepared), corpus.validate_corpus(prepared["sourceHistory"], metadata(prepared), expected_spec))

    def test_immutable_november_seven_java_captures_pin_complete_realized_oracle_summaries(self):
        # These are committed Java-produced captures, not the synthetic outer-validator fixtures.
        cases = (
            ("evidence/linux-observations/2026-11-07/append-base/worker.json.gz",
             "b33801d18462c16e588432baee439948b6aea87d2a8866dfeb6b0d15415150aa",
             "0a3b5455dfad2e0ac05cbf95954de3a43f9f95b3b7832b7ab0e20e367e51c8c5"),
            ("evidence/linux-attributed-observations/2026-11-07/corrections-base/worker.json.gz",
             "d773c131a39ef3427e152eecfbd2ae5e6ff67080edfd5eacbb8e13a683ce21a5",
             "4cd56d634a2bc44076417e4959e8ef06a1d5524bd17c0e9c0716bffa4f4d6d46"),
            ("evidence/linux-attributed-observations/2026-11-07/retractions-base/worker.json.gz",
             "0cba3581a50aa3bb55decb2c9711a99a6a552a55d4737b14c0be74045a303ecc",
             "a8cdce5ed84f7d3e462bb8e72ef222e7c0e850f2b1e58707433dd032fe7c5b6d"),
        )
        for filename, fingerprint, history in cases:
            with self.subTest(capture=filename), gzip.open(REPO / filename, "rt", encoding="utf-8") as stream:
                worker = json.load(stream)
            expected = worker["result"]
            prepared = corpus.prepare_corpus(expected["spec"])
            self.assertEqual(fingerprint, prepared["fingerprint"])
            self.assertEqual(history, prepared["historyFingerprint"])
            self.assertEqual(expected["encodedHistoryBytes"], prepared["encodedHistoryBytes"])
            self.assertEqual(expected["workload"], prepared["workload"])
            self.assertGreater(prepared["workload"]["pointHits"], 0)
            self.assertGreater(prepared["workload"]["pointMisses"], 0)
            self.assertGreater(prepared["workload"]["emptyResults"], 0)
            self.assertGreater(prepared["workload"]["nonemptyResults"], 0)
            if "attribution" in worker:
                self.assertEqual(worker["attribution"]["sourceHistory"], prepared["sourceHistory"])
                self.assertGreater(prepared["workload"]["noOpRetractions"], 0)
            self.assertEqual(metadata(prepared), corpus.validate_corpus(prepared["sourceHistory"], metadata(prepared), expected["spec"]))

    def test_repeat_intent_creates_distinct_noop_requests_and_preserves_geometric_overlap_counts(self):
        prepared = corpus.prepare_corpus(spec(seed=1, commits=4, repeat=100))
        expected = summary(attempts=4, commits=4, writeOperations=4, putOperations=4,
                           noOpCommits=3, noOpPuts=3, putsReplacingPresent=3, geometricOverlaps=3,
                           entityTouches={"e-0": 4}, scheduledIntents={intent: 1 if intent == "SEED" else 3 if intent == "REPEAT" else 0 for intent in INTENTS})
        self.assertEqual(expected, prepared["workload"])
        self.assertEqual([finite_put(sequence, 0, 1, -(1 << 63)).hex() for sequence in range(1, 5)],
                         prepared["sourceHistory"]["framesHex"])

    def test_retraction_intent_restores_an_absent_primary_as_a_put_and_keeps_intent_separate_from_effect(self):
        prepared = corpus.prepare_corpus(spec(seed=1, commits=4, retraction=100))
        expected = summary(attempts=4, commits=4, writeOperations=4, putOperations=2, retractOperations=2,
                           retractionsRemovingPresent=2, geometricOverlaps=3, entityTouches={"e-0": 4},
                           scheduledIntents={intent: 1 if intent == "SEED" else 3 if intent == "RETRACTION" else 0 for intent in INTENTS})
        self.assertEqual(expected, prepared["workload"])
        self.assertEqual([1, 2, 1, 2], [bytes.fromhex(frame)[38] for frame in prepared["sourceHistory"]["framesHex"]])

    def test_query_and_retry_stream_changes_preserve_original_history_but_change_schedule_and_realized_counts(self):
        original = spec(7, 1, 4, 5, 2, correction=100, retry=0, hot=100, historical=100)
        changed = dict(original, queries=7, retryPercent=100, historicalReadPercent=0)
        first = corpus.prepare_corpus(original)
        second = corpus.prepare_corpus(changed)
        self.assertEqual(first["sourceHistory"], second["sourceHistory"])
        self.assertEqual(first["historyFingerprint"], second["historyFingerprint"])
        self.assertEqual(first["encodedHistoryBytes"], second["encodedHistoryBytes"])
        self.assertNotEqual(first["fingerprint"], second["fingerprint"])
        self.assertEqual(0, first["workload"]["retries"])
        self.assertEqual(3, second["workload"]["retries"])
        self.assertEqual(7, second["workload"]["reads"])
        self.assertEqual(7, second["workload"]["headReads"])
        self.assertEqual(0, second["workload"]["historicalReads"])
        self.reject_validate(first["sourceHistory"], metadata(first), changed)

    def test_upper_sixteen_seed_bits_alias_java_random_history_but_remain_in_schedule_identity(self):
        original = spec(commits=4, queries=5, width=2, correction=100, retry=100)
        alias = dict(original, seed=original["seed"] + (1 << 48))
        first, second = corpus.prepare_corpus(original), corpus.prepare_corpus(alias)
        self.assertEqual(first["sourceHistory"], second["sourceHistory"])
        self.assertEqual(first["historyFingerprint"], second["historyFingerprint"])
        self.assertEqual(first["workload"], second["workload"])
        self.assertNotEqual(first["fingerprint"], second["fingerprint"])
        self.reject_validate(first["sourceHistory"], metadata(first), alias)

    def test_random48_matches_fixed_java_vectors_signed_low_word_and_rejection_sampling(self):
        self.assertEqual(-4962768465676381896, corpus._Random48(0).next_long())
        self.assertEqual(-5025562857975149833, corpus._Random48(42).next_long())
        random = corpus._Random48(0)
        with mock.patch.object(random, "_next", side_effect=[1, 0xFFFFFFFF]) as draws:
            self.assertEqual(4294967295, random.next_long())
            self.assertEqual([mock.call(32), mock.call(32)], draws.call_args_list)
        random = corpus._Random48(0)
        with mock.patch.object(random, "_next", side_effect=[0x7FFFFFFF, 0]) as draws:
            self.assertEqual(0, random.next_int((1 << 30) + 1))
            self.assertEqual([mock.call(31), mock.call(31)], draws.call_args_list)
        with mock.patch.object(random, "_next", return_value=0x7FFFFFFF) as draws:
            self.assertEqual((1 << 30) - 1, random.next_int(1 << 30))
            draws.assert_called_once_with(31)

    def test_strict_spec_fields_types_percentages_and_reference_work_reject_before_random_generation(self):
        invalid_specs = [None, [], dict(spec(), unknown=1)]
        for field in spec():
            missing = spec(); del missing[field]; invalid_specs.append(missing)
            for value in (True, False, 1.0, "1", None):
                invalid_specs.append(dict(spec(), **{field: value}))
        invalid_specs.extend(dict(spec(), **{field: value}) for field, value in (
            ("seed", -(1 << 63) - 1), ("seed", 1 << 63), ("entities", 0), ("entities", 1025),
            ("commits", 1), ("commits", 4097), ("queries", -1), ("queries", 1025),
            ("intervalWidth", 0), ("intervalWidth", 17), ("retryPercent", -1), ("hotEntityPercent", 101),
        ))
        invalid_specs.extend((dict(spec(), correctionPercent=70, retractionPercent=31),
                              spec(commits=4001), spec(entities=16, commits=4096, queries=1024, width=16)))
        for invalid in invalid_specs:
            with self.subTest(spec=invalid), mock.patch.object(corpus, "_Random48", side_effect=AssertionError("Invalid input generated random work")):
                with self.assertRaises(corpus.CorpusError):
                    corpus.prepare_corpus(invalid)
                self.reject_validate(tiny_source(), tiny_metadata(), invalid)

    def test_corpus_admission_preserves_java_scope_without_adding_the_recovery_matrix_gate(self):
        expected_spec = spec(commits=500)
        self.assertGreater(16 * (2 * (2 * 500) + 500 + 16), 40000)
        prepared = corpus.prepare_corpus(expected_spec)
        self.assertEqual(500, len(prepared["sourceHistory"]["framesHex"]))
        self.assertEqual(500, prepared["workload"]["commits"])
        self.assertEqual(500, prepared["workload"]["putOperations"])
        self.assertEqual(0, prepared["workload"]["geometricOverlaps"])
        self.assertEqual(40826, prepared["encodedHistoryBytes"])
        self.assertEqual(metadata(prepared), corpus.validate_corpus(prepared["sourceHistory"], metadata(prepared), expected_spec))

    def test_metadata_has_exact_five_fields_strict_canonical_hashes_and_integral_counts(self):
        for invalid in (None, [], {}, dict(tiny_metadata(), unknown=1)):
            self.reject_validate(tiny_source(), invalid, spec())
        for field in METADATA_FIELDS:
            value = tiny_metadata(); del value[field]
            self.reject_validate(tiny_source(), value, spec())
        for field, invalid in (("corpusVersion", True), ("corpusVersion", 1.0), ("corpusVersion", 2),
                               ("encodedHistoryBytes", 198.0), ("encodedHistoryBytes", True), ("encodedHistoryBytes", -1),
                               ("encodedHistoryBytes", 16 * 1024 * 1024 + 1), ("workload", [])):
            value = tiny_metadata(); value[field] = invalid
            self.reject_validate(tiny_source(), value, spec())
        for field in ("fingerprint", "historyFingerprint"):
            for invalid in (None, True, "", "a" * 63, "a" * 65, "A" * 64, "g" * 64, "a" * 64 + "\n"):
                value = tiny_metadata(); value[field] = invalid
                self.reject_validate(tiny_source(), value, spec())

    def test_every_workload_scalar_and_map_is_validated_with_zero_fields_retained(self):
        for field in WORKLOAD_FIELDS:
            value = tiny_metadata(); del value["workload"][field]
            self.reject_validate(tiny_source(), value, spec())
        value = tiny_metadata(); value["workload"]["unknown"] = 0
        self.reject_validate(tiny_source(), value, spec())
        for field in WORKLOAD_FIELDS - {"entityTouches", "scheduledIntents", "readOperators"}:
            for invalid in (True, False, 0.0, -1, "0", None, 1 << 63):
                value = tiny_metadata(); value["workload"][field] = invalid
                self.reject_validate(tiny_source(), value, spec())
        for field in ("entityTouches", "scheduledIntents", "readOperators"):
            for invalid in (None, [], True):
                value = tiny_metadata(); value["workload"][field] = invalid
                self.reject_validate(tiny_source(), value, spec())
            for key in tiny_metadata()["workload"][field]:
                for invalid in (True, False, 1.0, -1, "1", None):
                    value = tiny_metadata(); value["workload"][field][key] = invalid
                    self.reject_validate(tiny_source(), value, spec())
            value = tiny_metadata(); value["workload"][field]["UNKNOWN"] = 0
            self.reject_validate(tiny_source(), value, spec())
        value = tiny_metadata(); del value["workload"]["scheduledIntents"]["RETRACTION"]
        self.reject_validate(tiny_source(), value, spec())
        value = tiny_metadata(); del value["workload"]["readOperators"]["SCAN"]
        self.reject_validate(tiny_source(), value, spec())

    def test_source_hex_shape_count_and_bounds_reject_before_regenerating_a_corpus(self):
        invalid_sources = [None, [], {}, dict(tiny_source(), unknown=1)]
        for field in ("headerHex", "framesHex"):
            value = tiny_source(); del value[field]; invalid_sources.append(value)
        for invalid in (None, True, b"00", "", "0", "GG", "AA", "00 ", "00\n", "00" * 65557):
            invalid_sources.append(dict(tiny_source(), headerHex=invalid))
        for invalid in (None, {}, "00", tuple(TINY_FRAMES), [], TINY_FRAMES[:1], TINY_FRAMES * 2049):
            invalid_sources.append(dict(tiny_source(), framesHex=invalid))
        for invalid in (None, True, b"00", "", "0", "GG", "AA", "00 ", "00\n", "00" * 4097):
            value = tiny_source(); value["framesHex"][0] = invalid; invalid_sources.append(value)
        for source in invalid_sources:
            with self.subTest(source_type=type(source)), mock.patch.object(corpus, "_Random48", side_effect=AssertionError("Malformed source generated actions")):
                self.reject_validate(source, tiny_metadata(), spec())

    def test_resigned_structurally_valid_alternate_history_cannot_be_accepted_with_self_consistent_claims(self):
        source = tiny_source()
        different = finite_put(2, 1, 2, 8968817457247583391)
        source["framesHex"][1] = different.hex()
        value = tiny_metadata()
        original = bytes.fromhex(HEADER_HEX) + bytes.fromhex(TINY_FRAMES[0]) + different
        value["historyFingerprint"] = hashlib.sha256(original).hexdigest()
        value["fingerprint"] = tiny_schedule_fingerprint([bytes.fromhex(TINY_FRAMES[0]), different])
        self.assertNotEqual(TINY_HISTORY, value["historyFingerprint"])
        self.assertNotEqual(TINY_FINGERPRINT, value["fingerprint"])
        self.assertEqual(198, len(original))
        self.reject_validate(source, value, spec())
        self.reject_validate(source, tiny_metadata(), spec())

    def test_reordered_duplicate_truncated_corrupt_and_extra_source_bytes_are_rejected_without_repair(self):
        for frames in (list(reversed(TINY_FRAMES)), [TINY_FRAMES[0], TINY_FRAMES[0]],
                       [TINY_FRAMES[0][:-2], TINY_FRAMES[1]], [TINY_FRAMES[0] + "00", TINY_FRAMES[1]],
                       [TINY_FRAMES[0], TINY_FRAMES[1][:-2] + "00"]):
            self.reject_validate({"headerHex": HEADER_HEX, "framesHex": frames}, tiny_metadata(), spec())
        self.reject_validate({"headerHex": HEADER_HEX + "00", "framesHex": TINY_FRAMES}, tiny_metadata(), spec())

    def test_prepare_and_validate_return_fresh_nested_values_and_never_mutate_callers(self):
        expected_spec = spec()
        before_spec = copy.deepcopy(expected_spec)
        prepared = corpus.prepare_corpus(expected_spec)
        self.assertEqual(before_spec, expected_spec)
        source, value = tiny_source(), tiny_metadata()
        before = copy.deepcopy((source, value, expected_spec))
        checked = corpus.validate_corpus(source, value, expected_spec)
        self.assertEqual(METADATA_FIELDS, set(checked))
        self.assertEqual(before, (source, value, expected_spec))
        checked["workload"]["entityTouches"].clear()
        checked["workload"]["scheduledIntents"].clear()
        checked["workload"]["readOperators"].clear()
        prepared["spec"].clear()
        prepared["sourceHistory"]["framesHex"].clear()
        prepared["workload"]["entityTouches"].clear()
        self.assertEqual(before, (source, value, expected_spec))
        self.assertEqual(tiny_metadata(), corpus.validate_corpus(source, value, expected_spec))
        self.assertEqual(tiny_source(), corpus.prepare_corpus(expected_spec)["sourceHistory"])


if __name__ == "__main__":
    unittest.main()
