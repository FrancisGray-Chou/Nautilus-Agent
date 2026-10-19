"""Find one complete Windows JDK and use it for every Java build tool."""
from __future__ import annotations
import os
from pathlib import Path
import re
import shutil
import subprocess
from collections.abc import Mapping, MutableMapping

TOOLS = ("java", "javac", "jar", "jpackage", "jlink")


def candidate_homes(environment: Mapping[str, str]):
    """Bounded searches of known JDK locations; never scan an entire disk."""
    configured = environment.get("JAVA_HOME", "").strip().strip('"')
    if configured:
        yield Path(configured)
    for tool in TOOLS:
        found = shutil.which(tool, path=environment.get("PATH", environment.get("Path", "")))
        if found:
            yield Path(found).resolve().parent.parent
    roots = []
    for variable in ("ProgramW6432", "ProgramFiles"):
        base = environment.get(variable)
        if base:
            roots.extend(Path(base) / vendor for vendor in (
                "Eclipse Adoptium", "Java", "Microsoft", "Amazon Corretto",
                "BellSoft", "Zulu", "AdoptOpenJDK", "Semeru", "RedHat",
            ))
    local = environment.get("LOCALAPPDATA")
    if local:
        roots.extend(Path(local) / "Programs" / vendor for vendor in ("Eclipse Adoptium", "Java", "Microsoft", "Amazon Corretto"))
    home = environment.get("USERPROFILE")
    if home:
        roots.append(Path(home) / ".jdks")
    for root in roots:
        try:
            if root.is_dir():
                yield root
                yield from sorted((child for child in root.iterdir() if child.is_dir()), reverse=True)
        except OSError:
            continue


def validate_jdk(home: Path):
    missing = [tool + ".exe" for tool in TOOLS if not (home / "bin" / (tool + ".exe")).is_file()]
    if missing:
        raise ValueError("missing " + ", ".join(missing))
    try:
        result = subprocess.run([str(home / "bin/java.exe"), "-XshowSettings:properties", "-version"],
            capture_output=True, encoding="utf-8", errors="replace", check=True, timeout=15)
    except (OSError, subprocess.SubprocessError) as error:
        raise ValueError("java.exe could not run") from error
    properties = dict(re.findall(r"^\s*(os\.arch|java\.specification\.version)\s*=\s*(\S+)\s*$", result.stdout + "\n" + result.stderr, re.MULTILINE))
    if properties.get("os.arch", "").lower() not in ("amd64", "x86_64"):
        raise ValueError("not an x64 JDK")
    major = properties.get("java.specification.version", "").split(".")[0]
    if not major.isdigit() or int(major) < 17:
        raise ValueError("JDK 17 or newer is required")


def find_jdk(environment: Mapping[str, str], explicit_home: Path | None = None) -> Path:
    if explicit_home is not None:
        home = explicit_home.expanduser().resolve()
        try:
            validate_jdk(home)
        except ValueError as error:
            raise ValueError(f"Invalid --jdk-home '{home}': {error}. Select the JDK root directory, not bin.") from error
        return home
    seen = set()
    rejected = []
    for candidate in candidate_homes(environment):
        home = candidate.resolve()
        identity = str(home).casefold()
        if identity in seen:
            continue
        seen.add(identity)
        try:
            validate_jdk(home)
        except ValueError as error:
            if len(rejected) < 3:
                rejected.append(f"{home}: {error}")
        else:
            return home
    detail = "\nChecked: " + "; ".join(rejected) if rejected else ""
    raise ValueError("No complete Windows x64 JDK 17+ was found. A JRE or java.exe alone is insufficient. "
        "Install the full JDK (including jar.exe, javac.exe, jpackage.exe and jlink.exe), "
        "then set JAVA_HOME to its root, or pass --jdk-home \"C:\\path\\to\\jdk\"." + detail)


def activate_jdk(home: Path, environment: MutableMapping[str, str]):
    # Only the current builder and its children change; no setx/registry/system PATH edit.
    path_key = next((key for key in environment if key.upper() == "PATH"), "PATH")
    environment["JAVA_HOME"] = str(home)
    environment[path_key] = str(home / "bin") + os.pathsep + environment.get(path_key, "")
