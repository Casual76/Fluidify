#!/usr/bin/env python3
"""Pubblica la build per Wear OS di Fluidify accanto a quella del telefono.

Da lanciare *dopo* il publisher del Pampa Store, che crea la release
`<canale>-fluidify-v<versione>` con l'APK del telefono. Questo script:

  1. controlla l'APK dell'orologio: pacchetto `dev.pampa.fluidify`, versione uguale a
     quella in app/build.gradle.kts e, se gli dai anche l'APK del telefono, **la stessa
     firma** (senza, il Data Layer non fa parlare le due app e Android rifiuterebbe
     l'aggiornamento sull'orologio);
  2. lo carica come asset `fluidify-wear-<versione>.apk` sulla stessa release;
  3. aggiorna `manifest-wear.json` su master, con dimensione e SHA-256, che e' il file
     da cui telefono e orologio scoprono che c'e' una versione nuova.

Serve PAMPA_GH_TOKEN (lo stesso del publisher), con scrittura su Casual76/Fluidify.

Esempio (PowerShell):
  $env:PAMPA_GH_TOKEN = "ghp_..."
  python tools\\publish-wear.py --apk wear\\build\\outputs\\apk\\release\\wear-release.apk `
      --phone-apk app\\build\\outputs\\apk\\release\\app-release.apk
"""

import argparse
import base64
import datetime
import glob
import hashlib
import json
import os
import re
import subprocess
import sys
import urllib.error
import urllib.request

OWNER = "Casual76"
REPO = "Fluidify"
PACKAGE = "dev.pampa.fluidify"
MANIFEST_PATH = "manifest-wear.json"
API = "https://api.github.com"
UPLOADS = "https://uploads.github.com"


def fail(message):
    print(f"ERRORE: {message}", file=sys.stderr)
    sys.exit(1)


def root():
    return os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def phone_version():
    text = open(os.path.join(root(), "app", "build.gradle.kts"), encoding="utf-8").read()
    match = re.search(r'versionName\s*=\s*"([^"]+)"', text)
    if not match:
        fail("non trovo versionName in app/build.gradle.kts")
    return match.group(1)


def build_tool(name):
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk:
        local = os.path.join(root(), "local.properties")
        if os.path.exists(local):
            for line in open(local, encoding="utf-8"):
                if line.startswith("sdk.dir="):
                    sdk = line.split("=", 1)[1].strip().replace("\\:", ":").replace("\\\\", "\\")
    if not sdk:
        fail("non trovo l'Android SDK: imposta ANDROID_HOME")
    candidates = sorted(glob.glob(os.path.join(sdk, "build-tools", "*", name + "*")))
    candidates = [c for c in candidates if os.path.basename(c).split(".")[0] == name]
    if not candidates:
        fail(f"non trovo {name} nei build-tools di {sdk}")
    return candidates[-1]


def badging(apk):
    output = subprocess.run([build_tool("aapt2"), "dump", "badging", apk], capture_output=True, text=True)
    if output.returncode != 0:
        fail(f"aapt2 non legge {apk}: {output.stderr.strip()}")
    package = re.search(r"package: name='([^']+)'", output.stdout)
    version = re.search(r"versionName='([^']+)'", output.stdout)
    return (package.group(1) if package else None), (version.group(1) if version else None)


def certificate(apk):
    output = subprocess.run([build_tool("apksigner"), "verify", "--print-certs", apk], capture_output=True, text=True)
    if output.returncode != 0:
        fail(f"{apk} non e' firmato correttamente: {output.stderr.strip() or output.stdout.strip()}")
    match = re.search(r"certificate SHA-256 digest:\s*([0-9a-fA-F]+)", output.stdout)
    if not match:
        fail(f"non leggo il certificato di {apk}")
    return match.group(1).lower()


def version_key(text):
    return tuple(int(part) if part.isdigit() else 0 for part in text.split("-")[0].split("."))


def sha256(path):
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for block in iter(lambda: handle.read(1 << 16), b""):
            digest.update(block)
    return digest.hexdigest()


