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
    static String[] ultraCodecs(Set<String> available){
        String[] preferred={"svt_native","libsvtav1","libaom-av1","libkvazaar","libvpx-vp9","av1_mediacodec","hevc_mediacodec","h264_mediacodec","libopenh264"};
        if(available.isEmpty())return new String[]{"libaom-av1","libvpx-vp9","hevc_mediacodec","h264_mediacodec"};
        ArrayList<String> result=new ArrayList<>();
        for(String codec:preferred)if(available.contains(codec))result.add(codec);
        return result.toArray(new String[0]);
    }
    // Conservative presets, not a visual-quality guarantee. No video quality scoring.
    static int ultraQuality(String codec){
        if(hardware(codec))return 80;
        if("libkvazaar".equals(codec))return 21;
        if("libopenh264".equals(codec))return 85;
        if("svt_native".equals(codec)||"libsvtav1".equals(codec)||"libaom-av1".equals(codec))return 23;
        return 22;
    }
    /** Up to five non-overlapping samples. Long clips cover different scene complexity. */
    static List<Long> sampleStarts(long durationMs,long clipMs){
        if(durationMs<=0||clipMs<=0)return Collections.singletonList(0L);
        long span=Math.max(0,durationMs-clipMs);
        int count=(int)Math.min(5,Math.max(1,durationMs/clipMs));
        if(count==1)return Collections.singletonList(span/2);
        ArrayList<Long> starts=new ArrayList<>();
        if(count==5&&durationMs>=10.0*clipMs){
            for(int p:new int[]{15,30,50,70,80})
                starts.add(Math.max(0,Math.min(span,Math.round(durationMs*(p/100.0))-clipMs/2)));
        }else for(int i=0;i<count;i++)
            starts.add(Math.round(span*(i/(double)(count-1))));
        return starts;
    }
    static long estimateBytes(long sampleBytes,long sampledMs,long durationMs){
        if(sampleBytes<=0||sampledMs<=0||durationMs<=0)return -1;
        return Math.max(1,Math.round(sampleBytes*(durationMs/(double)sampledMs)));
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
        }else if("libkvazaar".equals(codec)){
            Collections.addAll(args,"-b:v:0","0","-kvazaar-params","qp="+quality+",preset="+(performance==3?"fast":"medium"),"-pix_fmt","yuv420p");
        }else if("libopenh264".equals(codec)){
            Collections.addAll(args,"-b:v:0",String.valueOf(hardwareRate(sourceRate,quality)),"-allow_skip_frames","0","-pix_fmt","yuv420p");
        }else if("libsvtav1".equals(codec)){
            Collections.addAll(args,"-crf",String.valueOf(quality),"-preset",performance==3?"8":"6");
        }else if("mpeg4".equals(codec)){
            Collections.addAll(args,"-q:v:0",String.valueOf(quality),"-pix_fmt","yuv420p");
        }else throw new IllegalArgumentException("Unsupported ASC encoder: "+codec);
    }
}
