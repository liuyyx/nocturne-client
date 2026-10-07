#!/usr/bin/env python3
"""Per-version, multi-loader namespace resolution for the mapping tables.

A mapping table answers one question: *this canonical (Mojang) class/member —
what does the running JVM actually call it?*  The answer depends on **which
loader** the game runs under, because every loader remaps the obfuscated jar to
a different namespace before the client ever sees it:

===========  ==================  ==========================================
namespace    who runs like that  runtime names
===========  ==================  ==========================================
``vanilla``  no loader at all    obfuscated (``enn`` / ``N`` / ``f_90977_``)
``fabric``   Fabric / Quilt      intermediary (``net/minecraft/class_1657``)
``forge``    Forge               SRG: readable class + ``func_``/``field_``
                                 (<= 1.15.2) or ``m_``/``f_`` (>= 1.16.5)
``neoforge`` NeoForge            SRG for 1.20.1; Mojang names from 1.20.2 on
===========  ==================  ==========================================

Joins (the obf name is the pivot every source agrees on):

* ``canonical <-> obf``   — Mojang's official ``client_mappings``
  (>= 1.14.4), or the legacy alias bridge (1.8.9 / 1.12.2);
* ``obf <-> srg``         — MinecraftForge/MCPConfig ``joined.tsrg``
  (TSRG2, >= 1.12.2), or the local MCP ``joined.srg`` for 1.8.9;
* ``obf <-> intermediary``— FabricMC/intermediary (>= 1.14) or
  Legacy-Fabric/Legacy-Intermediaries (1.8.2 - 1.13.2), both tiny v1.

Everything is cached under ``tools/mapping/cache/``; nothing from here is
committed or packed into the jar (see ``tools/mapping/README.md``).

Stdlib only.
"""

from __future__ import annotations

import os
import re
from collections import defaultdict

import sources

#: Loader namespaces, in the order the runtime is expected to try them.
#: ``vanilla`` first keeps ``className()``/``methodName()`` returning the
#: obfuscated name, which is what the pre-multi-loader tables did.
NAMESPACES = ("vanilla", "fabric", "forge", "neoforge")


# --------------------------------------------------------------------------
# generic helpers
# --------------------------------------------------------------------------


def convert_descriptor(descriptor: str, class_map: "dict[str, str]") -> str:
    """Rewrite every ``L<internal>;`` in a JNI descriptor through ``class_map``.

    Unknown types are kept verbatim: an unmapped type is a type that is not
    renamed in that namespace (``java/lang/String``) or one we have no data
    for, and both cases are handled correctly by leaving it alone.
    """
    if not descriptor:
        return descriptor
    out = []
    index = 0
    while index < len(descriptor):
        char = descriptor[index]
        if char == "L":
            end = descriptor.index(";", index)
            internal = descriptor[index + 1:end]
            out.append("L")
            out.append(class_map.get(internal, internal))
            out.append(";")
            index = end + 1
        else:
            out.append(char)
            index += 1
    return "".join(out)


def dotted(name: str) -> str:
    """Internal (slash) name -> binary (dotted) name, keeping ``$`` nested classes."""
    return name.replace("/", ".")


def java_type_to_descriptor(java_type: str) -> str:
    """``net.minecraft.client.Minecraft[]`` -> ``[Lnet/minecraft/client/Minecraft;``."""
    depth = java_type.count("[]")
    base = java_type.replace("[]", "")
    primitive = _PRIMITIVES.get(base)
    if primitive is not None:
        return "[" * depth + primitive
    return "[" * depth + "L" + base.replace(".", "/") + ";"


def parameter_count(descriptor: str) -> int:
    """Number of parameters in a JNI method descriptor."""
    inner = descriptor[descriptor.find("(") + 1: descriptor.find(")")]
    count = 0
    index = 0
    while index < len(inner):
        char = inner[index]
        if char == "[":
            index += 1
            continue
        if char == "L":
            index = inner.index(";", index) + 1
        else:
            index += 1
        count += 1
    return count


_PRIMITIVES = {
    "void": "V", "boolean": "Z", "byte": "B", "char": "C",
    "short": "S", "int": "I", "float": "F", "long": "J", "double": "D",
}


def _is_readable_class(srg_name: str) -> bool:
    """True when MCPConfig's SRG class name is usable at runtime.

    MCPConfig switched its class namespace to opaque ``net/minecraft/src/C_<id>_``
    ids at 1.16.5; those ids are *not* what Forge exposes (Forge keeps Mojang's
    readable class names and only renames members), so they must not be handed
    to the runtime as a class name.
    """
    return "/src/C_" not in srg_name


# --------------------------------------------------------------------------
# source parsers
# --------------------------------------------------------------------------


class Mojmap:
    """Official ProGuard ``client_mappings``: canonical <-> obf."""

    _CLASS_RE = re.compile(r"^([\w.$]+) -> ([\w$]+):$")
    _METHOD_RE = re.compile(
        r"^\s+(?:\d+:\d+:)?([\w.$\[\]]+)\s+([\w.$<>\-]+)\((.*)\)\s+->\s+([\w$<>\-]+)$"
    )
    _FIELD_RE = re.compile(r"^\s+([\w.$\[\]]+)\s+([\w$]+)\s+->\s+([\w$]+)$")

    def __init__(self, text: str) -> None:
        self.to_obf: "dict[str, str]" = {}
        self.from_obf: "dict[str, str]" = {}
        # (canonical class, member name) -> [(obf name, obf descriptor, canonical descriptor)]
        self.methods: "dict[tuple, list]" = defaultdict(list)
        self.fields: "dict[tuple, list]" = defaultdict(list)

        canonical_class = None
        for line in text.splitlines():
            match = self._CLASS_RE.match(line)
            if match:
                canonical_class = match.group(1).replace(".", "/")
                obf = match.group(2)
                self.to_obf[canonical_class] = obf
                self.from_obf.setdefault(obf, canonical_class)
                continue
            if canonical_class is None or not line.strip() or line.lstrip().startswith("#"):
                continue
            match = self._METHOD_RE.match(line)
            if match:
                returns, name, params, obf = match.groups()
                parts = [p.strip().split(" ")[0] for p in params.split(",") if p.strip()]
                canonical_desc = "(" + "".join(java_type_to_descriptor(p) for p in parts) + ")" \
                    + java_type_to_descriptor(returns)
                self.methods[(canonical_class, name)].append((obf, canonical_desc))
                continue
            match = self._FIELD_RE.match(line)
            if match:
                field_type, name, obf = match.groups()
                self.fields[(canonical_class, name)].append(
                    (obf, java_type_to_descriptor(field_type))
                )

    def class_of(self, canonical: str) -> "str | None":
        return self.to_obf.get(canonical.replace(".", "/"))


