"""Check SPAKE2 ELF LOAD alignment in a built APK or dependency AAR (no extraction).

Debug/AAR: python tools/check-spake2-alignment.py path/to/archive
Release:   python tools/check-spake2-alignment.py path/to/app.apk --abis arm64-v8a

Checks ELF headers only; APK ZIP alignment and real-device pairing need separate
validation. No Gradle invocation, third-party packages, or native code execution.
"""

import argparse
import hashlib
from pathlib import Path
import struct
import sys
import zipfile


PAGE_SIZE = 16384
MACHINES = {"arm64-v8a": 183, "x86_64": 62}


def check_elf(data, abi):
    if len(data) < 64 or data[:6] != b"\x7fELF\x02\x01":
        raise ValueError("expected a little-endian ELF64 library")
    header = struct.unpack_from("<HHIQQQIHHHHHH", data, 16)
    if header[1] != MACHINES[abi]:
        raise ValueError(f"unexpected ELF machine {header[1]} for {abi}")
    phoff, phsize, phnum = header[4], header[8], header[9]
    if phsize < 56 or not phnum or phoff + phsize * phnum > len(data):
        raise ValueError("invalid ELF program-header table")
    loads = []
    for index in range(phnum):
        kind, _, offset, vaddr, _, _, _, align = struct.unpack_from(
            "<IIQQQQQQ", data, phoff + index * phsize
        )
        if kind != 1:  # PT_LOAD
            continue
        loads.append(f"offset={offset:#x} vaddr={vaddr:#x} align={align}")
        if align < PAGE_SIZE or align & (align - 1):
            raise ValueError(f"LOAD alignment {align} is not a power of two >= {PAGE_SIZE}")
        if offset % PAGE_SIZE != vaddr % PAGE_SIZE:
            raise ValueError("LOAD file offset and virtual address disagree modulo 16384")
    if not loads:
        raise ValueError("no ELF LOAD segments")
    return loads


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", type=Path)
    parser.add_argument("--abis", nargs="+", choices=tuple(MACHINES), default=list(MACHINES))
    args = parser.parse_args()
    try:
        print(f"{args.archive}: SHA256 {hashlib.sha256(args.archive.read_bytes()).hexdigest()}")
        with zipfile.ZipFile(args.archive) as archive:
            names = archive.namelist()
            for abi in args.abis:
                matches = [name for name in names if name in (
                    f"jni/{abi}/libspake2.so", f"lib/{abi}/libspake2.so"
                )]
                if len(matches) != 1:
                    raise ValueError(f"expected exactly one libspake2.so for {abi}, found {len(matches)}")
                name = matches[0]
                loads = check_elf(archive.read(name), abi)
                print(f"PASS {name}: " + "; ".join(loads))
    except (OSError, ValueError, struct.error, zipfile.BadZipFile) as error:
        print(f"FAIL: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
