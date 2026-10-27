"""GitHub-hosted stable sync: check -> prepare -> validated build -> publish.

Publication runs in this workflow because GITHUB_TOKEN tag pushes intentionally
do not trigger a second push workflow. Main metadata advances only after the
verified release exists. No personal access token or local Codex is required.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import urllib.request

import metadata
import sync_upstream

ROOT = Path(__file__).resolve().parents[2]
PLAN = ROOT / "build/upstream-sync-plan.json"
REPOSITORY = "letasu/morphe-custom-patches"
UPSTREAM = "MorpheApp/morphe-patches"
ISSUE_TITLE = "[自動更新] Morphe本家の取り込みに対応が必要です"
MARKER = re.compile(r"<!-- letasu-sync (\{.*?\}) -->")


def command(*args, input=None):
    return subprocess.run(args, cwd=ROOT, input=input, check=True, text=True,
                          encoding="utf-8", stdout=subprocess.PIPE).stdout.strip()


def api(path, method="GET", data=None):
    args = ["gh", "api", path, "--method", method]
    if data is not None:
        args += ["--input", "-"]
    return json.loads(command(*args, input=json.dumps(data) if data is not None else None))


def semver(tag):
    match = re.fullmatch(r"v?(\d+)\.(\d+)\.(\d+)", tag)
    if not match:
        raise ValueError(f"Not a stable version: {tag}")
    return tuple(map(int, match.groups()))


def next_version(current, remote_refs):
    versions = [semver(current)]
    for line in remote_refs.splitlines():
        match = re.search(r"\srefs/tags/(v\d+\.\d+\.\d+)$", line)
        if match:
            versions.append(semver(match[1]))
    major, minor, patch = max(versions)
    return f"{major}.{minor}.{patch + 1}"


def save_plan(plan):
    PLAN.parent.mkdir(parents=True, exist_ok=True)
    metadata.write_json(PLAN, plan)


def load_plan():
    return json.loads(PLAN.read_text(encoding="utf-8"))


def issue_marker(issue):
    # Only our bot's issues control retry suppression; user text is never code.
    if issue.get("user", {}).get("login") != "github-actions[bot]":
        return None
    match = MARKER.search(issue.get("body") or "")
    if match:
        try:
            return json.loads(match[1])
        except json.JSONDecodeError:
            pass
    return None


def open_failure():
    pages = json.loads(command("gh", "api", f"repos/{REPOSITORY}/issues?state=open&per_page=100",
                               "--paginate", "--slurp"))
    return next((issue for page in pages for issue in page
                 if not issue.get("pull_request") and issue.get("title") == ISSUE_TITLE
                 and issue_marker(issue) is not None), None)


def marker_for(plan):
    return {"base": plan["base"], "upstream": plan.get("commit", plan.get("tag", "unresolved"))}


def output_changed(changed):
    with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as target:
        target.write(f"changed={str(changed).lower()}\n")


def summary(message):
    print(message)
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as target:
            target.write(message + "\n\n")


def custom_hashes():
    return {name: hashlib.sha256((ROOT / name).read_bytes()).hexdigest()
            for name in sync_upstream.CUSTOM_FILES}


def check():
    base = command("git", "rev-parse", "HEAD")
    plan = {"base": base}
    save_plan(plan)
    latest = api(f"repos/{UPSTREAM}/releases/latest")
    if latest.get("draft") or latest.get("prerelease"):
        raise ValueError("The upstream latest endpoint did not return a stable release")
    tag = latest["tag_name"]
    semver(tag)
    if not tag.startswith("v"):
        raise ValueError("Expected a v-prefixed upstream release tag")
    plan["tag"] = tag
    save_plan(plan)
    command("git", "-c", "fetch.negotiationAlgorithm=noop", "fetch", "--no-tags", "--depth=1",
            f"https://github.com/{UPSTREAM}.git", f"+refs/tags/{tag}:refs/remotes/upstream/stable")
    plan["commit"] = command("git", "rev-parse", "refs/remotes/upstream/stable^{commit}")
    save_plan(plan)
    state = json.loads((ROOT / ".letasu/upstream.json").read_text(encoding="utf-8"))
    if tag == state["tag"] and plan["commit"] != state["commit"]:
        raise ValueError("Upstream changed an already imported stable tag; manual review required")
    force = os.environ.get("FORCE_REBUILD", "false").lower() == "true"
    newer = semver(tag) > semver(state["tag"])
    if semver(tag) < semver(state["tag"]):
        raise ValueError("Refusing to downgrade the imported upstream")
    changed = newer or force
    if changed and not force:
        issue = open_failure()
        if issue and issue_marker(issue) == marker_for(plan):
            changed = False
            summary(f"Same candidate already needs attention: {issue['html_url']}. "
                    "Fix main or manually run with force=true to retry; no repeat notification.")
    plan["changed"] = changed
    plan["forced"] = force and not newer
    plan["custom_hashes"] = custom_hashes()
    save_plan(plan)
    output_changed(changed)
    summary(f"Upstream {tag} ({plan['commit']}): " + ("preparing a verified release" if changed else "no update to publish"))


def prepare():
    plan = load_plan()
    if not plan["changed"] or command("git", "rev-parse", "HEAD") != plan["base"]:
        raise ValueError("Candidate base changed")
    sync_upstream.run("refs/remotes/upstream/stable", plan["tag"])
    if custom_hashes() != plan["custom_hashes"]:
        raise ValueError("Upstream import modified a custom implementation")
    current = json.loads((ROOT / "patches-bundle.json").read_text(encoding="utf-8"))["version"]
    version = next_version(current, command("git", "ls-remote", "--tags", "--refs", "origin"))
    plan["version"] = version
    save_plan(plan)
    notes = (f"## Letasu Patches v{version}\n\n"
             f"- 本家Morphe Patches [{plan['tag']}](https://github.com/{UPSTREAM}/releases/tag/{plan['tag']}) "
             f"の正式版を取り込みました（`{plan['commit']}`）。\n"
             "- Smart Audio FocusとHide members videosを維持したオールインワン構成です。\n"
             "- GitHub Actions上でメタデータ・回帰テスト・本家のテスト・配布用ビルドを検証して公開しています。\n")
    if plan["forced"]:
        notes += "- 自動追従・正式公開の確認のため、取り込み済みの最新安定版から再ビルドしました。\n"
    notes += ("\nManagerを最新版にしてLetasu Patchesを更新し、YouTubeへ再パッチしてインストールしてください。"
              "公式Morphe Patchesとの同時適用は行わないでください。実機での連携動作は未確認です。\n")
    (ROOT / "RELEASE_NOTES.md").write_text(notes, encoding="utf-8", newline="\n")
    metadata.prepare(version, notes)
    summary(f"Prepared v{version}; publication is gated on all tests and the build.")


def verify_downloads(distribution, downloaded):
    expected = {path.name for path in distribution.iterdir() if path.is_file()}
    if {path.name for path in downloaded.iterdir() if path.is_file()} != expected:
        raise ValueError("Downloaded release asset names differ from staged assets")
    for name in expected:
        if hashlib.sha256((distribution / name).read_bytes()).digest() != hashlib.sha256((downloaded / name).read_bytes()).digest():
            raise ValueError(f"Published asset differs from verified build: {name}")


def verify_public(version):
    url = f"https://raw.githubusercontent.com/{REPOSITORY}/main/patches-bundle.json?release={version}"
    for attempt in range(6):
        try:
            request = urllib.request.Request(url, headers={"Cache-Control": "no-cache", "User-Agent": "Letasu-sync"})
            with urllib.request.urlopen(request, timeout=20) as response:
                data = json.load(response)
            metadata.validate(data)
            if data["version"] != version:
                raise ValueError("Public CDN still serves an older manifest")
            with urllib.request.urlopen(urllib.request.Request(data["download_url"], method="HEAD"), timeout=20) as response:
                if response.status != 200:
                    raise ValueError("Published bundle is not publicly available")
            return
        except Exception:
            if attempt == 5:
                raise
            time.sleep(5)


def publish():
    plan = load_plan()
    version = plan["version"]
    tag = f"v{version}"
    distribution = ROOT / "build/distribution"
    expected = {f"patches-{version}.mpp", "patches-bundle.json", "patches-list.json"}
    if {path.name for path in distribution.iterdir()} != expected:
        raise ValueError("Installable release files were not staged correctly")
    for name in ("patches-bundle.json", "patches-list.json"):
        if (distribution / name).read_bytes() != (ROOT / name).read_bytes():
            raise ValueError("Staged metadata differs from the candidate commit")
    if custom_hashes() != plan["custom_hashes"]:
        raise ValueError("Build unexpectedly modified a custom implementation")
    command(sys.executable, "-B", ".github/scripts/generate_patches_readme.py", REPOSITORY, "main")
    remote_main = command("git", "ls-remote", "origin", "refs/heads/main").split()[0]
    if remote_main != plan["base"]:
        raise ValueError("Main advanced during the build; refusing to overwrite another change")
    command("git", "add", "-A")
    command("git", "-c", "user.name=github-actions[bot]", "-c", "user.email=41898282+github-actions[bot]@users.noreply.github.com",
            "commit", "-m", f"chore(sync): release {tag} from Morphe {plan['tag']}")
    command("git", "tag", tag)
    # A tag exposes the tested commit, while main still advertises the previous working bundle.
    command("git", "push", "origin", f"refs/tags/{tag}")
    command("gh", "release", "create", tag, "--repo", REPOSITORY, "--verify-tag", "--draft",
            "--title", f"Release {tag}", "--notes-file", "RELEASE_NOTES.md",
            *(str(distribution / name) for name in sorted(expected)))
    downloaded = ROOT / "build/verified-release-download"
    downloaded.mkdir()
    command("gh", "release", "download", tag, "--repo", REPOSITORY, "--dir", str(downloaded))
    verify_downloads(distribution, downloaded)
    command("gh", "release", "edit", tag, "--repo", REPOSITORY, "--draft=false", "--latest")
    plan["published_tag"] = tag
    save_plan(plan)
    # Fast-forward only. No force push; a concurrent edit leaves main untouched.
    command("git", "push", "origin", "HEAD:refs/heads/main")
    verify_public(version)
    issue = open_failure()
    if issue:
        api(f"repos/{REPOSITORY}/issues/{issue['number']}", "PATCH", {"state": "closed", "state_reason": "completed"})
    summary(f"Published and verified https://github.com/{REPOSITORY}/releases/tag/{tag}")


def report():
    plan = load_plan() if PLAN.exists() else {"base": os.environ.get("GITHUB_SHA", "unknown")}
    marker = marker_for(plan)
    existing = open_failure()
    if existing and issue_marker(existing) == marker:
        summary("This failed candidate is already reported; no duplicate issue or comment.")
        return
    run_url = f"https://github.com/{REPOSITORY}/actions/runs/{os.environ['GITHUB_RUN_ID']}"
    body = ("本家パッチの自動取り込み・検証・公開のいずれかで停止しました。"
            "失敗したビルドは公開しません。API変更等はログを確認して修正してください。\n\n"
            f"- 本家: {plan.get('tag', '確認前')}\n- 実行ログ: {run_url}\n\n"
            "同じ本家コミット・同じmainでは定期再試行と重複通知を止めます。"
            "mainを修正するか、Actionsからforce=trueで手動実行すると再試行できます。\n")
    if plan.get("published_tag"):
        body += f"\n{plan['published_tag']}のファイル公開は完了していますが、mainの切り替えまたは公開URLの確認で停止しました。\n"
    body += "\n<!-- letasu-sync " + json.dumps(marker, sort_keys=True) + " -->"
    if existing:
        api(f"repos/{REPOSITORY}/issues/{existing['number']}", "PATCH", {"body": body})
        summary(f"Updated failure report: {existing['html_url']}")
    else:
        issue = api(f"repos/{REPOSITORY}/issues", "POST", {"title": ISSUE_TITLE, "body": body})
        summary(f"Opened failure report: {issue['html_url']}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("phase", choices=("check", "prepare", "publish", "report"))
    args = parser.parse_args()
    if os.environ.get("GITHUB_REPOSITORY") != REPOSITORY:
        parser.error("This publisher is restricted to the Letasu repository")
    {"check": check, "prepare": prepare, "publish": publish, "report": report}[args.phase]()