class McpSrg:
    """MCPConfig ``joined.tsrg`` (TSRG2): obf <-> SRG.

    Member lines are ``<obfName> <obfDesc> <srgName> [id]``; class lines are
    ``<obfName> <srgName> [id]``.  A few classes (server entry points, Blaze3D)
    already carry readable names on the obf side — those join verbatim.
    """

    def __init__(self, text: str) -> None:
        self.class_to_srg: "dict[str, str]" = {}
        self.methods: "dict[tuple, str]" = {}
        self.fields: "dict[tuple, str]" = {}
        # fallback indexes: (owner, name) -> srg name, only when unambiguous
        self._methods_by_name: "dict[tuple, list]" = defaultdict(list)
        self._fields_by_name: "dict[tuple, list]" = defaultdict(list)

        current = None
        for line in text.splitlines():
            if line.startswith("tsrg2") or not line.strip():
                continue
            if line.startswith("\t"):
                if current is None or line.startswith("\t\t"):
                    # Two tabs = a parameter row (``<index> <kind> <srg> <id>``)
                    # or a ``static`` marker; neither names a member.
                    continue
                parts = line.strip().split()
                if len(parts) < 2:
                    continue
                name = parts[0]
                if len(parts) >= 3:
                    # Method rows carry a descriptor (``<name> (<desc>) <srg> <id>``),
                    # field rows do not (``<name> <srg> <id>``).
                    if parts[1].startswith("("):
                        self.methods[(current, name, parts[1])] = parts[2]
                        self._methods_by_name[(current, name)].append(parts[2])
                    else:
                        self.fields[(current, name)] = parts[1]
                        self._fields_by_name[(current, name)].append(parts[1])
                else:
                    # TSRG1-style member row without an id column.
                    self._fields_by_name[(current, name)].append(parts[1])
            else:
                parts = line.split()
                current = parts[0]
                if len(parts) >= 2:
                    self.class_to_srg[current] = parts[1]

    def class_name(self, obf: str) -> "str | None":
        return self.class_to_srg.get(obf)

    def method(self, owner: str, name: str, descriptor: "str | None") -> "str | None":
        if descriptor is not None:
            found = self.methods.get((owner, name, descriptor))
            if found is not None:
                return found
        candidates = self._methods_by_name.get((owner, name))
        if candidates and len(set(candidates)) == 1:
            return candidates[0]
        return None

    def field(self, owner: str, name: str, descriptor: "str | None" = None) -> "str | None":
        if descriptor is not None:
            found = self.fields.get((owner, name, descriptor))
            if found is not None:
                return found
        candidates = self._fields_by_name.get((owner, name))
        if candidates and len(set(candidates)) == 1:
            return candidates[0]
        return None


class SrgV1:
    """MCP ``joined.srg`` (SRG v1): obf <-> SRG for the pre-1.12.2 era.

    ``CL:`` class rows, ``MD:``/``FD:`` member rows whose *left* half is the obf
    name + obf descriptor and whose *right* half is the MCP name + MCP
    descriptor.  The MCP descriptor is what Forge exposes at runtime for that
    era (readable class names, ``func_``/``field_`` members), so it is kept.
    """

    def __init__(self, text: str) -> None:
        self.class_to_srg: "dict[str, str]" = {}
        self.methods: "dict[tuple, tuple]" = {}
        self.fields: "dict[tuple, str]" = {}
        self._methods_by_name: "dict[tuple, list]" = defaultdict(list)
        for line in text.splitlines():
            parts = line.rstrip("\n").split(" ")
            if parts[0] == "CL:" and len(parts) == 3:
                self.class_to_srg[parts[1]] = parts[2]
            elif parts[0] == "MD:" and len(parts) == 5:
                obf_owner, obf_name = parts[1].rsplit("/", 1)
                srg_name = parts[3].rsplit("/", 1)[1]
                self.methods[(obf_owner, obf_name, parts[2])] = (srg_name, parts[4])
                self._methods_by_name[(obf_owner, obf_name)].append((srg_name, parts[4]))
            elif parts[0] == "FD:" and len(parts) == 3:
                obf_owner, obf_name = parts[1].rsplit("/", 1)
                self.fields[(obf_owner, obf_name)] = parts[2].rsplit("/", 1)[1]

    def class_name(self, obf: str) -> "str | None":
        return self.class_to_srg.get(obf)

    def method(self, owner: str, name: str, descriptor: "str | None") -> "str | None":
        if descriptor is not None:
            found = self.methods.get((owner, name, descriptor))
            if found is not None:
                return found[0]
        candidates = self._methods_by_name.get((owner, name))
        if candidates and len({c[0] for c in candidates}) == 1:
            return candidates[0][0]
        return None

    def method_descriptor(self, owner: str, name: str, descriptor: "str | None") -> "str | None":
        """The MCP-namespace descriptor Forge exposes for this method."""
        if descriptor is not None:
            found = self.methods.get((owner, name, descriptor))
            if found is not None:
                return found[1]
        candidates = self._methods_by_name.get((owner, name))
        return candidates[0][1] if candidates else None

    def field(self, owner: str, name: str, descriptor: "str | None" = None) -> "str | None":
        return self.fields.get((owner, name))


