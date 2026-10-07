#!/usr/bin/env python3
"""Unified mapping sources for the version tables.

Three backends produce the same answer to the same three questions —
``resolve_class`` / ``resolve_method`` / ``resolve_field``:

* :class:`LegacySource`  — 1.8.9 / 1.12.2.  Mojmap -> MCP human name (aliases
  TOML) -> SRG (forge CSVs) -> obf name + obf JNI descriptor (vanilla
  ``joined.srg``).
* :class:`ProguardSource` — 1.16.5 / 1.20.1 / 1.21.4 / 1.21.10 / 1.21.11.
  The official ``client_mappings`` ProGuard file already maps Mojmap -> obf, so
  one lookup plus descriptor rewriting is enough.
* :class:`IdentitySource` — 26.2 / 26.3 (unobfuscated).  ``name`` is the
  canonical name; member existence is verified against the real client jar so
  members that moved/were removed are marked ``absent`` instead of guessed.

Stdlib only.
"""

from __future__ import annotations

import json
import os
import re
import tomllib
import urllib.request
from collections import defaultdict
from dataclasses import dataclass

import javap


MANIFEST_URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"


@dataclass
class Answer:
    """One resolved (or unresolvable) class/member."""

    ok: bool
    name: "str | None" = None       # runtime (obf) name; None when absent
    signature: "str | None" = None  # obf JNI descriptor for methods
    reason: "str | None" = None     # why it is absent
    # Forge 等环境在运行期把成员重映射成 SRG 名（Minecraft.inGameHasFocus -> field_71415_G），
    # 而原版混淆名（w）在那里不存在。表里带上 SRG 名，运行期就能按 [obf, srg, canonical]
    # 依次尝试——否则 Forge 目标上按表名访问成员必然失败（写字段/读字段全落空）。
    srg: "str | None" = None        # SRG name, when the source knows one

    @staticmethod
    def found(name: str, signature: "str | None" = None, srg: "str | None" = None) -> "Answer":
        return Answer(True, name=name, signature=signature, srg=srg)

    @staticmethod
    def absent(reason: str) -> "Answer":
        return Answer(False, reason=reason)


# --------------------------------------------------------------------------
# shared helpers
# --------------------------------------------------------------------------


_DESC_RE = re.compile(r"^\((?:\[*(?:[ZBCSIJFD]|L[^;]+;))*\)(?:\[*(?:[ZBCSIJFD]|L[^;]+;)|V)$")


def validate_signature(signature: str) -> None:
    if not _DESC_RE.match(signature):
        raise ValueError(f"invalid JNI descriptor: {signature}")


def _download(url: str, dest: str) -> str:
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    if not os.path.exists(dest) or os.path.getsize(dest) == 0:
        tmp = dest + ".part"
        with urllib.request.urlopen(url, timeout=120) as response, open(tmp, "wb") as out:
            while True:
                chunk = response.read(1 << 20)
                if not chunk:
                    break
                out.write(chunk)
        os.replace(tmp, dest)
    return dest


class Manifest:
    """Cached view of Mojang's version manifest + per-version metadata."""

    def __init__(self, cache_dir: str) -> None:
        self.cache_dir = cache_dir
        self._manifest = None

    def _manifest_path(self) -> str:
        return os.path.join(self.cache_dir, "version_manifest_v2.json")

    def manifest(self) -> dict:
        if self._manifest is None:
            path = _download(MANIFEST_URL, self._manifest_path())
            self._manifest = json.load(open(path, encoding="utf-8"))
        return self._manifest

    def version_info(self, version: str) -> dict:
        manifest = self.manifest()
        url = next((v["url"] for v in manifest["versions"] if v["id"] == version), None)
        if url is None:
            raise KeyError(f"version {version} not in manifest")
        path = _download(url, os.path.join(self.cache_dir, f"{version}.json"))
        return json.load(open(path, encoding="utf-8"))

    def mappings_text(self, version: str) -> str:
        info = self.version_info(version)
        url = info.get("downloads", {}).get("client_mappings", {}).get("url")
        if not url:
            raise KeyError(f"version {version} has no client_mappings")
        path = _download(url, os.path.join(self.cache_dir, f"client-{version}.txt"))
        return open(path, encoding="utf-8").read()

    def client_jar(self, version: str) -> "str | None":
        info = self.version_info(version)
        url = info.get("downloads", {}).get("client", {}).get("url")
        if not url:
            return None
        return _download(url, os.path.join(self.cache_dir, f"client-{version}.jar"))


