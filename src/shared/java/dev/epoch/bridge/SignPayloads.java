package dev.epoch.bridge;

import com.google.gson.*;
import java.io.IOException;
import java.util.*;

/** Sign text migration with explicit downgrade checks for backs, filtering, wax and styles. */
final class SignPayloads {
    private SignPayloads() {}
    private static String component(String text) {
        try {JsonElement e=new JsonParser().parse(text);if(e.isJsonObject()||e.isJsonArray())return e.toString();if(e.isJsonPrimitive()&&e.getAsJsonPrimitive().isString()){JsonObject value=new JsonObject();value.addProperty("text",e.getAsString());return value.toString();}}catch(RuntimeException ignored){}
        JsonObject value=new JsonObject();value.addProperty("text",text);return value.toString();
    }
    private static String plain(String text) {
        try {
            JsonElement e=new JsonParser().parse(text);
            if(e.isJsonPrimitive()&&e.getAsJsonPrimitive().isString())return e.getAsString();
            if(e.isJsonObject()&&e.getAsJsonObject().entrySet().size()==1&&e.getAsJsonObject().has("text")&&e.getAsJsonObject().get("text").isJsonPrimitive())return e.getAsJsonObject().get("text").getAsString();
        }catch(RuntimeException ignored){}
        return null;
    }
    private static boolean off(SnbtFields n,String key)throws IOException {if(!n.has(key)||n.get(key).equals("false"))return true;if(n.get(key).equals("true"))return false;return n.integer(key,-1)==0;}
    private static List<String> messages(SnbtFields side,String key)throws IOException {
        if(!side.has(key))return null;List<String> raw=SnbtFields.list(side.raw(key));if(raw.size()!=4)return null;
        List<String> out=new ArrayList<String>();for(String value:raw){SnbtFields wrap=SnbtFields.parse("{v:"+value+"}");out.add(wrap.get("v"));}return out;
    }
    /** Null means not losslessly representable. Native payload preservation remains the final check. */
    static SnbtFields project(SnbtFields input,String target)throws IOException {
        SnbtFields n=input.copy();boolean modern=target.equals("1.20.1");
        if(n.has("front_text")&&!modern&&!target.equals("canonical")) {
            SnbtFields front=n.compound("front_text"),back=n.compound("back_text");
            Set<String> sideKeys=new HashSet<String>(Arrays.asList("messages","filtered_messages","color","has_glowing_text"));
            if(!sideKeys.containsAll(front.keys())||!sideKeys.containsAll(back.keys())||!off(n,"is_waxed"))return null;
            List<String> behind=messages(back,"messages"),filteredBack=messages(back,"filtered_messages");
            if(behind==null)return null;for(String text:behind)if(!"".equals(plain(text)))return null;
            if(filteredBack!=null)for(String text:filteredBack)if(!"".equals(plain(text)))return null;
            if(back.has("color")&&!back.get("color").equals("black")||!off(back,"has_glowing_text"))return null;
            List<String> lines=messages(front,"messages"),filtered=messages(front,"filtered_messages");if(lines==null)return null;
            for(int i=0;i<4;i++){n.put("Text"+(i+1),lines.get(i));if(filtered!=null)n.put("FilteredText"+(i+1),filtered.get(i));}
            n.put("Color",front.has("color")?front.get("color"):"black");n.raw("GlowingText",off(front,"has_glowing_text")?"0b":"1b");
            n.remove("front_text");n.remove("back_text");n.remove("is_waxed");
        }
        if(!n.has("front_text")) {
            for(int i=1;i<=4;i++)n.put("Text"+i,component(n.get("Text"+i)));
            if(modern) {
                SnbtFields front=new SnbtFields(),back=new SnbtFields();List<String> lines=new ArrayList<String>(),filtered=new ArrayList<String>();
                boolean hasFilter=false;
                for(int i=1;i<=4;i++){lines.add(SnbtFields.quote(n.get("Text"+i)));String key="FilteredText"+i;hasFilter|=n.has(key);filtered.add(SnbtFields.quote(n.has(key)?n.get(key):n.get("Text"+i)));n.remove("Text"+i);n.remove(key);}
                front.raw("messages","["+String.join(",",lines)+"]");if(hasFilter)front.raw("filtered_messages","["+String.join(",",filtered)+"]");
                front.put("color",n.has("Color")?n.get("Color"):"black");front.raw("has_glowing_text",off(n,"GlowingText")?"0b":"1b");
                String empty=SnbtFields.quote(component(""));back.raw("messages","["+empty+","+empty+","+empty+","+empty+"]");back.put("color","black");back.raw("has_glowing_text","0b");
                n.remove("Color");n.remove("GlowingText");n.raw("front_text",front.toString());n.raw("back_text",back.toString());n.raw("is_waxed","0b");
            } else if(target.equals("1.7.10")||target.equals("1.12.2")||target.equals("1.16.5")) {
                if(!off(n,"GlowingText"))return null;n.remove("GlowingText");
                for(int i=1;i<=4;i++)if(n.has("FilteredText"+i)){if(!n.get("FilteredText"+i).equals(n.get("Text"+i)))return null;n.remove("FilteredText"+i);}
                if(!target.equals("1.16.5")){if(n.has("Color")&&!n.get("Color").equals("black"))return null;n.remove("Color");}
                if(target.equals("1.7.10"))for(int i=1;i<=4;i++){String text=plain(n.get("Text"+i));if(text==null)return null;n.put("Text"+i,text);}
            }
        }
        return n;
    }
}
