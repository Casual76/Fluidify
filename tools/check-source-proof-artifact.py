#!/usr/bin/env python3
"""Check selected class_defs in an unminified APK, without SDK/compiler/device.

This is packaging evidence only: no runtime registration, typed negotiation,
signature/caller authorization or source readiness is established. R8-renamed or
inlined classes require a separate mapping-aware review; do not waive failures.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import struct
import sys
import zipfile


BRIDGE = "Ldev/lelonio/square/bridge/"
REQUIRED = {BRIDGE + name + ";" for name in (
    "FluidifyToolHost", "FluidifyBridgeContext", "FluidifyToolHostKt",
    "MusicTool", "SourceProofRegistry", "ProofState", "ProofIdentity",
    "ProofRejected", "ResultFragments", "SnapshotPager", "PlaylistOperations",
    "DurableOperationStore",
)} | {"Ldev/antigravity/fluidengine/ai/bridge/" + name + ";" for name in (
    "AiToolHostProvider", "BridgeProtocol",
)}


def dex_classes(data):
    """Follow class_def.class_idx -> type_id -> string_id; references do not count."""
    if len(data) < 112 or data[:4] != b"dex\n" or data[4:7] not in (
        b"035", b"036", b"037", b"038", b"039", b"040"
    ) or data[7] != 0:
        raise ValueError("unsupported standalone DEX header (035-040 required)")

    def u32(offset):
        return struct.unpack_from("<I", data, offset)[0]

    if u32(32) != len(data) or u32(36) != 112 or u32(40) != 0x12345678:
        raise ValueError("invalid DEX size/header/endian tag")

    def table(count_at, offset_at, width):
        count, offset = u32(count_at), u32(offset_at)
        if count and (offset < 112 or offset + count * width > len(data)):
            raise ValueError("DEX table outside file")
        return count, offset

    ns, strings = table(56, 60, 4)
    nt, types = table(64, 68, 4)
    nc, classes = table(96, 100, 32)
    result = set()
    for index in range(nc):
        type_index = u32(classes + 32 * index)
        if type_index >= nt:
            raise ValueError("invalid class type index")
        descriptor_index = u32(types + 4 * type_index)
        if descriptor_index >= ns:
            raise ValueError("invalid descriptor string index")
        start = u32(strings + 4 * descriptor_index)
        # Bounded ULEB128, then an ASCII class descriptor (not arbitrary MUTF8).
        for _ in range(5):
            if start >= len(data):
                raise ValueError("truncated string length")
            value = data[start]
            start += 1
            if not value & 0x80:
                break
        else:
            raise ValueError("invalid string length")
        end = data.find(b"\0", start)
        if end < 0:
            raise ValueError("unterminated descriptor")
        name = data[start:end].decode("ascii")
        if not name.startswith("L") or not name.endswith(";"):
            raise ValueError("invalid class descriptor")
        result.add(name)
    return result


def inspect_apk(path):
    defined, dex = set(), []
    with zipfile.ZipFile(path) as apk:
        for entry in apk.infolist():
            if re.fullmatch(r"classes(?:[2-9]|[1-9][0-9]+)?\.dex", entry.filename):
                dex.append(entry.filename)
                defined.update(dex_classes(apk.read(entry)))
    if not dex:
        raise ValueError("APK contains no standalone classes DEX")
    missing = sorted(REQUIRED - defined)
    return {"apk": str(path.resolve()), "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
            "bytes": path.stat().st_size, "dex": dex, "required": sorted(REQUIRED),
            "definedRequired": sorted(REQUIRED & defined), "missing": missing,
            "status": "FAIL" if missing else "PASS", "evidence": "DEX class_defs only",
            "limits": "Unminified APK only. No runtime/typed/signature/readiness verification; no R8 mapping support."}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    args = parser.parse_args()
    try:
        result = inspect_apk(args.apk)
    except (OSError, ValueError, IndexError, struct.error, zipfile.BadZipFile) as exc:
        print(json.dumps({"status": "ERROR", "error": str(exc)}))
        return 2
    print(json.dumps(result, indent=2))
    return 1 if result["missing"] else 0


if __name__ == "__main__":
    sys.exit(main())
