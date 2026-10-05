package dev.epoch.bridge;

import java.awt.geom.Rectangle2D;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Optional JourneyMap adapter. Reflection isolates API 1/2 and Minecraft's five type systems. */
public final class JourneyMapOrigins {
    private static Object api, fullscreen;
    private static String prefix;
    private static Class<?> apiType;
    private static final ExecutorService READS=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"Epoch map origins");t.setDaemon(true);return t;});
    private static Future<ChunkOrigins.View> pending;
    private static final List<Object> shown=new ArrayList<>();
    private static final Deque<ChunkOrigins.Area> publishing=new ArrayDeque<>();
    private static String world="", request="", shownRequest="";
    private static long nextRead;
    private static long retryAfter;
    private static boolean enabled=true, active, limited;
    public static void initialize(Object clientApi, boolean modernApi) {
        try {
            api=clientApi;prefix=modernApi?"journeymap.api.v2.client.":"journeymap.client.api.";
            apiType=Class.forName(prefix+"IClientAPI");
            Class<?> ui=Class.forName(modernApi?"journeymap.api.v2.common.Context$UI":prefix+"display.Context$UI");
            fullscreen=Enum.valueOf((Class)ui,"Fullscreen");
        } catch(Exception error){api=null;System.err.println("Epoch JourneyMap initialization: "+error);}
    }
    public static boolean active(){return active&&api!=null;}
    public static boolean enabled(){return enabled;}
    public static boolean limited(){return limited;}
    public static void toggle(){enabled=!enabled;nextRead=0;shownRequest="";}
    public static String legend(String version){return ChunkOrigins.UNKNOWN.equals(version)?"来源未记录":version;}
    private static Object invoke(Object object,String name,Object... args)throws Exception {
        for(Method m:object.getClass().getMethods())if(m.getName().equals(name)&&m.getParameterTypes().length==args.length) {
            boolean match=true;Class<?>[] types=m.getParameterTypes();
            for(int i=0;i<args.length;i++)if(args[i]!=null&&!types[i].isPrimitive()&&!types[i].isInstance(args[i]))match=false;
            if(match)return m.invoke(object,args);
        }
        throw new NoSuchMethodException(name);
    }
    private static Object field(Object object,String name)throws Exception{return object.getClass().getField(name).get(object);}
    private static double coordinate(Object bounds,String... names)throws Exception {
        for(String name:names)try{return ((Number)field(bounds,name)).doubleValue();}catch(NoSuchFieldException ignored){}
        throw new NoSuchFieldException(Arrays.toString(names));
    }
    private static void clear()throws Exception {
        if(!shown.isEmpty()){apiType.getMethod("removeAll",String.class).invoke(api,"epoch");shown.clear();}
        shownRequest="";
        publishing.clear();
    }
    public static void tick(Map<String,Object> diagnostics,boolean inWorld) {
        if(api==null)return;
        try {
            Object state=apiType.getMethod("getUIState",fullscreen.getClass()).invoke(api,fullscreen);
            active=inWorld&&state!=null&&Boolean.TRUE.equals(field(state,"active"));
            String binding=inWorld?String.valueOf(diagnostics.get("streamingBinding")):"";
            if(!binding.equals(world)){clear();world=binding;request="";if(pending!=null)pending.cancel(true);pending=null;nextRead=0;}
            if(!active||!enabled){clear();return;}
            Object dimension=field(state,"dimension");
            boolean overworld=dimension instanceof Number?((Number)dimension).intValue()==0:dimension.toString().contains("minecraft:overworld");
            if(!overworld||binding.equals("null")||binding.isEmpty()){clear();return;}
            if(System.currentTimeMillis()<retryAfter)return;
            Object bounds=field(state,"blockBounds");if(bounds==null)return;
            int x0=(int)Math.floor(coordinate(bounds,"minX","field_72340_a","f_82288_")/16);
            int z0=(int)Math.floor(coordinate(bounds,"minZ","field_72339_c","f_82290_")/16);
            int x1=(int)Math.floor(coordinate(bounds,"maxX","field_72336_d","f_82291_")/16);
            int z1=(int)Math.floor(coordinate(bounds,"maxZ","field_72334_f","f_82293_")/16);
            String view=binding+":"+x0+","+z0+","+x1+","+z1;
            if(pending!=null&&pending.isDone()) {
                ChunkOrigins.View result=pending.get();pending=null;
                if(request.equals(view)) {
                    clear();
                    publishing.addAll(result.areas);
                    shownRequest=view;diagnostics.put("mapOriginChunks",result.chunks);
                    limited=result.limited;
                    diagnostics.put("mapOriginOverlays",shown.size());diagnostics.put("mapOriginLimited",result.limited);
                    diagnostics.remove("mapOriginError");
                }
            }
            long started=System.nanoTime();int added=0;
            while(!publishing.isEmpty()&&added++<32&&System.nanoTime()-started<2000000)show(publishing.removeFirst(),dimension);
            diagnostics.put("mapOriginOverlays",shown.size());
            if(pending==null&&publishing.isEmpty()&&(System.currentTimeMillis()>=nextRead||!view.equals(shownRequest))) {
                request=view;nextRead=System.currentTimeMillis()+5000;
                pending=READS.submit(()->ChunkOrigins.read(Paths.get(binding),x0,z0,x1,z1));
            }
            diagnostics.put("mapOriginsReady",true);
        } catch(Exception error) {
            diagnostics.put("mapOriginError",error.toString());pending=null;publishing.clear();retryAfter=System.currentTimeMillis()+10000;
            // A map addon failure must never stop simulation or save processing.
        }
    }
    private static void show(ChunkOrigins.Area area,Object dimension)throws Exception {
        Class<?> shapes=Class.forName(prefix+"model.ShapeProperties");Object shape=shapes.newInstance();
        int color=ChunkOrigins.COLORS.get(area.version);
        invoke(shape,"setFillColor",color);invoke(shape,"setFillOpacity",.24f);
        invoke(shape,"setStrokeColor",color);invoke(shape,"setStrokeOpacity",.55f);invoke(shape,"setStrokeWidth",1);
        java.awt.geom.Area rectangle=new java.awt.geom.Area(new Rectangle2D.Double(area.x0*16.,area.z0*16.,(area.x1-area.x0)*16.,(area.z1-area.z0)*16.));
        List<?> polygons=(List<?>)Class.forName(prefix+"util.PolygonHelper").getMethod("createPolygonFromArea",java.awt.geom.Area.class,int.class).invoke(null,rectangle,64);
        for(Object polygon:polygons) {
            Object overlay=null;Class<?> type=Class.forName(prefix+"display.PolygonOverlay");
            String id="origin:"+area.x0+":"+area.z0+":"+area.x1;
            for(Constructor<?> c:type.getConstructors()) {
                Class<?>[] params=c.getParameterTypes();
                if(params.length==4&&params[3].isInstance(polygon))overlay=c.newInstance("epoch",dimension,shape,polygon);
                if(params.length==5&&params[1]==String.class&&params[4].isInstance(polygon))overlay=c.newInstance("epoch",id,dimension,shape,polygon);
            }
            if(overlay==null)throw new NoSuchMethodException("PolygonOverlay");
            invoke(overlay,"setOverlayGroupName","Epoch · "+legend(area.version));
            invoke(overlay,"setTitle","区块来源 · "+legend(area.version)+" ["+area.x0+", "+area.z0+"]");
            if(prefix.startsWith("journeymap.api.v2")) {
                Object array=Array.newInstance(fullscreen.getClass(),1);Array.set(array,0,fullscreen);
                invoke(overlay,"setActiveUIs",array);
            } else invoke(overlay,"setActiveUIs",EnumSet.of((Enum)fullscreen));
            apiType.getMethod("show",Class.forName(prefix+"display.Displayable")).invoke(api,overlay);shown.add(overlay);
        }
    }
}
