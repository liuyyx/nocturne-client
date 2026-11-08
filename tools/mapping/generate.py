#!/usr/bin/env python3
"""Generate every ``client/src/main/resources/mappings-<version>.json`` table.

The mapping *requirement* surface (which canonical classes/members the client
actually resolves through the table) comes from two places, merged:

* ``scan.py``       — ``ClassType`` constants + member string literals in
  ``client/src/main/java``;
* ``requirements.txt`` — the curated list for names that travel through a
  variable / GUI-font-input members added later.

Every requirement is resolved into **loader namespaces** (``vanilla`` /
``fabric`` / ``forge`` / ``neoforge``) by ``namespaces.py``; the runtime tries
those names in order, so one table serves a plain install, a Fabric install, a
Forge install and a NeoForge install of the same game version.  Anything that
cannot be resolved in a version is written as ``"absent": true`` and listed by
``--report`` — that list is the to-do list for patching an alias or a
requirement.

Usage::

    python tools/mapping/generate.py                 # write all tables
    python tools/mapping/generate.py --only 1.8.9    # one version
    python tools/mapping/generate.py --check         # 表是否最新 **且** 名字/描述符对得上 jar
    python tools/mapping/generate.py --check --fast  # 只比文本（快速路径，跳过 javap 交叉校验）
    python tools/mapping/generate.py --javap         # cross-check against local jars only
    python tools/mapping/generate.py --report        # print the absent-member lists

``--check`` 默认包含 ``--javap``：只比文本的检查抓不到"表是旧的但自洽"和"描述符在真实
jar 里不存在"这两类错误（1.16.x ``Style#withColor`` 幻影描述符就是这样漏过去的）。
装不上 javap 或没有本地 jar 的机器用 ``--fast`` 走原来的快速路径。

Stdlib only.
"""

from __future__ import annotations

import argparse
import json
import os
import sys

import javap
import namespaces
import scan
import sources


ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
TOOLS = os.path.join(ROOT, "tools", "mapping")
CACHE = os.path.join(TOOLS, "cache")
CLIENT_SRC = os.path.join(ROOT, "client", "src", "main", "java")
AGENT_SRC = os.path.join(ROOT, "agent", "src", "main", "java")
UI_SRC = os.path.join(ROOT, "ui", "src", "main", "java")
CLASS_TYPE = os.path.join(CLIENT_SRC, "dev", "nocturne", "client", "mapping", "ClassType.java")
REQUIREMENTS = os.path.join(TOOLS, "requirements.txt")
#: 成员/类的别名链。生成的表按**canonical** 名字建键，但个别版本把同一个成员换了
#: 名字（`ItemStack#getEnchantments` → `getEnchantmentTags`）；这份文件让生成器在
#: 首选名字解析失败时按序尝试别名，而不是在表里留一个 absent 让运行期静默失效。
ALIASES_FILE = os.path.join(TOOLS, "member-aliases.txt")
RESOURCES = os.path.join(ROOT, "client", "src", "main", "resources")
#: 旧的 SRG/MCP 资产（1.8.9 / 1.12.2 / 1.16.5 … 的 joined.srg 与 MCP CSV）。
#: 必须用**仓库内**的 vendor 副本：指向仓库外（../OpenVape4.21）会让干净检出上的重新生成
#: 静默退化成"没有 legacy 数据源"，进而把表写成全 absent，而不是报错。
VANILLA_ROOT = os.path.join(ROOT, "vendor", "vape", "src", "main", "resources", "mappings")

DEFAULT_JAVAP = os.environ.get(
    "NOCTURNE_JAVAP",
    r"C:\Program Files\Eclipse Adoptium\jdk-25.0.4.7-hotspot\bin\javap.exe",
)

#: Oldest version the client supports.  Everything released after it is a
#: candidate; ``namespaces.build_resolver`` decides per version whether a
#: source exists (it returns ``None`` for versions older than Mojang's
#: mappings that have no alias bridge — those are reported, not guessed).
OLDEST = "1.8.9"


def release_versions(manifest: sources.Manifest) -> "list[str]":
    """Every release from ``OLDEST`` up to the newest, newest first."""
    versions = [v["id"] for v in manifest.manifest()["versions"] if v["type"] == "release"]
    floor = namespaces._version_key(OLDEST)
    selected = [v for v in versions if namespaces._version_key(v) >= floor]
    return list(reversed(selected))


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
            descriptor = _first_signature(spec)
            if descriptor:
                hints[(canonical, name)] = namespaces.parameter_count(descriptor)
    return hints


