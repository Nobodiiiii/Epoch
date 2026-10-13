package dev.epoch.bridge;

import java.util.*;

/** Bounded phase timings; percentiles are histogram upper bounds, not sampled guesses. */
public final class TerrainMetrics {
    private static final Map<String,long[]> PHASES=new LinkedHashMap<String,long[]>();
    private static final long[] LIMITS={1,2,3,5,10,20,50,100,250,500,1000,5000,Long.MAX_VALUE};
    private TerrainMetrics(){}
    public static synchronized void record(String phase,long nanos){
        long[] values=PHASES.get(phase);if(values==null){values=new long[3+LIMITS.length];PHASES.put(phase,values);}
        values[0]++;values[1]+=nanos;values[2]=Math.max(values[2],nanos);
        double ms=nanos/1e6;for(int n=0;n<LIMITS.length;n++)if(ms<=LIMITS[n]){values[3+n]++;break;}
    }
    public static synchronized Map<String,Object> diagnostics(){
        Map<String,Object> result=new LinkedHashMap<String,Object>();
        for(Map.Entry<String,long[]> entry:PHASES.entrySet()){
            long[] value=entry.getValue();Map<String,Object> row=new LinkedHashMap<String,Object>();
            row.put("count",value[0]);row.put("totalMs",value[1]/1e6);row.put("maxMs",value[2]/1e6);
            row.put("meanMs",value[1]/1e6/value[0]);long sum=0;
            for(int n=0;n<LIMITS.length;n++){sum+=value[n+3];if(sum>=Math.ceil(value[0]*.95)){row.put("p95UpperMs",LIMITS[n]==Long.MAX_VALUE?"over 5000":LIMITS[n]);break;}}
            result.put(entry.getKey(),row);
        }
        return result;
    }
}