class Intermediary:
    """tiny v1: obf <-> intermediary (``CLASS``/``FIELD``/``METHOD`` lines)."""

    def __init__(self, text: str) -> None:
        self.class_to_inter: "dict[str, str]" = {}
        self.from_inter: "dict[str, str]" = {}
        self.methods: "dict[tuple, str]" = {}
        self.fields: "dict[tuple, str]" = {}
        # Descriptor-less fallbacks: the legacy path has no obf descriptor for a
        # field (MCP's ``FD:`` rows carry none), and obf member names are unique
        # per class, so ``(owner, name)`` is still an unambiguous key.
        self.methods_by_name: "dict[tuple, str]" = {}
        self.fields_by_name: "dict[tuple, str]" = {}
        # Reverse direction (intermediary -> obf): the anchor resolver starts
        # from an intermediary name it knows from another version and has to
        # find what *this* version calls it.  One intermediary name can appear
        # on several classes (an override shares its id), so these hold lists.
        self.methods_by_inter: "dict[str, list]" = defaultdict(list)
        self.fields_by_inter: "dict[str, list]" = defaultdict(list)
        for line in text.splitlines():
            parts = line.rstrip("\n").split("\t")
            if not parts:
                continue
            if parts[0] == "CLASS" and len(parts) >= 3:
                self.class_to_inter[parts[1]] = parts[2]
                self.from_inter.setdefault(parts[2], parts[1])
            elif parts[0] == "METHOD" and len(parts) >= 5:
                # METHOD <obfOwner> <obfDesc> <obfName> <intermediaryName>
                self.methods[(parts[1], parts[3], parts[2])] = parts[4]
                self.methods_by_name.setdefault((parts[1], parts[3]), parts[4])
                self.methods_by_inter[parts[4]].append((parts[1], parts[3], parts[2]))
            elif parts[0] == "FIELD" and len(parts) >= 5:
                # FIELD <obfOwner> <obfDesc> <obfName> <intermediaryName>
                self.fields[(parts[1], parts[3], parts[2])] = parts[4]
                self.fields_by_name.setdefault((parts[1], parts[3]), parts[4])
                self.fields_by_inter[parts[4]].append((parts[1], parts[3], parts[2]))

    def class_name(self, obf: str) -> "str | None":
        return self.class_to_inter.get(obf)

    def method(self, owner: str, name: str, descriptor: "str | None") -> "str | None":
        if descriptor is not None:
            found = self.methods.get((owner, name, descriptor))
            if found is not None:
                return found
        return self.methods_by_name.get((owner, name))

    def field(self, owner: str, name: str, descriptor: "str | None") -> "str | None":
        if descriptor is not None:
            found = self.fields.get((owner, name, descriptor))
            if found is not None:
                return found
        return self.fields_by_name.get((owner, name))


# --------------------------------------------------------------------------
# per-version fetch + cache
# --------------------------------------------------------------------------


MCPCONFIG_RAW = ("https://raw.githubusercontent.com/MinecraftForge/MCPConfig/master/versions/release/{v}/joined.tsrg",
                 "https://cdn.jsdelivr.net/gh/MinecraftForge/MCPConfig@master/versions/release/{v}/joined.tsrg")
INTERMEDIARY_RAW = ("https://raw.githubusercontent.com/FabricMC/intermediary/master/mappings/{v}.tiny",
                    "https://cdn.jsdelivr.net/gh/FabricMC/intermediary@master/mappings/{v}.tiny")
LEGACY_INTERMEDIARY_RAW = "https://raw.githubusercontent.com/Legacy-Fabric/Legacy-Intermediaries/v2/mappings/{v}.tiny"  # no jsdelivr mirror: it 404s for this repo
#: MCP's own SRG zip (pre-1.12.2, i.e. before MCPConfig existed).  The zip holds
#: a single ``joined.srg``.  Forge's maven requires auth these days; the
#: NeoForged mirror carries the same artifacts anonymously.
MCP_SRG_RAW = "https://maven.neoforged.net/releases/de/oceanlabs/mcp/mcp/{v}/mcp-{v}-srg.zip"

#: MCPConfig release versions (its ``versions/release`` directory).  Used to
#: decide whether a version has SRG data at all instead of probing the network.
MCPCONFIG_VERSIONS = {
    "1.12.2", "1.13", "1.13.1", "1.13.2", "1.14", "1.14.1", "1.14.2", "1.14.3",
    "1.14.4", "1.15", "1.15.1", "1.15.2", "1.16", "1.16.1", "1.16.2", "1.16.3",
    "1.16.4", "1.16.5", "1.17", "1.17.1", "1.18", "1.18.1", "1.18.2", "1.19",
    "1.19.1", "1.19.2", "1.19.3", "1.19.4", "1.20", "1.20.1", "1.20.2", "1.20.3",
    "1.20.4", "1.20.5", "1.20.6", "1.21", "1.21.1", "1.21.2", "1.21.3", "1.21.4",
    "1.21.5", "1.21.6", "1.21.7", "1.21.8", "1.21.9", "1.21.10", "1.21.11",
    "26.1", "26.1.1", "26.1.2", "26.2", "26.3",
}

#: Versions MCPConfig covers *with* a SRG file (26.x needed none: unobfuscated).
MCPCONFIG_SRG_VERSIONS = {v for v in MCPCONFIG_VERSIONS if not v.startswith("26.")}

