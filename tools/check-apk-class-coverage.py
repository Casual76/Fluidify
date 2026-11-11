#!/usr/bin/env python3
"""Check selected Kotlin classes are defined in an unminified APK, not just referenced.

Useful after builds sharing intermediates: JVM tests can pass while a DEX omits
generated classes required by the production constructor. No Android SDK needed.
DEX tables: https://source.android.com/docs/core/runtime/dex-format
"""

import argparse
from pathlib import Path
import struct
import sys
import zipfile


def dex_classes(data):
    if data[:4] != b"dex\n" or not (35 <= int(data[4:7]) <= 40):
        raise ValueError("unsupported DEX format (expected standalone versions 035–040)")

    def u32(offset):
        return struct.unpack_from("<I", data, offset)[0]

    strings, types = u32(0x3C), u32(0x44)
    count, classes = u32(0x60), u32(0x64)
    result = set()
    for index in range(count):
        # class_def.class_idx -> type_id.descriptor_idx -> string_id.string_data_off
        descriptor = u32(types + 4 * u32(classes + 32 * index))
        start = u32(strings + 4 * descriptor)
        # Skip the ULEB128 UTF-16 length; class descriptors here are ASCII.
        while data[start] & 0x80:
            start += 1
        start += 1
        result.add(data[start:data.index(0, start)].decode("utf-8", errors="replace"))
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--classes", required=True, type=Path,
                        help="Kotlin compiler output root (tmp/kotlin-classes/debug)")
    parser.add_argument("--class-prefix", required=True,
                        help="dotted class name prefix, e.g. dev.lelonio.square.auth.TokenStore")
    args = parser.parse_args()
    if not args.classes.is_dir():
        parser.error("compiled class directory does not exist")
    prefix = "L" + args.class_prefix.replace(".", "/")
    expected = {
        "L" + path.relative_to(args.classes).as_posix()[:-6] + ";"
        for path in args.classes.rglob("*.class")
        if ("L" + path.relative_to(args.classes).as_posix()[:-6]).startswith(prefix)
    }
    if not expected:
        parser.error("no compiled classes match the prefix; refusing an empty check")
    defined = set()
    with zipfile.ZipFile(args.apk) as apk:
        for entry in apk.namelist():
            if entry.startswith("classes") and entry.endswith(".dex") and "/" not in entry:
                defined.update(dex_classes(apk.read(entry)))
    missing = sorted(expected - defined)
    if missing:
        print(f"FAIL {args.apk}: {len(missing)}/{len(expected)} compiled classes missing from DEX")
        for name in missing:
            print("  " + name)
        return 1
    print(f"PASS {args.apk}: {len(expected)} compiled classes defined in DEX ({args.class_prefix})")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (OSError, ValueError, IndexError, struct.error, zipfile.BadZipFile) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        sys.exit(2)