def request(method, url, token, data=None, content_type="application/json"):
    body = None
    if data is not None:
        body = data if isinstance(data, bytes) else json.dumps(data).encode("utf-8")
    req = urllib.request.Request(url, data=body, method=method)
    req.add_header("Authorization", f"Bearer {token}")
    req.add_header("Accept", "application/vnd.github+json")
    req.add_header("X-GitHub-Api-Version", "2022-11-28")
    if body is not None:
        req.add_header("Content-Type", content_type)
    try:
        with urllib.request.urlopen(req) as response:
            raw = response.read()
            return json.loads(raw) if raw else None
    except urllib.error.HTTPError as error:
        fail(f"{method} {url}: {error.code} {error.read().decode('utf-8', 'replace')[:300]}")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--apk", required=True, help="APK release dell'orologio")
    parser.add_argument("--phone-apk", help="APK release del telefono, per confrontare la firma")
    parser.add_argument("--channel", choices=["stable", "beta"], default="stable")
    parser.add_argument("--changelog", default="", help="note della versione (facoltative)")
    parser.add_argument("--dry-run", action="store_true", help="controlla e basta, non carica niente")
    args = parser.parse_args()

    version = phone_version()
    package, apk_version = badging(args.apk)
    if package != PACKAGE:
        fail(f"l'APK e' di {package}, non di {PACKAGE}")
    if apk_version != version:
        fail(f"l'APK e' la versione {apk_version}, app/build.gradle.kts dice {version}")

    watch_cert = certificate(args.apk)
    if args.phone_apk:
        phone_cert = certificate(args.phone_apk)
        if phone_cert != watch_cert:
            fail("telefono e orologio sono firmati con chiavi diverse: il Data Layer non li farebbe parlare")
        print("firma: uguale a quella del telefono")
    else:
        print("firma: non confrontata (passa --phone-apk per farlo)")

    size = os.path.getsize(args.apk)
    checksum = sha256(args.apk)
    # The release the phone's publisher made for the same channel: beta-... or stable-...
    tag = f"{args.channel}-fluidify-v{version}"
    asset = f"fluidify-wear-{version}.apk"
    print(f"{asset}: {size} byte, sha256 {checksum}")
    if args.dry_run:
        print("--dry-run: niente caricato.")
        return

    token = os.environ.get("PAMPA_GH_TOKEN")
    if not token:
        fail("manca PAMPA_GH_TOKEN")

    release = request("GET", f"{API}/repos/{OWNER}/{REPO}/releases/tags/{tag}", token)
    for existing in release.get("assets", []):
        if existing["name"] == asset:
            request("DELETE", f"{API}/repos/{OWNER}/{REPO}/releases/assets/{existing['id']}", token)
            print(f"tolto l'asset {asset} che c'era gia'")
    with open(args.apk, "rb") as handle:
        uploaded = request(
            "POST",
            f"{UPLOADS}/repos/{OWNER}/{REPO}/releases/{release['id']}/assets?name={asset}",
            token,
            data=handle.read(),
            content_type="application/vnd.android.package-archive",
        )
    print(f"caricato: {uploaded.get('browser_download_url')}")

    current = request("GET", f"{API}/repos/{OWNER}/{REPO}/contents/{MANIFEST_PATH}?ref=master", token)
    manifest = json.loads(base64.b64decode(current["content"]).decode("utf-8"))
    entry = {
        "version": version,
        "releaseDate": datetime.date.today().isoformat(),
        "changelog": args.changelog,
        "releaseTag": tag,
        "apkAsset": asset,
        "sizeBytes": size,
        "sha256": checksum,
    }
    app = manifest.setdefault("app", {})
    app[args.channel] = entry
    if args.channel == "stable":
        # Chi segue la beta riceve almeno la stabile, come per il telefono.
        beta = app.get("beta")
        if not beta or version_key(beta.get("version", "0")) <= version_key(version):
            app["beta"] = entry
    text = json.dumps(manifest, ensure_ascii=False, indent=2) + "\n"
    request(
        "PUT",
        f"{API}/repos/{OWNER}/{REPO}/contents/{MANIFEST_PATH}",
        token,
        data={
            "message": f"Aggiorna manifest-wear per {version}",
            "content": base64.b64encode(text.encode("utf-8")).decode("ascii"),
            "sha": current["sha"],
            "branch": "master",
        },
    )
    print(f"{MANIFEST_PATH} aggiornato su master: telefono e orologio vedranno la {version}.")


if __name__ == "__main__":
    main()
