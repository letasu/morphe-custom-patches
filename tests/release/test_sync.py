import importlib.util
import io
import json
from pathlib import Path
import tarfile
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("sync_upstream", ROOT / "scripts/letasu/sync_upstream.py")
sync = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sync)


class SyncTest(unittest.TestCase):
    def test_owned_boundaries(self):
        self.assertTrue(sync.owned(".github/workflows/crowdin_pull.yml"))
        self.assertTrue(sync.owned(".github/actions/letasu-build/action.yml"))
        self.assertTrue(sync.owned("README.md"))
        self.assertTrue(all(sync.owned(name) for name in sync.CUSTOM_FILES))
        self.assertFalse(sync.owned("extensions/youtube/build.gradle.kts"))

    def test_reject_external_paths(self):
        for name in ("../outside", ".git/config", "./.git/config", ".git\\config", ".GIT/config", "/outside"):
            with self.subTest(name=name), self.assertRaises(ValueError):
                sync.safe_path(name)

    def test_snapshot_preserves_custom_overlay(self):
        build = (ROOT / "build").resolve()
        build.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(prefix="sync-test-", dir=build) as folder:
            root = Path(folder).resolve()
            assert root.is_relative_to(build)
            with patch.object(sync, "ROOT", root):
                files = {name: "custom" for name in sync.CUSTOM_FILES}
                files.update({"README.md": "Letasu", "obsolete.java": "old", "Provider.java": "old case",
                              ".github/workflows/build_release.yml": "local workflow",
                              "patches-bundle.json": '{"version":"1.0.10"}'})
                settings = "".join(f'<string name="{key}_{suffix}">custom</string>'
                                   for key in ("morphe_smart_audio_focus", "morphe_hide_members_videos")
                                   for suffix in ("title", "summary"))
                files.update({name: f"<resources>{settings}</resources>" for name in sync.RESOURCE_FILES})
                for name, content in files.items():
                    target = root / name
                    target.parent.mkdir(parents=True, exist_ok=True)
                    target.write_text(content, encoding="utf-8")
                upstream = {
                    "gradle.properties": "version = 1.48.0\n",
                    "patches/build.gradle.kts": '\n'.join([
                        'name = "Morphe Patches"', 'description = "Patches for Morphe"',
                        'source = "git@github.com:MorpheApp/morphe-patches.git"',
                        'author = "MorpheApp"', 'website = "https://morphe.software"']),
                    "new.java": "new upstream code",
                    "PROVIDER.java": "correct case",
                    ".github/workflows/crowdin_pull.yml": "must not be restored",
                    "README.md": "must not replace Letasu",
                    **{name: '<resources><string name="official">updated</string></resources>' for name in sync.RESOURCE_FILES},
                }
                archive = io.BytesIO()
                with tarfile.open(fileobj=archive, mode="w") as tar:
                    for name, content in upstream.items():
                        data = content.encode()
                        entry = tarfile.TarInfo(name)
                        entry.size = len(data)
                        tar.addfile(entry, io.BytesIO(data))

                def git_output(command, **kwargs):
                    if command[1] == "rev-parse":
                        return "a" * 40 + "\n"
                    if command[1] == "archive":
                        return archive.getvalue()
                    if command[1] == "ls-files":
                        return "\0".join(files).encode()
                    raise AssertionError(command)

                with patch.object(sync.subprocess, "run") as run_commands, patch.object(sync.subprocess, "check_output", side_effect=git_output):
                    sync.run("upstream/stable", "v1.48.0")
                run_commands.assert_any_call(["git", "update-index", "--force-remove", "--", "Provider.java"], cwd=root, check=True)
                run_commands.assert_any_call(["git", "add", "--", "PROVIDER.java"], cwd=root, check=True)
                self.assertEqual((root / "PROVIDER.java").read_text(), "correct case")
                self.assertFalse((root / "obsolete.java").exists())
                self.assertFalse((root / ".github/workflows/crowdin_pull.yml").exists())
                self.assertEqual((root / "new.java").read_text(), "new upstream code")
                self.assertEqual((root / "README.md").read_text(), "Letasu")
                self.assertEqual((root / "gradle.properties").read_text(), "version = 1.0.10\n")
                self.assertIn('name = "Letasu Patches"', (root / "patches/build.gradle.kts").read_text())
                for name in sync.CUSTOM_FILES:
                    self.assertEqual((root / name).read_text(), "custom")
                for name in sync.RESOURCE_FILES:
                    text = (root / name).read_text()
                    self.assertIn('name="official">updated', text)
                    self.assertEqual(len(sync.RESOURCE_PATTERN.findall(text)), 4)
                state = json.loads((root / ".letasu/upstream.json").read_text())
                self.assertEqual(state["tag"], "v1.48.0")


if __name__ == "__main__":
    unittest.main()
