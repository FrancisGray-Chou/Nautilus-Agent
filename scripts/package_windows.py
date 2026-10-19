#!/usr/bin/env python3
"""Build a self-contained Windows x64 installer, or export a clean build kit on any OS."""
from __future__ import annotations
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import sys
import tempfile
import zipfile

sys.dont_write_bytecode = True
from check_release import ROOT, validate, version
from test_desktop import desktop_tests, outside_project, run
from windows_jdk import find_jdk, activate_jdk

UPGRADE_UUID = "c78356c7-52cd-45c7-a9a7-e54c8799ea95"


def desktop_directory() -> Path:
    if os.name == "nt":
        import ctypes
        buffer = ctypes.create_unicode_buffer(32768)
        if ctypes.windll.shell32.SHGetFolderPathW(None, 0x10, None, 0, buffer) == 0:
            return Path(buffer.value)
    return Path.home() / "Desktop"


def export_kit(output: Path) -> Path:
    files = validate()
    prefix = f"Nautilus-{version()}-Windows-Build-Kit"
    destination = output / f"{prefix}.zip"
    if destination.exists():
        raise ValueError(f"Refusing to overwrite an existing artifact: {destination}")
    contents = {path.relative_to(ROOT).as_posix(): path.read_bytes() for path in files}
    manifest = {name: hashlib.sha256(data).hexdigest() for name, data in sorted(contents.items())}
    contents["BUILD-KIT-SHA256.json"] = (json.dumps(manifest, indent=2, ensure_ascii=False) + "\n").encode()
    contents["READ-ME-FIRST.txt"] = (
        "Nautilus Windows x64 Build Kit — THIS IS NOT AN INSTALLER\n\n"
        "This is a checked source snapshot, not a runnable Windows application.\n"
        "Use a Windows x64 build machine with Python 3.11+, Rust stable MSVC,\n"
        "Visual Studio C++ Build Tools/Windows SDK, a full x64 JDK 17+, and WiX 3.x.\n"
        "JDK detection uses JAVA_HOME, PATH and common installation directories.\n"
        "Double-click desktop\\build-windows.cmd, or run:\n"
        "  py -3 scripts\\package_windows.py\n\n"
        "The builder runs Rust and desktop regression checks before packaging.\n"
        "The resulting Setup.msi and its SHA-256 are written to the Windows desktop.\n"
        "Build intermediates stay in the OS temporary directory, outside the source.\n"
        "The installed app includes Java runtime and Rust backend; end users do not\n"
        "need Python, Java, Rust, WiX or administrator privileges.\n\n"
        "No developer credentials, .env, conversation history or local build output\n"
        "are included. An automatic/manual Windows builder is also included at\n"
        ".github/workflows/windows-installer.yml; it has not been run by this export.\n"
        "Windows native ACL, installation and uninstall must pass on Windows before\n"
        "publishing the resulting binary. This export alone does not prove that.\n"
    ).encode("utf-8")
    with zipfile.ZipFile(destination, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for name, data in sorted(contents.items()):
            archive.writestr(prefix + "/" + name, data)
    with zipfile.ZipFile(destination) as archive:
        if archive.testzip() is not None:
            raise ValueError("Build kit archive integrity check failed")
        for name, data in contents.items():
            if archive.read(prefix + "/" + name) != data:
                raise ValueError("Build kit payload verification failed")
    return destination


def require_windows_tools(jdk_home: Path | None = None):
    if os.name != "nt":
        raise ValueError("The Windows installer must be built on Windows. Use --prepare-only here to export a source build kit.")
    if platform.machine().lower() not in ("amd64", "x86_64"):
        raise ValueError("This installer targets Windows x64; use an x64 Windows build machine and x64 JDK.")
    home = find_jdk(os.environ, jdk_home)
    activate_jdk(home, os.environ)
    print(f"Using JDK: {home}")
    for tool in ("cargo", "rustup", "candle.exe", "light.exe"):
        if shutil.which(tool) is None:
            raise ValueError(f"Required Windows build tool is missing from PATH: {tool}")


def check_pe(path: Path):
    with path.open("rb") as stream:
        if stream.read(2) != b"MZ":
            raise ValueError(f"Not a Windows executable: {path.name}")
        stream.seek(0x3C)
        pointer = int.from_bytes(stream.read(4), "little")
        stream.seek(pointer)
        if stream.read(4) != b"PE\0\0":
            raise ValueError(f"Invalid Windows executable: {path.name}")
        machine = int.from_bytes(stream.read(2), "little")
        if machine != 0x8664:
            raise ValueError(f"Expected a Windows x64 executable: {path.name}")


def package_app(app: Path, destination: Path, common: list, installer_type: str) -> Path:
    if installer_type not in ("msi", "exe"):
        raise ValueError(f"Unsupported Windows installer type: {installer_type}")
    run(["jpackage", "--type", installer_type, "--app-image", app, "--dest", destination,
         "--win-per-user-install", "--win-dir-chooser", "--win-menu", "--win-menu-group",
         "StarSea Community", "--win-shortcut", "--win-upgrade-uuid", UPGRADE_UUID, *common])
    generated = list(destination.glob(f"*.{installer_type}"))
    if len(generated) != 1:
        raise ValueError(f"jpackage did not produce exactly one {installer_type.upper()} installer")
    # MSI is an OLE compound database, not a renamed executable. WiX EXE
    # bootstrappers can be x86 even when their application payload is x64.
    expected = bytes.fromhex("d0cf11e0a1b11ae1") if installer_type == "msi" else b"MZ"
    with generated[0].open("rb") as stream:
        if stream.read(len(expected)) != expected:
            raise ValueError(f"Installer is not a Windows {installer_type.upper()} package")
    return generated[0]


def build_installer(output: Path, transport: str, installer_type: str = "msi") -> Path:
    validate()
    full_version = version()
    installer = output / f"Nautilus-{full_version}-Windows-x64-Setup.{installer_type}"
    if installer.exists():
        raise ValueError(f"Refusing to overwrite an existing installer: {installer}")
    with tempfile.TemporaryDirectory(prefix="Nautilus-Windows-Package-") as temporary:
        work = Path(temporary)
        target = work / "rust-target"
        # Test the actual Windows platform branches, including native owner-only ACLs.
        run(["cargo", "test", "--locked", "--manifest-path", ROOT / "Cargo.toml", "--target-dir", target])
        environment = os.environ.copy()
        environment["CARGO_TARGET_X86_64_PC_WINDOWS_MSVC_RUSTFLAGS"] = "-C target-feature=+crt-static"
        run(["cargo", "build", "--locked", "--release", "--target", "x86_64-pc-windows-msvc", "--manifest-path", ROOT / "Cargo.toml", "--target-dir", target, "--bin", "nautilus-chat-bridge"], env=environment)
        backend = target / "x86_64-pc-windows-msvc/release/nautilus-chat-bridge.exe"
        check_pe(backend)
        classes = desktop_tests(work / "java", backend, transport)
        payload = work / "payload"
        payload.mkdir()
        shutil.copy2(backend, payload / backend.name)
        for library in (ROOT / "desktop/lib").iterdir():
            if library.is_file() and (library.suffix == ".jar" or library.name.startswith("LICENSE")):
                shutil.copy2(library, payload / library.name)
        manifest = work / "MANIFEST.MF"
        classpath = " ".join(path.name for path in sorted(payload.glob("*.jar")))
        manifest.write_text("Manifest-Version: 1.0\nMain-Class: com.starsea.nautilus.NautilusDesktop\nClass-Path: " + classpath + "\n\n", encoding="utf-8")
        run(["jar", "--create", "--file", payload / "Nautilus.jar", "--manifest", manifest, "-C", classes, "."])
        shutil.copy2(ROOT / "desktop/PRIVACY.md", payload / "PRIVACY.md")
        icon = work / "Nautilus.ico"
        run(["java", "-Djava.awt.headless=true", "-cp", classes, "com.starsea.nautilus.NautilusIcon", "--ico", icon])
        image_output = work / "app-image"
        # jpackage uses the JAR's own directory; no source-tree path is passed to the launcher.
        common = ["--name", "Nautilus", "--app-version", full_version.split("-", 1)[0], "--vendor", "StarSea Community", "--description", f"Nautilus {full_version} AI Chat", "--icon", icon]
        run(["jpackage", "--type", "app-image", "--input", payload, "--main-jar", "Nautilus.jar", "--main-class", "com.starsea.nautilus.NautilusDesktop", "--dest", image_output,
             "--add-modules", "java.base,java.desktop,java.logging,java.net.http,java.naming,java.management,jdk.crypto.ec,jdk.charsets", "--java-options", "-Dfile.encoding=UTF-8", "--jlink-options", "--strip-debug --no-header-files --no-man-pages", *common])
        app = image_output / "Nautilus"
        check_pe(app / "Nautilus.exe")
        # Smoke-test the exact bundled backend/JRE without paying for a model call.
        profile = work / "packaged-smoke-profile"
        smoke_env = os.environ.copy()
        smoke_env["NAUTILUS_PROFILE_DIR"] = str(profile)
        smoke_env["GEMINI_WEB_SEARCH"] = "false"
        smoke = subprocess.run([str(app / "app/nautilus-chat-bridge.exe")], input='{"type":"shutdown"}\n', capture_output=True, text=True, encoding="utf-8", check=True, env=smoke_env, cwd=app / "app", timeout=30)
        events = [json.loads(line) for line in smoke.stdout.splitlines() if line]
        if not events or events[0].get("type") != "ready" or events[-1].get("type") != "bye":
            raise ValueError("Packaged Rust backend did not pass startup/shutdown smoke test")
        bundled_java = app / "runtime/bin/java.exe"
        run([bundled_java, "-Djava.awt.headless=true", "-cp", str(app / "app/Nautilus.jar"), "com.starsea.nautilus.NautilusIcon", "--ico", work / "runtime-icon.ico"])
        produced = work / "installer"
        generated = package_app(app, produced, common, installer_type)
        shutil.copy2(generated, installer)
    return installer


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-dir", type=Path, help="Destination outside source project (default: desktop)")
    parser.add_argument("--jdk-home", type=Path, help="Full x64 JDK root; otherwise auto-detect JAVA_HOME/PATH/common install locations")
    parser.add_argument("--installer-type", choices=("msi", "exe"), default="msi", help="Windows installer format (default: msi)")
    parser.add_argument("--prepare-only", action="store_true", help="Export checked source/build scripts; does NOT produce an installer")
    parser.add_argument("--transport", choices=("stdio", "sse"), default="sse", help="Transport to verify on the Windows build host")
    args = parser.parse_args()
    try:
        output = outside_project(args.output_dir or desktop_directory())
        if not args.prepare_only:
            require_windows_tools(args.jdk_home)  # Fail before writing files on an unsupported host.
        output.mkdir(parents=True, exist_ok=True)
        artifact = export_kit(output) if args.prepare_only else build_installer(output, args.transport, args.installer_type)
        checksum = hashlib.sha256(artifact.read_bytes()).hexdigest()
        artifact.with_suffix(artifact.suffix + ".sha256").write_text(f"{checksum}  {artifact.name}\n", encoding="utf-8")
        print(f"READY: {artifact}")
        print("SOURCE BUILD KIT ONLY; NOT A WINDOWS INSTALLER" if args.prepare_only else f"READY: self-contained Windows x64 {args.installer_type.upper()} installer")
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        parser.exit(1, f"Packaging failed: {error}\n")

if __name__ == "__main__":
    main()
