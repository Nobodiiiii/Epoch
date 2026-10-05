package dev.epoch.bridge;

import java.util.Map;

/** Small map-only legend; color is always paired with version text. */
public final class MapOriginLegend {
    public interface Painter {
        void fill(int x0,int y0,int x1,int y1,int argb);
        void text(String text,int x,int y,int argb);
    }
    private static boolean keyDown;
    public static void draw(Painter painter,int width,int height,boolean toggleDown) {
        if(!JourneyMapOrigins.active()){keyDown=false;return;}
        if(toggleDown&&!keyDown)JourneyMapOrigins.toggle();keyDown=toggleDown;
        int x=8,y=Math.max(32,height-152);
        boolean enabled=JourneyMapOrigins.enabled();
        painter.fill(x,y,x+142,y+(enabled?112:29),0xDF18212B);
        painter.text("区块来源",x+8,y+7,0xFFE7ECF2);
        painter.text("V · "+(enabled?"隐藏覆层":"显示覆层"),x+66,y+7,0xFFBEC9D5);
        if(!enabled)return;
        int line=y+25;
        for(Map.Entry<String,Integer> entry:ChunkOrigins.COLORS.entrySet()) {
            painter.fill(x+8,line,x+16,line+8,0xFF000000|entry.getValue());
            painter.text(JourneyMapOrigins.legend(entry.getKey()),x+23,line,0xFFE7ECF2);line+=12;
        }
        painter.text(JourneyMapOrigins.limited()?"范围过大 · 放大查看":"首次生成版本 · 主世界",x+8,y+99,0xFFBEC9D5);
    }
}
