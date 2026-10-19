#!/usr/bin/env python3
"""Validate installer/build-kit boundaries without claiming a Windows runtime test."""
import hashlib
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile
sys.dont_write_bytecode = True
import package_windows as package
import windows_jdk as jdk

class WindowsPackageTest(unittest.TestCase):
    def fake_jdk(self, root, missing=()):
        for tool in jdk.TOOLS:
            if tool not in missing:
                target = root / "bin" / (tool + ".exe")
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text("fake tool; tests mock java execution", encoding="utf-8")
        return root.resolve()

    def java_info(self, arch="amd64", version="17"):
        import subprocess
        return subprocess.CompletedProcess([], 0, "", f"    os.arch = {arch}\n    java.specification.version = {version}\n")

    def test_java_home_finds_jar_without_path_and_activates_whole_jdk(self):
        with tempfile.TemporaryDirectory(prefix="nautilus-jdk-用户-") as temporary:
            home = self.fake_jdk(Path(temporary) / "JDK with spaces")
            environment = {"JAVA_HOME": str(home), "Path": "previous-path"}
            with patch.object(jdk.subprocess, "run", return_value=self.java_info()) as run:
                found = jdk.find_jdk(environment)
            self.assertEqual(found, home)
            self.assertEqual(run.call_args.args[0][0], str(home / "bin/java.exe"))
            jdk.activate_jdk(found, environment)
            self.assertTrue(environment["Path"].startswith(str(home / "bin") + package.os.pathsep))
            self.assertNotIn("PATH", environment)

    def test_common_install_found_despite_incomplete_home_and_java_shim(self):
        with tempfile.TemporaryDirectory(prefix="nautilus-jdk-") as temporary:
            root = Path(temporary)
            incomplete = self.fake_jdk(root / "jre", missing=("jar", "jpackage", "jlink"))
            installed = self.fake_jdk(root / "Program Files/Eclipse Adoptium/jdk-17")
            environment = {"JAVA_HOME": str(incomplete), "ProgramFiles": str(root / "Program Files")}
            with patch.object(jdk.shutil, "which", return_value=str(incomplete / "bin/java.exe")), patch.object(jdk.subprocess, "run", return_value=self.java_info()):
                self.assertEqual(jdk.find_jdk(environment), installed)

    def test_path_javac_resolves_home_and_wrong_arch_or_old_version_is_rejected(self):
        with tempfile.TemporaryDirectory(prefix="nautilus-jdk-") as temporary:
            home = self.fake_jdk(Path(temporary) / "jdk")
            with patch.object(jdk.shutil, "which", return_value=str(home / "bin/javac.exe")), patch.object(jdk.subprocess, "run", return_value=self.java_info()):
                self.assertEqual(jdk.find_jdk({"PATH": str(home / "bin")}), home)
            for arch, version in (("x86", "17"), ("aarch64", "21"), ("amd64", "11")):
                with patch.object(jdk.subprocess, "run", return_value=self.java_info(arch, version)):
                    with self.assertRaisesRegex(ValueError, "Invalid --jdk-home"):
                        jdk.find_jdk({}, home)

    def test_explicit_incomplete_jdk_does_not_silently_fall_back(self):
        with tempfile.TemporaryDirectory(prefix="nautilus-jdk-") as temporary:
            home = self.fake_jdk(Path(temporary) / "jdk", missing=("jar",))
            with self.assertRaisesRegex(ValueError, "jar.exe"):
                jdk.find_jdk({}, home)

    def test_absent_jdk_reports_full_jdk_and_explicit_home_fix(self):
        with patch.object(jdk.shutil, "which", return_value=None):
            with self.assertRaisesRegex(ValueError, "No complete Windows x64 JDK 17") as error:
                jdk.find_jdk({})
            self.assertIn("--jdk-home", str(error.exception))

    def test_output_inside_source_is_rejected(self):
        for path in (package.ROOT, package.ROOT / "dist", package.ROOT / "desktop/build/windows"):
            with self.assertRaisesRegex(ValueError, "outside"):
                package.outside_project(path)

    def test_build_kit_payload_is_checked_and_excludes_secrets_and_build_output(self):
        with tempfile.TemporaryDirectory(prefix="nautilus-kit-test-") as temporary:
            artifact = package.export_kit(Path(temporary))
            with zipfile.ZipFile(artifact) as archive:
                names = archive.namelist()
                prefix = names[0].split("/", 1)[0] + "/"
                notice = archive.read(prefix + "READ-ME-FIRST.txt").decode()
                self.assertIn("NOT AN INSTALLER", notice)
                manifest = json.loads(archive.read(prefix + "BUILD-KIT-SHA256.json"))
                for name, checksum in manifest.items():
                    self.assertEqual(hashlib.sha256(archive.read(prefix + name)).hexdigest(), checksum)
                for name in ("src/credentials/windows.rs", "desktop/build-windows.cmd", "desktop/WINDOWS.md", "scripts/package_windows.py", ".github/workflows/windows-installer.yml"):
                    self.assertIn(prefix + name, names)
                self.assertTrue(all(not name.endswith("/.env") and "/target/" not in name and "/desktop/build/" not in name for name in names))
            with self.assertRaisesRegex(ValueError, "overwrite"):
                package.export_kit(Path(temporary))

    def test_pe_validation_rejects_wrong_platform_and_architecture(self):
        with tempfile.TemporaryDirectory(prefix="nautilus-pe-test-") as temporary:
            executable = Path(temporary) / "backend.exe"
            executable.write_bytes(b"not a native binary")
            with self.assertRaisesRegex(ValueError, "Windows executable"):
                package.check_pe(executable)
            data = bytearray(256)
            data[:2] = b"MZ"
            data[0x3C:0x40] = (128).to_bytes(4, "little")
            data[128:132] = b"PE\0\0"
            data[132:134] = (0x14C).to_bytes(2, "little")
            executable.write_bytes(data)
            with self.assertRaisesRegex(ValueError, "x64"):
                package.check_pe(executable)
            data[132:134] = (0x8664).to_bytes(2, "little")
            executable.write_bytes(data)
            package.check_pe(executable)

    def test_msi_packaging_uses_native_type_and_accepts_database_header(self):
        with tempfile.TemporaryDirectory(prefix="nautilus-msi-test-") as temporary:
            output = Path(temporary)
            artifact = output / "Nautilus-0.0.2.msi"
            artifact.write_bytes(bytes.fromhex("d0cf11e0a1b11ae1") + b"mock database")
            with patch.object(package, "run") as run:
                self.assertEqual(package.package_app(output / "app", output, ["--name", "Nautilus"], "msi"), artifact)
            command = run.call_args.args[0]
            self.assertEqual(command[:3], ["jpackage", "--type", "msi"])
            self.assertIn("--win-per-user-install", command)
            self.assertEqual(command[command.index("--win-upgrade-uuid") + 1], package.UPGRADE_UUID)

    def test_msi_rejects_renamed_exe_missing_and_multiple_artifacts(self):
        with tempfile.TemporaryDirectory(prefix="nautilus-msi-test-") as temporary:
            output = Path(temporary)
            with patch.object(package, "run"):
                with self.assertRaisesRegex(ValueError, "exactly one MSI"):
                    package.package_app(output, output, [], "msi")
                (output / "renamed.msi").write_bytes(b"MZ-not-an-msi")
                with self.assertRaisesRegex(ValueError, "not a Windows MSI"):
                    package.package_app(output, output, [], "msi")
                (output / "extra.msi").write_bytes(bytes.fromhex("d0cf11e0a1b11ae1"))
                with self.assertRaisesRegex(ValueError, "exactly one MSI"):
                    package.package_app(output, output, [], "msi")

    def test_exe_format_remains_available(self):
        with tempfile.TemporaryDirectory(prefix="nautilus-exe-test-") as temporary:
            output = Path(temporary)
            artifact = output / "Nautilus.exe"
            artifact.write_bytes(b"MZ-mock-bootstrapper")
            with patch.object(package, "run") as run:
                self.assertEqual(package.package_app(output, output, [], "exe"), artifact)
            self.assertEqual(run.call_args.args[0][:3], ["jpackage", "--type", "exe"])

    def test_non_windows_does_not_claim_to_build_exe(self):
        with patch.object(package.os, "name", "posix"):
            with self.assertRaisesRegex(ValueError, "must be built on Windows"):
                package.require_windows_tools()

if __name__ == "__main__":
    unittest.main()
