#!/usr/bin/env python3
"""intermediary 名称与描述符配对回归。"""

import unittest

from namespaces import Intermediary


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
