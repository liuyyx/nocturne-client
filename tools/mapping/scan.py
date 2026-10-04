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
METHOD_APIS = ("callMapped", "methodName")
# APIS that name a *field*.
FIELD_APIS = ("readField", "writeField", "fieldName")


@dataclass
class Requirements:
    """The set of canonical classes/members the mapping tables must answer."""

    classes: set = field(default_factory=set)
    methods: dict = field(default_factory=dict)  # canonical class -> set(method)
    fields: dict = field(default_factory=dict)  # canonical class -> set(field)

    def add_class(self, canonical: str) -> None:
        canonical = canonical.replace(".", "/")
        self.classes.add(canonical)
        self.methods.setdefault(canonical, set())
        self.fields.setdefault(canonical, set())

    def add_method(self, canonical: str, name: str) -> None:
        self.add_class(canonical)
        self.methods[canonical.replace(".", "/")].add(name)

    def add_field(self, canonical: str, name: str) -> None:
        self.add_class(canonical)
        self.fields[canonical.replace(".", "/")].add(name)

    def merge(self, other: "Requirements") -> None:
        for c in other.classes:
            self.add_class(c)
        for c, names in other.methods.items():
            for n in names:
                self.add_method(c, n)
        for c, names in other.fields.items():
            for n in names:
                self.add_field(c, n)


def parse_class_type(path: str) -> "dict[str, str]":
    """``ClassType.java`` enum constant -> canonical (dotted) class name."""
    text = open(path, encoding="utf-8").read()
    result: "dict[str, str]" = {}
    # e.g.  MINECRAFT("net.minecraft.client.Minecraft"),
    for constant, canonical in re.findall(
        r"^\s{4}([A-Z][A-Z0-9_]*)\(\"([^\"]+)\"\)", text, re.M
    ):
        result[constant] = canonical
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


def scan_sources(client_src: str, class_type: "dict[str, str]") -> Requirements:
    """Walk ``client/src/main/java`` and collect every mapping-table literal."""
    req = Requirements()
    for canonical in class_type.values():
        req.add_class(canonical)

    api_re = re.compile(
        r"\b(" + "|".join(METHOD_APIS + FIELD_APIS) + r")\s*\(",
        re.S,
    )
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
            # Decide method vs field by the nearest preceding mapping API on the
            # same statement (within ~200 chars, not crossing a `;`).
            head = text[max(0, match.start() - 240):match.start()]
            statement = head.rsplit(";", 1)[-1]
            api = None
            for m in api_re.finditer(statement):
                api = m.group(1)
            if api in METHOD_APIS:
                req.add_method(canonical, member)
            elif api in FIELD_APIS:
                req.add_field(canonical, member)
    return req


def parse_requirements(path: str) -> Requirements:
    """Read the explicit ``requirements.txt`` list.

    ``class``                  -> the class is needed
    ``class#field``            -> a field
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
        if not member:
            req.add_class(canonical)
        elif member.endswith("()"):
            req.add_method(canonical, member[:-2])
        else:
            req.add_field(canonical, member)
    return req


def collect(client_src: str, class_type_path: str, requirements_path: str) -> Requirements:
    class_type = parse_class_type(class_type_path)
    req = scan_sources(client_src, class_type)
    req.merge(parse_requirements(requirements_path))
    return req
