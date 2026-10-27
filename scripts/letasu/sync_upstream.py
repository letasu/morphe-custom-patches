"""Import a fetched stable upstream snapshot while retaining the Letasu overlay.

Run from a clean checkout after fetching a pinned stable tag. This deliberately
does not merge upstream CI/release workflows or overwrite the custom classes.
Review, adapt custom APIs, run tests and build before publishing the result.
"""
import argparse
import io
import json
from pathlib import Path, PurePosixPath
import re
import subprocess
import tarfile

ROOT = Path(__file__).resolve().parents[2]
CUSTOM_FILES = {
    "extensions/youtube/src/main/java/app/morphe/extension/youtube/patches/audiofocus/SmartAudioFocusManager.java",
    "extensions/youtube/src/main/java/app/morphe/extension/youtube/patches/components/MembersVideoFilter.java",
    "patches/src/main/kotlin/app/morphe/patches/youtube/video/audiofocus/SmartAudioFocusPatch.kt",
    "patches/src/main/kotlin/app/morphe/patches/youtube/layout/hide/members/HideMembersVideosPatch.kt",
}
OWN_FILES = {"README.md", "patches-bundle.json", "patches-list.json", "RELEASE_NOTES.md"}
OWN_PREFIXES = (".letasu/", "scripts/letasu/", "tests/audiofocus/", "tests/release/", ".github/workflows/")
RESOURCE_FILES = [f"patches/src/main/resources/addresources/{locale}/youtube/strings.xml"
                  for locale in ("values", "values-ja-rJP")]
RESOURCE_PATTERN = re.compile(
    r'\s*<string name="(?:morphe_smart_audio_focus|morphe_hide_members_videos)_(?:title|summary)">.*?</string>', re.S)


def owned(name):
    return name in CUSTOM_FILES or name in OWN_FILES or name.startswith(OWN_PREFIXES)


def safe_path(name):
    parts = PurePosixPath(name).parts
    if not parts or any(part in ("..", ".git") for part in parts):
        raise ValueError(f"Unsafe archive path: {name}")
    target = (ROOT / name).resolve()
    if not target.is_relative_to(ROOT) or target == ROOT:
        raise ValueError(f"Path outside repository: {name}")
    return target


def run(ref, tag):
    if not re.fullmatch(r"v\d+\.\d+\.\d+", tag):
        raise ValueError("Only stable version tags may be imported")
    subprocess.run(["git", "diff", "--exit-code", "HEAD", "--"], cwd=ROOT, check=True, stdout=subprocess.DEVNULL)
    revision = subprocess.check_output(["git", "rev-parse", f"{ref}^{{commit}}"], cwd=ROOT, text=True).strip()
    # This tool never fetches or follows a moving branch itself.
    archive = subprocess.check_output(["git", "archive", "--format=tar", revision], cwd=ROOT)
    with tarfile.open(fileobj=io.BytesIO(archive)) as source:
        entries = {member.name: member for member in source if member.isfile()}
        if CUSTOM_FILES.intersection(entries):
            raise ValueError("Upstream now defines a custom class; review the collision manually")
        config_dir = ROOT / ".letasu"
        config_dir.mkdir(exist_ok=True)
        resource_config = config_dir / "resources.json"
        if resource_config.exists():
            resources = json.loads(resource_config.read_text(encoding="utf-8"))
        else:
            resources = {name: [match.strip() for match in RESOURCE_PATTERN.findall(safe_path(name).read_text(encoding="utf-8"))]
                         for name in RESOURCE_FILES}
            if any(len(strings) != 4 for strings in resources.values()):
                raise ValueError("Expected both custom settings with titles and summaries")
            resource_config.write_text(json.dumps(resources, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        version = json.loads((ROOT / "patches-bundle.json").read_text(encoding="utf-8"))["version"]
        tracked = subprocess.check_output(["git", "ls-files", "-z"], cwd=ROOT).decode().split("\0")
        managed = {name for name in entries if not owned(name)}
        for name in tracked:
            if name and not owned(name) and name not in managed:
                # Only individual tracked files are removed, with containment checked first.
                safe_path(name).unlink(missing_ok=True)
        for name in managed:
            target = safe_path(name)
            if target.exists() and name not in tracked:
                raise ValueError(f"Refusing to overwrite untracked file: {name}")
            target.parent.mkdir(parents=True, exist_ok=True)
            content = source.extractfile(entries[name]).read()
            if name == "gradle.properties":
                text, count = re.subn(r"(?m)^version\s*=.*$", f"version = {version}", content.decode())
                if count != 1:
                    raise ValueError("Upstream Gradle version layout changed")
                content = text.encode()
            elif name == "patches/build.gradle.kts":
                text = content.decode()
                replacements = {
                    'name = "Morphe Patches"': 'name = "Letasu Patches"',
                    'description = "Patches for Morphe"': 'description = "Morphe patches with Smart audio focus and Hide members videos"',
                    'source = "git@github.com:MorpheApp/morphe-patches.git"': 'source = "git@github.com:letasu/morphe-custom-patches.git"',
                    'author = "MorpheApp"': 'author = "letasu"',
                    'website = "https://morphe.software"': 'website = "https://github.com/letasu/morphe-custom-patches"',
                }
                for old, new in replacements.items():
                    if text.count(old) != 1:
                        raise ValueError(f"Upstream bundle branding layout changed: {old}")
                    text = text.replace(old, new)
                content = text.encode()
            elif name in resources:
                text = RESOURCE_PATTERN.sub("", content.decode())
                if text.count("</resources>") != 1:
                    raise ValueError(f"Upstream resource layout changed: {name}")
                text = text.replace("</resources>", "\n    " + "\n    ".join(resources[name]) + "\n</resources>")
                content = text.encode()
            target.write_bytes(content)
    state = {"repository": "MorpheApp/morphe-patches", "tag": tag, "commit": revision}
    (config_dir / "upstream.json").write_text(json.dumps(state, indent=2) + "\n", encoding="utf-8")
    for name in CUSTOM_FILES:
        if not safe_path(name).is_file():
            raise ValueError(f"Missing custom class: {name}")
    print(f"Imported {tag} ({revision}); preserved all custom classes and local workflows")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ref", required=True)
    parser.add_argument("--tag", required=True)
    args = parser.parse_args()
    run(args.ref, args.tag)
