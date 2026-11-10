"""Deterministic synthetic tone. No Spotify content, keys, identities or network."""
import hashlib
import json
import pathlib
import subprocess
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes

folder = pathlib.Path(__file__).resolve().parent
plain = subprocess.check_output([
    "ffmpeg", "-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i",
    "sine=frequency=440:sample_rate=44100:duration=1.2", "-ac", "2", "-c:a", "libmp3lame",
    "-b:a", "160k", "-map_metadata", "-1", "-write_xing", "0", "-f", "mp3", "pipe:1",
])
key = bytes.fromhex("00112233445566778899aabbccddeeff")
iv = bytes.fromhex("72e067fbddcbcf77ebe8bc643f630d93")
encryptor = Cipher(algorithms.AES(key), modes.CTR(iv)).encryptor()
encrypted = encryptor.update(plain) + encryptor.finalize()
(folder / "tone.mp3.aes").write_bytes(encrypted)
(folder / "provenance.json").write_text(json.dumps({
    "source": "ffmpeg lavfi sine 440 Hz; synthetic, not a Spotify archive",
    "ffmpeg": subprocess.check_output(["ffmpeg", "-version"]).decode().splitlines()[0],
    "sha256": hashlib.sha256(encrypted).hexdigest(), "bytes": len(encrypted),
    "key": key.hex(), "iv": iv.hex(), "durationMs": 1200,
}, indent=2) + "\n", encoding="utf-8")
print(f"Synthetic encrypted fixture: {len(encrypted)} bytes")
