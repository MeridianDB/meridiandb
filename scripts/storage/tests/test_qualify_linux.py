"""Focused, dependency-free checks for retained Linux qualification evidence."""

import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import signal
import sys
import tempfile
import time
from types import SimpleNamespace
import unittest
from unittest import mock
import xml.etree.ElementTree as ET


_spec = importlib.util.spec_from_file_location(
    "meridiandb_qualify_linux", Path(__file__).resolve().parents[1] / "qualify_linux.py")
qualify_linux = importlib.util.module_from_spec(_spec)
_previous_bytecode = sys.dont_write_bytecode
sys.dont_write_bytecode = True
try:
    _spec.loader.exec_module(qualify_linux)
finally:
    sys.dont_write_bytecode = _previous_bytecode

SUITE = "org.example.OneTest"
RUN_ID = "fresh-test-run"
RUNTIME = {"java.version": "25.0.1", "java.runtime.version": "25.0.1-test",
           "java.vendor": "test-vendor", "os.arch": "test-arch"}
CAPABILITIES = ["production-directory-probe", "file-force", "directory-force", "file-key",
                "actual-shared-lock", "exclusive-lock", "atomic-move", "identity-after-move"]


def mount_line(mount="/", device="8:1", mount_id="20", options="rw,relatime", super_options="rw"):
    return f"{mount_id} 1 {device} / {mount} {options} - ext4 /dev/private-source {super_options}"


def report_xml(scratch, run_id=RUN_ID, suite=SUITE, names=("works()",), outcomes=None):
    outcomes = outcomes or {}
    counts = {key: sum(value == key for value in outcomes.values())
              for key in ("failure", "error", "skipped")}
    root = ET.Element("testsuite", name=suite, tests=str(len(names)),
                      failures=str(counts["failure"]), errors=str(counts["error"]),
                      skipped=str(counts["skipped"]))
    properties = ET.SubElement(root, "properties")
    for key, value in {
        "meridiandb.qualification.root": str(scratch),
        "meridiandb.qualification.runId": run_id,
        "java.io.tmpdir": str(scratch),
        "junit.jupiter.tempdir.factory.default": qualify_linux.FACTORY,
        **RUNTIME,
    }.items():
        ET.SubElement(properties, "property", name=key, value=value)
    for name in names:
        case = ET.SubElement(root, "testcase", name=name, classname=suite)
        if name in outcomes:
            ET.SubElement(case, outcomes[name], message="fixture outcome")
    return root


def save_report(directory, root, filename=None, encoding="utf-8"):
    path = directory / (filename or f"TEST-{root.get('name')}.xml")
    path.write_bytes(ET.tostring(root, encoding=encoding, xml_declaration=True))
    return path


class TemporaryTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="mdb-harness-test-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name).resolve()
        self.sequence = 0

    def fresh_directory(self):
        self.sequence += 1
        path = self.root / f"case-{self.sequence}"
        path.mkdir()
        return path


class JsonEvidenceTests(unittest.TestCase):
    def test_utf8_json_round_trip_preserves_evidence_values(self):
        document = {"schemaVersion": 1, "nested": {"fixture": "temporary-☃", "known": None},
                    "capabilities": ["file-force"], "enabled": False}
        self.assertEqual(document, qualify_linux.read_json(json.dumps(document, ensure_ascii=False).encode("utf-8")))

    def test_duplicate_top_level_and_nested_keys_are_refused_even_when_values_agree(self):
        for image in (b'{"schemaVersion":1,"schemaVersion":1}',
                      b'{"runtime":{"vendor":"same","vendor":"same"}}'):
            with self.subTest(image=image), self.assertRaises(qualify_linux.ProfileError):
                qualify_linux.read_json(image)

    def test_nonstandard_constants_malformed_json_and_utf16_are_refused(self):
        images = [b'{"value":NaN}', b'{"value":Infinity}', b'{"value":-Infinity}',
                  b'{"incomplete":', '{"schemaVersion":1}'.encode("utf-16")]
        for image in images:
            with self.subTest(image=image), self.assertRaises(ValueError):
                qualify_linux.read_json(image)


