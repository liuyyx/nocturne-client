#!/usr/bin/env python3
"""构建期类名随机化：把 jar 里 My Project 自己的类改名，但保持全链路引用一致。

为什么需要
    OpenZen 的教训是按「类名黑名单」封杀公开构建产物。公开仓只放源码、
    官方二进制每次构建随机化类名，是 AGENT.md 里定死的第二条红线。

为什么是 Python
    `tools/mapping/` 已经是构建期 Python 工具链（generate.py），本工具沿用同一约定，
    不引入第二套构建期语言。

为什么不能用「改源码包名」实现
    本项目是**全反射**架构，类名同时以三种形态出现：
      1. 常量池里的内部名        dev/nocturne/agent/NocturneAgent
      2. 描述符里的类型          Ldev/nocturne/client/module/Module;
      3. **字符串常量**（最要命）  "dev.nocturne.agent.transform.FrameHookTransformer"
    第 3 类只在改字节码时才看得见。全仓实测有 7 处这类常量，漏改任何一处，
    注入链会在真机上**静默**断掉（不抛异常，只是类找不到）。

实现要点
    只替换常量池里 CONSTANT_Utf8 的**载荷字节**，不增删常量池条目。
    类文件里所有交叉引用都是按**常量池下标**（1-based 序号）而非字节偏移，
    所以载荷变长只让后续字节整体平移，下标全部保持有效——这是本工具能保持
    引用一致的根本原因，也是它不需要完整字节码解析器就能做对的原因。

用法
    python3 randomize.py --in nocturne.jar --out nocturne.jar --salt <random>
    python3 randomize.py --in a.jar --check        # 只检查有没有漏改的旧名
"""

import argparse
import hashlib
import hmac
import io
import os
import re
import sys
import zipfile

# 常量池 tag：只关心这三个，其余按固定长度跳过。
UTF8, CLASS, STRING = 1, 7, 8

# 各 tag 的定长载荷字节数（不含 tag 自身）。UTF8 变长，单独处理。
FIXED = {
    3: 4, 4: 4, 5: 8, 6: 8, 7: 2, 8: 2, 9: 4, 10: 4, 11: 4, 12: 4,
    15: 3, 16: 2, 17: 4, 18: 4, 19: 2, 20: 2,
}


def read_utf8(raw):
    """解码常量池 UTF-8（class 文件用的是 modified UTF-8，但类名/描述符全在 ASCII 区）。"""
    return raw.decode("utf-8", "surrogateescape")


def encode_utf8(text):
    return text.encode("utf-8", "surrogateescape")


def parse_pool(data):
    """解析常量池，返回 (条目列表, 常量池结束偏移)。

    条目形如 (tag, raw)，raw 是**定长载荷**的原样字节；UTF8 条目的 raw 是解码后的字符串。
    """
    if data[:4] != b"\xca\xfe\xba\xbe":
        raise ValueError("not a class file (bad magic)")
    count = int.from_bytes(data[8:10], "big")
    pos = 10
    items = [None] * count  # 下标 0 保留不用
    i = 1
    while i < count:
        tag = data[pos]
        pos += 1
        if tag == UTF8:
            n = int.from_bytes(data[pos:pos + 2], "big")
            pos += 2
            items[i] = (tag, read_utf8(data[pos:pos + n]))
            pos += n
        else:
            size = FIXED.get(tag)
            if size is None:
                raise ValueError("unknown constant pool tag %d" % tag)
            items[i] = (tag, data[pos:pos + size])
            pos += size
        # long / double 占两个槽位，紧随其后的下标必须留空。
        i += 2 if tag in (5, 6) else 1
    return items, pos


def rebuild(items, minor_major, rest):
    """重建：magic + minor/major + count + 各条目 + 类文件其余部分。"""
    out = io.BytesIO()
    out.write(b"\xca\xfe\xba\xbe")
    out.write(minor_major)                      # 4 字节 minor+major
    out.write(len(items).to_bytes(2, "big"))    # 常量池计数
    for it in items[1:]:
        if it is None:
            continue  # long/double 的占位槽
        tag, raw = it
        out.write(bytes([tag]))
        if tag == UTF8:
            payload = encode_utf8(raw)
            out.write(len(payload).to_bytes(2, "big"))
            out.write(payload)
        else:
            out.write(raw)
    out.write(rest)
    return out.getvalue()


DESC = re.compile(r"L([A-Za-z0-9_$/]+);")


