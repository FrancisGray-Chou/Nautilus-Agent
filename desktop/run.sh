#!/usr/bin/env bash
set -euo pipefail
desktop_dir="$(cd "$(dirname "$0")" && pwd)"
project_root="$(cd "$desktop_dir/.." && pwd)"
command -v cargo >/dev/null || { echo "请先安装 Rust / Cargo。" >&2; exit 1; }
command -v javac >/dev/null || { echo "请先安装 JDK 17 或更新版本。" >&2; exit 1; }
cargo build --locked --manifest-path "$project_root/Cargo.toml" --target-dir "$project_root/target" --bin nautilus-chat-bridge
cd "$desktop_dir"
mkdir -p build/classes
find src/main/java -name '*.java' -print > build/sources.list
javac --release 17 -encoding UTF-8 -cp 'lib/*' -d build/classes @build/sources.list
exec java -Dfile.encoding=UTF-8 -cp 'build/classes:lib/*' com.starsea.nautilus.NautilusDesktop "$project_root"
