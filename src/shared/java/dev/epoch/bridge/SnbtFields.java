package dev.epoch.bridge;

import java.io.IOException;
import java.util.*;

/** Lossless compound editor: unmodified values (including typed arrays/longs) stay opaque. */
public final class SnbtFields {
    private final Map<String, String> fields = new LinkedHashMap<String, String>();
    public static SnbtFields parse(String text) throws IOException {
        Cursor c = new Cursor(text); c.expect('{'); SnbtFields result = new SnbtFields();
        if (c.peek() != '}') while (true) {
            String key = c.key(); c.expect(':'); c.ws(); int start = c.at;
            c.value(0); String raw = text.substring(start, c.at).trim();
            if (result.fields.put(key, raw) != null) throw new IOException("Duplicate SNBT key: " + key);
            if (c.peek() != ',') break;
            c.at++; if (c.peek() == '}') break;
        }
        c.expect('}'); c.ws(); if (c.at != text.length()) throw new IOException("Trailing SNBT");
        return result;
    }
    public SnbtFields copy() { SnbtFields r = new SnbtFields(); r.fields.putAll(fields); return r; }
    public Set<String> keys() { return Collections.unmodifiableSet(fields.keySet()); }
    public boolean has(String key) { return fields.containsKey(key); }
    public String raw(String key) { return fields.get(key); }
    public void raw(String key, String value) { if (value == null) fields.remove(key); else fields.put(key, value); }
    public void put(String key, String value) { raw(key, quote(value)); }
    public void number(String key, int value) { raw(key, Integer.toString(value)); }
    public void remove(String key) { fields.remove(key); }
    public boolean empty() { return fields.isEmpty(); }
    public String get(String key) throws IOException {
        String value = fields.get(key); if (value == null) return "";
        if (value.startsWith("\"") || value.startsWith("'")) {
            Cursor c = new Cursor(value); String s = c.quoted(); c.ws();
            if (c.at != value.length()) throw new IOException("Invalid SNBT string"); return s;
        }
        return value;
    }
    public int integer(String key, int fallback) throws IOException {
        if (!has(key)) return fallback;
        String s = get(key); if (s.matches("[-+]?[0-9]+[bBsSlL]")) s = s.substring(0, s.length()-1);
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { throw new IOException("Invalid integer " + key, e); }
    }
    public SnbtFields compound(String key) throws IOException { return has(key) ? parse(raw(key)) : new SnbtFields(); }
    public static List<String> list(String text) throws IOException {
        Cursor c=new Cursor(text);c.expect('[');List<String> out=new ArrayList<String>();
        if(c.peek()!=']')while(true){c.ws();int start=c.at;c.value(0);out.add(text.substring(start,c.at).trim());if(c.peek()!=',')break;c.at++;}
        c.expect(']');c.ws();if(c.at!=text.length())throw new IOException("Trailing list data");return out;
    }
    public static String quote(String value) { return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""; }
    public String toString() {
        StringBuilder out = new StringBuilder("{");
        for (Map.Entry<String, String> e : fields.entrySet()) { if (out.length()>1) out.append(','); out.append(quote(e.getKey())).append(':').append(e.getValue()); }
        return out.append('}').toString();
    }
    private static final class Cursor {
        final String text; int at;
        Cursor(String text) throws IOException { if (text == null || text.length()>16*1024*1024) throw new IOException("Invalid SNBT size"); this.text=text; }
        void ws() { while(at<text.length() && Character.isWhitespace(text.charAt(at))) at++; }
        char peek() throws IOException { ws(); if(at==text.length()) throw new IOException("Unexpected SNBT end"); return text.charAt(at); }
        void expect(char c) throws IOException { if(peek()!=c) throw new IOException("Expected " + c + " at " + at); at++; }
        String quoted() throws IOException {
            char quote=peek(); at++; StringBuilder out=new StringBuilder();
            while(at<text.length()) { char ch=text.charAt(at++); if(ch==quote)return out.toString();
                if(ch=='\\') { if(at==text.length())throw new IOException("Bad escape"); ch=text.charAt(at++); if(ch!=quote && ch!='\\')throw new IOException("Invalid SNBT escape"); } out.append(ch); }
            throw new IOException("Unterminated SNBT string");
        }
        String key() throws IOException {
            char ch=peek(); if(ch=='\"'||ch=='\'')return quoted(); int start=at;
            while(at<text.length() && "{}[]:,".indexOf(text.charAt(at))<0 && !Character.isWhitespace(text.charAt(at)))at++;
            if(at==start)throw new IOException("Missing key"); return text.substring(start,at);
        }
        void value(int depth) throws IOException {
            if(depth>128)throw new IOException("SNBT nesting too deep"); char ch=peek();
            if(ch=='\"'||ch=='\'') { quoted(); return; }
            if(ch=='{' || ch=='[') {
                char close=ch=='{'?'}':']'; at++;
                while(peek()!=close) {
                    char next=peek(); if(next=='}'||next==']')throw new IOException("Mismatched SNBT delimiter");
                    if(next==','||next==':'||next==';')at++; else value(depth+1);
                } at++; return;
            }
            int start=at;
            while(at<text.length() && "{}[],:;".indexOf(text.charAt(at))<0 && !Character.isWhitespace(text.charAt(at)))at++;
            if(at==start)throw new IOException("Missing value");
        }
    }
}
