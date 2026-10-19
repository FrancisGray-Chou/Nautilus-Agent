#!/usr/bin/env python3
"""Publish the checked source snapshot while preserving the existing GitHub history.

The default is an offline preview. --publish clones the remote, commits the
allowlist, waits for GitHub CI, then pushes a new immutable prerelease tag.
"""
from __future__ import annotations

import argparse
import json
import re
import shutil
import subprocess
import sys
import tempfile
import time
from pathlib import Path

from check_release import ROOT, check_repository_extras, validate, version

REPOSITORY = "FrancisGray-Chou/Nautilus-Agent"
REMOTE = f"https://github.com/{REPOSITORY}.git"


def run(*args: str, cwd: Path = ROOT, capture: bool = False) -> str:
    result = subprocess.run(args, cwd=cwd, check=True, text=True, encoding="utf-8",
                            stdout=subprocess.PIPE if capture else None)
    return result.stdout.strip() if capture else ""


def push_branch(checkout: Path, branch: str) -> None:
    """Retry an unspecified server failure, while surfacing all rejection details."""
    command = ["git", "push", "origin", f"HEAD:refs/heads/{branch}"]
    for attempt in range(3):
        result = subprocess.run(command, cwd=checkout, capture_output=True,
                                text=True, encoding="utf-8", errors="replace", check=False)
        if result.stdout:
            print(result.stdout, end="", flush=True)
        if result.stderr:
            print(result.stderr, end="", file=sys.stderr, flush=True)
        if result.returncode == 0:
            return
        details = (result.stdout + "\n" + result.stderr).lower()
        unspecified_failure = re.search(r"\[remote rejected\].*\(failure\)", details)
        permanent = any(reason in details for reason in (
            "gh006", "gh013", "protected branch", "repository rule", "permission denied",
            "authentication failed", "refusing to allow", "workflow scope", "non-fast-forward",
            "fetch first", "pre-receive hook declined",
        ))
        if unspecified_failure and not permanent and attempt < 2:
            print(f"远端仅返回 failure，将重试正常推送（{attempt + 2}/3）。", flush=True)
            time.sleep(2 * (attempt + 1))
            continue
        raise ValueError(
            "分支推送被远端拒绝，修复尚未上传。请查看上方完整 remote: 错误；"
            f"本地提交已保留在 {checkout}。未强制推送或修改已有标签。")


def wait_for_ci(checkout: Path, revision: str) -> None:
    print("等待目标提交的 GitHub CI；通过后才发布标签。", flush=True)
    deadline = time.monotonic() + 180
    while time.monotonic() < deadline:
        rows = json.loads(run("gh", "run", "list", "--repo", REPOSITORY,
                              "--workflow", "ci.yml", "--commit", revision,
                              "--event", "push", "--json", "databaseId", cwd=checkout, capture=True))
        if rows:
            run_id = str(rows[0]["databaseId"])
            try:
                run("gh", "run", "watch", run_id, "--repo", REPOSITORY,
                    "--exit-status", "--interval", "10", cwd=checkout)
            except subprocess.CalledProcessError:
                print("CI 未通过，读取失败步骤日志：", flush=True)
                try:
                    run("gh", "run", "view", run_id, "--repo", REPOSITORY, "--log-failed", cwd=checkout)
                except (OSError, subprocess.CalledProcessError):
                    print(f"无法读取日志，请查看 https://github.com/{REPOSITORY}/actions/runs/{run_id}", flush=True)
                raise ValueError(f"CI 未通过，未推送发布标签：https://github.com/{REPOSITORY}/actions/runs/{run_id}") from None
            return
        time.sleep(5)
    raise ValueError("未发现目标提交的 CI，已停止发布标签；请检查仓库 Actions。")


