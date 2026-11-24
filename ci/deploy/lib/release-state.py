#!/usr/bin/env python3

"""
Banking APP persistent release state manager.

Phase 1: Load and validate existing deployment metadata.
Phase 2: Write state atomically to prevent partial JSON files.
Phase 3: Record pending deployments before replacing the JAR.
Phase 4: Commit successful deployments or record rollback.
Phase 5: Promote functionally validated releases.
"""

import argparse
import datetime
import hashlib
import json
import os
import re
import sys
import tempfile
from pathlib import Path


def utc_now():
    return datetime.datetime.now(
        datetime.timezone.utc
    ).isoformat()


def digest(path):
    """Calculate SHA-256 using Python 3.9-compatible streaming."""
    hasher = hashlib.sha256()

    with open(path, "rb") as stream:
        for chunk in iter(
            lambda: stream.read(1024 * 1024), b""
        ):
            hasher.update(chunk)

    return hasher.hexdigest()


def load_json(path):
    with path.open() as stream:
        return json.load(stream)


def sync_directory(directory):
    """Persist directory metadata after rename or removal."""
    fd = os.open(str(directory), os.O_RDONLY)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


def atomic_json(path, data):
    """Write complete JSON before replacing the authoritative file."""
    fd, temporary = tempfile.mkstemp(
        prefix=".state-", dir=str(path.parent)
    )

    try:
        with os.fdopen(fd, "w") as stream:
            json.dump(data, stream, indent=2)
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())

        os.replace(temporary, path)
        sync_directory(path.parent)

    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def append_history(directory, event, **details):
    """Append timestamped deployment audit events."""
    record = {
        "timestamp": utc_now(),
        "event": event,
        **details,
    }

    path = directory / "deployment-history.jsonl"

    with path.open("a") as stream:
        stream.write(json.dumps(record) + "\n")
        stream.flush()
        os.fsync(stream.fileno())


def release_from_manifest(path, jar):
    """Associate a verified application binary with its release."""
    manifest = load_json(path)

    commit = manifest["commit"]
    build = str(manifest["build_number"])
    sha = manifest["sha256"]

    if not re.fullmatch(r"[a-f0-9]{40}", commit):
        raise ValueError("Invalid release commit")

    if not re.fullmatch(r"[0-9]+", build):
        raise ValueError("Invalid build number")

    if not re.fullmatch(r"[a-f0-9]{64}", sha):
        raise ValueError("Invalid SHA-256")

    if digest(jar) != sha:
        raise ValueError("Release checksum mismatch")

    return {
        "commit": commit,
        "build_number": build,
        "sha256": sha,
        "artifact": str(jar.resolve()),
        "status": "runtime_verified",
        "verified_utc": utc_now(),
    }


def require(condition, message):
    if not condition:
        raise ValueError(message)