def _first_signature(spec: dict) -> "str | None":
    """The first recorded descriptor of a member entry, whatever its shape."""
    signatures = spec.get("signatures")
    if isinstance(signatures, dict):
        for namespace in namespaces.NAMESPACES:
            if signatures.get(namespace):
                return signatures[namespace]
    return spec.get("signature")


def build_anchor_table(requirements: scan.Requirements, manifest: sources.Manifest,
                       fetch: namespaces.Fetch, shape_hints: dict,
                       aliases: "tuple[dict, dict] | None" = None) -> "namespaces.AnchorTable | None":
    """canonical -> intermediary, learned on 1.12.2 (plus a 1.14.4 top-up).

    Built in memory from the same legacy path that produces the 1.12.2 table, so
    an anchor run never depends on a previously written artefact and the order
    of ``--only`` arguments cannot change the result.

    ``aliases`` must be the same map ``build_table`` gets: a member that only
    exists under an alias in 1.12.2 (``DeltaTracker#partialTick`` →
    ``renderPartialTicks``) is then still learned, and 1.9–1.14.3 resolve it
    through intermediary instead of falling back to absent.

    1.12.2 predates ``Camera`` (and its accessors), so the anchor alone can
    never answer them for 1.14–1.14.3.  Intermediary names are stable across a
    major version, so the 1.14.4 table (first official-mappings release) tops up
    classes the 1.12.2 anchor does not know at all.  Restricting the top-up to
    unknown classes matters: 1.12.2 is missing a member that MCP only named
    later (``Entity#isSpectator``) while owning the class, and borrowing the
    1.14.4 id for it makes 1.9–1.13 resolve an unrelated method that merely
    shares the id there.  Only whole classes that 1.12.2 lacks are unambiguous.
    """
    resolver = namespaces.build_resolver(
        "1.12.2", fetch, CACHE, manifest, VANILLA_ROOT, TOOLS, DEFAULT_JAVAP,
        find_local_jar("1.12.2"),
    )
    if resolver is None:
        return None
    base_table = build_table("1.12.2", resolver, requirements, {"versions": {}},
                             shape_hints, aliases)
    anchor = namespaces.AnchorTable(base_table)
    known_classes = {key for key, entry in (base_table.get("classes") or {}).items()
                     if not entry.get("absent")}
    resolver14 = namespaces.build_resolver(
        "1.14.4", fetch, CACHE, manifest, VANILLA_ROOT, TOOLS, DEFAULT_JAVAP,
        find_local_jar("1.14.4"),
    )
    if resolver14 is not None:
        top_up = namespaces.AnchorTable(
            build_table("1.14.4", resolver14, requirements, {"versions": {}}, shape_hints, aliases))
        for key, value in top_up.classes.items():
            if key not in known_classes:
                anchor.classes.setdefault(key, value)
        for attribute in ("methods", "fields", "arity"):
            base = getattr(anchor, attribute)
            for key, value in getattr(top_up, attribute).items():
                if key[0] not in known_classes:
                    base.setdefault(key, value)
    return anchor


def find_local_jar(version: str) -> "str | None":
    candidates = [
        os.path.join(ROOT, "..", "analysis", f"client-{version}.jar"),
        os.path.join(CACHE, f"client-{version}.jar"),
    ]
    for candidate in candidates:
        if os.path.isfile(candidate):
            return os.path.normpath(candidate)
    return None


def load_member_aliases(class_type: dict) -> "tuple[dict, dict]":
    """``member-aliases.txt`` -> ``(成员别名, 类别名)``.

    格式（一行一条，``#`` 注释）::

        ITEM_STACK#getEnchantments() = getEnchantmentTags()
        TextComponent = MutableComponent

    左边可用 ``ClassType`` 常量名或 canonical 类名；右边的类名同理。
    键统一成 canonical（斜杠分隔、成员去掉 ``()``），与 ``build_table`` 的查表口径一致。
    """
    members: "dict[tuple, list]" = {}
    classes: "dict[str, list]" = {}

    def canonical(text: str) -> str:
        text = text.strip()
        return class_type.get(text, text).replace(".", "/")

    if not os.path.exists(ALIASES_FILE):
        return members, classes
    for raw in open(ALIASES_FILE, encoding="utf-8"):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        left, right = line.split("=", 1)
        left = left.strip()
        alternatives = [part.strip() for part in right.split(",") if part.strip()]
        if not alternatives:
            continue
        if "#" in left:
            owner, member = left.split("#", 1)
            member = member.strip().rstrip("()")
            members[(canonical(owner), member)] = [a.rstrip("()") for a in alternatives]
        else:
            classes[canonical(left)] = [canonical(a) for a in alternatives]
    return members, classes

