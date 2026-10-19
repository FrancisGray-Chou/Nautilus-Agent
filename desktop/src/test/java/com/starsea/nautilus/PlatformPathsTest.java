package com.starsea.nautilus;

import java.io.IOException;
import java.nio.file.*;
import java.util.Map;
import java.util.List;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import javax.imageio.ImageIO;

public final class PlatformPathsTest {
    private PlatformPathsTest() {}
    public static void main(String[] args)throws Exception {
        Path directory=Files.createTempDirectory("nautilus-platform-用户-"),home=directory.resolve("Francis Gray");
        Path roaming=home.resolve("AppData/Roaming");
        check(PlatformPaths.profileDirectory("Windows 11",home,Map.of("APPDATA",roaming.toString())).equals(roaming.resolve("Nautilus")),"Windows APPDATA");
        check(PlatformPaths.profileDirectory("Windows 10",home,Map.of()).equals(roaming.resolve("Nautilus")),"Windows fallback");
        check(PlatformPaths.profileDirectory("Windows 11",home,Map.of("NAUTILUS_PROFILE_DIR",directory.resolve("override").toString())).equals(directory.resolve("override")),"profile override");
        check(PlatformPaths.profileDirectory("Mac OS X",home,Map.of()).equals(home.resolve("Library/Application Support/Nautilus")),"macOS unchanged");
        check(PlatformPaths.profileDirectory("Linux",home,Map.of()).equals(home.resolve(".config/Nautilus")),"Linux unchanged");
        for(String os:List.of("Windows 11","Mac OS X")) {
            String executable=os.startsWith("Windows")?"nautilus-chat-bridge.exe":"nautilus-chat-bridge";
            Path root=directory.resolve(os);Files.createDirectories(root.resolve("target/debug"));
            Path debug=root.resolve("target/debug").resolve(executable);Files.writeString(debug,"test");
            check(PlatformPaths.backend(root,os).equals(debug),"source launcher");
            Path bundled=root.resolve(executable);Files.writeString(bundled,"test");
            check(PlatformPaths.backend(root,os).equals(bundled),"bundled backend preferred");
            Files.delete(bundled);Files.delete(debug);
            try{PlatformPaths.backend(root,os);throw new AssertionError("missing backend accepted");}catch(IOException expected){ /* actionable missing install */ }
        }
        Path ico=directory.resolve("Nautilus.ico");NautilusIcon.writeIco(ico);
        byte[] bytes=Files.readAllBytes(ico);ByteBuffer buffer=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        check(buffer.getShort()==0&&buffer.getShort()==1&&buffer.getShort()==7,"Windows icon directory");
        for(int i=0;i<7;i++) {
            buffer.position(6+i*16);int width=Byte.toUnsignedInt(buffer.get());buffer.get();buffer.get();buffer.get();buffer.getShort();check(buffer.getShort()==32,"32 bit icon");int size=buffer.getInt(),offset=buffer.getInt();
            java.awt.image.BufferedImage image=ImageIO.read(new java.io.ByteArrayInputStream(bytes,offset,size));check(image.getWidth()==(width==0?256:width),"real ICO PNG sizes");
        }
        try(var files=Files.walk(directory)){for(Path file:files.sorted(java.util.Comparator.reverseOrder()).toList())Files.delete(file);}
        System.out.println("PASS: Windows profile paths, Unicode/spaces, installed/source backend discovery, missing install and seven-size ICO");
    }
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