def main():
    parser = argparse.ArgumentParser()

    parser.add_argument("--state-dir", required=True)

    commands = parser.add_subparsers(
        dest="command", required=True
    )

    init = commands.add_parser("init")
    init.add_argument("--manifest", required=True)
    init.add_argument("--jar", required=True)
    init.add_argument("--active-jar", required=True)

    begin = commands.add_parser("begin")
    begin.add_argument("--manifest", required=True)
    begin.add_argument("--jar", required=True)
    begin.add_argument("--backup", required=True)

    success = commands.add_parser("success")
    success.add_argument("--active-jar", required=True)

    rollback = commands.add_parser("rollback")
    rollback.add_argument("--active-jar", required=True)

    failure = commands.add_parser("failure")
    failure.add_argument("--reason", required=True)

    promote = commands.add_parser("promote")
    promote.add_argument("--active-jar", required=True)
    promote.add_argument("--commit", required=True)
    promote.add_argument("--build", required=True)
    promote.add_argument("--evidence", required=True)

    commands.add_parser("show")

    args = parser.parse_args()

    os.umask(0o077)

    directory = Path(args.state_dir)
    directory.mkdir(parents=True, exist_ok=True)

    state_path = directory / "state.json"
    pending_path = directory / "pending-deployment.json"

    # Phase 1: Initialize only from a verified release.
    if args.command == "init":
        require(
            not pending_path.exists(),
            "Pending deployment requires recovery"
        )

        require(
            not state_path.exists(),
            "Release state already initialized"
        )

        current = release_from_manifest(
            Path(args.manifest), Path(args.jar)
        )

        require(
            digest(args.active_jar) == current["sha256"],
            "Active JAR does not match baseline"
        )

        state = {
            "schema_version": 1,
            "current": current,
            "previous": None,
            "last_known_good": None,
        }

        atomic_json(state_path, state)
        append_history(directory, "BASELINE_INITIALIZED",
                       current=current)

        print("BASELINE_INITIALIZED")
        return

    state = load_json(state_path)

    # Phase 2: Persist recovery information before installation.
    if args.command == "begin":
        require(
            not pending_path.exists(),
            "Unresolved pending deployment exists"
        )

        candidate = release_from_manifest(
            Path(args.manifest), Path(args.jar)
        )
        candidate["status"] = "pending"

        previous = dict(state["current"])

        require(
            digest(args.backup) == previous["sha256"],
            "Backup does not match current release"
        )

        previous["artifact"] = str(
            Path(args.backup).resolve()
        )

        pending = {
            "started_utc": utc_now(),
            "candidate": candidate,
            "previous": previous,
            "original_state": state,
        }

        atomic_json(pending_path, pending)

        append_history(directory, "DEPLOYMENT_STARTED",
                       candidate=candidate,
                       previous=previous)

        print("DEPLOYMENT_STARTED")
        return

    # Phase 3: Record the candidate after successful health checks.
    if args.command == "success":
        pending = load_json(pending_path)
        candidate = pending["candidate"]

        require(
            digest(args.active_jar) == candidate["sha256"],
            "Installed candidate checksum mismatch"
        )

        candidate["status"] = "runtime_verified"
        candidate["verified_utc"] = utc_now()

        state = pending["original_state"]
        state["previous"] = pending["previous"]
        state["current"] = candidate

        atomic_json(state_path, state)

        append_history(directory, "DEPLOYMENT_SUCCESS",
                       current=candidate)

        pending_path.unlink()
        sync_directory(directory)

        print("DEPLOYMENT_SUCCESS")
        return

    # Phase 4: Restore the original state after verified rollback.
    if args.command == "rollback":
        pending = load_json(pending_path)

        require(
            digest(args.active_jar) ==
            pending["previous"]["sha256"],
            "Restored application checksum mismatch"
        )

        original = pending["original_state"]

        atomic_json(state_path, original)

        append_history(directory, "ROLLBACK_SUCCESS",
                       restored=original["current"],
                       failed_candidate=pending["candidate"])

        pending_path.unlink()
        sync_directory(directory)

        print("ROLLBACK_SUCCESS")
        return

    # Preserve the recovery journal if rollback fails.
    if args.command == "failure":
        append_history(directory, "DEPLOYMENT_FAILURE",
                       reason=args.reason)

        print("DEPLOYMENT_FAILURE_RECORDED")
        return

    # Phase 5: Promote only after an external functional test passes.
    if args.command == "promote":
        require(
            not pending_path.exists(),
            "Cannot promote during pending deployment"
        )

        current = state["current"]

        require(
            current["commit"] == args.commit and
            current["build_number"] == args.build,
            "Functional evidence targets another release"
        )

        require(
            digest(args.active_jar) == current["sha256"],
            "Active release checksum mismatch"
        )

        require(
            bool(args.evidence.strip()),
            "Functional validation evidence required"
        )

        promoted = dict(current)
        promoted["status"] = "functionally_verified"
        promoted["promoted_utc"] = utc_now()
        promoted["validation_evidence"] = args.evidence

        state["last_known_good"] = promoted

        atomic_json(state_path, state)

        append_history(directory, "LAST_KNOWN_GOOD_PROMOTED",
                       release=promoted)

        print("LAST_KNOWN_GOOD_PROMOTED")
        return

    if args.command == "show":
        print(json.dumps(state, indent=2))


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, KeyError, TypeError) as exc:
        print(f"RELEASE_STATE_ERROR: {exc}", file=sys.stderr)
        sys.exit(1)