class MountAndDeviceTests(TemporaryTest):
    def test_mount_selection_requires_both_device_and_physical_path_boundary(self):
        evidence = "\n".join([mount_line(), mount_line("/data", "8:2", "21"),
                              mount_line("/data/nested", "8:1", "22")])
        selected = qualify_linux.select_mount(evidence, Path("/data/nested/run"), "8:1")
        self.assertEqual("22", selected["mountId"])
        self.assertEqual("/data/nested", selected["mountPoint"])
        self.assertEqual("8:1", selected["majorMinor"])
        with self.assertRaises(qualify_linux.ProfileError):
            qualify_linux.select_mount(mount_line("/data"), Path("/database/run"), "8:1")
        with self.assertRaises(qualify_linux.ProfileError):
            qualify_linux.select_mount(evidence, Path("/data/nested/run"), "8:3")

    def test_mountinfo_escapes_are_decoded_before_path_binding(self):
        encoded = r"/space\040tab\011line\012back\134slash"
        decoded = "/space tab\tline\nback\\slash"
        selected = qualify_linux.select_mount(mount_line(encoded), Path(decoded) / "run", "8:1")
        self.assertEqual(decoded, selected["mountPoint"])
        self.assertEqual(decoded, qualify_linux.unescape_mount(encoded))

    def test_ambiguous_stacked_mounts_are_rejected_even_with_different_ids(self):
        evidence = mount_line("/data", mount_id="10") + "\n" + mount_line("/data", mount_id="11")
        with self.assertRaises(qualify_linux.ProfileError):
            qualify_linux.select_mount(evidence, Path("/data/run"), "8:1")

    def test_malformed_mount_lines_fail_instead_of_becoming_unknown_evidence(self):
        malformed = ["", "1 2 8:1 / / rw ext4 source rw", "1 2 bad / / rw - ext4 source rw",
                     "1 2 8:1 / relative rw - ext4 source rw", "1 2 8:1 / / rw - ext4 source",
                     mount_line() + "\nmalformed unrelated line"]
        for evidence in malformed:
            with self.subTest(evidence=evidence), self.assertRaises(qualify_linux.ProfileError):
                qualify_linux.select_mount(evidence, Path("/data/run"), "8:1")

    def test_mount_output_omits_sources_and_secret_or_unrecognized_options(self):
        selected = qualify_linux.select_mount(
            mount_line(options="rw,nodev,password=private", super_options="rw,lowerdir=/private,secret=x"),
            Path("/run"), "8:1")
        self.assertEqual(["nodev", "rw"], selected["recognizedOptions"])
        self.assertEqual(3, selected["unreportedOptionCount"])
        for secret in ("private", "lowerdir", "password", "secret=x"):
            self.assertNotIn(secret, json.dumps(selected))

    def test_parameterized_mount_options_are_reported_once_and_not_counted_as_unreported(self):
        selected = qualify_linux.select_mount(mount_line(
            options="rw,data=ordered,barrier=1,commit=5",
            super_options="rw,data=ordered,errors=remount-ro,private=value"), Path("/run"), "8:1")
        self.assertEqual(["barrier=1", "commit=5", "data=ordered", "errors=remount-ro", "rw"],
                         selected["recognizedOptions"])
        self.assertEqual(1, selected["unreportedOptionCount"])
        self.assertNotIn("private=value", json.dumps(selected))

    def test_bounded_read_accepts_exact_limit_and_rejects_one_more_byte(self):
        path = self.root / "evidence"
        path.write_bytes(b"1234")
        self.assertEqual(b"1234", qualify_linux.read_bounded(path, 4))
        path.write_bytes(b"12345")
        with self.assertRaises(qualify_linux.ProfileError):
            qualify_linux.read_bounded(path, 4)

    def test_absent_device_facts_remain_explicitly_unknown(self):
        result = qualify_linux.device_metadata("0:999", self.root)
        for key in ("writeCache", "logicalBlockBytes", "physicalBlockBytes", "rotational"):
            self.assertIsNone(result[key])
        self.assertIn("unknown", result["backingDeviceAndController"])

    def test_device_queue_values_are_bounded_and_never_imply_backing_controller(self):
        queue = self.root / "8:1" / "queue"
        queue.mkdir(parents=True)
        for name, value in {"write_cache": "write back\n", "logical_block_size": "512\n",
                            "physical_block_size": "4096\n", "rotational": "0\n"}.items():
            (queue / name).write_text(value, encoding="utf-8")
        result = qualify_linux.device_metadata("8:1", self.root)
        self.assertEqual("write back", result["writeCache"])
        self.assertEqual("512", result["logicalBlockBytes"])
        self.assertEqual("4096", result["physicalBlockBytes"])
        self.assertEqual("0", result["rotational"])
        self.assertIn("unknown", result["backingDeviceAndController"])
        (queue / "write_cache").write_bytes(b"x" * 257)
        with self.assertRaises(qualify_linux.ProfileError):
            qualify_linux.device_metadata("8:1", self.root)
        (queue / "write_cache").write_bytes(b" \n")
        with self.assertRaises(qualify_linux.ProfileError):
            qualify_linux.device_metadata("8:1", self.root)

    def test_permission_failure_is_not_silently_reported_as_unknown_device(self):
        with mock.patch.object(qualify_linux, "read_bounded", side_effect=PermissionError("denied")):
            with self.assertRaises(PermissionError):
                qualify_linux.device_metadata("8:1", self.root)


