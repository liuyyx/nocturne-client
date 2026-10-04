#!/usr/bin/env python3
"""Generate every ``client/src/main/resources/mappings-<version>.json`` table.

The mapping *requirement* surface (which canonical classes/members the client
actually resolves through the table) comes from two places, merged:

* ``scan.py``       — ``ClassType`` constants + member string literals in
  ``client/src/main/java``;
* ``requirements.txt`` — the curated list for names that travel through a
  variable / GUI-font-input members added later.

Each requirement is resolved against the version's native mapping source (see
``sources.py``); anything that cannot be resolved is written as
``"absent": true`` and listed in the report — that list is the to-do list for
patching an alias or a requirement.

Usage::

    python tools/mapping/generate.py                 # write all tables
    python tools/mapping/generate.py --only 1.8.9    # one version
    python tools/mapping/generate.py --check         # fail if any table is stale
    python tools/mapping/generate.py --javap         # also verify against local jars
    python tools/mapping/generate.py --report        # print the absent-member lists

Stdlib only.
"""

from __future__ import annotations

import argparse
import json
import os
import sys

import javap
import scan
import sources


ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
TOOLS = os.path.join(ROOT, "tools", "mapping")
CACHE = os.path.join(TOOLS, "cache")
CLIENT_SRC = os.path.join(ROOT, "client", "src", "main", "java")
CLASS_TYPE = os.path.join(CLIENT_SRC, "dev", "noturne", "client", "mapping", "ClassType.java")
REQUIREMENTS = os.path.join(TOOLS, "requirements.txt")
RESOURCES = os.path.join(ROOT, "client", "src", "main", "resources")
VANILLA_ROOT = os.path.join(ROOT, "..", "OpenVape4.21", "src", "main", "resources", "mappings")

DEFAULT_JAVAP = os.environ.get(
    "NOTURNE_JAVAP",
    r"C:\Program Files\Eclipse Adoptium\jdk-25.0.4.7-hotspot\bin\javap.exe",
)

# version -> backend kind
VERSIONS = {
    "1.8.9": "legacy",
    "1.12.2": "legacy",
    "1.16.5": "proguard",
    "1.20.1": "proguard",
    "1.21.4": "proguard",
    "1.21.10": "proguard",
    "1.21.11": "proguard",
    "26.2": "identity",
    "26.3": "identity",
}


def load_shape_hints() -> dict:
    """(canonical class, method) -> 1.8.9 parameter count.

    The shipped 1.8.9 table records which overload of a canonical method the
    client actually uses; reuse that shape when a modern Mojmap class exposes
    several overloads under the same name.
    """
    path = table_path("1.8.9")
    if not os.path.exists(path):
        return {}
    try:
        table = json.load(open(path, encoding="utf-8"))
    except (ValueError, OSError):
        return {}
    hints = {}
    for canonical, entry in table.get("classes", {}).items():
        for name, spec in (entry.get("methods") or {}).items():
            signature = spec.get("signature")
            if signature:
                hints[(canonical, name)] = _param_count(signature)
    return hints


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


def ensure_jar(version: str, manifest: sources.Manifest) -> "str | None":
    jar = find_local_jar(version)
    if jar is not None:
        return jar
    return manifest.client_jar(version)


def build_source(version: str, kind: str, manifest: sources.Manifest, shape_hints: dict):
    if kind == "legacy":
        aliases = os.path.join(TOOLS, f"aliases-{version}.toml")
        return sources.LegacySource(version, VANILLA_ROOT, aliases)
    if kind == "proguard":
        jar = ensure_jar(version, manifest)
        return sources.ProguardSource(
            version, manifest, jar_path=jar, javap_exe=DEFAULT_JAVAP,
            javap_cache=os.path.join(CACHE, f"javap-{version}.json"),
            shape_hints=shape_hints,
        )
    if kind == "identity":
        return IdentitySource(version)
    raise ValueError(f"unknown backend {kind}")


