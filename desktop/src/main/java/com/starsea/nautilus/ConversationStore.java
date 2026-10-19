package com.starsea.nautilus;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;

/** One atomic, versioned JSON file per conversation; contains no credentials. */
final class ConversationStore {
    record Entry(String role,String text,boolean user,boolean error,String modelId,String provider,String prompt,boolean committed,String time) {
        Map<String,Object> json(){return Map.of("role",role,"text",text,"user",user,"error",error,"model_id",modelId,"provider",provider,"prompt",prompt,"committed",committed,"time",time);}
    }
    record Session(String id,long updated,String modelId,ChatService.MixtureTeam team,List<Entry> entries,List<Map<String,String>> edits,String draft) {
        Session {entries=List.copyOf(entries);edits=List.copyOf(edits);}
        String title(){String title=entries.stream().filter(Entry::user).map(Entry::text).findFirst().orElse(I18n.t("新对话")).lines().findFirst().orElse("");return title.codePointCount(0,title.length())>80?title.substring(0,title.offsetByCodePoints(0,80))+"…":title;}
        String models(){return String.join(" · ",entries.stream().filter(e->e.user()&&!e.provider().isBlank()).map(Entry::provider).distinct().toList());}
        Map<String,Object> json(){
            Map<String,Object> value=new LinkedHashMap<>();value.put("version",1);value.put("id",id);value.put("updated",updated);value.put("model_id",modelId);
            value.put("team",teamJson(team));value.put("entries",entries.stream().map(Entry::json).toList());value.put("edits",edits);value.put("draft",draft);return value;
        }
        List<Map<String,String>> context(boolean mixture){
            List<Map<String,String>> result=new ArrayList<>();
            for(Entry entry:entries)if(entry.committed()&&!entry.error()&&entry.modelId().equals("mixture")==mixture)
                result.add(Map.of("role",entry.user()?"user":"assistant","text",entry.user()?entry.prompt():entry.text()));
            return List.copyOf(result);
        }
    }
    private final Path directory;
    ConversationStore(){this(System.getProperty("nautilus.history.path")==null?I18n.preferencePath().resolveSibling("conversations"):Path.of(System.getProperty("nautilus.history.path")));}
    ConversationStore(Path directory){this.directory=directory.toAbsolutePath();}
    List<Session> list() throws IOException {
        if(!Files.exists(directory))return List.of();validateDirectory();List<Session> result=new ArrayList<>();
        try(DirectoryStream<Path> files=Files.newDirectoryStream(directory,"*.json")) {
            for(Path path:files)try{if(!Files.isSymbolicLink(path)){Session session=decode(Json.object(Files.readString(path)));if(file(session.id()).equals(path))result.add(session);}}
                catch(IOException | IllegalArgumentException ignored){ /* Keep unreadable records intact; other conversations remain available. */ }
        }
        result.sort(Comparator.comparingLong(Session::updated).reversed());return List.copyOf(result);
    }
    void save(Session session) throws IOException {
        if(session.entries().isEmpty())return;
        Files.createDirectories(directory);validateDirectory();permissions(directory,"rwx------");
        Path target=file(session.id());if(Files.isSymbolicLink(target))throw new IOException("历史文件不能是符号链接");
        Path temporary=Files.createTempFile(directory,"conversation-",".tmp");
        try {
            permissions(temporary,"rw-------");Files.writeString(temporary,Json.encode(session.json()));
            try{Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
            catch(AtomicMoveNotSupportedException ex){Files.move(temporary,target,StandardCopyOption.REPLACE_EXISTING);}
        } finally{Files.deleteIfExists(temporary);}
    }
    void delete(String id)throws IOException{if(!Files.exists(directory))return;validateDirectory();Files.deleteIfExists(file(id));}
    private Path file(String id)throws IOException{try{if(!UUID.fromString(id).toString().equals(id))throw new IllegalArgumentException();return directory.resolve(id+".json");}catch(IllegalArgumentException ex){throw new IOException("无效历史编号",ex);}}
    private void validateDirectory()throws IOException{if(Files.isSymbolicLink(directory)||!Files.isDirectory(directory,LinkOption.NOFOLLOW_LINKS))throw new IOException("无效历史目录");}
    private static void permissions(Path path,String permissions)throws IOException{if(Files.getFileAttributeView(path,java.nio.file.attribute.PosixFileAttributeView.class)!=null)Files.setPosixFilePermissions(path,PosixFilePermissions.fromString(permissions));}
    static Object teamJson(ChatService.MixtureTeam team){return team==null?null:Map.of("members",team.members().stream().map(m->Map.of("model_id",m.modelId(),"stage",m.stage().id,"role",m.role(),"goal",m.goal())).toList(),"allow_role_fallback",team.allowRoleFallback());}
    private static Session decode(Map<String,Object> value)throws IOException{
        if(!(value.get("version") instanceof Number version)||version.intValue()!=1)throw new IOException("不支持的历史版本");
        List<Entry> entries=new ArrayList<>();
        for(Object item:list(value,"entries")){Map<?,?> e=map(item);entries.add(new Entry(text(e,"role"),text(e,"text"),bool(e,"user"),bool(e,"error"),text(e,"model_id"),text(e,"provider"),text(e,"prompt"),bool(e,"committed"),e.get("time") instanceof String time?time:""));}
        ChatService.MixtureTeam team=null;
        if(value.get("team")!=null){Map<?,?> t=map(value.get("team"));List<ChatService.MixtureMember> members=new ArrayList<>();for(Object item:list(t,"members")){Map<?,?> m=map(item);ChatService.MixtureStage stage=Arrays.stream(ChatService.MixtureStage.values()).filter(s->s.id.equals(m.get("stage"))).findFirst().orElseThrow(()->new IOException("无效协作阶段"));members.add(new ChatService.MixtureMember(text(m,"model_id"),stage,text(m,"role"),text(m,"goal")));}team=new ChatService.MixtureTeam(members,bool(t,"allow_role_fallback"));}
        List<Map<String,String>> edits=new ArrayList<>();for(Object item:list(value,"edits")){Map<?,?> edit=map(item);edits.add(Map.of("id",text(edit,"id"),"code",text(edit,"code"),"note",text(edit,"note")));}
        if(!(value.get("updated") instanceof Number updated))throw new IOException("无效历史日期");
        return new Session(text(value,"id"),updated.longValue(),text(value,"model_id"),team,entries,edits,text(value,"draft"));
    }
    private static String text(Map<?,?> value,String key)throws IOException{if(value.get(key) instanceof String s)return s;throw new IOException("历史缺少字段 "+key);}
    private static boolean bool(Map<?,?> value,String key)throws IOException{if(value.get(key) instanceof Boolean b)return b;throw new IOException("历史缺少字段 "+key);}
    private static List<?> list(Map<?,?> value,String key)throws IOException{if(value.get(key) instanceof List<?> l)return l;throw new IOException("无效历史列表");}
    private static Map<?,?> map(Object value)throws IOException{if(value instanceof Map<?,?> m)return m;throw new IOException("无效历史记录");}
}
