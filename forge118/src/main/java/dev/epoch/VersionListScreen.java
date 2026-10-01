package dev.epoch;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.epoch.bridge.AutoRuntime;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.TextComponent;
import java.util.Collections;
import java.util.List;

/** Uses vanilla controls; paging also works through the mouse wheel. */
public final class VersionListScreen extends Screen {
 private List<AutoRuntime.Instance> entries=Collections.emptyList();
 private int first,visibleRows;
 private long nextRefresh;
 private String error="",signature="";
 public VersionListScreen(){super(new TextComponent("Epoch - Switch version"));}
 public boolean isPauseScreen(){return false;}
 protected void init(){reload();}
 private void reload(){
  entries=AutoRuntime.instances(minecraft.gameDirectory.toPath());
  signature=new com.google.gson.Gson().toJson(entries);
  visibleRows=Math.max(1,(height-122)/40);
  first=Math.max(0,Math.min(first,Math.max(0,entries.size()-visibleRows)));
  clearWidgets();
  int rowWidth=Math.min(430,width-32),left=(width-rowWidth)/2;
  for(int i=first;i<entries.size()&&i<first+visibleRows;i++){
   AutoRuntime.Instance entry=entries.get(i);int y=48+(i-first)*40;
   String label=entry.label+" | "+entry.version+" / Forge "+entry.forgeVersion+(entry.current?" [Current]":!entry.supported?" [Unavailable]":"");
   Button button=addRenderableWidget(new Button(left,y,rowWidth,20,new TextComponent(font.plainSubstrByWidth(label,rowWidth-12)),b->choose(entry)));
   button.active=entry.supported&&!entry.current;
  }
  int bottom=height-28;
  Button previous=addRenderableWidget(new Button(left,bottom,28,20,new TextComponent("<"),b->page(-visibleRows)));previous.active=first>0;
  Button next=addRenderableWidget(new Button(left+32,bottom,28,20,new TextComponent(">"),b->page(visibleRows)));next.active=first+visibleRows<entries.size();
  addRenderableWidget(new Button(width/2-45,bottom,90,20,new TextComponent("Refresh"),b->{AutoRuntime.refresh(minecraft.gameDirectory.toPath());error="";reload();}));
  addRenderableWidget(new Button(left+rowWidth-80,bottom,80,20,new TextComponent("Cancel"),b->onClose()));
  nextRefresh=System.currentTimeMillis()+500;
 }
 private void choose(AutoRuntime.Instance entry){
  try{AutoRuntime.select(minecraft.gameDirectory.toPath(),Epoch.VERSION,entry.id);onClose();}catch(Exception ex){error=ex.getMessage()==null?ex.toString():ex.getMessage();reload();}
 }
 private void page(int delta){first=Math.max(0,Math.min(first+delta,Math.max(0,entries.size()-visibleRows)));reload();}
 public boolean mouseScrolled(double x,double y,double delta){if(delta!=0){page(delta>0?-1:1);return true;}return super.mouseScrolled(x,y,delta);}
 public void tick(){
  if(System.currentTimeMillis()>=nextRefresh){nextRefresh=System.currentTimeMillis()+500;List<AutoRuntime.Instance> latest=AutoRuntime.instances(minecraft.gameDirectory.toPath());if(!signature.equals(new com.google.gson.Gson().toJson(latest)))reload();}
 }
 public void render(PoseStack stack,int mouseX,int mouseY,float partial){
  renderBackground(stack);
  drawCenteredString(stack,font,title,width/2,14,0xF0F0EA);
  drawCenteredString(stack,font,"Choose a local Forge instance",width/2,30,0xB8C1C8);
  int rowWidth=Math.min(430,width-32),left=(width-rowWidth)/2;
  for(int i=first;i<entries.size()&&i<first+visibleRows;i++){
   AutoRuntime.Instance entry=entries.get(i);
   String detail=entry.current?"You are playing this instance":entry.supported?"Select to prepare this version":entry.reason;
   drawString(stack,font,font.plainSubstrByWidth(detail==null?"Unavailable":detail,rowWidth),left+4,71+(i-first)*40,entry.supported?0xB8C1C8:0xD4A080);
  }
  if(entries.isEmpty())drawCenteredString(stack,font,"No local instances found. Use Refresh to scan again.",width/2,58,0xB8C1C8);
  String status=error.isEmpty()?AutoRuntime.statusText(minecraft.gameDirectory.toPath()):error;
  drawCenteredString(stack,font,font.plainSubstrByWidth(status==null?"":status,width-24),width/2,height-55,error.isEmpty()?0xB8C1C8:0xF0A090);
  if(!entries.isEmpty())drawCenteredString(stack,font,(first+1)+"-"+Math.min(entries.size(),first+visibleRows)+" / "+entries.size(),width/2,height-42,0xB8C1C8);
  super.render(stack,mouseX,mouseY,partial);
 }
}
