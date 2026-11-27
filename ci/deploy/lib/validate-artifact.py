#!/usr/bin/env python3

"""
Validate immutable Banking APP TEST release artifacts.

Phase 1: Validate release identity.
Phase 2: Validate manifest structure and version.
Phase 3: Validate backend artifact integrity.
Phase 4: Validate frontend artifact integrity.
Phase 5: Return verified backend SHA256.

Supports legacy backend-only releases and full-stack releases.
Compatible with Python 3.9 and later.
"""

import argparse
import hashlib
import json
import re
import sys

from pathlib import Path


def fail(message):
    print(
        f"ARTIFACT_VALIDATION_FAILED: {message}",
        file=sys.stderr
    )
    sys.exit(1)


def sha256(path):
    digest = hashlib.sha256()

    with path.open("rb") as stream:
        for chunk in iter(
            lambda: stream.read(1024 * 1024), b""
        ):
            digest.update(chunk)

    return digest.hexdigest()


def validate_checksum(value):

    if not isinstance(value, str):
        fail("Missing SHA-256 checksum")

    if not re.fullmatch(r"[a-f0-9]{64}", value):
        fail("Invalid SHA-256 checksum")

    return value


def validate_file(
    release_dir,
    filename,
    expected,
    expected_size=None
):

    expected = validate_checksum(expected)

    artifact = release_dir / filename
    checksum = release_dir / f"{filename}.sha256"

    if artifact.is_symlink() or checksum.is_symlink():
        fail(f"Symbolic links prohibited: {filename}")

    if not artifact.is_file():
        fail(f"Missing artifact: {filename}")

    if artifact.stat().st_size == 0:
        fail(f"Empty artifact: {filename}")

    if not checksum.is_file():
        fail(f"Missing checksum file: {filename}")

    if checksum.read_text().split() != [
        expected, filename
    ]:
        fail(f"Checksum file mismatch: {filename}")

    if expected_size is not None:

        if type(expected_size) is not int:
            fail(f"Invalid artifact size: {filename}")

        if expected_size <= 0:
            fail(f"Invalid artifact size: {filename}")

        if artifact.stat().st_size != expected_size:
            fail(f"Artifact size mismatch: {filename}")

    if sha256(artifact) != expected:
        fail(f"Artifact checksum mismatch: {filename}")

    return expected


def validate_metadata(metadata, filename):

    if not isinstance(metadata, dict):
        fail(f"Invalid metadata: {filename}")

    if metadata.get("filename") != filename:
        fail(f"Artifact filename mismatch: {filename}")

    if "size_bytes" not in metadata:
        fail(f"Missing artifact size: {filename}")

    if type(metadata["size_bytes"]) is not int:
        fail(f"Invalid artifact size: {filename}")

    if metadata["size_bytes"] <= 0:
        fail(f"Invalid artifact size: {filename}")

    return metadata


def main():

    parser = argparse.ArgumentParser()

    parser.add_argument(
        "release_dir",
        type=Path
    )

    parser.add_argument("prefix")

    parser.add_argument(
        "--require-fullstack",
        action="store_true"
    )

    args = parser.parse_args()

    release_dir = args.release_dir

    # Phase 1: Validate release identity.

    match = re.fullmatch(
        r"jenkins-artifacts/banking-app/test/releases/"
        r"([a-f0-9]{40})/build-([0-9]+)",
        args.prefix
    )

    if not match:
        fail("Invalid release prefix")

    commit, build_number = match.groups()

    # Phase 2: Validate manifest.

    manifest_path = release_dir / "manifest.json"

    if manifest_path.is_symlink():
        fail("Manifest symbolic link prohibited")

    manifest = json.loads(manifest_path.read_text())

    if not isinstance(manifest, dict):
        fail("Invalid manifest structure")

    if manifest.get("commit") != commit:
        fail("Git commit mismatch")

    if str(manifest.get("build_number")) != build_number:
        fail("Build number mismatch")

    version = manifest.get("schema_version", 1)

    if type(version) is not int or version not in (1, 2):
        fail("Unsupported manifest schema version")

    if args.require_fullstack and version != 2:
        fail("Full-stack release required")

    expected = validate_checksum(manifest.get("sha256"))

    # Phase 3: Validate legacy backend-only releases.

    if version == 1:

        if "artifacts" in manifest:
            fail("Unexpected full-stack metadata in legacy manifest")

        validate_file(
            release_dir,
            "app.jar",
            expected
        )

    # Phase 4: Validate full-stack releases.

    else:

        if manifest.get("environment") != "test":
            fail("Invalid release environment")

        artifacts = manifest.get("artifacts")

        if not isinstance(artifacts, dict):
            fail("Missing artifact metadata")

        if set(artifacts) != {"backend", "frontend"}:
            fail("Unexpected release artifact structure")

        backend = validate_metadata(
            artifacts["backend"],
            "app.jar"
        )

        frontend = validate_metadata(
            artifacts["frontend"],
            "frontend.tar.gz"
        )

        if backend.get("sha256") != expected:
            fail("Backend manifest checksum mismatch")

        validate_file(
            release_dir,
            "app.jar",
            backend.get("sha256"),
            backend["size_bytes"]
        )

        validate_file(
            release_dir,
            "frontend.tar.gz",
            frontend.get("sha256"),
            frontend["size_bytes"]
        )

    # Phase 5: Preserve the existing stdout contract.
    # The deployment installer captures this value.

    print(expected)


if __name__ == "__main__":

    try:
        main()

    except (OSError, ValueError, TypeError) as exc:
        fail(str(exc))
