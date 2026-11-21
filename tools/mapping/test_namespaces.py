#!/usr/bin/env python3
"""intermediary 名称与描述符配对回归。"""

import unittest

from namespaces import Intermediary, ModernResolver


class AbsentReasonTest(unittest.TestCase):
    def resolver(self, index):
        resolver = ModernResolver.__new__(ModernResolver)
        resolver.index = index
        resolver._supers = {}
        resolver.to_obf = {"example/Type": "example/RuntimeType"}
        resolver.mojmap = type("MojmapStub", (), {
            "methods": {}, "fields": {}, "from_obf": {},
            "class_of": lambda self, key: "example/RuntimeType" if key == "example/Type" else None,
        })()
        return resolver

    def test_missing_class_without_jar_is_not_verified(self):
        self.assertEqual("unverified", self.resolver(None).absent_reason(
            "example/Missing", "class", None))

    def test_jar_hit_for_missing_class_is_ambiguous(self):
        index = type("Index", (), {"exists": lambda self, name: True})()
        self.assertEqual("ambiguous", self.resolver(index).absent_reason(
            "example/Missing", "class", None))

    def test_member_absence_is_checked_against_runtime_owner(self):
        index = type("Index", (), {
            "reachable": lambda self, owner, kind, name, signature=None: kind == "method",
            "class_info": lambda self, name: None,
        })()
        resolver = self.resolver(index)
        self.assertEqual("ambiguous", resolver.absent_reason("example/Type", "method", "tick"))
        self.assertEqual("verified", resolver.absent_reason("example/Type", "field", "missing"))


class IntermediaryTest(unittest.TestCase):
    def test_descriptor_miss_does_not_borrow_overload_name(self):
        intermediary = Intermediary(
            "v1\tofficial\tintermediary\n"
            "CLASS\tvg\tnet/minecraft/class_1297\n"
            "METHOD\tvg\t(DDD)D\td\tmethod_5649\n"
        )

        self.assertEqual("method_5649", intermediary.method("vg", "d", "(DDD)D"))
        self.assertIsNone(intermediary.method("vg", "d", "()Lbhe;"),
                          "position 不得借用 distanceToSqr 的 intermediary ID")
        self.assertEqual("method_5649", intermediary.method("vg", "d", None),
                         "只有显式无描述符查询才允许按名字回退")


if __name__ == "__main__":
    unittest.main()
