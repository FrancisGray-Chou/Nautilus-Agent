#!/usr/bin/env bash
set -euo pipefail
desktop_dir="$(cd "$(dirname "$0")" && pwd)"
project_root="$(cd "$desktop_dir/.." && pwd)"
profile_dir="$(mktemp -d "${TMPDIR:-/tmp}/nautilus-profile.XXXXXX")"
history_dir="$(mktemp -d "${TMPDIR:-/tmp}/nautilus-history.XXXXXX")"
trap 'rm -rf "$profile_dir" "$history_dir"' EXIT
cargo build --locked --manifest-path "$project_root/Cargo.toml" --target-dir "$project_root/target" --bin nautilus-chat-bridge
cd "$desktop_dir"
mkdir -p build/classes build/test-classes
find src/main/java -name '*.java' -print > build/sources.list
find src/test/java -name '*.java' -print > build/test-sources.list
javac --release 17 -encoding UTF-8 -Xlint:all -Werror -cp 'lib/*' -d build/classes @build/sources.list
javac --release 17 -encoding UTF-8 -Xlint:all -Werror -cp 'build/classes:lib/*' -d build/test-classes @build/test-sources.list
java -Dnautilus.history.path="$history_dir" -Dnautilus.language.path="$desktop_dir/build/test-language-default.txt" -Djava.awt.headless=true -cp 'build/classes:build/test-classes:lib/*' com.starsea.nautilus.DesktopSmokeTest "$desktop_dir/build"
java -Dnautilus.history.path="$history_dir" -Dnautilus.language.path="$desktop_dir/build/test-language-default.txt" -Djava.awt.headless=true -cp 'build/classes:build/test-classes:lib/*' com.starsea.nautilus.GeminiSelectorTest
java -Dnautilus.history.path="$history_dir" -Dnautilus.language.path="$desktop_dir/build/test-language-default.txt" -Djava.awt.headless=true -cp 'build/classes:build/test-classes:lib/*' com.starsea.nautilus.MarkdownTest "$desktop_dir/build"
java -Dnautilus.history.path="$history_dir" -Dnautilus.language.path="$desktop_dir/build/test-language-default.txt" -Djava.awt.headless=true -cp 'build/classes:build/test-classes:lib/*' com.starsea.nautilus.ReplyActionsTest "$desktop_dir/build"
java -Dnautilus.history.path="$history_dir" -Dnautilus.language.path="$desktop_dir/build/test-language-default.txt" -Djava.awt.headless=true -cp 'build/classes:build/test-classes:lib/*' com.starsea.nautilus.CredentialsPanelTest "$desktop_dir/build"
java -Dnautilus.history.path="$history_dir" -Dnautilus.language.path="$desktop_dir/build/test-language-default.txt" -Djava.awt.headless=true -cp 'build/classes:build/test-classes:lib/*' com.starsea.nautilus.MixturePanelTest "$desktop_dir/build"
java -Dnautilus.history.path="$history_dir" -Djava.awt.headless=true -cp 'build/classes:build/test-classes:lib/*' com.starsea.nautilus.SettingsMenuTest "$desktop_dir/build"
java -Dnautilus.history.path="$history_dir" -Dnautilus.language.path="$desktop_dir/build/test-language-default.txt" -Djava.awt.headless=true -cp 'build/classes:build/test-classes:lib/*' com.starsea.nautilus.ProgressInteractionTest "$desktop_dir/build"
java -Dnautilus.history.path="$history_dir" -Dnautilus.language.path="$desktop_dir/build/test-language-default.txt" -Djava.awt.headless=true -cp 'build/classes:build/test-classes:lib/*' com.starsea.nautilus.ThemeTest "$desktop_dir/build"
java -Dnautilus.history.path="$history_dir" -Dnautilus.language.path="$desktop_dir/build/test-language-default.txt" -Djava.awt.headless=true -cp 'build/classes:build/test-classes:lib/*' com.starsea.nautilus.SidebarHistoryTest "$desktop_dir/build"
java -Dnautilus.history.path="$history_dir" -Dnautilus.language.path="$desktop_dir/build/test-language-default.txt" -Djava.awt.headless=true -cp 'build/classes:build/test-classes:lib/*' com.starsea.nautilus.UiFluencyTest "$desktop_dir/build"
java -Djava.awt.headless=true -cp 'build/classes:build/test-classes:lib/*' com.starsea.nautilus.SearchBubbleTest "$desktop_dir/build"
java -Djava.awt.headless=true -cp 'build/classes:build/test-classes:lib/*' com.starsea.nautilus.PlatformPathsTest
java -Djava.awt.headless=true -cp 'build/classes:build/test-classes:lib/*' com.starsea.nautilus.MouseWheelTest
for search_default in true false; do
env DEEPSEEK_API_KEY=test-offline OPENAI_API_KEY=test-offline GEMINI_API_KEY=test-offline ANTHROPIC_API_KEY=test-offline \
  NAUTILUS_PROFILE_DIR="$profile_dir" \
  DEEPSEEK_MODEL=custom-legacy-model OPENAI_MODEL=gpt-5-mini CLAUDE_MODEL=invalid-legacy-claude GEMINI_MODEL=gemini-3.8-flash GEMINI_WEB_SEARCH="$search_default" \
  HTTPS_PROXY=http://127.0.0.1:9 HTTP_PROXY=http://127.0.0.1:9 ALL_PROXY=http://127.0.0.1:9 \
  https_proxy=http://127.0.0.1:9 http_proxy=http://127.0.0.1:9 all_proxy=http://127.0.0.1:9 NO_PROXY= no_proxy= \
  java -Dnautilus.history.path="$history_dir" -Dnautilus.transport="${NAUTILUS_TEST_TRANSPORT:-sse}" -Dnautilus.language.path="$desktop_dir/build/test-language-default.txt" -cp 'build/classes:build/test-classes:lib/*' com.starsea.nautilus.BridgeIntegrationTest "$project_root"
done