# --------------------------------------------------------------------------
# legacy (1.8.9 / 1.12.2): SRG + forge CSV + alias bridge
# --------------------------------------------------------------------------


class McpData:
    """``joined.srg`` + forge ``methods.csv``/``fields.csv`` in either direction."""

    def __init__(self) -> None:
        self.obf_class: "dict[str, str]" = {}   # MCP class -> obf class
        self.mcp_class: "dict[str, str]" = {}   # obf class -> MCP class
        self.method_name: "dict[str, str]" = {}  # SRG -> human
        self.field_name: "dict[str, str]" = {}
        # (mcp owner, srg) -> [(obf owner, obf name, obf desc, mcp desc)]
        self.methods: "dict[tuple, list]" = defaultdict(list)
        # (mcp owner, srg) -> [(obf owner, obf name)]
        self.fields: "dict[tuple, list]" = defaultdict(list)
        self.by_method_name: "dict[str, dict]" = defaultdict(lambda: defaultdict(list))
        self.by_field_name: "dict[str, dict]" = defaultdict(lambda: defaultdict(list))

    def load(self, srg_path: str, methods_csv: str, fields_csv: str) -> None:
        with open(srg_path, encoding="utf-8") as handle:
            for line in handle:
                parts = line.rstrip("\n").split(" ")
                if parts[0] == "CL:" and len(parts) == 3:
                    self.obf_class[parts[2]] = parts[1]
                    self.mcp_class[parts[1]] = parts[2]
                elif parts[0] == "MD:" and len(parts) == 5:
                    obf_owner, obf_name = parts[1].rsplit("/", 1)
                    mcp_owner, srg = parts[3].rsplit("/", 1)
                    self.methods[(mcp_owner, srg)].append(
                        (obf_owner, obf_name, parts[2], parts[4])
                    )
                elif parts[0] == "FD:" and len(parts) == 3:
                    obf_owner, obf_name = parts[1].rsplit("/", 1)
                    mcp_owner, srg = parts[2].rsplit("/", 1)
                    self.fields[(mcp_owner, srg)].append((obf_owner, obf_name))
        _load_csv(methods_csv, self.method_name)
        _load_csv(fields_csv, self.field_name)
        for (owner, srg), entries in self.methods.items():
            human = self.method_name.get(srg) or srg
            for entry in entries:
                self.by_method_name[owner][human].append((srg,) + entry)
        for (owner, srg), entries in self.fields.items():
            human = self.field_name.get(srg) or srg
            for entry in entries:
                self.by_field_name[owner][human].append((srg,) + entry)


def _load_csv(path: str, out: "dict[str, str]") -> None:
    with open(path, encoding="utf-8") as handle:
        next(handle, None)
        for line in handle:
            parts = line.rstrip("\n").split(",", 3)
            if len(parts) >= 2 and parts[0]:
                out[parts[0]] = parts[1]


