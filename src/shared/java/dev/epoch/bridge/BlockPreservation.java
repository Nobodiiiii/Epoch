package dev.epoch.bridge;

import java.util.*;

/** Import-local originals. Never persisted or keyed by world position after placement. */
public final class BlockPreservation {
    private final List<String> palette;
    private final int[] runs;
    private final Map<String,String> payloads;
    private int[] cells;
    public BlockPreservation(List<String> palette,int[] runs,Map<String,String> payloads) {
        this.palette=palette;this.runs=runs;this.payloads=payloads;
    }
    public String state(int index) {
        if(cells==null){int size=0;for(int n=1;n<runs.length;n+=2)size+=runs[n];cells=new int[size];int cursor=0;for(int n=0;n<runs.length;n+=2){Arrays.fill(cells,cursor,cursor+runs[n+1],runs[n]);cursor+=runs[n+1];}}
        return palette.get(cells[index]);
    }
    public String payload(int index){String value=payloads.get(Integer.toString(index));return value==null?"":value;}
}
