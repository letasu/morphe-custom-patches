"""Morphe Manager's MorpheAsset uses a UTC LocalDateTime, without Z or offset."""
import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[2]
REPO = "https://github.com/letasu/morphe-custom-patches"


def validate(data):
    version = data["version"]
    if not re.fullmatch(r"\d+\.\d+\.\d+", version):
        raise ValueError("Expected a stable semantic version")
    timestamp = data["created_at"]
    if not re.fullmatch(r"\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d+)?", timestamp):
        raise ValueError("created_at must be a UTC LocalDateTime without Z or offset")
    datetime.fromisoformat(timestamp)
    if data["download_url"] != f"{REPO}/releases/download/v{version}/patches-{version}.mpp":
        raise ValueError("Bundle URL and version disagree")
    if not isinstance(data["description"], str) or not data["description"].strip():
        raise ValueError("Missing release description")
    if data.get("signature_download_url") is not None:
        raise ValueError("Unsigned bundles must use null for signature_download_url")
    if data.get("page_url") != f"{REPO}/releases/tag/v{version}":
        raise ValueError("Release page and version disagree")


def write_json(path, data):
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")


def validate_patch_list(patch_list, version):
    if patch_list["version"] != version:
        raise ValueError("Patch-list version disagrees with release metadata")
    names = {patch["name"] for patch in patch_list["patches"]}
    if not {"Smart audio focus", "Hide members videos"}.issubset(names):
        raise ValueError("Generated patch list is missing a custom patch")


def prepare(version, description):
    data = {
        "created_at": datetime.now(timezone.utc).replace(tzinfo=None).isoformat(timespec="seconds"),
        "description": description,
        "download_url": f"{REPO}/releases/download/v{version}/patches-{version}.mpp",
        "signature_download_url": None,
        "page_url": f"{REPO}/releases/tag/v{version}",
        "version": version,
    }
    validate(data)
    properties = ROOT / "gradle.properties"
    text, count = re.subn(r"(?m)^version\s*=.*$", f"version = {version}", properties.read_text(encoding="utf-8"))
    if count != 1:
        raise ValueError("Expected exactly one Gradle version")
    properties.write_text(text, encoding="utf-8", newline="\n")
    write_json(ROOT / "patches-bundle.json", data)
    patch_list = ROOT / "patches-list.json"
    text, count = re.subn(r'"version": "[^"]+"', f'"version": "{version}"', patch_list.read_text(encoding="utf-8"), count=1)
    if count != 1:
        raise ValueError("Missing patch-list version")
    patch_list.write_text(text, encoding="utf-8", newline="\n")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version")
    parser.add_argument("--notes", type=Path)
    args = parser.parse_args()
    if args.version:
        if not args.notes:
            parser.error("--notes is required with --version")
        prepare(args.version, args.notes.read_text(encoding="utf-8"))
    data = json.loads((ROOT / "patches-bundle.json").read_text(encoding="utf-8"))
    validate(data)
    properties = (ROOT / "gradle.properties").read_text(encoding="utf-8")
    assert re.search(r"(?m)^version\s*=\s*" + re.escape(data["version"]) + r"\s*$", properties)
    validate_patch_list(json.loads((ROOT / "patches-list.json").read_text(encoding="utf-8")), data["version"])
    print(f"Manager metadata validated: {data['version']}")
