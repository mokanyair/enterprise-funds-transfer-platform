#!/usr/bin/env python3

import hashlib
import json
import re
import sys
from pathlib import Path


def fail(message):
    print(f"ARTIFACT_VALIDATION_FAILED: {message}", file=sys.stderr)
    sys.exit(1)


if len(sys.argv) != 3:
    fail("Expected release directory and S3 prefix")

release_dir = Path(sys.argv[1])
prefix = sys.argv[2]

match = re.fullmatch(
    r"jenkins-artifacts/banking-app/test/releases/"
    r"([a-f0-9]{40})/build-([0-9]+)",
    prefix,
)

if not match:
    fail("Invalid release prefix")

commit, build_number = match.groups()

try:
    manifest = json.loads(
        (release_dir / "manifest.json").read_text()
    )

    if not isinstance(manifest, dict):
        fail("Invalid manifest structure")

    if manifest.get("commit") != commit:
        fail("Git commit mismatch")

    if str(manifest.get("build_number")) != build_number:
        fail("Build number mismatch")

    expected = manifest.get("sha256")

    if not isinstance(expected, str):
        fail("Missing SHA-256 checksum")

    if not re.fullmatch(r"[a-f0-9]{64}", expected):
        fail("Invalid SHA-256 checksum")

    checksum_file = (
        release_dir / "app.jar.sha256"
    ).read_text().split()

    if checksum_file != [expected, "app.jar"]:
        fail("Checksum file does not match manifest")

    jar = release_dir / "app.jar"

    if not jar.is_file() or jar.stat().st_size == 0:
        fail("Missing or empty application JAR")

    hasher = hashlib.sha256()

    with jar.open("rb") as stream:
        for chunk in iter(
            lambda: stream.read(1024 * 1024), b""
        ):
            hasher.update(chunk)

    if hasher.hexdigest() != expected:
        fail("Application JAR checksum mismatch")

except (OSError, ValueError, TypeError) as exc:
    fail(str(exc))

print(expected)