class LegacySource:
    def __init__(self, version: str, mappings_root: str, aliases_path: str) -> None:
        self.version = version
        self.data = McpData()
        self.data.load(
            os.path.join(mappings_root, f"vanilla{_tag(version)}", "joined.srg"),
            os.path.join(mappings_root, f"forge{_tag(version)}", "methods.csv"),
            os.path.join(mappings_root, f"forge{_tag(version)}", "fields.csv"),
        )
        self.aliases = tomllib.load(open(aliases_path, "rb"))
        self.classes = dict(self.aliases.get("classes", {}))
        self.classes.update(self.aliases.get("class-aliases", {}))
        self.methods = self.aliases.get("methods", {})
        self.fields = self.aliases.get("fields", {})
        self.overrides = self.aliases.get("overrides", {})

    # -- classes ---------------------------------------------------------
    def _class_rule(self, canonical: str):
        return self.classes.get(canonical.replace(".", "/"))

    def resolve_class(self, canonical: str) -> Answer:
        key = canonical.replace(".", "/")
        rule = self.classes.get(key)
        if rule is None:
            return Answer.absent(f"no class rule for {key}")
        if isinstance(rule, dict):
            return Answer.absent(rule.get("unsupported") or rule.get("skip") or "class skipped")
        obf = self.data.obf_class.get(rule)
        if obf is None:
            return Answer.absent(f"MCP class {rule} not in joined.srg")
        return Answer.found(obf)

    # -- members ---------------------------------------------------------
    def _rule(self, cls: str, kind: str, name: str):
        override = self.overrides.get(cls, {})
        if name in override:
            return override[name]
        table = self.methods if kind == "method" else self.fields
        return table.get(name)

    def _resolve(self, cls: str, kind: str, name: str) -> Answer:
        key = cls.replace(".", "/")
        rule = self._rule(key, kind, name)
        if not rule:
            return Answer.absent(f"no alias rule for {key}#{name} ({kind})")
        if "unsupported" in rule:
            return Answer.absent(rule["unsupported"])
        if "skip" in rule:
            return Answer.absent(rule["skip"])
        class_rule = self.classes.get(key)
        if isinstance(class_rule, dict):
            return Answer.absent(class_rule.get("unsupported") or class_rule.get("skip"))
        mcp_class = rule.get("scope") or class_rule
        if mcp_class is None:
            return Answer.absent(f"no MCP scope for {key}")
        human = rule.get("mcp")
        srg_pin = rule.get("srg")
        index = self.data.by_method_name if kind == "method" else self.data.by_field_name
        candidates = index.get(mcp_class, {}).get(human, [])
        if srg_pin:
            candidates = [c for c in candidates if c[0] == srg_pin]
        if not candidates:
            return Answer.absent(f"{mcp_class}.{human} not in forge{_tag(self.version)} data")
        if len(candidates) > 1:
            return Answer.absent(
                f"{mcp_class}.{human} ambiguous: {sorted(c[0] for c in candidates)}"
            )
        if kind == "method":
            srg, _owner, obf_name, obf_desc, _mcp_desc = candidates[0]
            validate_signature(obf_desc)
            return Answer.found(obf_name, obf_desc, srg=srg)
        srg, _owner, obf_name = candidates[0]
        return Answer.found(obf_name, srg=srg)

    def resolve_method(self, cls: str, name: str) -> Answer:
        return self._resolve(cls, "method", name)

    def resolve_field(self, cls: str, name: str) -> Answer:
        return self._resolve(cls, "field", name)


def _tag(version: str) -> str:
    """``1.8.9`` -> ``189``;  ``1.12.2`` -> ``1122`` (matches OpenVape dir names)."""
    return "".join(version.split("."))


# --------------------------------------------------------------------------
# modern (1.16.5 - 1.21.11): official ProGuard client mappings
# --------------------------------------------------------------------------


_CLASS_RE = re.compile(r"^([\w.$]+) -> ([\w$]+):$")
_METHOD_RE = re.compile(
    r"^\s+(?:\d+:\d+:)?([\w.$]+)\s+([\w.$]+)\((.*)\)\s+->\s+([\w$]+)$"
)
_FIELD_RE = re.compile(r"^\s+([\w.$]+)\s+([\w$]+)\s+->\s+([\w$]+)$")

_PRIMITIVES = {
    "void": "V", "boolean": "Z", "byte": "B", "char": "C",
    "short": "S", "int": "I", "float": "F", "long": "J", "double": "D",
}


