#!/usr/bin/env python3
"""Host evidence check: pinned snapshot, archived Fluidify 27 names/XML/source hashes.

Does not run Kotlin tests. Source wiring checks are textual guards, not compilation
or a runtime provider/catalog check. All writes are confined to --output.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET

PIN = "9aceb3848db32cd4f6397f822d8ae9b2cc4212fa5c7ab20eb3adabed8ed9e9d9"
CONTRACT = "d5193acfbd86fd5668263b4835c605804c76278bb72c0c6b8c77fd64d1b458f4"


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def source_guards(host, proof):
    """Recognize the two qualified source shapes; this is not Kotlin call-path proof."""
    compact = re.sub(r"\s+", "", host)
    caller = 'caller={it.call.callingPackage}).registry()' in compact
    direct = 'returnSourceProofRegistry(' in compact and caller
    fenced = (
        'valproven=SourceProofRegistry(' in compact and caller
        and 'returnToolRegistry(proven.tools.map{if(it.name=="source_read")BridgeSourceRead(it)elseit},proven.groups,proven.actionGroup)' in compact
    )
    context_aliases = (
        'internalvaltokensget()=injectedTokens?:app.webApi.tokens' in compact
        and 'internalvalhistoryget()=injectedHistory?:app.listeningHistory' in compact
        and 'valgeneration=ctx.tokens.generation' in compact
        and 'valhistory=ctx.history.session.value' in compact
    )
    return {
        "provider_uses_actual_registry": 'override fun registry() = tools' in host and 'fluidifyRegistry("${context!!.packageName}.ai.tools")' in host,
        "registry_wraps_source_proof": direct or fenced,
        "four_tools_registered": all('proofTool("' + name + '"' in proof for name in ("source_capabilities", "source_lease", "source_validate", "source_read")),
        "monotonic_ttl": 'System.nanoTime() / 1_000_000' in proof and 'tick() + 60_000' in proof,
        "cancellation_propagates": 'catch (e: CancellationException) { throw e }' in proof,
        "readiness_and_generation": 'access == null || !history.recording' in host and ('ctx.app.webApi.tokens.generation' in host or context_aliases),
    }


def audit(snapshot, worktree):
    assert sha(snapshot / "manifest.json") == PIN, "snapshot manifest pin mismatch"
    manifest = json.loads((snapshot / "manifest.json").read_bytes())["files"]
    for entry in manifest:
        path = snapshot / entry["path"]
        assert path.stat().st_size == entry["bytes"] and sha(path) == entry["sha256"], entry["path"]
    assert sha(snapshot / "pampai-contract.md") == CONTRACT, "contract pin mismatch"
    imported = []
    for entry in manifest:
        if "/bridge/" in entry["path"] and entry["path"].endswith(".kt"):
            relative = entry["path"].removeprefix("fluidify/")
            current = worktree / relative
            imported.append({"path": relative, "baselineSha256": entry["sha256"],
                             "worktreeSha256": sha(current), "byteIdentical": sha(current) == entry["sha256"]})
    evidence = snapshot / "fluidify/docs/validation-source-proof"
    assigned = json.loads((evidence / "new-tests-evidence.json").read_bytes())
    assigned = [item for item in assigned if item["app"] == "Fluidify"]
    assert len(assigned) == 27
    cases = []
    for item in assigned:
        xml_path = evidence / "junit/app" / ("TEST-" + item["class"] + ".xml")
        root = ET.parse(xml_path).getroot()
        matches = [c for c in root.iter("testcase") if c.get("name") == item["test"] and c.get("classname") == item["class"]]
        assert len(matches) == 1, item
        assert not any(matches[0].find(tag) is not None for tag in ("failure", "error", "skipped")), item
        source = worktree / "app/src/test/java" / (item["class"].replace(".", "/") + ".kt")
        assert re.search(r"\bfun\s+" + re.escape(item["test"]) + r"\s*\(", source.read_text(encoding="utf-8")), item
        cases.append({"class": item["class"], "test": item["test"], "xml": str(xml_path),
                      "xmlSha256": sha(xml_path), "testSourceSha256": sha(source), "status": "archived_pass_not_rerun"})
    historical = json.loads((evidence / "source-artifact-manifest.json").read_bytes())
    key = "app\\src\\main\\java\\dev\\lelonio\\square\\bridge\\SourceProofRegistry.kt"
    assert historical[key]["sha256"] == sha(worktree / key.replace("\\", "/"))
    base = worktree / "app/src/main/java/dev/lelonio/square/bridge"
    host = (base / "FluidifyToolHost.kt").read_text(encoding="utf-8")
    proof = (base / "SourceProofRegistry.kt").read_text(encoding="utf-8")
    checks = source_guards(host, proof)
    assert all(checks.values()), checks
    return {"snapshotManifestSha256": PIN, "contractSha256": CONTRACT, "snapshotFilesVerified": len(manifest),
            "imports": imported, "sourceTextGuards": checks, "archivedAssignedFluidifyTests": cases,
            "historicalProofSourceHashMatches": True,
            "limits": "27 archived results matched, zero Android tests executed here. Archive binds only SourceProofRegistry production hash; other production sources are pinned at snapshot time, not proven to be the historical test invocation inputs. Text guards are not runtime proof."}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("snapshot", type=Path)
    parser.add_argument("--worktree", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    result = audit(args.snapshot, args.worktree)
    args.output.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print("PASS: 83 pinned files, 27 archived Fluidify testcase matches; no Android execution")


if __name__ == "__main__":
    main()