def absent_entry(resolver, canonical: str, kind: str, name: "str | None" = None) -> dict:
    """``absent`` 条目，带可信度：``verified`` / ``ambiguous`` / ``unverified``。

    ``verified`` 才表示"映射源/真实 jar 确认该成员本版本不存在"；``ambiguous`` 是
    生成器自己没能唯一确定（重载、来源不全），``unverified`` 表示根本没有可判定的来源。
    没有这一层区分，``--javap`` 会把"我们不知道"当成"确认没有"，整类成员静默消失。
    """
    reason = getattr(resolver, "absent_reason", None)
    state = reason(canonical, kind, name) if reason is not None else "unverified"
    return {"absent": True, "absentReason": state}

def build_table(version: str, resolver, requirements: scan.Requirements,
                report: dict, shape_hints: dict, aliases: "tuple[dict, dict] | None" = None,
                lint: "list | None" = None) -> dict:
    member_aliases, class_aliases = aliases if aliases is not None else ({}, {})
    problems = lint if lint is not None else []
    classes = {}
    resolved = 0
    absent = []
    for canonical in sorted(requirements.classes):
        class_names = resolver.resolve_class(canonical)
        # 类别名（member-aliases.txt 里的 `Old = New`，如 DeltaTracker = Timer）不仅换类名：
        # 成员也必须按**别名类**去解析，否则表里类名是 Timer、成员却仍按 DeltaTracker 查，
        # 现代版本一条都解析不到（1.13–1.20.1 的 partialTick 就是这样静默缺失的）。
        member_owner = canonical
        if not class_names:
            for alternative in class_aliases.get(canonical, ()):
                class_names = resolver.resolve_class(alternative)
                if class_names:
                    member_owner = alternative
                    break
        if not class_names:
            classes[canonical] = {"absent": True, "names": {},
                                  "absentReason": absent_entry(resolver, canonical, "class")["absentReason"]}
            absent.append((canonical, None, f"no counterpart in {version}"))
            continue

        def resolve(kind: str, name: str, hint: "int | None"):
            """先按 canonical 类解析，再按类别名指向的类解析。"""
            for owner in ((canonical, member_owner) if member_owner != canonical else (canonical,)):
                answer = (resolver.resolve_method(owner, name, hint) if kind == "method"
                          else resolver.resolve_field(owner, name))
                if answer is not None:
                    return answer
            return None

        methods = {}
        fields = {}
        for name in sorted(requirements.methods[canonical]):
            hint = shape_hints.get((canonical, name))
            if hint is None and member_owner != canonical:
                hint = shape_hints.get((member_owner, name))
            answer = resolve("method", name, hint)
            if answer is None:
                for alternative in member_aliases.get((canonical, name), ()):
                    answer = resolve("method", alternative, hint)
                    if answer is not None:
                        break
            if answer:
                entry = {"names": {ns: name_ for ns, (name_, _) in answer.items()}}
                signatures = {ns: sig for ns, (_, sig) in answer.items() if sig}
                if signatures:
                    entry["signatures"] = signatures
                methods[name] = entry
                resolved += 1
            else:
                methods[name] = absent_entry(resolver, member_owner, "method", name)
                absent.append((canonical, name + "()", f"{version}: {methods[name]['absentReason']}"))
        for name in sorted(requirements.fields[canonical]):
            answer = resolve("field", name, None)
            if answer is None:
                for alternative in member_aliases.get((canonical, name), ()):
                    answer = resolve("field", alternative, None)
                    if answer is not None:
                        break
            if answer:
                entry = {"names": {ns: name_ for ns, (name_, _) in answer.items()}}
                descriptors = {ns: desc for ns, (_, desc) in answer.items() if desc}
                if descriptors:
                    entry["descriptors"] = descriptors
                fields[name] = entry
                resolved += 1
            else:
                # 反方向登记错误：需求把方法写成了裸名（没有 `()`），于是这里按字段解析，
                # 解析不到就被写成 absent——运行期的 `callMapped(..., "name")` 永远落空。
                # 这种错误必须在生成期失败，而不是静默变成 absent。
                exists = getattr(resolver, "method_exists", None)
                forced = name in getattr(requirements, "forced_fields", {}).get(canonical, ())
                if answer is None and not forced and exists is not None and exists(member_owner, name):
                    problems.append(
                        f"{version}: {canonical}#{name} 作为字段登记，但它在映射源里是方法"
                        f" → 在 requirements.txt 里写成 {name}()"
                    )
                fields[name] = absent_entry(resolver, member_owner, "field", name)
                absent.append((canonical, name, f"{version}: {fields[name]['absentReason']}"))
        classes[canonical] = {"names": class_names, "methods": methods, "fields": fields}
    save = getattr(resolver, "save", None)
    if save is not None:
        save()
    report["versions"][version] = {
        "classes": len(requirements.classes),
        "resolved": resolved,
        "absent": absent,
        "namespaces": sorted(
            {ns for entry in classes.values() for ns in (entry.get("names") or {})}
        ),
    }
    return {"version": version, "classes": classes}


