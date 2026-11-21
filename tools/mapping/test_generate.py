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
    def test_check_fails_when_selected_version_has_no_resolver(self):
        with tempfile.TemporaryDirectory() as directory:
            missing = os.path.join(directory, "mappings-26.3.json")
            argv = ["generate.py", "--check", "--only", "26.3"]
            with mock.patch.object(generate.sys, "argv", argv), \
                    mock.patch.object(generate.scan, "collect", return_value=object()), \
                    mock.patch.object(generate, "load_member_aliases", return_value={}), \
                    mock.patch.object(generate.sources, "Manifest", return_value=object()), \
                    mock.patch.object(generate, "load_shape_hints", return_value={}), \
                    mock.patch.object(generate.namespaces, "Fetch", return_value=object()), \
                    mock.patch.object(generate, "build_anchor_table", return_value=object()), \
                    mock.patch.object(generate, "release_versions", return_value=["26.3"]), \
                    mock.patch.object(generate.namespaces, "build_resolver", return_value=None), \
                    mock.patch.object(generate, "table_path", return_value=missing), \
                    mock.patch.object(generate, "build_table") as build:
                with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
                    result = generate.main()

            self.assertEqual(1, result, "显式覆盖的版本缺 resolver/表时不得通过门禁")
            build.assert_not_called()
    def test_fast_check_rejects_ambiguous_absent_reason(self):
        table = {"version": "1.8.9", "classes": {
            "example/Type": {"absent": True, "absentReason": "ambiguous"},
        }}
        with tempfile.NamedTemporaryFile("w", encoding="utf-8", newline="", delete=False) as handle:
            handle.write(generate.render(table))
            path = handle.name
        try:
            def fresh_table(version, resolver, requirements, report, shape_hints, aliases, lint):
                report["versions"][version] = {
                    "classes": 1, "resolved": 0, "absent": [], "namespaces": [],
                }
                return table

            argv = ["generate.py", "--check", "--fast", "--only", "1.8.9"]
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
                    mock.patch.object(generate, "verify_javap") as verify:
                with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
                    result = generate.main()

            self.assertEqual(1, result, "--fast 也不能接受 ambiguous absentReason")
            verify.assert_not_called()
        finally:
            os.unlink(path)
    def test_field_descriptor_is_checked_against_jar(self):
        with tempfile.NamedTemporaryFile(suffix=".jar") as jar:
            class Index:
                transient_failures = 0

                def __init__(self):
                    self.calls = []

                def exists(self, internal):
                    return True

                def reachable(self, internal, kind, name, signature=None):
                    self.calls.append((kind, name, signature))
                    return False

                def save(self):
                    pass

            index = Index()
            table = {"classes": {"example/Type": {
                "names": {"vanilla": "example/Type"}, "methods": {}, "fields": {
                    "count": {"names": {"vanilla": "a"}, "descriptors": {"vanilla": "I"}},
                },
            }}}
            with mock.patch.object(generate, "find_local_jar", return_value=jar.name), \
                    mock.patch.object(generate.javap, "JavapIndex", return_value=index):
                problems, checked, skipped, absent = generate.verify_javap("1.8.9", table)
            self.assertEqual([("field", "a", "I")], index.calls)
            self.assertTrue(problems, "字段描述符不匹配必须成为 jar 校验问题")

    def test_non_vanilla_field_descriptor_syntax_is_checked(self):
        table = {"classes": {"example/Type": {"fields": {
            "count": {"descriptors": {"fabric": "not-a-descriptor"}},
        }}}}
        problems = generate.descriptor_problems("1.20.1", table)
        self.assertEqual(1, len(problems))
        self.assertIn("fabric", problems[0])


if __name__ == "__main__":
    unittest.main()
