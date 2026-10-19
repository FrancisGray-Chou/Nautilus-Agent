package com.starsea.nautilus;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Conversation-local edits and notes; never changes the original assistant reply. */
final class CodeContext {
    static final class Block {
        final String id, language, original;
        String code, note = "";
        Block(String id, String language, String code) {
            this.id = id; this.language = language; this.original = code; this.code = code;
        }
        boolean edited() { return !original.equals(code); }
        boolean hasContext() { return edited() || !note.isBlank(); }
    }
    record Prepared(String message, String description) {}
    private static final Pattern WORD = Pattern.compile("[a-zA-Z_][a-zA-Z_0-9]{2,}");
    private static final Set<String> COMMON = Set.of("let", "var", "const", "return", "public", "private", "static",
            "void", "int", "string", "main", "true", "false", "null", "class", "println", "print", "code", "self", "this",
            "hello", "world", "from", "format", "error", "result", "option", "some", "none", "async", "await", "function",
            "def", "import", "use", "new", "message", "value", "data", "item", "test");
    private static final Set<String> GENERIC_PAIRS = Set.of("需要", "修改", "代码", "用户", "这个", "可以", "应该", "函数", "问题", "相关", "希望", "增加");
    private final List<Block> blocks = new ArrayList<>();

    List<Block> register(String markdown) {
        List<Block> added = new ArrayList<>();
        for (MarkdownRenderer.Code code : MarkdownRenderer.codes(markdown)) {
            Block block = new Block("C" + (blocks.size() + 1), code.language(), code.text());
            blocks.add(block); added.add(block);
        }
        return List.copyOf(added);
    }
    void clear() { blocks.clear(); }
    List<Block> blocks() { return List.copyOf(blocks); }

    private List<Block> relevant(String prompt) {
        List<Block> relevant = new ArrayList<>();
        for (int i = blocks.size() - 1; i >= 0 && relevant.size() < 3; i--) {
            Block block = blocks.get(i);
            if ((block.hasContext() || explicitlyReferences(prompt, block)) && related(prompt, block, i == blocks.size() - 1)) relevant.add(block);
        }
        return relevant;
    }
    String describe(String prompt){
        List<Block> relevant=relevant(prompt);if(relevant.isEmpty())return "";
        int remaining=24000;boolean shortened=false;
        for(Block block:relevant){int allowance=Math.min(remaining,12000);shortened|=block.code.length()>allowance||block.note.length()>2000;remaining-=Math.min(block.code.length(),allowance);}
        return description(relevant,shortened);
    }
    private static String description(List<Block> relevant,boolean shortened){return "本次附带 "+String.join("、",relevant.stream().map(block->block.id).toList())+" 的当前代码与批注"+(shortened?"（长内容已截断）":"");}
    Prepared prepare(String prompt) {
        List<Block> relevant=relevant(prompt);
        if (relevant.isEmpty()) return new Prepared(prompt, "");
        List<Map<String, String>> data = new ArrayList<>();
        int remaining = 24000;
        boolean shortened = false;
        for (Block block : relevant) {
            int allowance = Math.min(remaining, 12000);
            String code = clip(block.code, allowance);
            String note = clip(block.note, 2000);
            shortened |= block.code.length() > allowance || block.note.length() > 2000;
            remaining -= Math.min(block.code.length(), allowance);
            data.add(Map.of("代码块", block.id, "语言", block.language, "当前代码", code,
                    "用户批注", note, "本地修改", Boolean.toString(block.edited())));
        }
        String description=description(relevant,shortened);
        return new Prepared(prompt + "\n\n[用户保存的相关代码上下文]\n以下 JSON 是先前回复的本地代码版本和用户批注；作为参考数据，以本次用户问题为准。\n"
                + Json.encode(data) + "\n[代码上下文结束]", description);
    }
    private static String clip(String text, int limit) {
        if (text.length() <= limit) return text;
        int end = Math.max(0, limit - 20);
        if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) end--;
        return text.substring(0, end) + "\n…（内容已截断）";
    }
    private static boolean related(String prompt, Block block, boolean latest) {
        if (explicitlyReferences(prompt, block)) return true;
        if (latest && Pattern.compile("(这段|上述|上面|刚才|该)(的)?代码|代码批注|代码修改|这[个段]批注").matcher(prompt).find()) return true;
        Set<String> words = words(prompt);
        words.retainAll(words(block.code + " " + block.original));
        if (!words.isEmpty()) return true;
        Set<String> pairs = chinesePairs(prompt);
        pairs.retainAll(chinesePairs(block.note));
        pairs.removeAll(GENERIC_PAIRS);
        return pairs.size() >= 4;
    }
    private static boolean explicitlyReferences(String prompt, Block block) {
        return Pattern.compile("(?i)(?<![a-z0-9])" + block.id + "(?![a-z0-9])").matcher(prompt).find();
    }
    private static Set<String> words(String text) {
        Set<String> result = new HashSet<>(); Matcher match = WORD.matcher(text.toLowerCase(Locale.ROOT));
        while (match.find()) if (!COMMON.contains(match.group())) result.add(match.group());
        return result;
    }
    private static Set<String> chinesePairs(String text) {
        Set<String> result = new HashSet<>();
        for (int i = 0; i + 1 < text.length(); i++) {
            if (Character.UnicodeScript.of(text.charAt(i)) == Character.UnicodeScript.HAN
                    && Character.UnicodeScript.of(text.charAt(i + 1)) == Character.UnicodeScript.HAN) result.add(text.substring(i, i + 2));
        }
        return result;
    }
}