#: FabricMC/intermediary release coverage (1.14 - 1.21.11) and the legacy repo
#: coverage (1.8.2 - 1.13.2).  Hardcoded so an offline/cached run behaves the
#: same as an online one.
FABRIC_INTERMEDIARY_VERSIONS = {
    "1.14", "1.14.1", "1.14.2", "1.14.3", "1.14.4", "1.15", "1.15.1", "1.15.2",
    "1.16", "1.16.1", "1.16.2", "1.16.3", "1.16.4", "1.16.5", "1.17", "1.17.1",
    "1.18", "1.18.1", "1.18.2", "1.19", "1.19.1", "1.19.2", "1.19.3", "1.19.4",
    "1.20", "1.20.1", "1.20.2", "1.20.3", "1.20.4", "1.20.5", "1.20.6", "1.21",
    "1.21.1", "1.21.2", "1.21.3", "1.21.4", "1.21.5", "1.21.6", "1.21.7", "1.21.8",
    "1.21.9", "1.21.10", "1.21.11",
}

LEGACY_INTERMEDIARY_VERSIONS = {
    "1.8.2", "1.8.3", "1.8.4", "1.8.5", "1.8.6", "1.8.7", "1.8.8", "1.8.9",
    "1.9", "1.9.1", "1.9.2", "1.9.3", "1.9.4", "1.10", "1.10.1", "1.10.2",
    "1.11", "1.11.1", "1.11.2", "1.12", "1.12.1", "1.12.2",
    "1.13", "1.13.1", "1.13.2",
}

#: Versions MCP shipped its own ``joined.srg`` for (the ``mcp`` artifact, before
#: MCPConfig took over at 1.12.2).  MCP skipped some point releases, and those
#: skipped ones simply have no SRG — their Forge namespace stays empty.
MCP_LEGACY_SRG_VERSIONS = {
    "1.9", "1.9.2", "1.9.4", "1.10", "1.10.2", "1.11", "1.11.1", "1.11.2",
    "1.12", "1.12.1",
}

#: Versions that predate Mojang's official mappings and are therefore resolved
#: through the intermediary anchor (see :class:`AnchorResolver`).
ANCHOR_VERSIONS = {
    "1.9", "1.9.1", "1.9.2", "1.9.3", "1.9.4", "1.10", "1.10.1", "1.10.2",
    "1.11", "1.11.1", "1.11.2", "1.12", "1.12.1",
    "1.13", "1.13.1", "1.13.2", "1.14", "1.14.1", "1.14.2", "1.14.3",
}


class Fetch:
    """Supplies the raw mapping sources into ``cache/`` (idempotent).

    Sources are read from the cache first.  When a cache file is missing they
    are either copied out of a local checkout (``NOCTURNE_MCPCONFIG_DIR`` /
    ``NOCTURNE_INTERMEDIARY_DIR`` / ``NOCTURNE_LEGACY_INTERMEDIARY_DIR`` — the
    clone ``git clone --depth 1`` of the upstream repositories) or downloaded
    from GitHub raw.  The checkout route exists because these repositories are
    large and raw.githubusercontent.com is rate-limited/flaky; both routes
    land in exactly the same cache file, so a later run is offline either way.
    """

    def __init__(self, cache_dir: str) -> None:
        self.cache = cache_dir

    @staticmethod
    def _download_any(urls, dest: str) -> None:
        """Try each mirror in turn; the last failure is re-raised."""
        candidates = [urls] if isinstance(urls, str) else list(urls)
        last: "Exception | None" = None
        for url in candidates:
            try:
                sources._download(url, dest)
                return
            except Exception as failure:  # noqa: BLE001 - tried in turn, raised below
                last = failure
        raise last if last is not None else RuntimeError(f"download failed: {urls}")

    def _text(self, url, name: str, copies: "list[tuple[str, str]]" = ()) -> str:
        path = os.path.join(self.cache, name)
        for root, relative in copies:
            source = os.path.join(root, relative)
            if root and os.path.isfile(source):
                # The checkout is the source of truth when one is configured:
                # a cache file left behind by an interrupted download must not
                # win over a complete local copy.
                if (not os.path.exists(path)
                        or os.path.getsize(path) != os.path.getsize(source)):
                    os.makedirs(os.path.dirname(path), exist_ok=True)
                    with open(source, "rb") as handle:
                        data = handle.read()
                    with open(path, "wb") as handle:
                        handle.write(data)
                break
        if not os.path.exists(path) or os.path.getsize(path) == 0:
            self._download_any(url, path)
        return open(path, encoding="utf-8").read()

    def _zip_text(self, url: str, name: str, member_suffix: str,
                  copies: "list[tuple[str, str]]" = ()) -> str:
        """Like :meth:`_text`, but the artifact is a zip holding one member."""
        path = os.path.join(self.cache, name)
        for root, relative in copies:
            source = os.path.join(root, relative)
            if root and os.path.isfile(source):
                if (not os.path.exists(path)
                        or os.path.getsize(path) != os.path.getsize(source)):
                    os.makedirs(os.path.dirname(path), exist_ok=True)
                    with open(source, "rb") as handle:
                        data = handle.read()
                    with open(path, "wb") as handle:
                        handle.write(data)
                break
        if not os.path.exists(path) or os.path.getsize(path) == 0:
            archive = os.path.join(self.cache, "mcp",
                                   os.path.basename(name).replace(".srg", ".zip"))
            self._download_any(url, archive)
            import zipfile
            with zipfile.ZipFile(archive) as bundle:
                member = next(n for n in bundle.namelist() if n.endswith(member_suffix))
                data = bundle.read(member)
            os.makedirs(os.path.dirname(path), exist_ok=True)
            with open(path, "wb") as handle:
                handle.write(data)
        return open(path, encoding="utf-8").read()

    @staticmethod
    def _urls(template, **kwargs):
        """One or more mirrors for the same file, ready to try in order."""
        if isinstance(template, tuple):
            return [t.format(**kwargs) for t in template]
        return template.format(**kwargs)

    def mcpconfig(self, version: str) -> str:
        root = os.environ.get("NOCTURNE_MCPCONFIG_DIR", "")
        return self._text(
            self._urls(MCPCONFIG_RAW, v=version),
            os.path.join("mcpconfig", f"joined-{version}.tsrg"),
            [(root, os.path.join("versions", "release", version, "joined.tsrg"))],
        )

    def intermediary(self, version: str) -> "str | None":
        if version in FABRIC_INTERMEDIARY_VERSIONS:
            root = os.environ.get("NOCTURNE_INTERMEDIARY_DIR", "")
            return self._text(
                self._urls(INTERMEDIARY_RAW, v=version),
                os.path.join("intermediary", f"official-{version}.tiny"),
                [(root, os.path.join("mappings", f"{version}.tiny"))],
            )
        if version in LEGACY_INTERMEDIARY_VERSIONS:
            root = os.environ.get("NOCTURNE_LEGACY_INTERMEDIARY_DIR", "")
            return self._text(
                self._urls(LEGACY_INTERMEDIARY_RAW, v=version),
                os.path.join("intermediary", f"legacy-{version}.tiny"),
                [(root, os.path.join("mappings", f"{version}.tiny"))],
            )
        return None

    def mcp_srg(self, version: str) -> "str | None":
        """MCP's own ``joined.srg`` for the pre-MCPConfig era, or ``None``."""
        if version not in MCP_LEGACY_SRG_VERSIONS:
            return None
        root = os.environ.get("NOCTURNE_MCP_SRG_DIR", "")
        return self._zip_text(
            self._urls(MCP_SRG_RAW, v=version),
            os.path.join("mcp", f"srg-{version}.srg"),
            "joined.srg",
            [(root, f"srg-{version}.srg")],
        )


