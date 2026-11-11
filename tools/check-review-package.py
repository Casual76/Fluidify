#!/usr/bin/env python3
"""Check mandatory evidence exists and matches a review package's manifest.

Read-only and host-only. Checks the selected required files, not the truth or
completeness of their claims, source/APK equivalence, signatures or hardware.
Example: python tools/check-review-package.py PACKAGE --requirements required.json
"""
import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import sys


def within(package, name):
    if not isinstance(name, str) or not name or "\\" in name or ":" in name:
        raise ValueError("expected a canonical relative POSIX path")
    relative = PurePosixPath(name)
    if relative.is_absolute() or ".." in relative.parts or relative.as_posix() != name:
        raise ValueError("path is not canonical or escapes the package")
    target = (package / name).resolve()
    if not target.is_relative_to(package.resolve()):
        raise ValueError("resolved path escapes the package")
    return target


def check(package, requirements):
    data = json.loads(requirements.read_text(encoding="utf-8-sig"))
    if data.get("schema") != 1 or not isinstance(data.get("requiredFiles"), list) or not data["requiredFiles"]:
        raise ValueError("requirements schema1 needs a nonempty requiredFiles list")
    wanted = data["requiredFiles"]
    for name in wanted:
        within(package, name)
    if len(set(wanted)) != len(wanted):
        raise ValueError("duplicate mandatory evidence path")
    manifest = package / "manifest.json"
    payload = json.loads(manifest.read_text(encoding="utf-8-sig"))
    index = {}
    for entry in payload["files"]:
        name = entry["path"]
        within(package, name)
        if name in index:
            raise ValueError("duplicate manifest entry: " + name)
        if type(entry.get("bytes")) is not int or entry["bytes"] < 0 or not re.fullmatch(r"[0-9a-f]{64}", entry.get("sha256", "")):
            raise ValueError("invalid size/hash in manifest entry: " + name)
        index[name] = entry
    errors = []
    for name in wanted:
        path = within(package, name)
        entry = index.get(name)
        if not path.is_file():
            errors.append(dict(path=name, problem="MISSING_FILE"))
        elif entry is None:
            errors.append(dict(path=name, problem="NOT_IN_MANIFEST"))
        elif path.stat().st_size != entry["bytes"]:
            errors.append(dict(path=name, problem="SIZE_MISMATCH"))
        elif hashlib.sha256(path.read_bytes()).hexdigest() != entry["sha256"]:
            errors.append(dict(path=name, problem="HASH_MISMATCH"))
    return dict(scope="Mandatory evidence files and manifest membership/size/hash only",
                package=str(package), requirements=str(requirements), requiredFiles=len(wanted),
                manifestSha256=hashlib.sha256(manifest.read_bytes()).hexdigest(),
                requirementsSha256=hashlib.sha256(requirements.read_bytes()).hexdigest(),
                errors=errors, passed=not errors, hardwareCertification=False)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("package", type=Path)
    parser.add_argument("--requirements", type=Path, required=True)
    args = parser.parse_args()
    try:
        result = check(args.package, args.requirements)
    except (OSError, ValueError, KeyError, TypeError) as error:
        print(json.dumps(dict(passed=False, error=str(error), hardwareCertification=False)))
        return 2
    print(json.dumps(result, indent=2))
    return int(not result["passed"])


if __name__ == "__main__":
    sys.exit(main())