class ReportTests(TemporaryTest):
    def read(self, directory, inventory=None):
        return qualify_linux.read_reports(directory, inventory or {SUITE: 1}, self.root, RUN_ID)

    def test_exact_suite_and_invocation_inventory_retains_all_case_identities(self):
        directory = self.fresh_directory()
        other = "org.example.AnotherTest"
        save_report(directory, report_xml(self.root, names=("works()", "parameterized(String)[1]")))
        save_report(directory, report_xml(self.root, suite=other, names=("other()",)))
        result = self.read(directory, {SUITE: 2, other: 1})
        self.assertEqual(3, result["tests"])
        self.assertEqual((0, 0, 0), (result["failures"], result["errors"], result["skipped"]))
        by_suite = {suite["suite"]: suite for suite in result["suites"]}
        self.assertEqual([{"name": "works()", "status": "passed"},
                          {"name": "parameterized(String)[1]", "status": "passed"}], by_suite[SUITE]["cases"])

    def test_stale_run_wrong_fixture_or_wrong_factory_never_counts_as_a_pass(self):
        keys = ("meridiandb.qualification.root", "meridiandb.qualification.runId",
                "java.io.tmpdir", "junit.jupiter.tempdir.factory.default")
        for key in keys:
            for missing in (False, True):
                with self.subTest(property=key, missing=missing):
                    directory = self.fresh_directory()
                    root = report_xml(self.root)
                    properties = root.find("properties")
                    prop = next(item for item in properties if item.get("name") == key)
                    if missing:
                        properties.remove(prop)
                    else:
                        prop.set("value", "stale-or-foreign")
                    save_report(directory, root)
                    with self.assertRaises(qualify_linux.ProfileError):
                        self.read(directory)

    def test_duplicate_binding_property_is_rejected_even_if_values_agree(self):
        directory = self.fresh_directory()
        root = report_xml(self.root)
        ET.SubElement(root.find("properties"), "property", name="meridiandb.qualification.runId", value=RUN_ID)
        save_report(directory, root)
        with self.assertRaises(qualify_linux.ProfileError):
            self.read(directory)

    def test_missing_extra_misnamed_or_duplicate_suite_files_are_rejected(self):
        for defect in ("missing", "extra", "filename", "duplicate-suite", "unknown-suite"):
            with self.subTest(defect=defect):
                directory = self.fresh_directory()
                if defect != "missing":
                    root = report_xml(self.root)
                    if defect == "unknown-suite":
                        root.set("name", "org.example.UnknownTest")
                    save_report(directory, root, "TEST-wrong.xml" if defect == "filename" else None)
                if defect in ("extra", "duplicate-suite"):
                    save_report(directory, report_xml(self.root), "TEST-duplicate.xml")
                inventory = {SUITE: 1, "org.example.MissingTest": 1} if defect == "duplicate-suite" else None
                with self.assertRaises(qualify_linux.ProfileError):
                    self.read(directory, inventory)

    def test_testcase_names_classnames_and_invocation_counts_are_exact(self):
        for defect in ("missing-name", "empty-name", "long-name", "duplicate-name", "classname", "count", "zero"):
            with self.subTest(defect=defect):
                directory = self.fresh_directory()
                root = report_xml(self.root, names=() if defect == "zero" else ("works()",))
                case = root.find("testcase")
                if defect == "missing-name":
                    case.attrib.pop("name")
                elif defect == "empty-name":
                    case.set("name", "")
                elif defect == "long-name":
                    case.set("name", "x" * 4097)
                elif defect == "duplicate-name":
                    ET.SubElement(root, "testcase", name="works()", classname=SUITE)
                    root.set("tests", "2")
                elif defect == "classname":
                    case.set("classname", "org.example.ForeignTest")
                save_report(directory, root)
                with self.assertRaises(qualify_linux.ProfileError):
                    self.read(directory, {SUITE: 2 if defect == "count" else 1})

    def test_testcase_identity_at_the_documented_name_bound_is_accepted(self):
        directory = self.fresh_directory()
        save_report(directory, report_xml(self.root, names=("x" * 4096,)))
        self.assertEqual(1, self.read(directory)["tests"])

    def test_totals_must_match_actual_cases_and_outcomes(self):
        for key, value in (("tests", "2"), ("tests", "-1"), ("errors", "1"),
                           ("failures", "bad"), ("skipped", None)):
            with self.subTest(counter=key, value=value):
                directory = self.fresh_directory()
                root = report_xml(self.root)
                if value is None:
                    del root.attrib[key]
                else:
                    root.set(key, value)
                save_report(directory, root)
                with self.assertRaises(qualify_linux.ProfileError):
                    self.read(directory)

    def test_each_failure_error_or_skip_is_an_unqualified_failure_even_when_counts_match(self):
        for outcome in ("failure", "error", "skipped"):
            with self.subTest(outcome=outcome):
                directory = self.fresh_directory()
                save_report(directory, report_xml(self.root, outcomes={"works()": outcome}))
                with self.assertRaises(qualify_linux.ProfileError):
                    self.read(directory)

    def test_conflicting_case_outcomes_are_rejected(self):
        directory = self.fresh_directory()
        root = report_xml(self.root, outcomes={"works()": "failure"})
        ET.SubElement(root.find("testcase"), "skipped")
        root.set("skipped", "1")
        save_report(directory, root)
        with self.assertRaises(qualify_linux.ProfileError):
            self.read(directory)

    def test_malformed_xml_and_non_suite_document_roots_are_rejected(self):
        for image in (b"<testsuite", b"<testsuites/>", b"<other/>"):
            with self.subTest(image=image):
                directory = self.fresh_directory()
                (directory / f"TEST-{SUITE}.xml").write_bytes(image)
                with self.assertRaises(qualify_linux.ProfileError):
                    self.read(directory)

    def test_dtds_and_entities_are_refused_in_ascii_and_utf16_evidence(self):
        for encoding in ("utf-8", "utf-16"):
            with self.subTest(encoding=encoding):
                directory = self.fresh_directory()
                xml = ET.tostring(report_xml(self.root), encoding="unicode")
                xml = xml.replace('name="works()"', 'name="&invocation;"')
                xml = (f'<?xml version="1.0" encoding="{encoding}"?>'
                       '<!DOCTYPE testsuite [<!ENTITY invocation "works()">]>' + xml)
                (directory / f"TEST-{SUITE}.xml").write_bytes(xml.encode(encoding))
                with self.assertRaises(qualify_linux.ProfileError):
                    self.read(directory)

    def test_runtime_evidence_is_required_bounded_java25_and_identical_across_suites(self):
        for defect in ("missing", "blank", "oversized", "old-java", "mixed-runtime"):
            with self.subTest(defect=defect):
                directory = self.fresh_directory()
                root = report_xml(self.root)
                properties = root.find("properties")
                prop = next(item for item in properties if item.get("name") == "java.version")
                if defect == "missing":
                    properties.remove(prop)
                elif defect == "blank":
                    prop.set("value", " ")
                elif defect == "oversized":
                    prop.set("value", "25." + "x" * 513)
                elif defect == "old-java":
                    prop.set("value", "21.0.1")
                inventory = {SUITE: 1}
                if defect == "mixed-runtime":
                    other = "org.example.OtherTest"
                    second = report_xml(self.root, suite=other)
                    vendor = next(item for item in second.find("properties") if item.get("name") == "java.vendor")
                    vendor.set("value", "different-vendor")
                    save_report(directory, second)
                    inventory[other] = 1
                save_report(directory, root)
                with self.assertRaises(qualify_linux.ProfileError):
                    self.read(directory, inventory)

    def test_unknown_or_retry_masked_outcomes_and_duplicate_property_containers_are_rejected(self):
        for defect in ("flakyFailure", "rerunError", "unknown-case", "flakes", "unknown-suite", "duplicate-properties"):
            with self.subTest(defect=defect):
                directory = self.fresh_directory()
                root = report_xml(self.root)
                if defect == "flakes":
                    root.set("flakes", "1")
                elif defect == "unknown-suite":
                    ET.SubElement(root, "unknown")
                elif defect == "duplicate-properties":
                    ET.SubElement(root, "properties")
                else:
                    ET.SubElement(root.find("testcase"), defect)
                save_report(directory, root)
                with self.assertRaises(qualify_linux.ProfileError):
                    self.read(directory)

    def test_single_and_aggregate_report_limits_accept_exact_bound_and_reject_one_less(self):
        directory = self.fresh_directory()
        first = save_report(directory, report_xml(self.root))
        first_size = first.stat().st_size
        with mock.patch.object(qualify_linux, "MAX_XML_BYTES", first_size):
            self.assertEqual(1, self.read(directory)["tests"])
        with mock.patch.object(qualify_linux, "MAX_XML_BYTES", first_size - 1):
            with self.assertRaises(qualify_linux.ProfileError):
                self.read(directory)
        other = "org.example.OtherTest"
        second = save_report(directory, report_xml(self.root, suite=other))
        total = first_size + second.stat().st_size
        with mock.patch.object(qualify_linux, "MAX_REPORT_BYTES", total):
            self.assertEqual(2, self.read(directory, {SUITE: 1, other: 1})["tests"])
        with mock.patch.object(qualify_linux, "MAX_REPORT_BYTES", total - 1):
            with self.assertRaises(qualify_linux.ProfileError):
                self.read(directory, {SUITE: 1, other: 1})

    @unittest.skipUnless(hasattr(os, "symlink"), "Symbolic links are unavailable")
    def test_symlinked_report_is_rejected_before_reading_external_evidence(self):
        directory = self.fresh_directory()
        external = save_report(self.root, report_xml(self.root))
        (directory / external.name).symlink_to(external)
        with self.assertRaises(qualify_linux.ProfileError):
            self.read(directory)

    @unittest.skipUnless(hasattr(os, "mkfifo"), "FIFO files are unavailable")
    def test_fifo_report_is_rejected_without_a_blocking_open(self):
        directory = self.fresh_directory()
        os.mkfifo(directory / f"TEST-{SUITE}.xml")
        with self.assertRaises(qualify_linux.ProfileError):
            self.read(directory)