# --------------------------------------------------------------------------
# version arithmetic
# --------------------------------------------------------------------------


def _version_key(version: str) -> tuple:
    parts = []
    for chunk in version.split("."):
        digits = "".join(c for c in chunk if c.isdigit())
        parts.append(int(digits) if digits else 0)
    return tuple(parts)


def at_least(version: str, floor: str) -> bool:
    """Version comparison by numeric components (``1.21.10`` > ``1.21.4``)."""
    left, right = _version_key(version), _version_key(floor)
    length = max(len(left), len(right))
    return left + (0,) * (length - len(left)) >= right + (0,) * (length - len(right))


#: NeoForge's first release; before it the loader does not exist.
NEOFORGE_FROM = "1.20.1"
#: NeoForge switched from SRG to Mojang names here (upstream 20.2).
NEOFORGE_MOJMAP_FROM = "1.20.2"


# --------------------------------------------------------------------------
# resolvers
# --------------------------------------------------------------------------


class ModernResolver:
    """>= 1.14.4: Mojang's ``client_mappings`` is the canonical <-> obf pivot."""

    def __init__(self, version: str, mojmap: Mojmap, srg: "McpSrg | None",
                 intermediary: "Intermediary | None", jar_path: "str | None" = None,
                 javap_exe: str = "javap", javap_cache: "str | None" = None) -> None:
        self.version = version
        self.mojmap = mojmap
        self.srg = srg
        self.intermediary = intermediary
        self.index = None
        if jar_path is not None:
            import javap as _javap
            self.index = _javap.JavapIndex(jar_path, javap_exe, javap_cache)
        self._supers: "dict[str, list[str]]" = {}
        self.to_obf = mojmap.to_obf
        self.to_fabric: "dict[str, str]" = {}
        for canonical, obf in self.to_obf.items():
            name = intermediary.class_name(obf) if intermediary is not None else None
            self.to_fabric[canonical] = name or canonical
        self.to_forge: "dict[str, str]" = {}
        for canonical, obf in self.to_obf.items():
            srg_name = srg.class_name(obf) if srg is not None else None
            if srg_name is not None and _is_readable_class(srg_name):
                self.to_forge[canonical] = srg_name
            else:
                self.to_forge[canonical] = canonical
        self.has_forge = srg is not None
        self.has_neoforge = at_least(version, NEOFORGE_FROM)
        self.neoforge_is_mojmap = at_least(version, NEOFORGE_MOJMAP_FROM)

    # -- classes ---------------------------------------------------------
    def resolve_class(self, canonical: str) -> "dict[str, str] | None":
        key = canonical.replace(".", "/")
        obf = self.mojmap.class_of(key)
        if obf is None:
            return None
        names = {"vanilla": obf}
        fabric = self.intermediary.class_name(obf) if self.intermediary is not None else None
        # No intermediary file means the version is not obfuscated (26.x), so
        # Fabric's namespace *is* the official one.
        names["fabric"] = fabric or key
        if self.has_forge:
            names["forge"] = self.to_forge.get(key, key)
        if self.has_neoforge:
            names["neoforge"] = key if self.neoforge_is_mojmap else names.get("forge", key)
        return {namespace: dotted(name) for namespace, name in names.items()}

    # -- hierarchy -------------------------------------------------------
    def _mojmap_supers(self, canonical: str) -> "list[str]":
        """Direct canonical superclasses/interfaces of a canonical class."""
        if canonical in self._supers:
            return self._supers[canonical]
        supers: "list[str]" = []
        if self.index is not None:
            obf = self.to_obf.get(canonical)
            if obf is not None:
                info = self.index.class_info(obf)
                if info is not None:
                    for obf_super in info.get("supers", []):
                        mapped = self.mojmap.from_obf.get(obf_super)
                        if mapped is not None:
                            supers.append(mapped)
        self._supers[canonical] = supers
        return supers

    def _ancestors(self, canonical: str) -> "list[str]":
        """The class itself first, then its supers breadth-first.

        Requirements name a member on the class the *client* talks through
        (``Player#getHealth``), while the member is declared further up
        (``Entity``/``LivingEntity``); the obf name only exists on the
        declaring class, so the lookup has to walk up.
        """
        order: "list[str]" = []
        seen = set()
        queue = [canonical]
        while queue:
            current = queue.pop(0)
            if current in seen:
                continue
            seen.add(current)
            order.append(current)
            if self.index is not None:
                queue.extend(self._mojmap_supers(current))
        return order

    def save(self) -> None:
        if self.index is not None:
            self.index.save()

    # -- members ---------------------------------------------------------
    def _pick_method(self, canonical: str, name: str, shape_hint: "int | None"):
        """(declaring class, (obf name, canonical descriptor)) for a method."""
        for ancestor in self._ancestors(canonical):
            candidates = self.mojmap.methods.get((ancestor, name))
            if not candidates:
                continue
            if len(candidates) > 1:
                if shape_hint is not None:
                    matching = [c for c in candidates if parameter_count(c[1]) == shape_hint]
                    if matching:
                        candidates = matching
                candidates = sorted(candidates, key=lambda c: (parameter_count(c[1]), c[1], c[0]))
            return ancestor, candidates[0]
        return None

    def _pick_field(self, canonical: str, name: str):
        """(declaring class, (obf name, canonical descriptor)) for a field."""
        for ancestor in self._ancestors(canonical):
            candidates = self.mojmap.fields.get((ancestor, name))
            if candidates:
                return ancestor, sorted(candidates)[0]
        return None

    def resolve_method(self, canonical: str, name: str,
                       shape_hint: "int | None") -> "dict[str, tuple] | None":
        key = canonical.replace(".", "/")
        if self.mojmap.class_of(key) is None:
            return None
        picked = self._pick_method(key, name, shape_hint)
        if picked is None:
            return None
        declaring, (obf_name, canonical_desc) = picked
        obf_owner = self.to_obf[declaring]
        result = {"vanilla": (obf_name, convert_descriptor(canonical_desc, self.to_obf))}
        fabric = None
        if self.intermediary is not None:
            fabric = self.intermediary.method(obf_owner, obf_name,
                                             convert_descriptor(canonical_desc, self.to_obf))
        result["fabric"] = (fabric or name, convert_descriptor(canonical_desc, self.to_fabric))
        if self.has_forge:
            srg_name = self.srg.method(obf_owner, obf_name,
                                       convert_descriptor(canonical_desc, self.to_obf)) or name
            result["forge"] = (srg_name, convert_descriptor(canonical_desc, self.to_forge))
        if self.has_neoforge:
            if self.neoforge_is_mojmap:
                result["neoforge"] = (name, canonical_desc)
            else:
                result["neoforge"] = result.get("forge", (name, canonical_desc))
        return result

    def resolve_field(self, canonical: str, name: str) -> "dict[str, tuple] | None":
        key = canonical.replace(".", "/")
        if self.mojmap.class_of(key) is None:
            return None
        picked = self._pick_field(key, name)
        if picked is None:
            return None
        declaring, (obf_name, canonical_desc) = picked
        obf_owner = self.to_obf[declaring]
        result = {"vanilla": (obf_name, convert_descriptor(canonical_desc, self.to_obf))}
        fabric = None
        if self.intermediary is not None:
            fabric = self.intermediary.field(obf_owner, obf_name,
                                            convert_descriptor(canonical_desc, self.to_obf))
        result["fabric"] = (fabric or name, convert_descriptor(canonical_desc, self.to_fabric))
        if self.has_forge:
            srg_name = self.srg.field(obf_owner, obf_name)
            result["forge"] = (srg_name or name,
                               convert_descriptor(canonical_desc, self.to_forge))
        if self.has_neoforge:
            if self.neoforge_is_mojmap:
                result["neoforge"] = (name, canonical_desc)
            else:
                result["neoforge"] = result.get("forge", (name, canonical_desc))
        return result


