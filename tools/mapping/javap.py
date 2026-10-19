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
        # 瞬时失败计数（javap 不存在/超时/非零退出但**不是**"类不存在"）。
        # 这些结果绝不进缓存：一次网络盘抖动或超时若被当成"该类在本版本不存在"，
        # 生成出来的表会把整类成员标成 absent，而 --check 依然全绿——运行期表现为
        # "功能静默失效"，排查成本极高。
        self.transient_failures = 0
        # jar 里的类名集合（惰性加载）：判断"类确实不存在"要比对 javap 的错误文本可靠得多——
        # javap 的错误信息是本地化文本（中文环境走 GBK），按字符串匹配在非英文环境恒不命中。
        self._entries: "set[str] | None" = None
        if cache_path and os.path.exists(cache_path):
            try:
                data = json.load(open(cache_path, encoding="utf-8"))
                if data.get("jar") == self._jar_stamp():
                    self._cache = data.get("classes", {})
            except (ValueError, OSError):
                self._cache = {}

    def _jar_entries(self) -> "set[str] | None":
        """惰性读取 jar 的类条目集合；读取失败返回 ``None``（此时不做任何可信性判断）。"""
        if self._entries is None:
            try:
                self._entries = class_entries(self.jar_path)
            except (OSError, zipfile.BadZipFile):
                return None
        return self._entries

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
        info, definitive = self._run(internal_name)
        if definitive:
            # 只有"javap 确实跑完并给出了结论"才缓存（结论也可能是 None＝该类不在 jar 里）。
            self._cache[internal_name] = info
            self._dirty = True
        else:
            self.transient_failures += 1
        return info

    def _run(self, internal_name: str) -> "tuple[dict | None, bool]":
        """跑一次 javap。

        @return ``(信息, 结论是否可信)``。第二个值为 ``False`` 表示这次调用**没能给出结论**
                （javap 缺失、超时、崩溃……），调用方不得把它当成"该类不存在"。
        """
        try:
            proc = subprocess.run(
                [self.javap_exe, "-p", "-s", "-classpath", self.jar_path, internal_name],
                capture_output=True, text=True, encoding="utf-8", errors="replace",
                timeout=180,
            )
        except (OSError, subprocess.SubprocessError):
            return None, False
        if proc.returncode != 0:
            # 只有"这个类根本不在 jar 里"才是可信结论。判据用 jar 条目本身，而不是 javap 的
            # 错误文本（那是本地化字符串，非英文环境匹配不到）。类在 jar 里却失败 ⇒ 瞬时故障。
            if self._jar_entries() is not None:
                if internal_name not in self._jar_entries():
                    return None, True
                return None, False
            return None, False
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
        }, True

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
