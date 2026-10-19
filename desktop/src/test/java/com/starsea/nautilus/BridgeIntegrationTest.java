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
        System.setProperty("java.awt.headless", "true");
        try (BridgeClient client = new BridgeClient(Path.of(args[0]))) {
            if (client.initialize().size() != 11) throw new AssertionError("missing models");
            String expectedTransport = "stdio".equals(System.getProperty("nautilus.transport")) ? "stdio" : "sse";
            if (!client.transportName().equals(expectedTransport)) throw new AssertionError("requested transport not active");
            if (client.credentials().deepseek()) throw new AssertionError("inherited developer key was used");
            try { client.select("claude-opus"); throw new AssertionError("Claude inherited a developer key"); } catch (IOException expected) { /* expected */ }
            try { client.select("deepseek"); throw new AssertionError("inherited key selected"); } catch (IOException expected) { /* expected */ }
            try { client.saveCredentials(Map.of("deepseek", "test-account-deepseek"), false); throw new AssertionError("notice acknowledgement missing"); }
            catch (IOException expected) { /* expected */ }
            ChatService.Credentials credentials = client.saveCredentials(Map.of("deepseek", "test-account-deepseek", "openai", "test-account-openai", "gemini", "test-account-gemini", "claude", "test-account-claude"), true);
            boolean expectedSearch=Boolean.parseBoolean(System.getenv("GEMINI_WEB_SEARCH"));
            if (client.webSearch().defaultEnabled()!=expectedSearch || client.webSearch().available()) throw new AssertionError("default before selection");
            ChatService.Selection search = client.select("gemini-flash");
            if (search.search()!=expectedSearch || !search.searchState().available()) throw new AssertionError("search default not applied");
            if (client.setWebSearch(false).enabled()) throw new AssertionError("search not disabled");
            if (client.select("gemini-auto").search()) throw new AssertionError("switch reset search preference");
            client.reset(); if (client.webSearch().enabled()) throw new AssertionError("reset lost search preference");
            if (!client.setWebSearch(true).enabled()) throw new AssertionError("search not enabled");
            for(String id:new String[]{"deepseek-flash","deepseek-pro","openai","claude-opus","claude-fable","claude-sonnet","claude-haiku"}) {
                ChatService.Selection selected=client.select(id);
                if(!selected.searchState().available()||!selected.search())throw new AssertionError("search unavailable: "+id);
                if(client.setWebSearch(false).enabled())throw new AssertionError("search not disabled: "+id);
                if(!client.setWebSearch(true).enabled())throw new AssertionError("search not enabled: "+id);
            }
            if(!client.configureMixture(java.util.List.of("deepseek-flash","openai","claude-sonnet")).search())throw new AssertionError("non-Gemini Mixture search unavailable");
            if (!client.configureMixture(java.util.List.of("deepseek-pro","gemini-auto")).search()) throw new AssertionError("Mixture search not enabled");
            if (!client.setWebSearch(false).available() || client.webSearch().enabled()) throw new AssertionError("Mixture search toggle failed");
            client.setWebSearch(true);
            Path file = Path.of(credentials.storagePath());
            if (!file.startsWith(Path.of(System.getenv("NAUTILUS_PROFILE_DIR")))) throw new AssertionError("wrong account path");
            if (!Files.readString(file).contains("test-account-deepseek")) throw new AssertionError("plaintext .env not written");
            if (Files.getFileAttributeView(file,java.nio.file.attribute.PosixFileAttributeView.class)!=null && !Files.getPosixFilePermissions(file).equals(PosixFilePermissions.fromString("rw-------"))) throw new AssertionError("unsafe file permissions");
            if (credentials.toString().contains("test-account-deepseek")) throw new AssertionError("key echoed in status");
            for (String id : new String[]{"deepseek", "deepseek-flash", "deepseek-pro", "openai", "gemini-flash", "gemini-argon", "gemini-auto", "claude-opus", "claude-fable", "claude-sonnet", "claude-haiku"}) {
                if (!client.select(id).modelId().equals(id)) throw new AssertionError("wrong selection");
            }
            if (!client.select("deepseek-flash").model().equals("deepseek-flash")) throw new AssertionError("wrong Flash model");
            if (!client.select("deepseek-pro").model().equals("deepseek-v4-pro")) throw new AssertionError("wrong Pro model");
            if (!client.credentials().claude()) throw new AssertionError("Claude status lost");
            String[][] claudePresets = {{"claude-opus","claude-opus-5-5"},{"claude-fable","claude-fable-5-1"},
                {"claude-sonnet","claude-sonnet-5-5"},{"claude-haiku","claude-haiku-5-5"}};
            for (String[] preset : claudePresets) {
                ChatService.Selection selected = client.select(preset[0]);
                if (!selected.model().equals(preset[1]) || !selected.provider().equals("Claude")) throw new AssertionError("wrong Claude model");
            }
            try { client.select("unknown"); throw new AssertionError("unknown model accepted"); }
            catch (IOException expected) { /* session must remain usable */ }
            try { client.chat(" "); throw new AssertionError("empty prompt accepted"); }
            catch (IOException expected) { /* expected */ }
            try { client.chat("中文协议测试\n第二行"); throw new AssertionError("offline proxy unexpectedly succeeded"); }
            catch (IOException expected) { /* expected */ }
            java.util.List<String> notices = new java.util.ArrayList<>();
            client.setNoticeListener(notices::add);
            java.util.List<ChatService.Progress> progress = new java.util.ArrayList<>();
            client.setProgressListener(progress::add);
            try { client.configureMixture(java.util.List.of()); throw new AssertionError("empty team accepted"); }
            catch (IOException expected) { /* no empty team */ }
            ChatService.Selection team = client.configureMixture(java.util.List.of("deepseek-pro", "gemini-flash"));
            if (!team.model().contains("deepseek-v4-pro") || !team.model().contains("gemini-3.8-flash")) throw new AssertionError("submodels lost in bridge");
            try { client.configureMixture(java.util.List.of("deepseek-pro", "deepseek-flash")); throw new AssertionError("duplicate provider accepted"); }
            catch (IOException expected) { /* only one child per provider */ }
            ChatService.Selection four = client.configureMixture(java.util.List.of("deepseek-pro", "openai", "gemini-auto", "claude-sonnet"));
            if (!four.model().contains("claude-sonnet-5-5")) throw new AssertionError("Claude missing from team");
            try { client.configureMixture(java.util.List.of("claude-opus", "claude-haiku")); throw new AssertionError("duplicate Claude accepted"); }
            catch (IOException expected) { /* one child per provider */ }
            client.configureMixture(java.util.List.of("deepseek", "openai"));
            try { client.chat("offline mixture"); throw new AssertionError("offline mixture succeeded"); }
            catch (IOException expected) { /* all requests fail through local proxy */ }
            if (progress.stream().noneMatch(p -> p.role().equals("Planner") && p.status().equals("running"))) throw new AssertionError("missing live Planner event");
            if (progress.stream().noneMatch(p -> p.status().equals("failed"))) throw new AssertionError("missing live failure event");
            if (notices.stream().noneMatch(n -> n.contains("已停用"))) throw new AssertionError("missing fallback notice");
            java.util.concurrent.atomic.AtomicReference<ChatService.MixtureTeam> boardConfig = new java.util.concurrent.atomic.AtomicReference<>();
            ChatService.Credentials boardKeys = client.credentials();
            for (MixturePresets.Kind kind : MixturePresets.Kind.values()) {
                javax.swing.SwingUtilities.invokeAndWait(() -> {
                    MixturePanel board = new MixturePanel(boardKeys, (ChatService.MixtureTeam) null, config -> {}, () -> {});
                    if (!board.applyPreset(kind)) throw new AssertionError("preset could not be applied");
                    boardConfig.set(board.configuration()); board.removeNotify();
                });
                String configured = client.configureMixture(boardConfig.get()).model();
                String[] expected = kind == MixturePresets.Kind.EXPERT
                    ? new String[]{"deepseek-v4-pro", "gpt-5-mini", "gemini-4-argon", "claude-fable-5-1"}
                    : new String[]{"deepseek-flash", "gpt-5-mini", "gemini-3.8-flash", "claude-sonnet-5-5"};
                for (String model : expected) if (!configured.contains(model)) throw new AssertionError("preset lost API model: " + model);
            }
            javax.swing.SwingUtilities.invokeAndWait(() -> {
                MixturePanel board = new MixturePanel(boardKeys, (ChatService.MixtureTeam) null, config -> {}, () -> {});
                board.assignModel("claude-haiku", ChatService.MixtureStage.PLANNER, 0);
                board.assignModel("gemini-auto", ChatService.MixtureStage.WORKER, 0);
                board.assignModel("openai", ChatService.MixtureStage.REVIEWER, 0);
                board.assignModel("deepseek-pro", ChatService.MixtureStage.SUMMARIZER, 0);
                boardConfig.set(board.configuration()); board.removeNotify();
            });
            client.configureMixture(boardConfig.get()); progress.clear();
            try { client.chat("offline drag board"); throw new AssertionError("offline board unexpectedly succeeded"); }
            catch (IOException expected) { /* generated role goals travel through the real bridge */ }
            if (progress.stream().noneMatch(p -> p.role().equals("Planner") && p.model().equals("claude-haiku-5-5") && p.roleTitle().equals(I18n.t("规划")))) throw new AssertionError("drag board planner mapping lost");
            if (progress.stream().noneMatch(p -> p.role().equals("Reviewer") && p.roleTitle().equals(I18n.t("复核")))) throw new AssertionError("drag board reviewer mapping lost");
            ChatService.MixtureTeam customTeam = new ChatService.MixtureTeam(java.util.List.of(
                new ChatService.MixtureMember("claude-sonnet", ChatService.MixtureStage.PLANNER, "用户规划师🌊", "依据目标拆分"),
                new ChatService.MixtureMember("openai", ChatService.MixtureStage.WORKER, "用户开发者", "实现 Rust 接口"),
                new ChatService.MixtureMember("gemini-flash", ChatService.MixtureStage.REVIEWER, "用户复核者", "检查边界条件"),
                new ChatService.MixtureMember("deepseek-pro", ChatService.MixtureStage.SUMMARIZER, "用户编辑", "给出中文结论")), false);
            client.configureMixture(customTeam); progress.clear();
            try { client.chat("offline custom roles"); throw new AssertionError("offline custom roles succeeded"); }
            catch (IOException expected) { /* offline requests fail */ }
            if (progress.stream().noneMatch(p -> p.role().equals("Planner") && p.model().equals("claude-sonnet-5-5") && p.roleTitle().equals("用户规划师🌊"))) throw new AssertionError("custom planner or Unicode role lost");
            if (progress.stream().noneMatch(p -> p.role().equals("Reviewer") && p.roleTitle().equals("用户复核者"))) throw new AssertionError("custom reviewer lost");
            if (progress.stream().anyMatch(p -> p.role().equals("Summarizer") && !p.model().equals("deepseek-v4-pro"))) throw new AssertionError("unapproved role takeover");
            try { client.configureMixture(new ChatService.MixtureTeam(java.util.List.of(customTeam.members().get(0)), false)); throw new AssertionError("missing summarizer accepted"); }
            catch (IOException expected) { /* preserve current team on invalid config */ }
            java.util.List<Map<String,String>> savedSingle=java.util.List.of(Map.of("role","user","text","恢复中文问题\n🌊"),Map.of("role","assistant","text","恢复单模型回答"));
            java.util.List<Map<String,String>> savedMixture=java.util.List.of(Map.of("role","user","text","混合问题"),Map.of("role","assistant","text","混合回答"));
            if(!client.restoreConversation("deepseek-pro",savedSingle,savedMixture,customTeam).modelId().equals("deepseek-pro"))throw new AssertionError("single history restore failed");
            if(!client.restoreConversation("mixture",savedSingle,savedMixture,customTeam).model().contains("claude-sonnet-5-5"))throw new AssertionError("team restore failed");
            try{client.restoreConversation("unknown",savedSingle,savedMixture,null);throw new AssertionError("invalid restore model accepted");}catch(IOException expected){ /* previous context remains live */ }
            if(client.restoreConversation(null,savedSingle,savedMixture,customTeam)!=null)throw new AssertionError("read-only history selected a model");
            client.select("openai");
            try{client.restoreConversation("openai",savedSingle.subList(0,1),savedMixture,null);throw new AssertionError("unpaired context accepted");}catch(IOException expected){ /* malformed imports are transactional */ }
            client.reset();
            if (!client.select("deepseek").modelId().equals("deepseek")) throw new AssertionError("recovery failed");
            client.saveCredentials(Map.of("deepseek", "rotated-account-key"), true);
            try { client.chat("should require reselection"); throw new AssertionError("old agent survived key rotation"); } catch (IOException expected) { /* expected */ }
            if (!client.credentials().gemini()) throw new AssertionError("partial update erased another platform key");
        }
        try (BridgeClient reopened = new BridgeClient(Path.of(args[0]))) {
            reopened.initialize();
            if (!reopened.credentials().deepseek()) throw new AssertionError("saved account was not loaded");
            if (!reopened.credentials().claude()) throw new AssertionError("Claude key was not persisted");
            reopened.select("claude-opus");
            reopened.deleteCredentials("claude");
            try { reopened.select("claude-opus"); throw new AssertionError("deleted Claude key fell back to environment"); } catch (IOException expected) { /* expected */ }
            reopened.select("gemini-flash");
            reopened.deleteCredentials("gemini");
            try { reopened.select("gemini-flash"); throw new AssertionError("deleted key fell back to process environment"); } catch (IOException expected) { /* expected */ }
            Path file = Path.of(reopened.credentials().storagePath());
            reopened.deleteCredentials("all");
            if (Files.exists(file)) throw new AssertionError("credential file not deleted");
            java.util.List<Map<String,String>> history=java.util.List.of(Map.of("role","user","text","offline history"),Map.of("role","assistant","text","saved answer"));
            if(reopened.restoreConversation(null,history,java.util.List.of(),null)!=null)throw new AssertionError("history requires a credential");
        }
        System.out.println("PASS: Java/Rust account credentials, plaintext persistence, 0600 permissions, rotation, deletion, no developer-key fallback and restored conversation protocol");
    }
}
