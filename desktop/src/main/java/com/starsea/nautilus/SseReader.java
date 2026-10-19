package com.starsea.nautilus;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.Map;

/** Reads complete SSE events across arbitrary network packet boundaries. */
final class SseReader {
    record Event(String name, Map<String,Object> data) {}
    private SseReader() {}
    static Event next(BufferedReader reader) throws IOException {
        String name="message";StringBuilder data=new StringBuilder();
        for(String line;(line=reader.readLine())!=null;) {
            if(line.isEmpty()) {
                if(data.length()>0)return new Event(name,Json.object(data.toString()));
                name="message";continue;
            }
            if(line.startsWith(":"))continue;
            int colon=line.indexOf(':');String field=colon<0?line:line.substring(0,colon);
            String value=colon<0?"":line.substring(colon+1);if(value.startsWith(" "))value=value.substring(1);
            if(field.equals("event"))name=value;
            if(field.equals("data")) {if(data.length()>0)data.append('\n');data.append(value);if(data.length()>16_777_216)throw new IOException("SSE event too large");}
        }
        // An interrupted frame is not a completed event or a successful reply.
        return null;
    }
}
