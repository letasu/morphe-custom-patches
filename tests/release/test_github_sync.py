import importlib.util
import json
import os
from pathlib import Path
import shutil
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts/letasu"))
import github_sync as sync


class GitHubSyncTest(unittest.TestCase):
    def test_only_stable_versions(self):
        for value in ("v1.48.0-dev.1", "main", "v1.48.0\nchanged=true", "v1.48.0;echo test"):
            with self.subTest(value=value), self.assertRaises(ValueError):
                sync.semver(value)
        self.assertEqual(sync.semver("v1.48.0"), (1, 48, 0))

    def test_next_version_avoids_partial_release_tag(self):
        refs = "a refs/tags/v1.0.11\nb refs/tags/v1.0.12-dev.1\nc refs/tags/v1.0.9\n"
        self.assertEqual(sync.next_version("1.0.10", refs), "1.0.12")

    def test_only_bot_issues_can_suppress_retry(self):
        body = '<!-- letasu-sync {"base":"a","upstream":"b"} -->'
        self.assertIsNone(sync.issue_marker({"body": body, "user": {"login": "someone"}}))
        self.assertEqual(sync.issue_marker({"body": body, "user": {"login": "github-actions[bot]"}}), {"base": "a", "upstream": "b"})
        self.assertIsNone(sync.issue_marker({"body": '<!-- letasu-sync {invalid} -->', "user": {"login": "github-actions[bot]"}}))

    def temp_root(self):
        build = (ROOT / "build").resolve()
        build.mkdir(exist_ok=True)
        directory = tempfile.TemporaryDirectory(prefix="github-sync-test-", dir=build)
        self.addCleanup(directory.cleanup)
        root = Path(directory.name).resolve()
        self.assertTrue(root.is_relative_to(build))
        return root

    def check_candidate(self, tag="v1.48.0", commit="b" * 40, issue=None):
        root = self.temp_root()
        (root / ".letasu").mkdir()
        (root / ".letasu/upstream.json").write_text(json.dumps({"tag": "v1.48.0", "commit": "b" * 40}))
        output = root / "output"

        def command(*args, **kwargs):
            if args == ("git", "rev-parse", "HEAD"):
                return "a" * 40
            if args == ("git", "rev-parse", "refs/remotes/upstream/stable^{commit}"):
                return commit
            return ""

        with patch.object(sync, "ROOT", root), patch.object(sync, "PLAN", root / "plan.json"), \
                patch.object(sync, "command", side_effect=command), patch.object(sync, "api", return_value={"tag_name": tag}), \
                patch.object(sync, "custom_hashes", return_value={}), patch.object(sync, "open_failure", return_value=issue), \
                patch.object(sync, "summary"), patch.dict(os.environ, {"GITHUB_OUTPUT": str(output), "FORCE_REBUILD": "false", "DRY_RUN": "false"}):
            sync.check()
            return output.read_text(), sync.load_plan()

    def test_unchanged_upstream_is_quiet(self):
        output, plan = self.check_candidate()
        self.assertEqual(output, "changed=false\n")
        self.assertFalse(plan["changed"])

    def test_new_stable_release_is_prepared(self):
        output, plan = self.check_candidate("v1.49.0", "c" * 40)
        self.assertEqual(output, "changed=true\n")
        self.assertEqual(plan["tag"], "v1.49.0")

    def test_same_failed_candidate_is_not_retried(self):
        marker = {"base": "a" * 40, "upstream": "c" * 40}
        issue = {"user": {"login": "github-actions[bot]"}, "html_url": "https://example.test/issue",
                 "body": "<!-- letasu-sync " + json.dumps(marker) + " -->"}
        output, _ = self.check_candidate("v1.49.0", "c" * 40, issue)
        self.assertEqual(output, "changed=false\n")

    def test_moved_stable_tag_requires_review(self):
        with self.assertRaisesRegex(ValueError, "already imported stable tag"):
            self.check_candidate(commit="c" * 40)

    def publication(self, remote_base="a" * 40, corrupt=False):
        root = self.temp_root()
        distribution = root / "build/distribution"
        distribution.mkdir(parents=True)
        files = {"patches-1.0.11.mpp": b"verified bundle", "patches-bundle.json": b"{}", "patches-list.json": b"{}"}
        for name, content in files.items():
            (distribution / name).write_bytes(content)
            if name.endswith(".json"):
                (root / name).write_bytes(content)
        calls = []

        def command(*args, **kwargs):
            calls.append(args)
            if args[:2] == ("git", "ls-remote"):
                return remote_base + "\trefs/heads/main"
            if args[:3] == ("gh", "release", "download"):
                destination = root / "build/verified-release-download"
                for name in files:
                    shutil.copy2(distribution / name, destination / name)
                if corrupt:
                    (destination / "patches-1.0.11.mpp").write_bytes(b"changed")
            return ""

        plan = {"base": "a" * 40, "tag": "v1.48.0", "version": "1.0.11", "custom_hashes": {}}
        with patch.object(sync, "ROOT", root), patch.object(sync, "PLAN", root / "plan.json"), \
                patch.object(sync, "load_plan", return_value=plan), patch.object(sync, "command", side_effect=command), \
                patch.object(sync, "custom_hashes", return_value={}), patch.object(sync, "verify_public"), \
                patch.object(sync, "open_failure", return_value=None), patch.object(sync, "summary"), \
                patch.dict(os.environ, {"DRY_RUN": "false"}):
            try:
                sync.publish()
            except ValueError as error:
                return calls, error
        return calls, None

    def test_main_is_updated_only_after_verified_publication(self):
        calls, error = self.publication()
        self.assertIsNone(error)
        publish_index = next(i for i, call in enumerate(calls) if call[:3] == ("gh", "release", "edit"))
        download_index = next(i for i, call in enumerate(calls) if call[:3] == ("gh", "release", "download"))
        main_index = calls.index(("git", "push", "origin", "HEAD:refs/heads/main"))
        self.assertLess(download_index, publish_index)
        self.assertLess(publish_index, main_index)
        self.assertIn("--draft=false", calls[publish_index])
        self.assertTrue(all("--force" not in call for call in calls))

    def test_corrupt_download_is_never_published(self):
        calls, error = self.publication(corrupt=True)
        self.assertIn("differs from verified build", str(error))
        self.assertFalse(any(call[:3] == ("gh", "release", "edit") for call in calls))
        self.assertNotIn(("git", "push", "origin", "HEAD:refs/heads/main"), calls)

    def test_concurrent_main_change_is_not_overwritten(self):
        calls, error = self.publication(remote_base="d" * 40)
        self.assertIn("Main advanced", str(error))
        self.assertFalse(any(call[:2] == ("git", "push") for call in calls))

    def test_dry_run_cannot_publish(self):
        with patch.dict(os.environ, {"DRY_RUN": "true"}), patch.object(sync, "command") as command:
            with self.assertRaisesRegex(ValueError, "disabled in dry-run"):
                sync.publish()
            command.assert_not_called()


if __name__ == "__main__":
    unittest.main()
