package com.xarkon.medialab;

import java.util.*;
import java.util.regex.*;

/** Options for the installed LGPL FFmpegKit Full pack. No GPL encoder assumptions. */
final class AscVideoOptions {
    static Set<String> parse(String output){
        Set<String> encoders=new HashSet<>();
        if(output!=null){
            Matcher matcher=Pattern.compile("(?m)^\\s*V[A-Z.]{5}\\s+([a-zA-Z0-9_-]+)\\s").matcher(output);
            while(matcher.find())encoders.add(matcher.group(1));
        }
        return encoders;
    }
    static String[] codecs(Set<String> available,int performance){
        String[] preferred=performance==3?
            new String[]{"hevc_mediacodec","h264_mediacodec","libvpx-vp9","libaom-av1","mpeg4"}:
            new String[]{"hevc_mediacodec","libvpx-vp9","h264_mediacodec","libaom-av1","mpeg4"};
        // A failed encoder listing must not prevent real attempts at the known Full codecs.
        if(available.isEmpty())return preferred;
        ArrayList<String> result=new ArrayList<>();
        for(String codec:preferred)if(available.contains(codec))result.add(codec);
        return result.toArray(new String[0]);
    }
    static boolean hardware(String codec){return codec.endsWith("_mediacodec");}
    static int[] steps(String codec,int performance){
        if(hardware(codec))return performance==3?new int[]{50,75,100,135}:new int[]{45,60,80,100,135};
        if("mpeg4".equals(codec))return new int[]{2,1};
        return performance==3?new int[]{26,18,10,4}:new int[]{30,24,18,12,6};
    }
    static long hardwareRate(long sourceRate,int percent){
        return Math.max(150_000L,Math.min(160_000_000L,Math.round(sourceRate*(percent/100.0))));
    }
    static void append(List<String> args,String codec,int quality,long sourceRate,int performance){
        if(hardware(codec)){
            Collections.addAll(args,"-b:v:0",String.valueOf(hardwareRate(sourceRate,quality)),"-pix_fmt","yuv420p");
        }else if("libvpx-vp9".equals(codec)){
            Collections.addAll(args,"-crf",String.valueOf(quality),"-b:v:0","0","-deadline","good","-cpu-used",performance==3?"6":"4","-row-mt","1");
        }else if("libaom-av1".equals(codec)){
            Collections.addAll(args,"-crf",String.valueOf(quality),"-b:v:0","0","-cpu-used",performance==3?"8":"6","-row-mt","1");
        }else if("mpeg4".equals(codec)){
            Collections.addAll(args,"-q:v:0",String.valueOf(quality),"-pix_fmt","yuv420p");
        }else throw new IllegalArgumentException("Unsupported ASC encoder: "+codec);
    }
}
