#!/usr/bin/env python3
"""``javap``-backed class introspection, for the identity/v26 tables and for
cross-checking the obfuscated tables against a real client jar.

The results are cached in ``cache/javap-<version>.json`` keyed by the jar so
``--check`` never has to re-run javap for an unchanged jar.

Stdlib only (subprocess + zipfile).
"""

from __future__ import annotations

import json
import os
import re
import subprocess
import zipfile
from collections import defaultdict


def class_entries(jar_path: str) -> "set[str]":
    """Every internal class name in the jar (``a/b/C`` for ``a/b/C.class``)."""
    names = set()
    with zipfile.ZipFile(jar_path) as jar:
        for entry in jar.namelist():
            if entry.endswith(".class") and not entry.startswith("META-INF/"):
                names.add(entry[:-len(".class")])
    return names


class JavapIndex:
    """Lazy, cached ``javap`` view of one jar."""

    def __init__(self, jar_path: str, javap_exe: str, cache_path: "str | None" = None) -> None:
        self.jar_path = jar_path
        self.javap_exe = javap_exe
        self.cache_path = cache_path
        self._cache: "dict[str, dict | None]" = {}
        self._dirty = False
        if cache_path and os.path.exists(cache_path):
            try:
                data = json.load(open(cache_path, encoding="utf-8"))
                if data.get("jar") == self._jar_stamp():
                    self._cache = data.get("classes", {})
            except (ValueError, OSError):
                self._cache = {}

    def _jar_stamp(self) -> dict:
        st = os.stat(self.jar_path)
        return {"size": st.st_size, "mtime": int(st.st_mtime)}

    def save(self) -> None:
        if not self.cache_path or not self._dirty:
            return
        os.makedirs(os.path.dirname(self.cache_path), exist_ok=True)
        json.dump(
            {"jar": self._jar_stamp(), "classes": self._cache},
            open(self.cache_path, "w", encoding="utf-8"),
            ensure_ascii=False,
        )
        self._dirty = False

    def class_info(self, internal_name: str) -> "dict | None":
        if internal_name in self._cache:
            return self._cache[internal_name]
        info = self._run(internal_name)
        self._cache[internal_name] = info
        self._dirty = True
        return info

    def _run(self, internal_name: str) -> "dict | None":
        try:
            proc = subprocess.run(
                [self.javap_exe, "-p", "-s", "-classpath", self.jar_path, internal_name],
                capture_output=True, text=True, encoding="utf-8", errors="replace",
                timeout=180,
            )
        except (OSError, subprocess.SubprocessError):
            return None
        if proc.returncode != 0:
            return None
        text = proc.stdout
        supers: "list[str]" = []
        header = re.search(r"\b(?:class|interface|enum|record)\s+([\w.$]+)(.*)", text)
        if header:
            rest = re.sub(r"<[^<>]*>", "", header.group(2))
            ext = re.search(r"\bextends\s+(.*?)(?:\bimplements\b|$)", rest)
            imp = re.search(r"\bimplements\s+(.*)$", rest)
            for chunk in (ext.group(1) if ext else "", imp.group(1) if imp else ""):
                for name in re.findall(r"[\w.$]+", chunk):
                    if name not in ("extends", "implements"):
                        supers.append(name)
        methods: "dict[str, list]" = defaultdict(list)
        fields: "dict[str, list]" = defaultdict(list)
        lines = text.split("\n")
        for i, line in enumerate(lines):
            stripped = line.strip()
            if i + 1 >= len(lines) or not lines[i + 1].strip().startswith("descriptor:"):
                continue
            desc = lines[i + 1].split("descriptor:", 1)[1].strip()
            if "(" in stripped:
                m = re.search(r"([\w$<>]+)\(", stripped)
                if m:
                    methods[m.group(1)].append(desc)
            elif stripped.endswith(";"):
                fname = re.sub(r".*\s", "", stripped.rstrip(";"))
                fname = fname.split(".")[-1]
                fields[fname].append(desc)
        return {
            "supers": [s.replace(".", "/") for s in supers],
            "methods": dict(methods),
            "fields": dict(fields),
        }

    def reachable(self, internal_name: str, kind: str, name: str,
                  signature: "str | None" = None) -> bool:
        """Whether ``name`` (with optional descriptor) is declared on the class or a super."""
        seen = set()
        stack = [internal_name]
        while stack:
            current = stack.pop()
            if current in seen:
                continue
            seen.add(current)
            info = self.class_info(current)
            if info is None:
                continue
            table = info["methods"] if kind == "method" else info["fields"]
            if name in table:
                descriptors = table[name]
                if signature is None or signature in descriptors:
                    return True
            stack.extend(info["supers"])
        return False

    def exists(self, internal_name: str) -> bool:
        return self.class_info(internal_name) is not None
