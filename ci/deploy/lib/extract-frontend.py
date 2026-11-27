#!/usr/bin/env python3

"""
Secure extraction of NovaBank frontend release artifacts.

Phase 1: Validate archive and destination.
Phase 2: Validate archive members and resource limits.
Phase 3: Extract regular files into an isolated directory.
Phase 4: Verify the extracted application structure.
Phase 5: Publish the validated frontend directory.

Compatible with Python 3.9 and later.
"""

import argparse
import hashlib
import json
import os
import shutil
import tarfile
import tempfile

from pathlib import Path, PurePosixPath


MAX_FILES = 10000
MAX_FILE_SIZE = 50 * 1024 * 1024
MAX_TOTAL_SIZE = 200 * 1024 * 1024


def sha256(path):
    digest = hashlib.sha256()

    with path.open("rb") as stream:
        for chunk in iter(
            lambda: stream.read(1024 * 1024), b""
        ):
            digest.update(chunk)

    return digest.hexdigest()


def extract(archive, destination):

    if not archive.is_file():
        raise ValueError("Frontend archive does not exist")

    if os.path.lexists(destination):
        raise ValueError("Destination already exists")

    if not destination.parent.is_dir():
        raise ValueError("Destination parent does not exist")

    staging = Path(tempfile.mkdtemp(
        prefix=".frontend-staging-",
        dir=str(destination.parent)
    ))

    try:
        total_size = 0
        file_count = 0
        seen = set()

        with tarfile.open(archive, "r:gz") as package:

            for member in package:

                file_count += 1

                if file_count > MAX_FILES:
                    raise ValueError("Archive member limit exceeded")

                name = PurePosixPath(member.name)

                if name.is_absolute() or ".." in name.parts:
                    raise ValueError(
                        f"Unsafe archive path: {member.name}"
                    )

                if not name.parts:
                    if member.isdir():
                        continue

                    raise ValueError("Invalid archive member")

                if any(
                    part.startswith(".") for part in name.parts
                ):
                    raise ValueError(
                        f"Hidden archive path: {member.name}"
                    )

                if not (member.isdir() or member.isfile()):
                    raise ValueError(
                        f"Unsupported archive object: {member.name}"
                    )

                if name in seen:
                    raise ValueError(
                        f"Duplicate archive path: {member.name}"
                    )

                seen.add(name)

                target = staging.joinpath(*name.parts)

                if member.isdir():
                    target.mkdir(
                        parents=True,
                        exist_ok=True
                    )
                    continue

                if (
                    name.name == "mockServiceWorker.js"
                    or name.suffix == ".map"
                ):
                    raise ValueError(
                        f"Development artifact: {member.name}"
                    )

                if member.size < 0 or member.size > MAX_FILE_SIZE:
                    raise ValueError("Individual file size exceeded")

                total_size += member.size

                if total_size > MAX_TOTAL_SIZE:
                    raise ValueError("Total extraction size exceeded")

                target.parent.mkdir(
                    parents=True,
                    exist_ok=True
                )

                source = package.extractfile(member)

                if source is None:
                    raise ValueError("Cannot read archive member")

                with source, target.open("xb") as output:
                    shutil.copyfileobj(source, output)

                if target.stat().st_size != member.size:
                    raise ValueError("Extracted file size mismatch")

                target.chmod(0o644)

        index = staging / "index.html"
        assets = staging / "assets"

        if not index.is_file() or index.stat().st_size == 0:
            raise ValueError("Frontend index.html missing or empty")

        if not assets.is_dir():
            raise ValueError("Frontend assets directory missing")

        if not any(
            path.is_file() and path.stat().st_size > 0
            for path in assets.rglob("*.js")
        ):
            raise ValueError("Frontend JavaScript assets missing")

        # Generate deterministic inventory of extracted files.
        inventory = {}

        for path in sorted(staging.rglob("*")):

            if path.is_dir():
                path.chmod(0o755)

            elif path.is_file():
                relative = path.relative_to(staging).as_posix()
                inventory[relative] = sha256(path)

        inventory_file = staging / ".release-inventory.json"

        inventory_file.write_text(
            json.dumps(inventory, indent=2, sort_keys=True) + "\n"
        )

        inventory_file.chmod(0o644)

        staging.chmod(0o755)

        # Publish into the release directory.
        # Caller must hold the deployment lock.
        os.rename(staging, destination)

        print("FRONTEND_EXTRACTION_SUCCESS")

        print(f"Release directory: {destination}")
        print(f"Validated files: {len(inventory)}")
        print(f"Extracted bytes: {total_size}")

    finally:
        if staging.exists():
            shutil.rmtree(staging)


def main():

    parser = argparse.ArgumentParser()

    parser.add_argument("--archive", required=True, type=Path)
    parser.add_argument("--destination", required=True, type=Path)

    args = parser.parse_args()

    extract(args.archive, args.destination)


if __name__ == "__main__":
    main()