class LegacyResolver:
    """1.8.9 / 1.12.2: the alias TOML names the MCP human name, the CSVs and
    ``joined.srg`` carry the rest.

    This era has no Mojmap text, so the descriptors that exist are the obf one
    (vanilla, from ``MD:``'s left half) and the MCP one (forge, right half).
    The Fabric descriptor is derived from the obf one through the legacy
    intermediary's obf->intermediary class map.
    """

    def __init__(self, version: str, legacy: "sources.LegacySource",
                 intermediary: "Intermediary | None") -> None:
        self.version = version
        self.legacy = legacy
        self.data = legacy.data
        self.intermediary = intermediary
        self.obf_to_fabric = dict(intermediary.class_to_inter) if intermediary else {}

    def resolve_class(self, canonical: str) -> "dict[str, str] | None":
        key = canonical.replace(".", "/")
        answer = self.legacy.resolve_class(key)
        if not answer.ok:
            return None
        obf = answer.name
        names = {"vanilla": obf}
        fabric = self.intermediary.class_name(obf) if self.intermediary else None
        if fabric is not None:
            names["fabric"] = fabric
        mcp_class = self.legacy.classes.get(key)
        if isinstance(mcp_class, str):
            names["forge"] = mcp_class
        return {namespace: dotted(name) for namespace, name in names.items()}

    def _rule(self, key: str, kind: str, name: str):
        override = self.legacy.overrides.get(key, {})
        if name in override:
            return override[name]
        table = self.legacy.methods if kind == "method" else self.legacy.fields
        return table.get(name)

    def _scope(self, key: str, rule) -> "str | None":
        scope = rule.get("scope")
        if scope:
            return scope
        class_rule = self.legacy.classes.get(key)
        return class_rule if isinstance(class_rule, str) else None

    def resolve_method(self, canonical: str, name: str,
                       shape_hint: "int | None") -> "dict[str, tuple] | None":
        key = canonical.replace(".", "/")
        rule = self._rule(key, "method", name)
        if not rule or "unsupported" in rule or "skip" in rule:
            return None
        scope = self._scope(key, rule)
        if scope is None:
            return None
        entries = list(self.data.by_method_name.get(scope, {}).get(rule.get("mcp"), []))
        if rule.get("srg"):
            entries = [e for e in entries if e[0] == rule["srg"]]
        if not entries:
            return None
        if shape_hint is not None:
            matching = [e for e in entries if parameter_count(e[3]) == shape_hint]
            if matching:
                entries = matching
        entries.sort(key=lambda e: (parameter_count(e[3]), e[3], e[2]))
        srg, obf_owner, obf_name, obf_desc, mcp_desc = entries[0]
        result = {"vanilla": (obf_name, obf_desc)}
        if self.intermediary is not None:
            fabric = self.intermediary.method(obf_owner, obf_name, obf_desc)
            result["fabric"] = (fabric or name, convert_descriptor(obf_desc, self.obf_to_fabric))
        result["forge"] = (srg, mcp_desc or obf_desc)
        return result

    def resolve_field(self, canonical: str, name: str) -> "dict[str, tuple] | None":
        key = canonical.replace(".", "/")
        rule = self._rule(key, "field", name)
        if not rule or "unsupported" in rule or "skip" in rule:
            return None
        scope = self._scope(key, rule)
        if scope is None:
            return None
        entries = list(self.data.by_field_name.get(scope, {}).get(rule.get("mcp"), []))
        if rule.get("srg"):
            entries = [e for e in entries if e[0] == rule["srg"]]
        if not entries:
            return None
        entries.sort(key=lambda e: (e[2], e[0]))
        srg = entries[0][0]
        result = {"vanilla": (entries[0][2], None)}
        if self.intermediary is not None:
            fabric = self.intermediary.field(entries[0][1], entries[0][2], None)
            result["fabric"] = (fabric or name, None)
        result["forge"] = (srg, None)
        return result


