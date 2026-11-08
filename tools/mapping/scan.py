#!/usr/bin/env python3
"""Scan the client Java sources for members that go through the mapping table.

The client spells canonical Mojmap names in two places:

* every game class it cares about appears as a ``ClassType`` enum constant
  (``ClassType.java``), and
* every member it resolves is a **string literal** passed next to that
  ``ClassType`` to one of the reflection-bridge calls
  (``callMapped`` / ``methodName`` for methods, ``readField`` / ``writeField``
  / ``fieldName`` for fields).

Those two literal kinds are exactly what this module extracts.  Members whose
name travels through a variable cannot be seen here; they belong in
``requirements.txt``.

Stdlib only.
"""

from __future__ import annotations

import os
import re
from dataclasses import dataclass, field


# APIS that name a *method* on the class following the ClassType literal.
METHOD_APIS = ("callMapped", "callMappedStatus", "callStaticMapped", "methodName")
# APIS that name a *field*.
FIELD_APIS = ("readField", "writeField", "fieldName")


@dataclass
class Requirements:
    """The set of canonical classes/members the mapping tables must answer."""

    classes: set = field(default_factory=set)
    methods: dict = field(default_factory=dict)  # canonical class -> set(method)
    fields: dict = field(default_factory=dict)  # canonical class -> set(field)
    # 显式声明"就算同名方法存在，客户端也是按字段用"的成员（`#name!field`）。
    # 用于 dayTime/rgb 这类**同时**有字段与方法入口的成员，免得生成器的
    # 方法/字段互斥检查把它们误判成登记错误。
    forced_fields: dict = field(default_factory=dict)

    def add_class(self, canonical: str) -> None:
        canonical = canonical.replace(".", "/")
        self.classes.add(canonical)
        self.methods.setdefault(canonical, set())
        self.fields.setdefault(canonical, set())

    def add_method(self, canonical: str, name: str) -> None:
        self.add_class(canonical)
        self.methods[canonical.replace(".", "/")].add(name)

    def add_field(self, canonical: str, name: str, forced: bool = False) -> None:
        self.add_class(canonical)
        key = canonical.replace(".", "/")
        self.fields[key].add(name)
        if forced:
            self.forced_fields.setdefault(key, set()).add(name)

    def merge(self, other: "Requirements") -> None:
        for c in other.classes:
            self.add_class(c)
        for c, names in other.methods.items():
            for n in names:
                self.add_method(c, n)
        for c, names in other.fields.items():
            for n in names:
                self.add_field(c, n, forced=n in other.forced_fields.get(c, ()))


def parse_class_type(path: str) -> "dict[str, str]":
    """``ClassType.java`` enum constant -> canonical (dotted) class name.

    缩进不敏感：早先的写法要求"恰好四个空格"，一次 IDE 重排（或换成制表符）就会**静默**
    丢掉整类需求——映射表随之少一项，而 ``--check`` 依然全绿，运行期表现为"功能悄悄失效"。
    这里放宽缩进，并在末尾做一次自检：源文件里出现的 ``NAME("...")`` 形态若多于解析结果，
    直接报错而不是继续往下生成。
    """
    text = open(path, encoding="utf-8").read()
    result: "dict[str, str]" = {}
    # e.g.  MINECRAFT("net.minecraft.client.Minecraft"),   （空格/制表符缩进均可）
    for constant, canonical in re.findall(
        r"^[ \t]*([A-Z][A-Z0-9_]*)[ \t]*\([ \t]*\"([^\"]+)\"", text, re.M
    ):
        result[constant] = canonical
    # 枚举常量声明（`NAME(`、`NAME,`、`NAME;`、`NAME {`）都要被上面的正则解释掉；
    # 少解释一个就说明有需求面被静默丢掉，此时拒绝继续生成。
    declared = len(re.findall(r"^[ \t]*[A-Z][A-Z0-9_]*[ \t]*[(:,;{]", text, re.M))
    if declared != len(result):
        raise ValueError(
            f"{path}: 解析出 {len(result)} 个 ClassType 常量，但源文件里有 {declared} 处 "
            f"NAME(\"...\") 形态——两者的差异意味着有需求面被静默丢掉，拒绝继续生成"
        )
    return result