class IdentitySource:
    """Unobfuscated build: the canonical name is the runtime name.

    Member existence is verified against the real client jar (class list from
    the jar; members via ``javap``); anything not found is reported absent
    rather than silently claimed to exist.
    """

    def __init__(self, version: str) -> None:
        self.version = version
        self.jar = find_local_jar(version)
        self.index = None
        self._entries = None
        self._missing_jar = self.jar is None
        if self.jar is not None:
            self._entries = javap.class_entries(self.jar)
            self.index = javap.JavapIndex(
                self.jar, DEFAULT_JAVAP, os.path.join(CACHE, f"javap-{version}.json")
            )

    def resolve_class(self, canonical: str) -> sources.Answer:
        internal = canonical.replace(".", "/")
        if self._missing_jar:
            return sources.Answer.absent(f"no local client jar for {self.version}")
        if internal not in self._entries:
            return sources.Answer.absent(f"{internal} not present in {self.version} client jar")
        # Runtime name is the canonical binary (dotted) name.
        return sources.Answer.found(canonical.replace("/", "."))

    def _member(self, canonical: str, kind: str, name: str) -> sources.Answer:
        internal = canonical.replace(".", "/")
        if self._missing_jar:
            return sources.Answer.absent(f"no local client jar for {self.version}")
        if not self.index.reachable(internal, kind, name):
            return sources.Answer.absent(f"{internal}#{name} not found in {self.version} client jar")
        # Unobfuscated: the member keeps its canonical name.
        return sources.Answer.found(name)

    def resolve_method(self, cls: str, name: str) -> sources.Answer:
        return self._member(cls, "method", name)

    def resolve_field(self, cls: str, name: str) -> sources.Answer:
        return self._member(cls, "field", name)

    def save(self) -> None:
        if self.index is not None:
            self.index.save()


def find_local_jar(version: str) -> "str | None":
    candidates = [
        os.path.join(ROOT, "..", "analysis", f"client-{version}.jar"),
        os.path.join(CACHE, f"client-{version}.jar"),
        os.path.join(ROOT, "..", "analysis", f"client-{version}.jar"),
    ]
    for candidate in candidates:
        if os.path.isfile(candidate):
            return os.path.normpath(candidate)
    return None


def build_table(version: str, kind: str, requirements: scan.Requirements,
                manifest: sources.Manifest, report: dict, shape_hints: dict) -> dict:
    source = build_source(version, kind, manifest, shape_hints)
    classes = {}
    resolved = 0
    absent = []
    overloads = []
    for canonical in sorted(requirements.classes):
        class_answer = source.resolve_class(canonical)
        if not class_answer.ok:
            classes[canonical] = {"absent": True, "methods": {}, "fields": {}}
            absent.append((canonical, None, class_answer.reason))
            continue
        methods = {}
        fields = {}
        for name in sorted(requirements.methods[canonical]):
            answer = source.resolve_method(canonical, name)
            if answer.ok:
                entry = {"name": answer.name}
                if answer.signature is not None:
                    entry["signature"] = answer.signature
                methods[name] = entry
                resolved += 1
            else:
                methods[name] = {"absent": True}
                absent.append((canonical, name + "()", answer.reason))
            if kind == "proguard" and source.overload_count(canonical, name) > 1:
                overloads.append(f"{canonical}#{name}()")
        for name in sorted(requirements.fields[canonical]):
            answer = source.resolve_field(canonical, name)
            if answer.ok:
                fields[name] = {"name": answer.name}
                resolved += 1
            else:
                fields[name] = {"absent": True}
                absent.append((canonical, name, answer.reason))
        classes[canonical] = {"name": class_answer.name, "methods": methods, "fields": fields}
    if isinstance(source, IdentitySource):
        source.save()
    elif isinstance(source, sources.ProguardSource):
        source.close()
    report["versions"][version] = {
        "classes": len(requirements.classes),
        "resolved": resolved,
        "absent": absent,
        "overloads": overloads,
    }
    return {"version": version, "classes": classes}


def render(table: dict) -> str:
    return json.dumps(table, indent=4, ensure_ascii=False) + "\n"


def table_path(version: str) -> str:
    return os.path.join(RESOURCES, f"mappings-{version}.json")


# --------------------------------------------------------------------------
# javap cross-check
# --------------------------------------------------------------------------