def render(table: dict) -> str:
    return json.dumps(table, indent=4, ensure_ascii=False) + "\n"


def table_path(version: str) -> str:
    return os.path.join(RESOURCES, f"mappings-{version}.json")


# --------------------------------------------------------------------------
# javap cross-check
# --------------------------------------------------------------------------


def verify_javap(version: str, table: dict) -> "tuple[list, int, bool, dict]":
    """Check the ``vanilla`` (obfuscated) names against the real client jar.

    That namespace is the only one whose jar we can obtain: Fabric/Forge
    installs are produced by the loader at launch, so their names are verified
    against the mapping sources themselves, not a jar.
    """
    jar = find_local_jar(version)
    if jar is None:
        return ([f"{version}: no local client jar"], 0, True, {"verified": 0, "unverified": 0})
    index = javap.JavapIndex(jar, DEFAULT_JAVAP, os.path.join(CACHE, f"javap-{version}.json"))
    problems = []
    checked = 0
    transient_before = 0
    absent_verified = absent_unverified = 0
    for canonical, entry in table["classes"].items():
        if entry.get("absent"):
            # absent 不再一律当作"已验证不存在"：只有 verified 才记账，
            # ambiguous（生成器择优失败）在本函数里是**错误**，unverified 只统计。
            state = entry.get("absentReason", "unverified")
            if state == "verified":
                absent_verified += 1
            else:
                absent_unverified += 1
                if state == "ambiguous":
                    problems.append(f"{version}: {canonical} 类被标为 absent 但状态是 ambiguous（生成器没能择优）")
            continue
        obf = (entry.get("names") or {}).get("vanilla")
        if not obf:
            continue
        internal = obf.replace(".", "/")
        if not index.exists(internal):
            problems.append(f"{version}: {canonical} -> {obf} not in jar")
            continue
        for name, spec in entry["methods"].items():
            if spec.get("absent"):
                state = spec.get("absentReason", "unverified")
                if state == "verified":
                    absent_verified += 1
                else:
                    absent_unverified += 1
                    if state == "ambiguous":
                        problems.append(f"{version}: {canonical}#{name}() 被标为 absent 但状态是 ambiguous")
                continue
            runtime = (spec.get("names") or {}).get("vanilla")
            if not runtime:
                continue
            signature = (spec.get("signatures") or {}).get("vanilla")
            if not index.reachable(internal, "method", runtime, signature):
                problems.append(
                    f"{version}: {canonical}.{name} -> {runtime}{signature or ''} not found on {obf}"
                )
            else:
                checked += 1
        for name, spec in entry["fields"].items():
            if spec.get("absent"):
                state = spec.get("absentReason", "unverified")
                if state == "verified":
                    absent_verified += 1
                else:
                    absent_unverified += 1
                    if state == "ambiguous":
                        problems.append(f"{version}: {canonical}#{name} 被标为 absent 但状态是 ambiguous")
                continue
            runtime = (spec.get("names") or {}).get("vanilla")
            if not runtime:
                continue
            if not index.reachable(internal, "field", runtime):
                problems.append(f"{version}: {canonical}.{name} -> {runtime} not found on {obf}")
            else:
                checked += 1
    index.save()
    transient = index.transient_failures - transient_before
    if transient:
        # 一次瞬时失败（javap 缺失/超时/崩溃）绝不能被读成"该类在本版本不存在"：
        # 那是把整类成员标成 absent，而 --check 依然全绿。这里显式失败。
        problems.append(
            f"{version}: {transient} javap call(s) failed without a verdict "
            f"(javap missing/timed out?) — results are NOT trustworthy"
        )
    report_absent = {"verified": absent_verified, "unverified": absent_unverified}
    return (problems, checked, False, report_absent)