class QualificationTests(TemporaryTest):
    def setUp(self):
        super().setUp()
        self.repo = self.root / "repository"
        self.inventory_path = self.repo / "scripts/storage/required-suites.json"
        self.inventory_path.parent.mkdir(parents=True)
        self.inventory_path.write_text(json.dumps({"schemaVersion": 1, "suites": {SUITE: 1}}), encoding="utf-8")
        source = self.repo / "src/test/java/org/example/OneTest.java"
        source.parent.mkdir(parents=True)
        source.write_text("class OneTest {}", encoding="utf-8")

    def invoke(self, behavior="pass", parent=None):
        parent = parent or self.fresh_directory()
        real_read = qualify_linux.read_bounded
        dev = parent.stat().st_dev
        device = f"{os.major(dev)}:{os.minor(dev)}"

        def evidence(path, limit):
            if str(path) == "/proc/self/mountinfo":
                return mount_line(device=device).encode()
            return real_read(path, limit)

        def git_result(command, **_kwargs):
            if command == ["git", "rev-parse", "HEAD"]:
                return SimpleNamespace(stdout="a" * 40 + "\n")
            if command == ["git", "status", "--porcelain"]:
                return SimpleNamespace(stdout=b"")
            self.fail(f"Unexpected external command: {command}")

        def maven_result(command, cwd, log_path, timeout):
            self.assertEqual(self.repo, cwd)
            self.assertEqual(30, timeout)
            log_path.write_text("bounded fake Maven log\n", encoding="utf-8")
            if behavior == "timeout":
                raise qualify_linux.ProfileError("Maven timed out; process-group cleanup attempted")
            if behavior == "exit":
                return 7
            options = dict(arg[2:].split("=", 1) for arg in command if arg.startswith("-D") and "=" in arg)
            scratch = Path(options["meridiandb.qualification.root"])
            reports = Path(options["meridiandb.test.reportsDirectory"])
            run_id = options["meridiandb.qualification.runId"]
            root = report_xml(scratch, run_id="stale-run" if behavior == "stale-report" else run_id)
            save_report(reports, root)
            if behavior != "missing-probe":
                profile = {"schemaVersion": 1, "runId": run_id, "fixtureRoot": str(scratch),
                           "fileStoreType": "ext4", "javaVersion": RUNTIME["java.version"],
                           "javaRuntimeVersion": RUNTIME["java.runtime.version"],
                           "javaVendor": RUNTIME["java.vendor"], "osArch": RUNTIME["os.arch"],
                           "observedCapabilities": CAPABILITIES}
                if behavior == "probe-list":
                    profile = []
                elif behavior == "wrong-probe-run":
                    profile["runId"] = "stale-run"
                elif behavior == "wrong-probe-root":
                    profile["fixtureRoot"] = str(parent)
                elif behavior == "missing-capability":
                    profile["observedCapabilities"] = CAPABILITIES[:-1]
                elif behavior == "duplicate-capability":
                    profile["observedCapabilities"] = CAPABILITIES[:-1] + [CAPABILITIES[0]]
                elif behavior == "wrong-probe-filesystem":
                    profile["fileStoreType"] = "xfs"
                elif behavior == "wrong-probe-runtime":
                    profile["javaVendor"] = "different-vendor"
                elif behavior == "missing-capability-field":
                    del profile["observedCapabilities"]
                elif behavior == "malformed-capability-list":
                    profile["observedCapabilities"] = "file-force"
                elif behavior == "malformed-capability-item":
                    profile["observedCapabilities"] = CAPABILITIES[:-1] + [{}]
                elif behavior == "unknown-capability":
                    profile["observedCapabilities"] = CAPABILITIES[:-1] + ["unrecorded-capability"]
                elif behavior == "missing-probe-runtime":
                    del profile["javaVendor"]
                elif behavior == "blank-probe-runtime":
                    profile["javaVendor"] = " "
                elif behavior == "malformed-probe-runtime":
                    profile["javaVendor"] = [RUNTIME["java.vendor"]]
                elif behavior == "missing-probe-filesystem":
                    del profile["fileStoreType"]
                elif behavior == "malformed-probe-filesystem":
                    profile["fileStoreType"] = ["ext4"]
                image = "{invalid JSON" if behavior == "malformed-probe-json" else json.dumps(profile)
                if behavior == "duplicate-probe-key":
                    image = image[:-1] + ',"javaVendor":"test-vendor"}'
                Path(options["meridiandb.qualification.profile"]).write_text(image, encoding="utf-8")
            return 0

        output = io.StringIO()
        with mock.patch.object(qualify_linux.subprocess, "run", side_effect=git_result), \
                mock.patch.object(qualify_linux, "read_bounded", side_effect=evidence), \
                mock.patch.object(qualify_linux, "device_metadata", return_value={"writeCache": None, "backingDeviceAndController": "unknown"}), \
                mock.patch.object(qualify_linux, "run_command", side_effect=maven_result) as runner, \
                contextlib.redirect_stdout(output):
            code = qualify_linux.qualify(self.repo, parent, "unit-linux", 30)
        runs = list(parent.glob("mdb-linux-*"))
        self.assertEqual(1, len(runs))
        profile_path = runs[0] / "profile.json"
        self.assertTrue(profile_path.is_file(), "Every handled failure retains its evidence report")
        profile = json.loads(profile_path.read_text(encoding="utf-8"))
        return code, profile, runs[0], runner, output.getvalue()

    def test_success_is_checks_passed_without_a_production_or_device_qualification_claim(self):
        code, profile, run, runner, output = self.invoke()
        self.assertEqual(0, code)
        self.assertEqual("checks_passed", profile["status"])
        self.assertFalse(profile["productionQualified"])
        self.assertEqual(1, profile["tests"]["tests"])
        self.assertEqual("works()", profile["tests"]["suites"][0]["cases"][0]["name"])
        self.assertEqual(run / "fixtures", Path(profile["fixtureRoot"]))
        self.assertEqual(1, runner.call_count)
        self.assertIn("Retained evidence:", output)
        self.assertTrue((run / "maven.log").is_file())
        self.assertIn("clean", profile["command"])

    def test_existing_caller_scratch_content_is_preserved_and_only_a_fresh_child_is_used(self):
        parent = self.fresh_directory()
        (parent / "keep.bin").write_bytes(b"\x00caller-data\xff")
        nested = parent / "existing-directory"
        nested.mkdir()
        (nested / "keep.txt").write_text("existing content", encoding="utf-8")
        before = {path.relative_to(parent): path.read_bytes() for path in parent.rglob("*") if path.is_file()}
        code, profile, run, _, _ = self.invoke(parent=parent)
        self.assertEqual(0, code)
        self.assertEqual(parent, run.parent)
        self.assertNotEqual(parent, Path(profile["fixtureRoot"]))
        self.assertEqual({"keep.bin", "existing-directory", run.name}, {path.name for path in parent.iterdir()})
        for relative, image in before.items():
            self.assertEqual(image, (parent / relative).read_bytes())

    def test_missing_inventory_fails_before_maven_and_retains_the_run(self):
        self.inventory_path.unlink()
        code, profile, _, runner, _ = self.invoke()
        self.assertEqual(1, code)
        self.assertEqual("failed", profile["status"])
        self.assertEqual(0, runner.call_count)
        self.assertNotIn("mavenExitCode", profile)

    def test_malformed_inventory_shapes_counts_and_source_mismatches_fail_closed(self):
        documents = [[], {"schemaVersion": 1, "suites": [SUITE]},
                     {"schemaVersion": 1, "suites": {SUITE: True}},
                     {"schemaVersion": 1, "suites": {SUITE: 0}},
                     {"schemaVersion": 2, "suites": {SUITE: 1}},
                     {"schemaVersion": 1, "suites": {"org.example.UnknownTest": 1}}]
        for document in documents:
            with self.subTest(document=document):
                self.inventory_path.write_text(json.dumps(document), encoding="utf-8")
                code, profile, _, runner, _ = self.invoke()
                self.assertEqual(1, code)
                self.assertEqual("failed", profile["status"])
                self.assertEqual(0, runner.call_count)

    def test_ordinary_maven_exit_and_timeout_have_distinct_retained_failure_evidence(self):
        for behavior in ("exit", "timeout"):
            with self.subTest(behavior=behavior):
                code, profile, run, runner, output = self.invoke(behavior)
                self.assertEqual(1, code)
                self.assertEqual("failed", profile["status"])
                self.assertEqual(1, runner.call_count)
                if behavior == "exit":
                    self.assertEqual(7, profile["mavenExitCode"])
                    self.assertIn("exit code 7", profile["error"])
                else:
                    self.assertNotIn("mavenExitCode", profile)
                    self.assertIn("timed out", profile["error"])
                self.assertEqual("bounded fake Maven log\n", (run / "maven.log").read_text(encoding="utf-8"))
                self.assertIn("bounded fake Maven log", output)

    def test_missing_malformed_or_wrongly_bound_java_probe_is_not_a_success(self):
        for behavior in ("missing-probe", "probe-list", "wrong-probe-run", "wrong-probe-root",
                         "missing-capability", "duplicate-capability", "wrong-probe-filesystem", "wrong-probe-runtime",
                         "missing-capability-field", "malformed-capability-list", "malformed-capability-item",
                         "unknown-capability", "missing-probe-runtime", "blank-probe-runtime", "malformed-probe-runtime",
                         "missing-probe-filesystem", "malformed-probe-filesystem", "malformed-probe-json", "duplicate-probe-key"):
            with self.subTest(behavior=behavior):
                code, profile, _, _, _ = self.invoke(behavior)
                self.assertEqual(1, code)
                self.assertEqual("failed", profile["status"])
                self.assertEqual(0, profile["mavenExitCode"])
                self.assertIn("error", profile)

    def test_fresh_maven_exit_cannot_legitimize_a_stale_report(self):
        code, profile, _, _, _ = self.invoke("stale-report")
        self.assertEqual(1, code)
        self.assertEqual(0, profile["mavenExitCode"])
        self.assertEqual("failed", profile["status"])
        self.assertIn("binding", profile["error"])


