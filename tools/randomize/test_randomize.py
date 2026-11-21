import io
import tempfile
import unittest
import zipfile
from pathlib import Path

import importlib.util

_SPEC = importlib.util.spec_from_file_location(
    "nocturne_randomize", Path(__file__).with_name("randomize.py"))
randomize = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(randomize)


def class_blob(internal_name):
    encoded = internal_name.encode("utf-8")
    pool = (b"\x01" + len(encoded).to_bytes(2, "big") + encoded
            + b"\x07\x00\x01")
    return b"\xca\xfe\xba\xbe\x00\x00\x00\x34" + (3).to_bytes(2, "big") + pool


class SaltedCheckTest(unittest.TestCase):
    def test_check_verifies_mapping_against_original_and_output(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "source.jar"
            output = Path(directory) / "randomized.jar"
            with zipfile.ZipFile(source, "w") as jar:
                jar.writestr("dev/nocturne/example/Original.class",
                             class_blob("dev/nocturne/example/Original"))
            result = randomize.main(["--in", str(source), "--out", str(output),
                                     "--salt", "fixed-test-salt"])
            self.assertEqual(0, result)

            checked = randomize.main(["--in", str(source), "--out", str(output),
                                      "--salt", "fixed-test-salt", "--check"])
            self.assertEqual(0, checked)
            wrong = randomize.main(["--in", str(source), "--out", str(output),
                                    "--salt", "another-salt", "--check"])
            self.assertEqual(1, wrong)
            missing_output = randomize.main(["--in", str(source),
                                             "--salt", "fixed-test-salt", "--check"])
            self.assertEqual(2, missing_output)


if __name__ == "__main__":
    unittest.main()
