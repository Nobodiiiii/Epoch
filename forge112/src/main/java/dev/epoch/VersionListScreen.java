package dev.epoch;

import dev.epoch.bridge.AutoRuntime;
import net.minecraft.client.gui.*;
import java.util.*;
import java.io.IOException;
import org.lwjgl.input.Mouse;

public final class VersionListScreen extends GuiScreen {
 private List<AutoRuntime.Instance> entries=Collections.emptyList();
 private int first,visibleRows;private long nextRefresh;private String error="",signature="";
 public boolean doesGuiPauseGame(){return false;}
 public void initGui(){reload();}
 private void reload(){entries=AutoRuntime.instances(mc.gameDir.toPath());signature=new com.google.gson.Gson().toJson(entries);visibleRows=Math.max(1,(height-122)/40);first=Math.max(0,Math.min(first,Math.max(0,entries.size()-visibleRows)));buttonList.clear();int rowWidth=Math.min(430,width-32),left=(width-rowWidth)/2;
  for(int n=first;n<entries.size()&&n<first+visibleRows;n++){AutoRuntime.Instance entry=entries.get(n);String label=entry.label+" | "+entry.version+" / Forge "+entry.forgeVersion+(entry.current?" [Current]":!entry.supported?" [Unavailable]":"");GuiButton button=new GuiButton(100+n,left,48+(n-first)*40,rowWidth,20,fontRenderer.trimStringToWidth(label,rowWidth-12));button.enabled=entry.supported&&!entry.current;buttonList.add(button);}
  GuiButton previous=new GuiButton(1,left,height-28,28,20,"<");previous.enabled=first>0;buttonList.add(previous);GuiButton next=new GuiButton(2,left+32,height-28,28,20,">");next.enabled=first+visibleRows<entries.size();buttonList.add(next);buttonList.add(new GuiButton(3,width/2-45,height-28,90,20,"Refresh"));buttonList.add(new GuiButton(4,left+rowWidth-80,height-28,80,20,"Cancel"));nextRefresh=System.currentTimeMillis()+500;
 }
 protected void actionPerformed(GuiButton button){if(button.id>=100){try{ClientHooks.select(entries.get(button.id-100).id);}catch(Exception ex){error=ex.getMessage();reload();}}else if(button.id==1)page(-visibleRows);else if(button.id==2)page(visibleRows);else if(button.id==3){AutoRuntime.refresh(mc.gameDir.toPath());error="";reload();}else if(button.id==4)mc.displayGuiScreen(null);}
 private void page(int delta){first=Math.max(0,Math.min(first+delta,Math.max(0,entries.size()-visibleRows)));reload();}
 public void handleMouseInput()throws IOException{super.handleMouseInput();int delta=Mouse.getEventDWheel();if(delta!=0)page(delta>0?-1:1);}
 public void updateScreen(){if(System.currentTimeMillis()>=nextRefresh){nextRefresh=System.currentTimeMillis()+500;if(!signature.equals(new com.google.gson.Gson().toJson(AutoRuntime.instances(mc.gameDir.toPath()))))reload();}}
 public void drawScreen(int x,int y,float partial){drawDefaultBackground();drawCenteredString(fontRenderer,"Epoch - Switch version",width/2,14,0xF0F0EA);drawCenteredString(fontRenderer,"Choose a local Forge instance",width/2,30,0xB8C1C8);int rowWidth=Math.min(430,width-32),left=(width-rowWidth)/2;
  for(int n=first;n<entries.size()&&n<first+visibleRows;n++){AutoRuntime.Instance entry=entries.get(n);String detail=entry.current?"You are playing this instance":entry.supported?"Select to prepare this version":entry.reason;drawString(fontRenderer,fontRenderer.trimStringToWidth(detail==null?"Unavailable":detail,rowWidth),left+4,71+(n-first)*40,entry.supported?0xB8C1C8:0xD4A080);}
  if(entries.isEmpty())drawCenteredString(fontRenderer,"No local instances found. Use Refresh to scan again.",width/2,58,0xB8C1C8);String status=error.isEmpty()?AutoRuntime.statusText(mc.gameDir.toPath()):error;drawCenteredString(fontRenderer,fontRenderer.trimStringToWidth(status==null?"":status,width-24),width/2,height-55,error.isEmpty()?0xB8C1C8:0xF0A090);if(!entries.isEmpty())drawCenteredString(fontRenderer,(first+1)+"-"+Math.min(entries.size(),first+visibleRows)+" / "+entries.size(),width/2,height-42,0xB8C1C8);super.drawScreen(x,y,partial);
 }
}