class AnchorTable:
    """canonical -> intermediary, read off the 1.12.2 table.

    Intermediary names are version-stable by design, so a name learned on
    1.12.2 can be looked up in any older version's tiny file.  The 1.12.2 table
    is the anchor because the legacy path builds it from the alias bridge with
    no anchor involvement, so there is no circular dependency.
    """

    def __init__(self, table: dict) -> None:
        self.classes: "dict[str, str]" = {}
        self.methods: "dict[tuple, str]" = {}
        self.fields: "dict[tuple, str]" = {}
        self.arity: "dict[tuple, int]" = {}
        for canonical, entry in (table.get("classes") or {}).items():
            if entry.get("absent"):
                continue
            inter = (entry.get("names") or {}).get("fabric")
            if inter:
                # tiny files key classes by internal name; the table stores the
                # binary (dotted) form, so normalise on the way in.
                self.classes[canonical] = inter.replace(".", "/")
            for member, spec in (entry.get("methods") or {}).items():
                if spec.get("absent"):
                    continue
                name = (spec.get("names") or {}).get("fabric")
                if not name:
                    continue
                self.methods[(canonical, member)] = name
                descriptor = (spec.get("signatures") or {}).get("fabric")
                if descriptor:
                    self.arity[(canonical, member)] = parameter_count(descriptor)
            for member, spec in (entry.get("fields") or {}).items():
                if spec.get("absent"):
                    continue
                name = (spec.get("names") or {}).get("fabric")
                if name:
                    self.fields[(canonical, member)] = name


class AnchorResolver:
    """1.9 - 1.14.3: bridge the canonical name through intermediary.

    These versions predate Mojang's ``client_mappings`` (1.14.4) and have no MCP
    human names for the 1.13+ era either, so neither the ProGuard route nor the
    alias bridge reaches them.  Intermediary does: it is a version-stable naming
    layer covering 1.8.2 - 1.13.2 (Legacy-Fabric) and 1.14+ (upstream), and each
    version's own tiny file carries the obf name behind every intermediary name.
    The Forge namespace comes from that version's SRG (MCP ``joined.srg`` before
    1.12.2, MCPConfig from 1.12.2 on) keyed by the obf name; versions MCP never
    published an SRG for simply have no Forge namespace.
    """

    def __init__(self, version: str, anchor: AnchorTable, intermediary: Intermediary,
                 srg) -> None:
        self.version = version
        self.anchor = anchor
        self.intermediary = intermediary
        self.srg = srg

    def _forge_class(self, obf: str, canonical: str) -> "str | None":
        if self.srg is None:
            return None
        name = self.srg.class_name(obf)
        if name is None:
            return None
        return name if _is_readable_class(name) else canonical

    def resolve_class(self, canonical: str) -> "dict[str, str] | None":
        key = canonical.replace(".", "/")
        inter = self.anchor.classes.get(key)
        if inter is None:
            return None
        obf = self.intermediary.from_inter.get(inter)
        if obf is None:
            return None
        names = {"vanilla": obf, "fabric": inter}
        forge = self._forge_class(obf, key)
        if forge is not None:
            names["forge"] = forge
        return {namespace: dotted(name) for namespace, name in names.items()}

    def _forge_descriptor(self, owner: str, obf_name: str, obf_desc: str) -> "str | None":
        if isinstance(self.srg, SrgV1):
            # The MCP descriptor is exactly what Forge exposes for this era.
            return self.srg.method_descriptor(owner, obf_name, obf_desc)
        class_map = getattr(self.srg, "class_to_srg", None)
        return convert_descriptor(obf_desc, class_map) if class_map else None

    def _owner_filter(self, canonical: str, inter: str, entries: list) -> list:
        """Keep only entries that can actually belong to this class.

        A canonical-looking anchor name (the anchor table falls back to the
        canonical name when a version has no intermediary entry) can otherwise
        match an unrelated class that simply kept the same name, which silently
        yields a name that does not exist on the target class.
        """
        anchor_class = self.anchor.classes.get(canonical)
        expected = self.intermediary.from_inter.get(anchor_class) if anchor_class else None
        exact = [e for e in entries if e[0] == expected] if expected else []
        if exact:
            return exact
        # Nothing is declared on this very class.  A genuine intermediary name
        # may legitimately live on an ancestor (an override shares its id), but
        # a canonical fallback name must not be trusted to.
        if inter.startswith(("method_", "field_")):
            return entries
        return []

    def resolve_method(self, canonical: str, name: str,
                       shape_hint: "int | None") -> "dict[str, tuple] | None":
        key = canonical.replace(".", "/")
        inter = self.anchor.methods.get((key, name))
        if inter is None:
            return None
        entries = self._owner_filter(key, inter, list(self.intermediary.methods_by_inter.get(inter, ())))
        if not entries:
            return None
        arity = self.anchor.arity.get((key, name))
        if arity is not None:
            matching = [e for e in entries if parameter_count(e[2]) == arity]
            if matching:
                entries = matching
        owner, obf_name, obf_desc = sorted(entries)[0]
        result = {
            "vanilla": (obf_name, obf_desc),
            "fabric": (inter, convert_descriptor(obf_desc, self.intermediary.class_to_inter)),
        }
        if self.srg is not None:
            srg_name = self.srg.method(owner, obf_name, obf_desc)
            if srg_name:
                descriptor = self._forge_descriptor(owner, obf_name, obf_desc)
                result["forge"] = (srg_name, descriptor or obf_desc)
        return result

    def resolve_field(self, canonical: str, name: str) -> "dict[str, tuple] | None":
        key = canonical.replace(".", "/")
        inter = self.anchor.fields.get((key, name))
        if inter is None:
            return None
        entries = self._owner_filter(key, inter, list(self.intermediary.fields_by_inter.get(inter, ())))
        if not entries:
            return None
        owner, obf_name, obf_desc = sorted(entries)[0]
        result = {
            "vanilla": (obf_name, obf_desc),
            "fabric": (inter, convert_descriptor(obf_desc, self.intermediary.class_to_inter)),
        }
        if self.srg is not None:
            srg_name = self.srg.field(owner, obf_name)
            if srg_name:
                result["forge"] = (srg_name, None)
        return result