def publish(files: list[Path], current: str, *, create_release: bool = True) -> None:
    for command in ("git", "gh"):
        if shutil.which(command) is None:
            raise ValueError(f"请先安装 {command}。")
    run("gh", "auth", "status", "--hostname", "github.com")
    tag = f"v{current}"
    if create_release:
        existing = run("git", "ls-remote", "--tags", REMOTE, f"refs/tags/{tag}", capture=True)
        if existing:
            raise ValueError(f"远端标签 {tag} 已存在，未修改它；请先查看对应 Release。")
    output = ROOT / "dist"
    output.mkdir(exist_ok=True)
    if output.is_symlink():
        raise ValueError("dist 不能是符号链接")
    checkout = Path(tempfile.mkdtemp(prefix="github-publish-", dir=output))
    print(f"发布工作目录：{checkout}", flush=True)
    run("git", "clone", REMOTE, str(checkout))
    branch = run("git", "branch", "--show-current", cwd=checkout, capture=True)
    if not branch:
        raise ValueError("远端没有可用默认分支，未提交或推送。")
    allowed = {source.relative_to(ROOT).as_posix() for source in files}
    tracked = set(run("git", "ls-files", "-z", cwd=checkout, capture=True).split("\0")) - {""}
    preserved = check_repository_extras(checkout, tracked - allowed)
    if preserved:
        print(f"保留远端演示及历史发布文件：{len(preserved)} 个，不覆盖或删除。", flush=True)
    for source in files:
        destination = checkout / source.relative_to(ROOT)
        # A remote symlink must never redirect the source copy outside this checkout.
        if any(part.is_symlink() for part in [destination, *destination.parents] if part != checkout.parent):
            raise ValueError("远端目标路径包含符号链接，未覆盖：" + str(source.relative_to(ROOT)))
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, destination)
    run("git", "add", "--", *sorted(allowed), cwd=checkout)
    run("git", "diff", "--cached", "--check", cwd=checkout)
    changed = subprocess.run(["git", "diff", "--cached", "--quiet"], cwd=checkout, check=False).returncode
    if changed == 1:
        run("git", "commit", "-m", f"Release Nautilus Alpha {current}", cwd=checkout)
    elif changed != 0:
        raise ValueError("无法检查暂存改动，已停止发布。")
    revision = run("git", "rev-parse", "HEAD", cwd=checkout, capture=True)
    if not create_release:
        run(sys.executable, "scripts/check_release.py", cwd=checkout)
        push_branch(checkout, branch)
        print(f"PUSHED: {branch} · {revision}；未创建或修改标签及 Release，请在 Actions 查看构建。", flush=True)
        return
    run("git", "tag", "-a", tag, "-m", f"Nautilus Alpha {current}", cwd=checkout)
    run(sys.executable, "scripts/check_release.py", "--tag", tag, cwd=checkout)
    run(sys.executable, "scripts/package_release.py", cwd=checkout)
    # No force push: a concurrent remote update safely rejects this operation.
    push_branch(checkout, branch)
    wait_for_ci(checkout, revision)
    run("git", "push", "origin", f"refs/tags/{tag}", cwd=checkout)
    run("gh", "release", "create", tag, "--repo", REPOSITORY, "--verify-tag", "--prerelease",
        "--title", f"Nautilus Alpha {current}", "--notes-file", f"docs/releases/{tag}.md",
        f"dist/nautilus-{current}-source.zip", f"dist/nautilus-{current}-source.tar.gz",
        "dist/SHA256SUMS", cwd=checkout)
    print(f"PUBLISHED: https://github.com/{REPOSITORY}/releases/tag/{tag}", flush=True)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--publish", action="store_true", help="提交、推送并公开 GitHub Pre-release")
    mode.add_argument("--push-only", action="store_true", help="仅提交并推送默认分支以触发 Actions；不创建或修改版本标签及 Release")
    args = parser.parse_args()
    try:
        files = validate()
        current = version()
        print(f"READY: {REPOSITORY} · v{current} · {len(files)} 个源码文件", flush=True)
        if args.publish:
            publish(files, current)
        elif args.push_only:
            publish(files, current, create_release=False)
        else:
            print("离线预览完成，未连接或修改 GitHub。实际发布：python3 scripts/publish_github.py --publish")
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        print(f"发布未完成：{error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
