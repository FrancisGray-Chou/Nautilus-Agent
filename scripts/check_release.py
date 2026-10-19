#!/usr/bin/env python3
"""Validate the release allowlist without reading private .env files. Python 3.11+."""
from __future__ import annotations

import argparse
import hashlib
import re
import subprocess
import sys
import tomllib
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ROOT_FILES = (
    "README.md", "Cargo.toml", "Cargo.lock", ".env.example", ".gitignore", "CHANGELOG.md", "更新内容.md",
    "desktop/README.md", "desktop/PRIVACY.md", "desktop/run.sh", "desktop/test.sh",
    "desktop/lib/README.md", "desktop/lib/LICENSE-commonmark.txt", "desktop/lib/SHA256SUMS",
)
PATTERNS = (
    "src/**/*.rs", "desktop/src/main/java/**/*.java", "desktop/src/test/java/**/*.java",
    "desktop/lib/*.jar", "docs/**/*.md", "docs/assets/*.png", "scripts/*.py",
    ".github/workflows/*.yml", ".github/ISSUE_TEMPLATE/*.yml",
)
SECRET = re.compile(r"sk-[A-Za-z0-9_-]{20,}|AIza[A-Za-z0-9_-]{30,}|gh[pousr]_[A-Za-z0-9]{30,}|-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----")
LINK = re.compile(r"\[[^\]]*\]\(([^\s)]+)(?:\s+\"[^\"]*\")?\)")


def git(*args: str) -> str:
    result = subprocess.run(["git", "-C", str(ROOT), *args], capture_output=True, text=True, check=False)
    if result.returncode:
        raise ValueError("Git 检查失败：" + " ".join(args[:2]))
    return result.stdout.strip()


def version() -> str:
    value = tomllib.loads((ROOT / "Cargo.toml").read_text())["package"]["version"]
    if not re.fullmatch(r"\d+\.\d+\.\d+-alpha\.\d+", value):
        raise ValueError("预发布版本应使用 x.y.z-alpha.n 格式")
    return value


def release_files() -> list[Path]:
    files = {ROOT / name for name in ROOT_FILES}
    for pattern in PATTERNS:
        files.update(ROOT.glob(pattern))
    # Include a project license if the owner adds one later.
    files.update(path for path in (ROOT / "LICENSE", ROOT / "LICENSE.md") if path.exists())
    for path in files:
        if not path.is_file():
            raise ValueError(f"缺少发布文件：{path.relative_to(ROOT)}")
        relative = path.relative_to(ROOT)
        if any((ROOT / Path(*relative.parts[:i])).is_symlink() for i in range(1, len(relative.parts) + 1)):
            raise ValueError(f"发布清单不接受符号链接：{relative}")
        if path.name.startswith(".env") and path.name != ".env.example":
            raise ValueError("发布清单不能包含用户 .env")
    return sorted(files)


def validate(tag: str | None = None) -> list[Path]:
    files = release_files()
    allowed = set(files)
    current = version()
    lock = tomllib.loads((ROOT / "Cargo.lock").read_text())
    package = next(item for item in lock["package"] if item["name"] == "Nautilus-Agent")
    if package["version"] != current:
        raise ValueError("Cargo.toml 与 Cargo.lock 版本不一致")
    notes = ROOT / "docs/releases" / f"v{current}.md"
    if notes not in allowed:
        raise ValueError("缺少对应版本的发布说明")
    for name in ("README.md", "desktop/README.md", "CHANGELOG.md"):
        if current not in (ROOT / name).read_text():
            raise ValueError(f"版本信息未同步：{name}")
    for line in (ROOT / ".env.example").read_text().splitlines():
        if "_API_KEY=" in line and not line.lstrip().startswith("#") and line.split("=", 1)[1].strip():
            raise ValueError(".env.example 的 API key 必须留空")
    for path in files:
        if path.suffix in {".jar", ".png"}:
            continue
        content = path.read_text(encoding="utf-8")
        if SECRET.search(content):
            raise ValueError(f"发现疑似凭据；请本地检查文件（不显示内容）：{path.relative_to(ROOT)}")
        if path.suffix == ".md":
            for destination in LINK.findall(content):
                if destination.startswith(("https://", "http://", "mailto:", "#")):
                    continue
                local = destination.split("#", 1)[0]
                if not local or (path.parent / local).resolve() not in allowed:
                    raise ValueError(f"文档链接未包含在发布清单：{path.relative_to(ROOT)} → {destination}")
    library = ROOT / "desktop/lib"
    recorded = set()
    for line in (library / "SHA256SUMS").read_text().splitlines():
        expected, name = line.split()
        path = library / name
        if path not in allowed or hashlib.sha256(path.read_bytes()).hexdigest() != expected:
            raise ValueError(f"JAR 校验失败：{name}")
        recorded.add(path)
        with zipfile.ZipFile(path) as archive:
            members = archive.namelist()
            if any(member.endswith(".class") and not member.startswith("org/commonmark/") for member in members):
                raise ValueError(f"包含非 CommonMark 实现类：{name}")
            licenses = [member for member in members if "LICENSE" in member.upper() and not member.endswith("/")]
            if not any(b"Robin Stocker" in archive.read(member) for member in licenses):
                raise ValueError(f"缺少内嵌版权声明：{name}")
    if recorded != {path for path in allowed if path.suffix == ".jar"}:
        raise ValueError("存在没有登记校验值的 JAR")
    if tag:
        if tag != f"v{current}":
            raise ValueError("标签与 Cargo 版本不一致")
        if git("rev-parse", "HEAD") != git("rev-parse", f"refs/tags/{tag}^{{commit}}"):
            raise ValueError("当前提交不是目标标签")
        if git("status", "--porcelain"):
            raise ValueError("发布标签检查要求干净工作区，请先提交或移走未提交改动")
        # NUL-delimited paths preserve Unicode filenames without Git's quoted escapes.
        tracked = {ROOT / name for name in git("ls-files", "-z").split("\0") if name}
        if tracked != allowed:
            raise ValueError("Git 跟踪文件与发布清单不同，请检查遗漏的源码或意外提交的文件")
    return files


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tag", help="严格校验已提交的发布标签及干净工作区")
    args = parser.parse_args()
    try:
        files = validate(args.tag)
    except (ValueError, OSError, StopIteration, zipfile.BadZipFile) as error:
        print(f"FAIL: {error}", file=sys.stderr)
        return 1
    print(f"PASS: v{version()} · {len(files)} 个允许发布的文件 · 版本、链接、常见凭据格式及 JAR 校验")
    if not (ROOT / "LICENSE").exists() and not (ROOT / "LICENSE.md").exists():
        print("NOTE: 项目级许可证尚未指定；第三方许可已保留。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