def _blank_comments(text: str) -> str:
    """Blank out comments (preserving offsets) so literals in docs don't count."""
    out = list(text)
    i, n = 0, len(text)
    while i < n:
        ch = text[i]
        if ch == '"':
            i += 1
            while i < n:
                if text[i] == "\\":
                    i += 2
                    continue
                if text[i] == '"':
                    i += 1
                    break
                i += 1
            continue
        if ch == "/" and i + 1 < n and text[i + 1] == "/":
            while i < n and text[i] != "\n":
                out[i] = " "
                i += 1
            continue
        if ch == "/" and i + 1 < n and text[i + 1] == "*":
            while i < n:
                if text[i] == "*" and i + 1 < n and text[i + 1] == "/":
                    out[i] = out[i + 1] = " "
                    i += 2
                    break
                if text[i] != "\n":
                    out[i] = " "
                i += 1
            continue
        i += 1
    return "".join(out)


def _enclosing_api(statement: str, position: int) -> "str | None":
    """The API call that directly wraps ``position`` in ``statement``.

    括号栈从内往外找：第一个落在 ``METHOD_APIS``/``FIELD_APIS`` 里的调用就是它。
    """
    stack: "list[str | None]" = []
    for index in range(position):
        char = statement[index]
        if char == "(":
            preceding = re.search(r"([A-Za-z_$][\w$]*)\s*$", statement[:index])
            stack.append(preceding.group(1) if preceding else None)
        elif char == ")" and stack:
            stack.pop()
    for name in reversed(stack):
        if name in METHOD_APIS or name in FIELD_APIS:
            return name
    return None

def scan_sources(client_src: str, class_type: "dict[str, str]") -> Requirements:
    """Walk ``client/src/main/java`` and collect every mapping-table literal."""
    req = Requirements()
    for canonical in class_type.values():
        req.add_class(canonical)

    # ClassType.<CONST> ... "literal"  — allow other arguments in between, but
    # never cross a statement terminator (`;`) or another ClassType reference.
    member_re = re.compile(
        r"ClassType\.([A-Z][A-Z0-9_]*)\s*,\s*\"([^\"]+)\"",
        re.S,
    )

    files = []
    for root, _dirs, names in os.walk(client_src):
        for name in names:
            if name.endswith(".java"):
                files.append(os.path.join(root, name))
    files.sort()

    for path in files:
        raw = open(path, encoding="utf-8").read()
        text = _blank_comments(raw)
        for match in member_re.finditer(text):
            constant, member = match.group(1), match.group(2)
            if constant not in class_type:
                continue
            canonical = class_type[constant]
            # 方法/字段由**直接包住这个字面量的那次调用**决定，而不是"往前最近出现的 API"：
            # callMapped(readField(opts, OPTIONS, "fov"), OPTION_INSTANCE, "get")
            # 里 `get` 的直接外层是 callMapped（方法），往前最近的却是 readField（字段）。
            head = text[max(0, match.start() - 400):match.start()]
            statement = head.rsplit(";", 1)[-1]
            api = _enclosing_api(statement, len(statement))
            if api in METHOD_APIS:
                req.add_method(canonical, member)
            elif api in FIELD_APIS:
                req.add_field(canonical, member)
    return req


def parse_requirements(path: str) -> Requirements:
    """Read the explicit ``requirements.txt`` list.

    ``class``                  -> the class is needed
    ``class#field``            -> a field
    ``class#field!field``      -> a field, even though a same-named method exists
    ``class#method()``         -> a method
    """
    req = Requirements()
    if not os.path.exists(path):
        return req
    for raw in open(path, encoding="utf-8"):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if "#" not in line:
            req.add_class(line.split()[0] if line.split() else line)
            continue
        canonical, member = line.split("#", 1)
        canonical = canonical.strip()
        member = member.strip().split(" ")[0] if member.strip() else ""
        forced = member.endswith("!field")
        if forced:
            member = member[:-len("!field")]
        if not member:
            req.add_class(canonical)
        elif member.endswith("()"):
            req.add_method(canonical, member[:-2])
        else:
            req.add_field(canonical, member, forced=forced)
    return req


def collect(client_src, class_type_path: str, requirements_path: str) -> Requirements:
    """Scan every mapping-table consumer, not just ``client``.

    ``ui``/``agent`` call the same bridge (``GameBridge.callMapped`` etc.), so a
    literal spelled only there is just as much a requirement as one in
    ``client``.  Missing roots are skipped silently (they cannot exist in a
    trimmed checkout), but every existing root is scanned.
    """
    roots = [client_src] if isinstance(client_src, str) else list(client_src)
    class_type = parse_class_type(class_type_path)
    req = Requirements()
    for root in roots:
        if os.path.isdir(root):
            req.merge(scan_sources(root, class_type))
    req.merge(parse_requirements(requirements_path))
    return req