class ProguardSource:
    def __init__(self, version: str, manifest: Manifest, jar_path: "str | None" = None,
                 javap_exe: str = "javap", javap_cache: "str | None" = None,
                 shape_hints: "dict | None" = None) -> None:
        self.version = version
        text = manifest.mappings_text(version)
        # canonical internal name -> {"name": obf, "methods": {...}, "fields": {...}}
        self.classes: "dict[str, dict]" = {}
        self.class_map: "dict[str, str]" = {}
        self.obf_to_canonical: "dict[str, str]" = {}
        for line in text.splitlines():
            m = _CLASS_RE.match(line)
            if not m:
                continue
            original = m.group(1).replace(".", "/")
            obf = m.group(2)
            self.class_map[original] = obf
            self.obf_to_canonical.setdefault(obf, original)
            self.classes[original] = {"name": obf, "methods": {}, "fields": {}}
        current = None
        for line in text.splitlines():
            m = _CLASS_RE.match(line)
            if m:
                current = m.group(1).replace(".", "/")
                continue
            if current is None or not line.strip() or line.lstrip().startswith("#"):
                continue
            m = _METHOD_RE.match(line)
            if m:
                ret, name, params, obf = m.groups()
                sig = _signature(ret, params, self.class_map)
                self.classes[current]["methods"].setdefault(name, []).append((obf, sig))
                continue
            m = _FIELD_RE.match(line)
            if m:
                _type, name, obf = m.groups()
                self.classes[current]["fields"].setdefault(name, obf)
        self.index = None
        if jar_path is not None:
            self.index = javap.JavapIndex(jar_path, javap_exe, javap_cache)
        self._supers: "dict[str, list]" = {}
        # (canonical class, method) -> parameter count recorded by the 1.8.9
        # reference table, used to pick the right modern overload.
        self.shape_hints: "dict[tuple, int]" = shape_hints or {}

    def close(self) -> None:
        if self.index is not None:
            self.index.save()

    # -- hierarchy -------------------------------------------------------
    def _mojmap_supers(self, canonical: str) -> "list[str]":
        """Direct Mojmap superclasses/interfaces of a canonical class."""
        if canonical in self._supers:
            return self._supers[canonical]
        result: "list[str]" = []
        if self.index is not None:
            entry = self.classes.get(canonical)
            if entry is not None:
                info = self.index.class_info(entry["name"])
                if info is not None:
                    for obf_super in info["supers"]:
                        mapped = self.obf_to_canonical.get(obf_super)
                        if mapped is not None:
                            result.append(mapped)
        self._supers[canonical] = result
        return result

    def _ancestors(self, canonical: str) -> "list[str]":
        """Breadth-first: the class itself, then supers, most-derived first."""
        order: "list[str]" = []
        seen = set()
        queue = [canonical]
        while queue:
            current = queue.pop(0)
            if current in seen:
                continue
            seen.add(current)
            order.append(current)
            queue.extend(self._mojmap_supers(current))
        return order

    def resolve_class(self, canonical: str) -> Answer:
        entry = self.classes.get(canonical.replace(".", "/"))
        if entry is None:
            return Answer.absent(f"class not present in {self.version} client_mappings")
        return Answer.found(entry["name"])

    def resolve_method(self, cls: str, name: str) -> Answer:
        key = cls.replace(".", "/")
        if key not in self.classes:
            return Answer.absent(f"class not present in {self.version} client_mappings")
        for ancestor in self._ancestors(key):
            entry = self.classes.get(ancestor)
            if entry is None:
                continue
            candidates = entry["methods"].get(name)
            if not candidates:
                continue
            if len(candidates) > 1:
                hint = self.shape_hints.get((key, name))
                if hint is not None:
                    matching = [c for c in candidates if _param_count(c[1]) == hint]
                    if matching:
                        candidates = matching
                candidates = sorted(candidates, key=lambda c: (_param_count(c[1]), c[1], c[0]))
            obf, sig = candidates[0]
            return Answer.found(obf, sig)
        return Answer.absent(f"{key}#{name} not present in {self.version} client_mappings")

    def resolve_field(self, cls: str, name: str) -> Answer:
        key = cls.replace(".", "/")
        if key not in self.classes:
            return Answer.absent(f"class not present in {self.version} client_mappings")
        for ancestor in self._ancestors(key):
            entry = self.classes.get(ancestor)
            if entry is None:
                continue
            obf = entry["fields"].get(name)
            if obf:
                return Answer.found(obf)
        return Answer.absent(f"{key}#{name} not present in {self.version} client_mappings")

    def overload_count(self, cls: str, name: str) -> int:
        for ancestor in self._ancestors(cls.replace(".", "/")):
            entry = self.classes.get(ancestor)
            if entry and name in entry["methods"]:
                return len(entry["methods"][name])
        return 0


def _convert_type(java_type: str, class_map: "dict[str, str]") -> str:
    depth = java_type.count("[]")
    base = java_type.replace("[]", "")
    if base in _PRIMITIVES:
        jvm = _PRIMITIVES[base]
    else:
        internal = base.replace(".", "/")
        jvm = "L" + class_map.get(internal, internal) + ";"
    return "[" * depth + jvm


def _signature(ret: str, params: str, class_map: "dict[str, str]") -> str:
    parts = []
    if params.strip():
        for param in params.split(","):
            parts.append(_convert_type(param.strip().split(" ")[0], class_map))
    return "(" + "".join(parts) + ")" + _convert_type(ret, class_map)


def _param_count(descriptor: str) -> int:
    inner = descriptor[descriptor.find("(") + 1: descriptor.find(")")]
    count = 0
    i = 0
    while i < len(inner):
        ch = inner[i]
        if ch == "[":
            i += 1
            continue
        if ch == "L":
            i = inner.index(";", i) + 1
        else:
            i += 1
        count += 1
    return count
