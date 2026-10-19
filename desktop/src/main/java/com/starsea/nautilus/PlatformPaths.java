package com.starsea.nautilus;

import java.io.IOException;
import java.nio.file.*;
import java.util.Map;

/** Shared user-data and installed-app paths; independent of the launch working directory. */
final class PlatformPaths {
    private PlatformPaths() {}
    static Path profileDirectory() {
        return profileDirectory(System.getProperty("os.name"), Path.of(System.getProperty("user.home")), System.getenv());
    }
    static Path profileDirectory(String os, Path home, Map<String,String> environment) {
        String override=environment.get("NAUTILUS_PROFILE_DIR");
        if(override!=null&&!override.isBlank())return Path.of(override).toAbsolutePath().normalize();
        if(os.startsWith("Windows")) {
            String roaming=environment.get("APPDATA");
            return (roaming!=null&&!roaming.isBlank()?Path.of(roaming):home.resolve("AppData/Roaming")).resolve("Nautilus").toAbsolutePath().normalize();
        }
        if(os.startsWith("Mac"))return home.resolve("Library/Application Support/Nautilus");
        String xdg=environment.get("XDG_CONFIG_HOME");
        return (xdg!=null&&!xdg.isBlank()?Path.of(xdg):home.resolve(".config")).resolve("Nautilus").toAbsolutePath().normalize();
    }
    static Path applicationRoot() {
        try {
            Path source=Path.of(NautilusDesktop.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if(Files.isRegularFile(source))return source.getParent();
        } catch(java.net.URISyntaxException | SecurityException ignored) { /* Source launchers pass their explicit root. */ }
        return Path.of(".").toAbsolutePath().normalize();
    }
    static Path backend(Path root,String os) throws IOException {
        String name=os.startsWith("Windows")?"nautilus-chat-bridge.exe":"nautilus-chat-bridge";
        for(Path candidate:new Path[]{root.resolve(name),root.resolve("bin").resolve(name),root.resolve("target/release").resolve(name),root.resolve("target/debug").resolve(name)}) {
            if(Files.isRegularFile(candidate)&&!Files.isSymbolicLink(candidate))return candidate;
        }
        throw new IOException("未找到聊天服务，请重新安装 Nautilus；源码运行请先构建 nautilus-chat-bridge");
    }
}
