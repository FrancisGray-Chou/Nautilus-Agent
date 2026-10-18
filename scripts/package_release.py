#!/usr/bin/env python3
"""Build reproducible source archives from the checked release allowlist; no upload."""
from __future__ import annotations

import gzip
import hashlib
import io
import json
import stat
import tarfile
import zipfile

from check_release import ROOT, git, validate, version


def main() -> None:
    files = validate()
    current = version()
    prefix = f"nautilus-{current}"
    output = ROOT / "dist"
    output.mkdir(exist_ok=True)
    if output.is_symlink():
        raise ValueError("dist 不能是符号链接")
    try:
        revision = git("rev-parse", "HEAD")
        clean = not git("status", "--porcelain")
    except ValueError:
        revision, clean = None, False
    contents = {}
    entries = []
    for path in files:
        name = path.relative_to(ROOT).as_posix()
        data = path.read_bytes()
        mode = 0o755 if path.suffix == ".sh" else 0o644
        contents[name] = (data, mode)
        entries.append({"path": name, "sha256": hashlib.sha256(data).hexdigest(), "mode": oct(mode)})
    manifest = {"version": current, "git_revision": revision, "clean_worktree": clean, "files": entries}
    contents["SOURCE-MANIFEST.json"] = ((json.dumps(manifest, ensure_ascii=False, indent=2) + "\n").encode(), 0o644)
    archives = [output / f"{prefix}-source.zip", output / f"{prefix}-source.tar.gz"]
    for path in [*archives, output / "SHA256SUMS"]:
        if path.is_symlink():
            raise ValueError("输出文件不能是符号链接")
    with zipfile.ZipFile(archives[0], "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for name, (data, mode) in sorted(contents.items()):
            info = zipfile.ZipInfo(f"{prefix}/{name}", date_time=(1980, 1, 1, 0, 0, 0))
            info.create_system = 3
            info.external_attr = (stat.S_IFREG | mode) << 16
            info.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(info, data)
    with archives[1].open("wb") as stream:
        with gzip.GzipFile(filename="", fileobj=stream, mode="wb", mtime=0) as compressed:
            with tarfile.open(fileobj=compressed, mode="w", format=tarfile.PAX_FORMAT) as archive:
                for name, (data, mode) in sorted(contents.items()):
                    info = tarfile.TarInfo(f"{prefix}/{name}")
                    info.size = len(data); info.mode = mode; info.mtime = 0
                    archive.addfile(info, io.BytesIO(data))
    # Read both archives back: the payload must match the checked source snapshot exactly.
    with zipfile.ZipFile(archives[0]) as zipped, tarfile.open(archives[1], "r:gz") as tarred:
        expected = {f"{prefix}/{name}" for name in contents}
        if set(zipped.namelist()) != expected or set(tarred.getnames()) != expected:
            raise ValueError("归档清单不一致")
        for name, (data, _) in contents.items():
            member = f"{prefix}/{name}"
            if zipped.read(member) != data or tarred.extractfile(member).read() != data:
                raise ValueError("归档内容校验失败")
    sums = "".join(f"{hashlib.sha256(path.read_bytes()).hexdigest()}  {path.name}\n" for path in archives)
    (output / "SHA256SUMS").write_text(sums, encoding="utf-8")
    for path in archives:
        print(f"READY: dist/{path.name} ({path.stat().st_size:,} bytes)")
    print("PASS: 两种归档均回读校验；SHA256SUMS 已生成。")
    if not clean:
        print("NOTE: 当前为未提交/未清理工作区快照；正式标签提交后应重新生成。")


if __name__ == "__main__":
    main()
