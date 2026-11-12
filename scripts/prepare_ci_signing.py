"""Restore and validate the existing test key without logging private key bytes."""

import base64
import binascii
import hashlib
import os
from pathlib import Path
import subprocess

EXPECTED_SHA256 = "82a94fb920b5eb7ef1afe9ab250b0f59d3fecf52ec789fad9734b83eb7a73200"


def main():
    encoded = os.environ.get("ORDERS_DEBUG_KEYSTORE_BASE64", "").strip()
    if not encoded:
        raise SystemExit(
            "Missing repository secret ORDERS_DEBUG_KEYSTORE_BASE64. "
            "Restore the original Orders test key; a random replacement key must not be shipped."
        )
    path = Path(os.environ["RUNNER_TEMP"]) / "orders-debug.keystore"
    try:
        content = base64.b64decode("".join(encoded.split()), validate=True)
        with open(path, "wb", opener=lambda name, flags: os.open(name, flags, 0o600)) as file:
            file.write(content)
        certificate = subprocess.run(
            ["keytool", "-exportcert", "-keystore", str(path), "-storepass", "android",
             "-alias", "androiddebugkey"],
            check=True, capture_output=True,
        ).stdout
        fingerprint = hashlib.sha256(certificate).hexdigest()
        if fingerprint != EXPECTED_SHA256:
            raise ValueError("The signing certificate does not match the original Orders key")
        with Path(os.environ["GITHUB_ENV"]).open("a") as file:
            file.write(f"ORDERS_KEYSTORE_PATH={path}\n")
        print(f"Original Orders test signing certificate verified: {fingerprint}")
    except (ValueError, binascii.Error, subprocess.CalledProcessError):
        path.unlink(missing_ok=True)
        raise SystemExit("Invalid Orders signing key. Refusing to build an incompatible update.") from None


if __name__ == "__main__":
    main()