@unittest.skipUnless(os.name == "posix", "Dedicated process groups require POSIX")
class ProcessGroupTests(TemporaryTest):
    def test_ordinary_nonzero_exit_retains_both_output_streams_and_returns_the_exit_code(self):
        log = self.root / "ordinary.log"
        code = qualify_linux.run_command(
            [sys.executable, "-c", "import sys; print('stdout evidence'); print('stderr evidence', file=sys.stderr); sys.exit(7)"],
            self.root, log, 5)
        self.assertEqual(7, code)
        text = log.read_text(encoding="utf-8")
        self.assertIn("stdout evidence", text)
        self.assertIn("stderr evidence", text)

    def test_nonzero_leader_exit_kills_its_term_resistant_grandchild_and_retains_exit_code(self):
        child_pid = self.root / "failed-grandchild.pid"
        group_pid = self.root / "failed-leader.pid"
        grandchild = ("import os,signal,sys,time; from pathlib import Path; "
                      "signal.signal(signal.SIGTERM,signal.SIG_IGN); "
                      "Path(sys.argv[1]).write_text(str(os.getpid())); time.sleep(8)")
        leader = (
            "import os,subprocess,sys,time\n"
            "from pathlib import Path\n"
            "Path(sys.argv[2]).write_text(str(os.getpid()))\n"
            f"subprocess.Popen([sys.executable,'-c',{grandchild!r},sys.argv[1]])\n"
            "deadline=time.monotonic()+3\n"
            "while not Path(sys.argv[1]).exists() and time.monotonic()<deadline:\n"
            "    time.sleep(0.01)\n"
            "if not Path(sys.argv[1]).exists():\n"
            "    sys.exit(91)\n"
            "print('leader failed after grandchild readiness',flush=True)\n"
            "sys.exit(7)\n")
        try:
            log = self.root / "nonzero-group.log"
            code = qualify_linux.run_command(
                [sys.executable, "-c", leader, str(child_pid), str(group_pid)], self.root, log, 5)
            self.assertEqual(7, code, "Ordinary failure remains distinct from a timeout")
            self.assertTrue(child_pid.is_file(), "Grandchild must install TERM resistance before leader failure")
            leader_id, grandchild_id = int(group_pid.read_text()), int(child_pid.read_text())
            deadline = time.monotonic() + 2
            while time.monotonic() < deadline and (self.running(leader_id) or self.running(grandchild_id)):
                time.sleep(0.01)
            self.assertFalse(self.running(leader_id))
            self.assertFalse(self.running(grandchild_id), "Failed leader left its grandchild executing")
            self.assertIn("leader failed after grandchild readiness", log.read_text())
        finally:
            for path in (group_pid, child_pid):
                if path.is_file():
                    pid = int(path.read_text())
                    if self.running(pid):
                        try:
                            os.kill(pid, signal.SIGKILL)
                        except ProcessLookupError:
                            pass

    def test_existing_log_is_never_overwritten_or_used_to_start_another_process(self):
        log = self.root / "existing.log"
        log.write_bytes(b"retained evidence")
        with mock.patch.object(qualify_linux.subprocess, "Popen") as popen:
            with self.assertRaises(FileExistsError):
                qualify_linux.run_command([sys.executable, "-c", "pass"], self.root, log, 5)
            popen.assert_not_called()
        self.assertEqual(b"retained evidence", log.read_bytes())

    def test_timeout_kills_a_term_resistant_grandchild_after_the_leader_exits(self):
        child_pid = self.root / "grandchild.pid"
        group_pid = self.root / "leader.pid"
        grandchild = ("import os,signal,sys,time; from pathlib import Path; "
                      "signal.signal(signal.SIGTERM,signal.SIG_IGN); "
                      "Path(sys.argv[1]).write_text(str(os.getpid())); time.sleep(8)")
        leader = ("import os,subprocess,sys,time; from pathlib import Path; "
                  f"subprocess.Popen([sys.executable,'-c',{grandchild!r},sys.argv[1]]); "
                  "Path(sys.argv[2]).write_text(str(os.getpid())); "
                  "print('leader started',flush=True); time.sleep(8)")
        leader_id = grandchild_id = None
        try:
            with self.assertRaisesRegex(qualify_linux.ProfileError, "timed out|interrupted"):
                qualify_linux.run_command([sys.executable, "-c", leader, str(child_pid), str(group_pid)],
                                          self.root, self.root / "timeout.log", 3)
            self.assertTrue(group_pid.is_file(), "Leader must actually start before the timeout")
            self.assertTrue(child_pid.is_file(), "Grandchild must actually install TERM resistance")
            leader_id, grandchild_id = int(group_pid.read_text()), int(child_pid.read_text())
            deadline = time.monotonic() + 2
            while time.monotonic() < deadline and (self.running(leader_id) or self.running(grandchild_id)):
                time.sleep(0.01)
            self.assertFalse(self.running(leader_id), "Timed-out leader survived cleanup")
            self.assertFalse(self.running(grandchild_id), "Grandchild survived after its leader exited on TERM")
            self.assertIn("leader started", (self.root / "timeout.log").read_text())
        finally:
            # Even a regressed cleanup path leaves only an eight-second fixture; actively
            # terminate surviving test-owned processes so this test never leaks workers.
            for path in (group_pid, child_pid):
                if path.is_file():
                    pid = int(path.read_text())
                    if self.running(pid):
                        try:
                            os.kill(pid, signal.SIGKILL)
                        except ProcessLookupError:
                            pass

    @staticmethod
    def running(pid):
        try:
            os.kill(pid, 0)
        except ProcessLookupError:
            return False
        # Linux container PID 1 may delay reaping an orphan. A zombie has exited and
        # cannot execute I/O, even though kill(pid, 0) still finds its process table row.
        status = Path(f"/proc/{pid}/stat")
        try:
            if status.is_file() and status.read_text().rsplit(")", 1)[1].split()[0] == "Z":
                return False
        except FileNotFoundError:
            return False
        return True


if __name__ == "__main__":
    unittest.main()