# --------------------------------------------------------------------------
# main
# --------------------------------------------------------------------------


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--check", action="store_true",
                        help="do not write; fail if a table is stale (also cross-checks jars)")
    parser.add_argument("--javap", action="store_true", help="cross-check tables against local client jars")
    parser.add_argument("--fast", action="store_true",
                        help="with --check: skip the jar cross-check (text-only comparison, no javap)")
    parser.add_argument("--only", default=None, help="comma-separated version filter")
    parser.add_argument("--report", action="store_true", help="print absent-member lists")
    args = parser.parse_args()

    # ui/agent 也通过同一座桥取成员（GameBridge.callMapped 等）：只扫 client 会让
    # "字面量只写在 ui/agent 里"的需求整项消失，运行期表现为该钩子静默落空。
    sources_roots = [CLIENT_SRC, AGENT_SRC, UI_SRC]
    requirements = scan.collect(sources_roots, CLASS_TYPE, REQUIREMENTS)
    member_aliases = load_member_aliases(scan.parse_class_type(CLASS_TYPE))
    manifest = sources.Manifest(CACHE)
    shape_hints = load_shape_hints()
    fetch = namespaces.Fetch(CACHE)
    anchor_table = build_anchor_table(requirements, manifest, fetch, shape_hints, member_aliases)

    versions = release_versions(manifest)
    if args.only:
        wanted = {v.strip() for v in args.only.split(",")}
        versions = [v for v in versions if v in wanted]
        unknown = wanted - set(versions)
        if unknown:
            print(f"unknown versions: {sorted(unknown)}", file=sys.stderr)
            return 2

    report = {"versions": {}, "unsupported": []}
    stale = False
    failures = []
    # 需求面的结构性错误（方法写成字段名）与版本无关，但每条只报一次。
    lint: "list" = []
    if args.check and not args.fast:
        args.javap = True
    for version in versions:
        local_jar = find_local_jar(version)
        resolver = namespaces.build_resolver(
            version, fetch, CACHE, manifest, VANILLA_ROOT, TOOLS, DEFAULT_JAVAP, local_jar,
            anchor_table,
        )
        if resolver is None:
            report["unsupported"].append(version)
            print(f"{version:8} skipped  (no mapping source for this version)")
            continue
        table = build_table(version, resolver, requirements, report, shape_hints,
                            member_aliases, lint)
        text = render(table)
        path = table_path(version)
        info = report["versions"][version]
        print(
            f"{version:8} classes={info['classes']:3} "
            f"resolved={info['resolved']:4} absent={len(info['absent']):3} "
            f"namespaces={','.join(info['namespaces'])}"
        )
        if args.javap:
            problems, checked, skipped, absent_stats = verify_javap(version, table)
            report["versions"][version]["javap"] = problems
            if skipped:
                print("  javap: no local client jar — skipped")
            elif problems:
                print(f"  javap: {len(problems)} PROBLEMS ({checked} checked, "
                      f"absent verified={absent_stats['verified']} unverified={absent_stats['unverified']})")
            else:
                print(f"  javap: ok ({checked} members verified against the client jar, "
                      f"absent verified={absent_stats['verified']} unverified={absent_stats['unverified']})")
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
            info = report["versions"].get(version)
            if info is None:
                continue
            print(f"\n===== {version} — {len(info['absent'])} absent =====")
            for canonical, member, reason in info["absent"]:
                where = canonical + ("#" + member if member else "")
                print(f"  {where}: {reason}")
        if report["unsupported"]:
            print(f"\n===== no mapping source ({len(report['unsupported'])}) =====")
            print("  " + ", ".join(report["unsupported"]))
            print("  No route reaches these: they need either an aliases-<version>.toml"
                  " bridge, an intermediary file, or an SRG source for that version.")

    if lint:
        for problem in sorted(set(lint)):
            print(f"requirements: {problem}", file=sys.stderr)
        print(f"\n{len(set(lint))} requirements entries登记成了错误种类", file=sys.stderr)
        return 1
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
