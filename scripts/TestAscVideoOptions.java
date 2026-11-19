package com.xarkon.medialab;
import java.util.*;
public final class TestAscVideoOptions {
    public static void main(String[] args) {
        String list=" V..... hevc_mediacodec HEVC\n V..... h264_mediacodec H264\n V....D libvpx-vp9 VP9\n V....D libaom-av1 AV1\n V..... mpeg4 MPEG4\n A..... aac AAC\n";
        Set<String> available=AscVideoOptions.parse(list);
        if(available.size()!=5)throw new AssertionError(available);
        String[] codecs=AscVideoOptions.codecs(available,3);
        if(codecs.length!=5||!"hevc_mediacodec".equals(codecs[0]))throw new AssertionError(Arrays.toString(codecs));
        if(AscVideoOptions.codecs(Collections.singleton("libvpx-vp9"),3).length!=1)throw new AssertionError("availability");
        if(AscVideoOptions.steps("hevc_mediacodec",3).length<3)throw new AssertionError("bitrate retry");
        if(AscVideoOptions.hardwareRate(10_000_000,135)!=13_500_000)throw new AssertionError("bitrate");
        for(String codec:codecs){
            ArrayList<String> opts=new ArrayList<>();
            AscVideoOptions.append(opts,codec,AscVideoOptions.steps(codec,3)[0],10_000_000,3);
            if(opts.contains("-preset")||opts.contains("-x265-params"))throw new AssertionError(opts);
        }
        if(!AscVideoOptions.sampleStarts(100000,5000).equals(Arrays.asList(12500L,27500L,47500L,67500L,77500L)))throw new AssertionError("sample positions");
        for(long duration:new long[]{1000,5000,9999,10000,20000,25000,49999,50000,100000}){
            long previous=-5000;
            for(long start:AscVideoOptions.sampleStarts(duration,5000)){
                if(start<0||start>Math.max(0,duration-5000)||start<previous+5000)throw new AssertionError("overlap "+duration);
                previous=start;
            }
        }
        if(AscVideoOptions.estimateBytes(5000000,25000,100000)!=20000000L)throw new AssertionError("weighted estimate");
        if(AscVideoOptions.estimateBytes(0,25000,100000)!=-1)throw new AssertionError("empty estimate");
        String[] ultra=AscVideoOptions.ultraCodecs(available);
        if(!Arrays.equals(ultra,new String[]{"libaom-av1","libvpx-vp9","hevc_mediacodec","h264_mediacodec"}))throw new AssertionError(Arrays.toString(ultra));
        Set<String> optional=new HashSet<>(Arrays.asList("libsvtav1","libx265","libx264"));
        for(String codec:AscVideoOptions.ultraCodecs(optional)){
            ArrayList<String> opts=new ArrayList<>();
            AscVideoOptions.append(opts,codec,AscVideoOptions.ultraQuality(codec),10_000_000,3);
            if(!opts.contains("-crf")||!opts.contains("-preset"))throw new AssertionError(opts);
        }
        for(String codec:new String[]{"libkvazaar","libopenh264","av1_mediacodec"}){
            if(!Arrays.asList(AscVideoOptions.ultraCodecs(Collections.singleton(codec))).contains(codec))throw new AssertionError(codec);
            ArrayList<String> opts=new ArrayList<>();
            AscVideoOptions.append(opts,codec,AscVideoOptions.ultraQuality(codec),10_000_000,3);
            if(opts.isEmpty())throw new AssertionError(codec);
        }
        if(args.length>0){
            ArrayList<String> opts=new ArrayList<>();
            AscVideoOptions.append(opts,args[0],args.length>1?AscVideoOptions.ultraQuality(args[0]):4,10_000_000,3);
            for(String token:opts)System.out.println(token);
        }else System.out.println("ASC Full encoder selection: PASS");
    }
}