class IdentityResolver:
    """Unobfuscated build (26.1+): every namespace names the same thing.

    Member existence is still checked against the real client jar, so a member
    that moved in that specific version lands in the table as ``absent``
    instead of being claimed to exist.
    """

    def __init__(self, version: str, jar: "str | None", javap_exe: str,
                 javap_cache: str) -> None:
        self.version = version
        self.jar = jar
        self._entries = None
        self.index = None
        if jar is not None:
            import javap as _javap
            self._entries = _javap.class_entries(jar)
            self.index = _javap.JavapIndex(jar, javap_exe, javap_cache)

    def resolve_class(self, canonical: str) -> "dict[str, str] | None":
        if self.jar is None:
            return None
        internal = canonical.replace(".", "/")
        if internal not in self._entries:
            return None
        name = canonical.replace("/", ".")
        return {namespace: name for namespace in NAMESPACES}

    def _member(self, canonical: str, kind: str, name: str):
        if self.jar is None:
            return None
        internal = canonical.replace(".", "/")
        if not self.index.reachable(internal, kind, name):
            return None
        return {namespace: (name, None) for namespace in NAMESPACES}

    def resolve_method(self, canonical: str, name: str, shape_hint: "int | None"):
        return self._member(canonical, "method", name)

    def resolve_field(self, canonical: str, name: str):
        return self._member(canonical, "field", name)

    def save(self) -> None:
        if self.index is not None:
            self.index.save()


def build_resolver(version: str, fetch: Fetch, cache_dir: str, manifest,
                   legacy_root: str, tools_dir: str, javap_exe: str,
                   local_jar: "str | None", anchor_table: "AnchorTable | None" = None):
    """Pick and construct the resolver for one version.

    Returns ``None`` when no source can express this version at all — those
    versions are reported by the generator instead of being guessed at.
    """
    if version in ANCHOR_VERSIONS:
        # Pre-1.14.4 (and pre-MCP-human-name): resolved through intermediary.
        text = fetch.intermediary(version)
        if text is None or anchor_table is None:
            return None
        srg = None
        if version in MCP_LEGACY_SRG_VERSIONS:
            srg_text = fetch.mcp_srg(version)
            if srg_text:
                srg = SrgV1(srg_text)
        elif version in MCPCONFIG_SRG_VERSIONS:
            srg = McpSrg(fetch.mcpconfig(version))
        return AnchorResolver(version, anchor_table, Intermediary(text), srg)
    if version.startswith("26."):
        # 26.1+ is not obfuscated, so there is no client_mappings file to find:
        # the canonical name *is* the runtime name in every namespace.  Member
        # existence still has to be checked against the real jar (a member that
        # moved in that version must land as absent, not be claimed), so a
        # version whose jar we cannot get produces no table at all and the
        # runtime keeps its identity fallback.
        jar = local_jar
        if jar is None:
            try:
                jar = manifest.client_jar(version)
            except Exception:
                jar = None
        if jar is None:
            return None
        return IdentityResolver(version, jar, javap_exe,
                                os.path.join(cache_dir, f"javap-{version}.json"))
    has_mojmap = False
    try:
        has_mojmap = bool(
            manifest.version_info(version).get("downloads", {}).get("client_mappings")
        )
    except Exception:
        has_mojmap = False
    if not has_mojmap and version not in {"1.8.9", "1.12.2"}:
        return None
    intermediary = None
    text = fetch.intermediary(version)
    if text is not None:
        intermediary = Intermediary(text)
    if has_mojmap:
        mojmap = Mojmap(manifest.mappings_text(version))
        srg = McpSrg(fetch.mcpconfig(version)) if version in MCPCONFIG_SRG_VERSIONS else None
        jar = local_jar
        if jar is None:
            try:
                jar = manifest.client_jar(version)
            except Exception:
                jar = None
        return ModernResolver(
            version, mojmap, srg, intermediary, jar_path=jar, javap_exe=javap_exe,
            javap_cache=os.path.join(cache_dir, f"javap-{version}.json"),
        )
    aliases = os.path.join(tools_dir, f"aliases-{version}.toml")
    legacy = sources.LegacySource(version, legacy_root, aliases)
    return LegacyResolver(version, legacy, intermediary)
