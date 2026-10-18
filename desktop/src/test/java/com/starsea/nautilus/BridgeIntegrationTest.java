package com.starsea.nautilus;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;

/** Real Java <-> Rust process test. test.sh supplies fake keys and an unavailable local proxy. */
public final class BridgeIntegrationTest {
    private BridgeIntegrationTest() {}
    public static void main(String[] args) throws Exception {
        try (BridgeClient client = new BridgeClient(Path.of(args[0]))) {
            if (client.initialize().size() != 5) throw new AssertionError("missing models");
            if (client.credentials().deepseek()) throw new AssertionError("inherited developer key was used");
            try { client.select("deepseek"); throw new AssertionError("inherited key selected"); } catch (IOException expected) { /* expected */ }
            try { client.saveCredentials(Map.of("deepseek", "test-account-deepseek"), false); throw new AssertionError("notice acknowledgement missing"); }
            catch (IOException expected) { /* expected */ }
            ChatService.Credentials credentials = client.saveCredentials(Map.of("deepseek", "test-account-deepseek", "openai", "test-account-openai", "gemini", "test-account-gemini"), true);
            Path file = Path.of(credentials.storagePath());
            if (!file.startsWith(Path.of(System.getenv("NAUTILUS_PROFILE_DIR")))) throw new AssertionError("wrong account path");
            if (!Files.readString(file).contains("test-account-deepseek")) throw new AssertionError("plaintext .env not written");
            if (!Files.getPosixFilePermissions(file).equals(PosixFilePermissions.fromString("rw-------"))) throw new AssertionError("unsafe file permissions");
            if (credentials.toString().contains("test-account-deepseek")) throw new AssertionError("key echoed in status");
            for (String id : new String[]{"deepseek", "openai", "gemini-flash", "gemini-argon", "gemini-auto"}) {
                if (!client.select(id).modelId().equals(id)) throw new AssertionError("wrong selection");
            }
            try { client.select("unknown"); throw new AssertionError("unknown model accepted"); }
            catch (IOException expected) { /* session must remain usable */ }
            try { client.chat(" "); throw new AssertionError("empty prompt accepted"); }
            catch (IOException expected) { /* expected */ }
            try { client.chat("中文协议测试\n第二行"); throw new AssertionError("offline proxy unexpectedly succeeded"); }
            catch (IOException expected) { /* expected */ }
            client.reset();
            if (!client.select("deepseek").modelId().equals("deepseek")) throw new AssertionError("recovery failed");
            client.saveCredentials(Map.of("deepseek", "rotated-account-key"), true);
            try { client.chat("should require reselection"); throw new AssertionError("old agent survived key rotation"); } catch (IOException expected) { /* expected */ }
            if (!client.credentials().gemini()) throw new AssertionError("partial update erased another platform key");
        }
        try (BridgeClient reopened = new BridgeClient(Path.of(args[0]))) {
            reopened.initialize();
            if (!reopened.credentials().deepseek()) throw new AssertionError("saved account was not loaded");
            reopened.select("gemini-flash");
            reopened.deleteCredentials("gemini");
            try { reopened.select("gemini-flash"); throw new AssertionError("deleted key fell back to process environment"); } catch (IOException expected) { /* expected */ }
            Path file = Path.of(reopened.credentials().storagePath());
            reopened.deleteCredentials("all");
            if (Files.exists(file)) throw new AssertionError("credential file not deleted");
        }
        System.out.println("PASS: Java/Rust account credentials, plaintext persistence, 0600 permissions, rotation, deletion, no developer-key fallback and chat protocol");
    }
}
