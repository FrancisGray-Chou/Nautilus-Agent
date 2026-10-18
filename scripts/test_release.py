#!/usr/bin/env python3
"""Regression checks for the packaging boundary, using a temporary repository."""
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

import check_release as release


class ReleaseBoundaryTest(unittest.TestCase):
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
        self.assertNotIn(".env", selected)
        self.assertNotIn("desktop/out/stale.class", selected)

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
