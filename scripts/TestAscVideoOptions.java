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
        if(args.length>0){
            ArrayList<String> opts=new ArrayList<>();
            AscVideoOptions.append(opts,args[0],4,10_000_000,3);
            for(String token:opts)System.out.println(token);
        }else System.out.println("ASC Full encoder selection: PASS");
    }
}
