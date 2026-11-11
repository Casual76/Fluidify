"""Host-only parser regressions with synthetic DEX; never compiles Android code."""
import importlib.util
from pathlib import Path
import struct
import tempfile
import unittest
import zipfile

spec = importlib.util.spec_from_file_location("checker", Path(__file__).resolve().parents[1] / "tools/check-source-proof-artifact.py")
checker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checker)


def dex(names, defined):
    data = bytearray(112 + 8 * len(names) + 32 * len(defined))
    data[:8] = b"dex\n035\0"
    def put(offset, value):
        struct.pack_into("<I", data, offset, value)
    put(36, 112)
    put(40, 0x12345678)
    put(56, len(names)); put(60, 112)
    put(64, len(names)); put(68, 112 + 4 * len(names))
    put(96, len(defined)); put(100, 112 + 8 * len(names))
    for i, name in enumerate(names):
        put(112 + 4 * i, len(data))
        put(112 + 4 * len(names) + 4 * i, i)
        data.extend(bytes([len(name)]) + name.encode("ascii") + b"\0")
    for i, index in enumerate(defined):
        put(112 + 8 * len(names) + 32 * i, index)
    put(32, len(data))
    return bytes(data)


class ArtifactTests(unittest.TestCase):
    def test_referenced_descriptor_does_not_count_as_definition(self):
        self.assertEqual({"Lpresent;"}, checker.dex_classes(dex(["Lpresent;", "Lreference;"], [0])))

    def test_definition_is_resolved_through_type_index(self):
        self.assertEqual({"Lsecond;"}, checker.dex_classes(dex(["Lfirst;", "Lsecond;"], [1])))

    def test_truncated_and_unsupported_headers_fail_closed(self):
        for raw in (b"dex\n", b"bad" * 50, dex([], [])[:111], b"dex\n041\0" + bytes(104)):
            with self.assertRaises(ValueError): checker.dex_classes(raw)

    def test_out_of_bounds_table_fails_closed(self):
        raw = bytearray(dex(["Lone;"], [0]))
        struct.pack_into("<I", raw, 100, len(raw))
        with self.assertRaises(ValueError): checker.dex_classes(raw)

    def test_invalid_class_index_fails_closed(self):
        with self.assertRaises(ValueError): checker.dex_classes(dex(["Lone;"], [1]))

    def test_string_only_apk_fails_coverage(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "test.apk"
            with zipfile.ZipFile(path, "w") as apk:
                apk.writestr("classes.dex", dex(sorted(checker.REQUIRED), []))
            self.assertEqual("FAIL", checker.inspect_apk(path)["status"])

    def test_multidex_definitions_pass_coverage(self):
        names = sorted(checker.REQUIRED)
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "test.apk"
            with zipfile.ZipFile(path, "w") as apk:
                apk.writestr("classes.dex", dex(names, list(range(0, len(names), 2))))
                apk.writestr("classes2.dex", dex(names, list(range(1, len(names), 2))))
            self.assertEqual("PASS", checker.inspect_apk(path)["status"])

    def test_apk_without_dex_fails_closed(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "test.apk"
            with zipfile.ZipFile(path, "w") as apk: apk.writestr("assets/classes.dex", b"ignored")
            with self.assertRaises(ValueError): checker.inspect_apk(path)


if __name__ == "__main__":
    unittest.main()