def rewrite_value(text, mapping, prefix, new_prefix):
    """把一段 Utf8 载荷里的旧类名换成新类名。

    三种形态都要覆盖：
      1. 内部名本身            dev/nocturne/agent/Foo
      2. 描述符内嵌类型        (Ldev/nocturne/agent/Foo;I)V
      3. 字符串常量（点号）     dev.nocturne.agent.Foo
      4. 前缀常量             "dev.nocturne.agent.transform."（拼接用，必须整体换前缀）
    """
    if text in mapping:
        return mapping[text]

    dotted = text.replace("/", ".")
    if dotted in DOTTED_MAPPING:
        return DOTTED_MAPPING[dotted]

    # 描述符（含泛型签名）：逐段替换 L...; 里的类型
    if "L" in text and ";" in text:
        def repl(m):
            name = m.group(1)
            return "L" + mapping.get(name, name) + ";"
        replaced = DESC.sub(repl, text)
        if replaced != text:
            return replaced

    # 前缀常量：前缀本身变了要跟着换（类名拼接场景）
    if prefix != new_prefix and dotted.startswith(prefix):
        return new_prefix + dotted[len(prefix):]
    return text


# 模块级映射表：rebuild 阶段要跨文件共用（保证同名类在所有 class 里映射一致）。
MAPPING = {}
DOTTED_MAPPING = {}


def seeded_name(salt, old_internal):
    """从 salt 派生新简单名：确定性（同 salt 同结果）、避免 Java 关键字、以字母开头。"""
    digest = hmac.new(salt.encode("utf-8"), old_internal.encode("utf-8"),
                      hashlib.sha256).hexdigest()
    alpha = "abcdefghijklmnopqrstuvwxyz"
    # 首位强制为字母（Java 标识符不允许数字开头），其余取十六进制。
    return alpha[int(digest[0], 16) % 26] + digest[1:11]


def build_mapping(names, salt, prefix, keep):
    """为一组内部类名生成映射。默认只换简单名、保留包路径——包路径是反射前缀常量的锚点，
    动它会同时放大「前缀拼接」与「加载器守卫」两类风险。"""
    keep = set(keep)
    for internal in sorted(names):
        if not internal.startswith(prefix + "/") or internal in keep:
            continue
        pkg, _, _simple = internal.rpartition("/")
        MAPPING[internal] = pkg + "/" + seeded_name(salt, internal)
    for old, new in MAPPING.items():
        DOTTED_MAPPING[old.replace("/", ".")] = new.replace("/", ".")
    return MAPPING


def collect_names(items):
    """从常量池里收集所有 CONSTANT_Class 指向的内部名。"""
    names = set()
    for it in items[1:]:
        if it and it[0] == CLASS:
            idx = int.from_bytes(it[1], "big")
            target = items[idx]
            if target and target[0] == UTF8:
                names.add(target[1])
    return names


def process_class(data, prefix, new_prefix):
    items, rest_off = parse_pool(data)
    minor_major = data[4:8]
    rest = data[rest_off:]
    for i, it in enumerate(items):
        if it and it[0] == UTF8:
            items[i] = (UTF8, rewrite_value(it[1], MAPPING, prefix, new_prefix))
    return rebuild(items, minor_major, rest)


def rewrite_manifest(blob):
    """改写清单里指向被改名类的属性。

    Premain-Class / Agent-Class 是 JVM 用来找 agentmain 的；Main-Class 是双击启动入口。
    这三个值必须跟着类名一起改——注入器读的正是清单，漏改等于发行一个永远注入不进去的 jar。
    """
    lines = blob.decode("utf-8", "surrogateescape").split("\r\n")
    out = []
    for line in lines:
        if ": " in line:
            key, _, value = line.partition(": ")
            stripped = value.strip()
            if stripped in DOTTED_MAPPING:
                line = "%s: %s" % (key, DOTTED_MAPPING[stripped])
        out.append(line)
    return "\r\n".join(out).encode("utf-8", "surrogateescape")


def dangling_refs(blobs, prefix):
    """jar 内**引用了但不存在**的类 = 悬空引用。

    这是随机化最容易出的错（改类文件却漏改某处引用），在真机上表现为「类找不到」的
    静默失效。构建期必须挡住，所以 --check 与写盘后的自校验共用这一份判据。
    """
    blobs_class = {n[:-len(".class")]: b for n, b in blobs.items() if n.endswith(".class")}
    own = set(blobs_class)
    referenced = set()
    dotted_prefix = prefix.replace("/", ".")
    for blob in blobs_class.values():
        items, _ = parse_pool(blob)
        for name in collect_names(items):
            if name.startswith(prefix + "/"):
                referenced.add(name)
        for it in items[1:]:
            if it and it[0] == UTF8:
                for m in DESC.finditer(it[1]):
                    if m.group(1).startswith(prefix + "/"):
                        referenced.add(m.group(1))
                if it[1].startswith(dotted_prefix):
                    referenced.add(it[1].replace(".", "/"))
    # 改名后包路径保留，所以「前缀命中」的字符串常量会额外捞到工具自己写下的
    # 前缀文本（如 EmbeddedAsmLoader 的 TRANSFORMER_PREFIX），用它拼出的类名无法静态判定，
    # 这里只在「后缀形如合法类名」时才算引用，避免把纯前缀误报成悬空。
    referenced = {r for r in referenced
                  if len(r) > len(prefix) + 1 and r[len(prefix) + 1].isupper()
                  or r in own}
    return sorted(referenced - own), own, referenced