def verify_javap(version: str, table: dict) -> "tuple[list, int, bool]":
    jar = find_local_jar(version)
    if jar is None:
        return ([f"{version}: no local client jar"], 0, True)
    index = javap.JavapIndex(jar, DEFAULT_JAVAP, os.path.join(CACHE, f"javap-{version}.json"))
    problems = []
    checked = 0
    for canonical, entry in table["classes"].items():
        if entry.get("absent"):
            continue
        obf = entry["name"]
        internal = obf.replace(".", "/")
        if not index.exists(internal):
            problems.append(f"{version}: {canonical} -> {obf} not in jar")
            continue
        for name, spec in entry["methods"].items():
            if spec.get("absent"):
                continue
            if not index.reachable(internal, "method", spec["name"], spec.get("signature")):
                problems.append(
                    f"{version}: {canonical}.{name} -> {spec['name']}"
                    f"{spec.get('signature', '')} not found on {obf}"
                )
            else:
                checked += 1
        for name, spec in entry["fields"].items():
            if spec.get("absent"):
                continue
            if not index.reachable(internal, "field", spec["name"]):
                problems.append(f"{version}: {canonical}.{name} -> {spec['name']} not found on {obf}")
            else:
                checked += 1
    index.save()
    return (problems, checked, False)


# --------------------------------------------------------------------------
# main
# --------------------------------------------------------------------------


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--check", action="store_true", help="do not write; fail if a table is stale")
    parser.add_argument("--javap", action="store_true", help="cross-check tables against local client jars")
    parser.add_argument("--only", default=None, help="comma-separated version filter")
    parser.add_argument("--report", action="store_true", help="print absent-member lists")
    args = parser.parse_args()

    requirements = scan.collect(CLIENT_SRC, CLASS_TYPE, REQUIREMENTS)
    manifest = sources.Manifest(CACHE)
    shape_hints = load_shape_hints()

    versions = list(VERSIONS)
    if args.only:
        wanted = {v.strip() for v in args.only.split(",")}
        unknown = wanted - set(VERSIONS)
        if unknown:
            print(f"unknown versions: {sorted(unknown)}", file=sys.stderr)
            return 2
        versions = [v for v in versions if v in wanted]

    report = {"versions": {}}
    stale = False
    failures = []
    for version in versions:
        kind = VERSIONS[version]
        table = build_table(version, kind, requirements, manifest, report, shape_hints)
        text = render(table)
        path = table_path(version)
        info = report["versions"][version]
        print(
            f"{version:8} {kind:8} classes={info['classes']:3} "
            f"resolved={info['resolved']:4} absent={len(info['absent']):3}"
        )
        if args.javap:
            problems, checked, skipped = verify_javap(version, table)
            report["versions"][version]["javap"] = problems
            if skipped:
                print("  javap: no local client jar — skipped")
            elif problems:
                print(f"  javap: {len(problems)} PROBLEMS ({checked} checked)")
            else:
                print(f"  javap: ok ({checked} members verified against the client jar)")
            for problem in problems:
                print(f"    {problem}")
                if not skipped:
                    failures.append(problem)
        if args.check:
            current = None
            if os.path.exists(path):
                current = open(path, encoding="utf-8", newline="").read()
            if current != text:
                print(f"  STALE: {path}", file=sys.stderr)
                stale = True
        else:
            with open(path, "w", encoding="utf-8", newline="\n") as handle:
                handle.write(text)

    if args.report:
        for version in versions:
            info = report["versions"][version]
            print(f"\n===== {version} — {len(info['absent'])} absent =====")
            for canonical, member, reason in info["absent"]:
                where = canonical + ("#" + member if member else "")
                print(f"  {where}: {reason}")
            if info["overloads"]:
                print(f"  (overloaded methods, deterministic pick applied: {len(info['overloads'])})")
                for item in info["overloads"]:
                    print(f"    {item}")

    if failures:
        print(f"\n{len(failures)} javap problems", file=sys.stderr)
        return 1
    if args.check and stale:
        print("tables are stale — re-run without --check", file=sys.stderr)
        return 1
    if args.check:
        print("all tables up to date")
    return 0


if __name__ == "__main__":
    sys.exit(main())
