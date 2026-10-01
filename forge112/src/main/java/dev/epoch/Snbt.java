package dev.epoch;

import net.minecraft.nbt.JsonToNBT;
import net.minecraft.nbt.NBTException;
import net.minecraft.nbt.NBTTagCompound;

/** 1.12 understands every snapshot NBT type, but its lexer only accepts double quotes. */
final class Snbt {
 private Snbt(){}
 static NBTTagCompound compound(String text)throws NBTException{return JsonToNBT.getTagFromJson(doubleQuoted(text));}
 static String doubleQuoted(String text)throws NBTException{
  StringBuilder out=new StringBuilder(text.length());
  for(int i=0;i<text.length();){
   char quote=text.charAt(i++);
   if(quote!='\''&&quote!='"'){out.append(quote);continue;}
   StringBuilder value=new StringBuilder();boolean closed=false;
   while(i<text.length()){
    char c=text.charAt(i++);
    if(c==quote){closed=true;break;}
    if(c=='\\'){
     if(i==text.length())throw new NBTException("Unterminated SNBT escape",text,i);
     c=text.charAt(i++);
     if(c!=quote&&c!='\\')throw new NBTException("Invalid SNBT escape",text,i-1);
    }
    value.append(c);
   }
   if(!closed)throw new NBTException("Unterminated SNBT string",text,i);
   out.append('"');
   for(int n=0;n<value.length();n++){char c=value.charAt(n);if(c=='"'||c=='\\')out.append('\\');out.append(c);}
   out.append('"');
  }
  return out.toString();
 }
}
