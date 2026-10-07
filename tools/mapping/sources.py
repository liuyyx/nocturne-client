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


def _download(url: str, dest: str, attempts: int = 3) -> str:
    """Fetch ``url`` into ``dest`` (no-op when already cached).

    Retried a few times: the raw.githubusercontent.com endpoint used for the
    community mapping repositories answers with 502/timeouts under load.
    """
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    if os.path.exists(dest) and os.path.getsize(dest) > 0:
        return dest
    last: "Exception | None" = None
    for _ in range(max(1, attempts)):
        tmp = dest + ".part"
        try:
            with urllib.request.urlopen(url, timeout=180) as response, open(tmp, "wb") as out:
                while True:
                    chunk = response.read(1 << 20)
                    if not chunk:
                        break
                    out.write(chunk)
            os.replace(tmp, dest)
            return dest
        except Exception as failure:  # noqa: BLE001 - retried below, re-raised at the end
            last = failure
    raise last if last is not None else RuntimeError(f"download failed: {url}")


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


def _tag(version: str) -> str:
    """``1.8.9`` -> ``189``;  ``1.12.2`` -> ``1122`` (matches OpenVape dir names)."""
    return "".join(version.split("."))
