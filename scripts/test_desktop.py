#!/usr/bin/env python3
"""Cross-platform offline desktop regression, with all build output outside the project."""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
TESTS = (
    "DesktopSmokeTest", "GeminiSelectorTest", "MarkdownTest", "ReplyActionsTest",
    "CredentialsPanelTest", "MixturePanelTest", "SettingsMenuTest", "ProgressInteractionTest",
    "ThemeTest", "SidebarHistoryTest", "UiFluencyTest", "SearchBubbleTest", "MouseWheelTest", "PlatformPathsTest",
)

def outside_project(path: Path) -> Path:
    path = path.resolve()
    if path == ROOT or ROOT in path.parents:
        raise ValueError("Build/output directories must be outside the source project")
    return path

def run(command, **kwargs):
    subprocess.run([str(value) for value in command], check=True, **kwargs)

def compile_java(work: Path) -> Path:
    work.mkdir(parents=True, exist_ok=True)
    classes = work / "classes"
    classes.mkdir(parents=True, exist_ok=True)
    sources = sorted((ROOT / "desktop/src/main/java").rglob("*.java"))
    # javac argument files avoid the Windows command-line length limit.
    args = work / "java-main.args"
    args.write_text("\n".join('"' + path.as_posix() + '"' for path in sources), encoding="utf-8")
    run(["javac", "-J-Dfile.encoding=UTF-8", "--release", "17", "-encoding", "UTF-8", "-Xlint:all", "-Werror", "-cp", str(ROOT / "desktop/lib/*"), "-d", classes, "@" + str(args)])
    return classes

def desktop_tests(work: Path, backend: Path | None = None, transport="stdio"):
    classes = compile_java(work)
    tests = work / "test-classes"
    tests.mkdir(exist_ok=True)
    classpath = os.pathsep.join(map(str, [classes, tests, ROOT / "desktop/lib/*"]))
    args = work / "java-tests.args"
    args.write_text("\n".join('"' + path.as_posix() + '"' for path in sorted((ROOT / "desktop/src/test/java").rglob("*.java"))), encoding="utf-8")
    run(["javac", "-J-Dfile.encoding=UTF-8", "--release", "17", "-encoding", "UTF-8", "-Xlint:all", "-Werror", "-cp", classpath, "-d", tests, "@" + str(args)])
    previews = work / "previews"
    previews.mkdir(exist_ok=True)
    props = [f"-Dnautilus.history.path={work / 'history'}", f"-Dnautilus.language.path={work / 'language.txt'}", f"-Dnautilus.theme.path={work / 'theme.txt'}", "-Dfile.encoding=UTF-8", "-Djava.awt.headless=true"]
    for name in TESTS:
        run(["java", *props, "-cp", classpath, "com.starsea.nautilus." + name, previews], cwd=ROOT / "desktop")
    if backend is not None:
        runtime = work / "runtime"
        runtime.mkdir(exist_ok=True)
        shutil.copy2(backend, runtime / ("nautilus-chat-bridge.exe" if os.name == "nt" else "nautilus-chat-bridge"))
        for default in ("true", "false"):
            environment = os.environ.copy()
            environment.update(DEEPSEEK_API_KEY="test-offline", OPENAI_API_KEY="test-offline", GEMINI_API_KEY="test-offline", ANTHROPIC_API_KEY="test-offline",
                NAUTILUS_PROFILE_DIR=str(work / "profile"), DEEPSEEK_MODEL="custom-legacy-model", OPENAI_MODEL="gpt-5-mini", CLAUDE_MODEL="invalid-legacy-claude", GEMINI_MODEL="gemini-3.8-flash", GEMINI_WEB_SEARCH=default)
            for proxy in ("HTTPS_PROXY", "HTTP_PROXY", "ALL_PROXY", "https_proxy", "http_proxy", "all_proxy"):
                environment[proxy] = "http://127.0.0.1:9"
            environment["NO_PROXY"] = environment["no_proxy"] = ""
            run(["java", *props, f"-Dnautilus.transport={transport}", "-cp", classpath, "com.starsea.nautilus.BridgeIntegrationTest", runtime], env=environment)
    return classes

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--work-dir", type=Path)
    parser.add_argument("--backend", type=Path)
    parser.add_argument("--transport", choices=("stdio", "sse"), default="stdio")
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix="nautilus-desktop-tests-") as temporary:
        work = outside_project(args.work_dir or Path(temporary))
        work.mkdir(parents=True, exist_ok=True)
        desktop_tests(work, args.backend.resolve() if args.backend else None, args.transport)
    print("PASS: desktop regression; output stayed outside the source project")

if __name__ == "__main__":
    main()