def main(argv=None):
    ap = argparse.ArgumentParser(description="构建期类名随机化（保持全链路引用一致）")
    ap.add_argument("--in", dest="src", required=True, help="输入 jar")
    ap.add_argument("--out", dest="dst", help="输出 jar（--check 时可省）")
    ap.add_argument("--salt", default=None, help="随机化种子；缺省取 os.urandom（每次构建都不同）")
    ap.add_argument("--prefix", default="dev/nocturne", help="要改名的类名前缀（斜杠形态）")
    ap.add_argument("--keep", default="", help="逗号分隔的白名单类（永不改名）")
    ap.add_argument("--check", action="store_true", help="只检查是否还有未改名的旧类")
    args = ap.parse_args(argv)

    salt = args.salt or os.urandom(16).hex()
    keep = [x.strip() for x in args.keep.split(",") if x.strip()]

    if not os.path.isfile(args.src):
        print("input jar not found: %s" % args.src, file=sys.stderr)
        return 2

    with zipfile.ZipFile(args.src) as zin:
        entries = zin.infolist()
        blobs = {e.filename: zin.read(e.filename) for e in entries}

    if args.check:
        dangling, own, referenced = dangling_refs(blobs, args.prefix)
        if dangling:
            print("check failed: %d dangling references under %s/:" % (len(dangling), args.prefix))
            for d in dangling[:20]:
                print("  - %s" % d)
            return 1
        print("check ok: %d classes, %d in-prefix references, no dangling"
              % (len(own), len(referenced)))
        return 0

    if not args.dst:
        print("--out is required unless --check", file=sys.stderr)
        return 2

    all_names = set()
    for name, blob in blobs.items():
        if name.endswith(".class"):
            items, _ = parse_pool(blob)
            all_names |= collect_names(items)
    build_mapping(all_names, salt, args.prefix, keep)
    print("salt=%s  renaming %d classes under %s/" % (salt, len(MAPPING), args.prefix))

    with zipfile.ZipFile(args.dst, "w", zipfile.ZIP_DEFLATED) as zout:
        for entry in entries:
            name = entry.filename
            blob = blobs[name]
            new_name = name
            if name.endswith(".class"):
                internal = name[:-len(".class")]
                new_name = MAPPING.get(internal, internal) + ".class"
                blob = process_class(blob, args.prefix, args.prefix)
            elif name == "META-INF/MANIFEST.MF":
                blob = rewrite_manifest(blob)
            if new_name == name:
                # 原样写回：沿用原条目的时间戳与属性。
                zout.writestr(entry, blob)
            else:
                # 改名条目要新建 ZipInfo，但必须把原时间戳/属性/压缩方式抄过来——
                # 否则每次构建的 zip 头部都不同，「同 salt 可复现」这条性质就没了。
                info = zipfile.ZipInfo(new_name, date_time=entry.date_time)
                info.external_attr = entry.external_attr
                info.compress_type = entry.compress_type
                info.create_system = entry.create_system
                zout.writestr(info, blob)
    print("wrote %s (%d entries)" % (args.dst, len(blobs)))

    # 写盘后立刻自校验：与 PayloadTool 的往返自检同一哲学——引用一旦对不上，
    # 真机上只是「类找不到」的静默失效，必须在构建期以非 0 退出暴露。
    with zipfile.ZipFile(args.dst) as zout:
        out_blobs = {e.filename: zout.read(e.filename) for e in zout.infolist()}
    dangling, _own, _ref = dangling_refs(out_blobs, args.prefix)
    if dangling:
        print("randomize failed: %d dangling references after rename:" % len(dangling),
              file=sys.stderr)
        for d in dangling[:20]:
            print("  - %s" % d, file=sys.stderr)
        return 1
    print("self-check ok: no dangling references")
    return 0


if __name__ == "__main__":
    sys.exit(main())
