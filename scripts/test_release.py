#!/usr/bin/env python3
"""Regression checks for the packaging boundary, using a temporary repository."""
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import patch
from contextlib import redirect_stdout
import io
import os
from pathlib import Path

import check_release as release
import publish_github


class ReleaseBoundaryTest(unittest.TestCase):
    def test_publish_defaults_to_offline_preview(self):
        with patch("sys.argv", ["publish_github.py"]), patch.object(publish_github, "publish") as publish:
            with redirect_stdout(io.StringIO()) as output:
                self.assertEqual(publish_github.main(), 0)
            publish.assert_not_called()
            self.assertIn("未连接或修改 GitHub", output.getvalue())

    def test_published_tags_are_not_overwritten(self):
        with patch.object(publish_github.shutil, "which", return_value="/test/command"):
            with patch.object(publish_github, "run", side_effect=["", "existing-tag"]) as run:
                with self.assertRaisesRegex(ValueError, "已存在"):
                    publish_github.publish([], release.version())
                self.assertEqual(run.call_count, 2)

    def setUp(self):
        self.previous = release.ROOT
        self.temporary = tempfile.TemporaryDirectory(prefix="nautilus-release-test-")
        self.root = Path(self.temporary.name).resolve()
        for source in release.release_files():
            target = self.root / source.relative_to(self.previous)
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source, target)
        release.ROOT = self.root

    def tearDown(self):
        release.ROOT = self.previous
        self.temporary.cleanup()

    def test_private_and_build_files_are_not_selected(self):
        for name in (".env", "desktop/out/stale.class", "desktop/build/private.txt", ".idea/workspace.xml"):
            path = self.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("not-for-release")
        selected = {str(path.relative_to(self.root)) for path in release.validate()}
        self.assertIn(".env.example", selected)
        self.assertIn("更新内容.md", selected)
        self.assertNotIn(".env", selected)
        self.assertNotIn("desktop/out/stale.class", selected)

    def test_demo_is_repository_only_and_unknown_remote_files_still_fail(self):
        demo = self.root / "docs/nautilus-demo/README.md"
        demo.parent.mkdir(parents=True)
        demo.write_text("[demo](examples/demo.html)\n")
        selected = {path.relative_to(self.root).as_posix() for path in release.validate()}
        self.assertNotIn("docs/nautilus-demo/README.md", selected)
        release.check_repository_extras(self.root, {"docs/nautilus-demo/README.md"})
        with self.assertRaisesRegex(ValueError, "白名单以外"):
            release.check_repository_extras(self.root, {"unexpected.txt"})
        demo.unlink()
        demo.symlink_to(self.root / "README.md")
        with self.assertRaisesRegex(ValueError, "符号链接"):
            release.check_repository_extras(self.root, {"docs/nautilus-demo/README.md"})

    def test_publish_preserves_remote_demo_and_old_release_through_tag_validation(self):
        source_files = release.validate()
        preserved = {
            "docs/nautilus-demo/README.md": b"[example](examples/demo.html)\n",
            "docs/nautilus-demo/examples/demo.html": b"<!doctype html><title>Demo</title>",
            "docs/nautilus-demo/assets/demo.gif": b"GIF89a mock demo",
            "docs/nautilus-demo/sources.json": b'{"preserve":true}',
            "docs/releases/v0.0.1-alpha.2.md": b"# Historical release\n",
        }
        with tempfile.TemporaryDirectory(prefix="nautilus-local-remote-") as temporary:
            seed = Path(temporary) / "seed"
            shutil.copytree(self.root, seed)
            # Reproduce importing a new CRLF Windows launcher into an older
            # repository that has neither the launcher nor line-ending rules.
            (seed / ".gitattributes").unlink()
            (seed / "desktop/build-windows.cmd").unlink()
            for name, content in preserved.items():
                target = seed / name
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(content)
            def git(*args):
                return subprocess.run(["git", *args], check=True, capture_output=True, text=True).stdout
            git("init", "-q", str(seed))
            git("-C", str(seed), "add", ".")
            git("-C", str(seed), "-c", "user.name=Release Test", "-c", "user.email=release@example.invalid", "-c", "commit.gpgsign=false", "commit", "-qm", "existing remote")
            remote = Path(temporary) / "remote.git"
            git("clone", "--bare", str(seed), str(remote))
            readme = self.root / "README.md"
            readme.write_text(readme.read_text() + "\nCurrent release source update.\n")
            real_run = publish_github.run
            def local_run(*args, **kwargs):
                if args[0] == "gh":
                    return ""
                return real_run(*args, **kwargs)
            identity = {"GIT_AUTHOR_NAME": "Release Test", "GIT_AUTHOR_EMAIL": "release@example.invalid",
                        "GIT_COMMITTER_NAME": "Release Test", "GIT_COMMITTER_EMAIL": "release@example.invalid",
                        "GIT_CONFIG_COUNT": "2", "GIT_CONFIG_KEY_0": "commit.gpgsign", "GIT_CONFIG_VALUE_0": "false",
                        "GIT_CONFIG_KEY_1": "tag.gpgsign", "GIT_CONFIG_VALUE_1": "false"}
            with patch.object(publish_github, "ROOT", self.root), patch.object(publish_github, "REMOTE", str(remote)), \
                 patch.object(publish_github, "run", side_effect=local_run), patch.object(publish_github, "wait_for_ci"), \
                 patch.object(publish_github.shutil, "which", return_value="test-command"), patch.dict(os.environ, identity):
                publish_github.publish(source_files, release.version())
            tag = "v" + release.version()
            for name, expected in preserved.items():
                actual = subprocess.run(["git", "--git-dir", str(remote), "show", f"{tag}:{name}"], check=True, capture_output=True).stdout
                self.assertEqual(actual, expected)
            launcher = subprocess.run(["git", "--git-dir", str(remote), "show", f"{tag}:desktop/build-windows.cmd"], check=True, capture_output=True).stdout
            self.assertNotIn(b"\r", launcher)
            self.assertIn(b"@echo off\n", launcher)
            checkout = next((self.root / "dist").glob("github-publish-*"))
            self.assertEqual((checkout / "README.md").read_bytes(), (self.root / "README.md").read_bytes())
            import zipfile
            with zipfile.ZipFile(checkout / "dist" / f"nautilus-{release.version()}-source.zip") as archive:
                self.assertFalse(any("/docs/nautilus-demo/" in name for name in archive.namelist()))

    def test_version_mismatch_is_rejected(self):
        path = self.root / "Cargo.lock"
        path.write_text(path.read_text().replace(release.version(), "9.9.9-alpha.1", 1))
        with self.assertRaisesRegex(ValueError, "版本不一致"):
            release.validate()

    def test_credential_pattern_is_rejected_without_echo(self):
        path = self.root / "README.md"
        fake = "sk-" + "x" * 24
        path.write_text(path.read_text() + "\n" + fake)
        with self.assertRaisesRegex(ValueError, "疑似凭据") as caught:
            release.validate()
        self.assertNotIn(fake, str(caught.exception))

    def test_broken_link_is_rejected(self):
        path = self.root / "README.md"
        path.write_text(path.read_text() + "\n[missing](not-in-package.md)\n")
        with self.assertRaisesRegex(ValueError, "文档链接"):
            release.validate()

    def test_tampered_library_is_rejected(self):
        jar = next((self.root / "desktop/lib").glob("*.jar"))
        with jar.open("ab") as stream:
            stream.write(b"changed")
        with self.assertRaisesRegex(ValueError, "JAR 校验"):
            release.validate()

    def test_symlink_is_rejected(self):
        (self.root / "src/linked.rs").symlink_to(self.root / "README.md")
        with self.assertRaisesRegex(ValueError, "符号链接"):
            release.validate()

    def test_tag_must_match_clean_committed_sources(self):
        def git(*args):
            subprocess.run(["git", "-C", str(self.root), *args], check=True, capture_output=True)
        git("init", "-q")
        git("add", ".")
        git("-c", "user.name=Release Test", "-c", "user.email=release@example.invalid", "-c", "commit.gpgsign=false", "commit", "-qm", "test snapshot")
        tag = "v" + release.version()
        git("-c", "tag.gpgsign=false", "tag", tag)
        release.validate(tag)
        path = self.root / "README.md"
        path.write_text(path.read_text() + "\nUncommitted change.\n")
        with self.assertRaisesRegex(ValueError, "干净工作区"):
            release.validate(tag)


if __name__ == "__main__":
    unittest.main()
