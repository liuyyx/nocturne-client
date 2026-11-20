#!/usr/bin/env python3
"""映射门禁的进程级返回码回归。"""

import contextlib
import io
import os
import tempfile
import unittest
from unittest import mock

import generate


class GenerateCheckTest(unittest.TestCase):
    def test_check_fails_when_fresh_table_has_no_jar(self):
        table = {"classes": {}}
        with tempfile.NamedTemporaryFile("w", encoding="utf-8", newline="", delete=False) as handle:
            handle.write(generate.render(table))
            path = handle.name
        try:
            def fresh_table(version, resolver, requirements, report, shape_hints, aliases, lint):
                report["versions"][version] = {
                    "classes": 0, "resolved": 0, "absent": [], "namespaces": [],
                }
                return table

            skipped = (["1.8.9: no local client jar"], 0, True,
                       {"verified": 0, "unverified": 0})
            argv = ["generate.py", "--check", "--only", "1.8.9"]
            with mock.patch.object(generate.sys, "argv", argv), \
                    mock.patch.object(generate.scan, "collect", return_value=object()), \
                    mock.patch.object(generate, "load_member_aliases", return_value={}), \
                    mock.patch.object(generate.sources, "Manifest", return_value=object()), \
                    mock.patch.object(generate, "load_shape_hints", return_value={}), \
                    mock.patch.object(generate.namespaces, "Fetch", return_value=object()), \
                    mock.patch.object(generate, "build_anchor_table", return_value=object()), \
                    mock.patch.object(generate, "release_versions", return_value=["1.8.9"]), \
                    mock.patch.object(generate.namespaces, "build_resolver", return_value=object()), \
                    mock.patch.object(generate, "build_table", side_effect=fresh_table), \
                    mock.patch.object(generate, "table_path", return_value=path), \
                    mock.patch.object(generate, "verify_javap", return_value=skipped) as verify:
                with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
                    result = generate.main()

            self.assertEqual(1, result, "表即使最新，默认 --check 也不能把缺 jar 当作成功")
            verify.assert_called_once()
        finally:
            os.unlink(path)


if __name__ == "__main__":
    unittest.main()
