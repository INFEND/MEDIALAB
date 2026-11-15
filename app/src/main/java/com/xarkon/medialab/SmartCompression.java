package com.xarkon.medialab;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.graphics.ColorSpace;
import android.os.Build;
import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.FFmpegSession;
import com.arthenica.ffmpegkit.FFprobeKit;
import com.arthenica.ffmpegkit.MediaInformation;
import com.arthenica.ffmpegkit.ReturnCode;
import java.io.*;
import java.util.*;
import java.util.regex.*;

/** ASC PRO: local, conservative quality-gated encoding. The original is never modified. */
final class SmartCompression {
    static final int QUALITY = 101;
    interface Status { void update(String message); }
    static final class Result {
        final File file; final String format; final boolean original; final boolean verified;
        Result(File f,String x,boolean o){this(f,x,o,true);}
        Result(File f,String x,boolean o,boolean v){file=f;format=x;original=o;verified=v;}
    }
    private static void check(ConvertEngine.Canceled c) throws IOException {
        if(c.canceled()) throw new IOException("Операция отменена");
    }
    private static String ext(File f){return ConvertEngine.extension(f.getName());}
    static Result run(Context ctx, File in, ConvertEngine.Config cfg, ConvertEngine.MediaInfo info,
                      long start,long length,boolean preview,ConvertEngine.Canceled canceled,Status status) throws IOException {
        check(canceled);
        Result best=null;
        if(cfg.mode==ConvertEngine.IMAGE) best=image(ctx,in,preview,canceled,status);
        else if(cfg.mode==ConvertEngine.VIDEO || cfg.mode==ConvertEngine.AUDIO || cfg.mode==ConvertEngine.EXTRACT)
            best=av(ctx,in,cfg,info,start,length,preview,canceled,status);
        if(canceled.canceled()){if(best!=null)best.file.delete();check(canceled);}
        // Never publish a bigger file in smart mode (except extraction, which is a different medium).
        if(best!=null && (length>0 || cfg.mode==ConvertEngine.EXTRACT || best.file.length()<in.length())) return best;
        if(best!=null) best.file.delete();
        if(length>0 && preview){
            // Demonstration only: never use it as an approved export or size measurement.
            status.update("ASC PRO: проверенный вариант не найден, готовим демонстрационный образец");
            String format=cfg.mode==ConvertEngine.VIDEO?"mp4":"m4a";
            File demo=ConvertEngine.outputTemp(ctx,format);
            try{
                ConvertEngine.Config fallback=new ConvertEngine.Config(cfg.mode,format,95,0,3,cfg.performance);
                FFmpegSession session=ConvertEngine.convert(in,demo,fallback,info,start,length);
                check(canceled);
                if(session==null||!ReturnCode.isSuccess(session.getReturnCode())||demo.length()==0)
                    throw new IOException("ASC PRO: не удалось создать даже демонстрационный фрагмент");
                Result result=new Result(demo,format,false,false);demo=null;return result;
            }finally{if(demo!=null)demo.delete();}
        }
        if(length>0 || cfg.mode==ConvertEngine.EXTRACT)
            throw new IOException("ASC PRO: не найден проверенный вариант для этого фрагмента или формата");
        status.update("ASC PRO: исходник уже оптимален или проверка качества не пройдена");
        File copy=ConvertEngine.outputTemp(ctx,ext(in));
        try {
            try(InputStream a=new FileInputStream(in); OutputStream b=new FileOutputStream(copy)){
                byte[] buf=new byte[128*1024];int n;
                while((n=a.read(buf))!=-1){check(canceled);b.write(buf,0,n);}
            }
            return new Result(copy,ext(in),true);
        }catch(IOException e){copy.delete();throw e;}
    }

    // Decode with EXIF orientation, no resizing and no conversion of wide-gamut originals.
    private static Bitmap bitmap(File f) throws IOException {
        return ImageDecoder.decodeBitmap(ImageDecoder.createSource(f),(d,i,s)->{
            if(i.isAnimated() || (long)i.getSize().getWidth()*i.getSize().getHeight()>24_000_000L
                    || (i.getColorSpace()!=null && i.getColorSpace().isWideGamut()))
                throw new IllegalArgumentException("Animated, wide-gamut or very large image");
            d.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            d.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB));
        });
    }
    private static Result image(Context ctx,File in,boolean preview,ConvertEngine.Canceled c,Status status) throws IOException {
        Bitmap a=null; Result best=null;
        try{
            a=bitmap(in);
            // Lossless candidate also protects alpha, text and line art.
            String[] formats=a.hasAlpha()?new String[]{"webp","png"}:preview?
                    ("jpg".equals(ext(in))||"jpeg".equals(ext(in))?new String[]{"jpg","webp"}:new String[]{"webp","jpg"}):
                    new String[]{"webp","jpg","png","avif"};
            for(String fmt:formats){
                int[] qualities="png".equals(fmt)?new int[]{100}:a.hasAlpha()?new int[]{100}:
                    preview?new int[]{94,98}:new int[]{78,84,90,94,98,100};
                for(int q:qualities){
                    check(c);
                    File out=ConvertEngine.outputTemp(ctx,fmt); Bitmap b=null;
                    try{
                        status.update("ASC PRO · фото: "+fmt.toUpperCase(Locale.ROOT)+" "+q+" · контроль деталей");
                        boolean ok;
                        if("avif".equals(fmt)){
                            // The oriented bitmap is the reference; compare dimensions and pixels after decode.
                            File png=ConvertEngine.outputTemp(ctx,"png");
                            try{
                                try(OutputStream stream=new FileOutputStream(png)){if(!a.compress(Bitmap.CompressFormat.PNG,100,stream))continue;}
                                ok=exec(c,"-i",png.getAbsolutePath(),"-frames:v","1","-c:v","libaom-av1","-still-picture","1","-cpu-used","6","-crf",String.valueOf(63-q*45/100),"-b:v","0",out.getAbsolutePath())!=null;
                            }finally{png.delete();}
                        } else {
                            Bitmap.CompressFormat codec="jpg".equals(fmt)?Bitmap.CompressFormat.JPEG:
                                "png".equals(fmt)?Bitmap.CompressFormat.PNG:
                                Build.VERSION.SDK_INT>=30?(q==100?Bitmap.CompressFormat.WEBP_LOSSLESS:Bitmap.CompressFormat.WEBP_LOSSY):Bitmap.CompressFormat.WEBP;
                            if(a.hasAlpha() && "webp".equals(fmt) && Build.VERSION.SDK_INT<30)continue;
                            try(OutputStream stream=new FileOutputStream(out)){ok=a.compress(codec,q,stream);}
                        }
                        if(!ok || out.length()<=0 || out.length()>=in.length() || (best!=null && out.length()>=best.file.length()))continue;
                        b=bitmap(out);
                        if(!photoPass(a,b,c))continue;
                        if(best!=null)best.file.delete();
                        best=new Result(out,fmt,false);out=null;
                        if(preview)return best; // first quality-approved native candidate
                        break; // first passing quality for this codec; compare codecs by bytes
                    }catch(IllegalArgumentException|IOException ex){check(c);}
                    finally{if(b!=null)b.recycle();if(out!=null)out.delete();}
                }
            }
            return best;
        }catch(IllegalArgumentException|OutOfMemoryError ex){return best;}
        catch(IOException ex){if(c.canceled()){if(best!=null)best.file.delete();throw ex;}return best;}
        finally{if(a!=null)a.recycle();}
    }
    // Full-resolution, non-overlapping 32x32 local SSIM, Y/Cb/Cr error and exact alpha.
    private static boolean photoPass(Bitmap a,Bitmap b,ConvertEngine.Canceled c)throws IOException{
        int w=a.getWidth(),h=a.getHeight();if(w!=b.getWidth()||h!=b.getHeight())return false;
        int[] x=new int[1024],y=new int[1024];double sum=0,chroma=0;long pixels=0,bad=0;
        for(int j=0;j<h;j+=32)for(int i=0;i<w;i+=32){
            check(c);int tw=Math.min(32,w-i),th=Math.min(32,h-j),n=tw*th;
            a.getPixels(x,0,tw,i,j,tw,th);b.getPixels(y,0,tw,i,j,tw,th);
            double sa=0,sb=0,saa=0,sbb=0,sab=0;
            for(int k=0;k<n;k++){
                if((x[k]>>>24)!=(y[k]>>>24))return false;
                if((x[k]>>>24)==0)continue;
                int ar=(x[k]>>16)&255,ag=(x[k]>>8)&255,ab=x[k]&255;
                int br=(y[k]>>16)&255,bg=(y[k]>>8)&255,bb=y[k]&255;
                double l=.299*ar+.587*ag+.114*ab,r=.299*br+.587*bg+.114*bb;
                sa+=l;sb+=r;saa+=l*l;sbb+=r*r;sab+=l*r;
                double cb=Math.abs((ab-l)-(bb-r))*.564,cr=Math.abs((ar-l)-(br-r))*.713;
                chroma+=cb+cr;if(cb>7)bad++;if(cr>7)bad++;
            }
            double m=sa/n,v=sb/n,va=Math.max(0,saa/n-m*m),vb=Math.max(0,sbb/n-v*v),cov=sab/n-m*v;
            double score=((2*m*v+6.5025)*(2*cov+58.5225))/((m*m+v*v+6.5025)*(va+vb+58.5225));
            if(score<.965)return false;
            sum+=score*n;pixels+=n;
        }
        return pixels>0 && sum/pixels>=.990 && chroma/(2*pixels)<=2.5 && bad/(2.0*pixels)<=.05;
    }

    private static FFmpegSession exec(ConvertEngine.Canceled c,String... tail)throws IOException{
        check(c);ArrayList<String> args=new ArrayList<>(Arrays.asList("-hide_banner","-nostdin","-y"));
        Collections.addAll(args,tail);FFmpegSession s=FFmpegKit.executeWithArguments(args.toArray(new String[0]));
        check(c);return s!=null&&ReturnCode.isSuccess(s.getReturnCode())?s:null;
    }
    private static void seek(List<String> a,long start){if(start>0)Collections.addAll(a,"-ss",sec(start));}
    private static String sec(long ms){return String.format(Locale.US,"%.3f",ms/1000.0);}
    private static void duration(List<String> a,long length){if(length>0)Collections.addAll(a,"-t",sec(length));}
    private static Result av(Context ctx,File in,ConvertEngine.Config cfg,ConvertEngine.MediaInfo inf,long start,long length,
                             boolean preview,ConvertEngine.Canceled c,Status status)throws IOException{
        boolean video=cfg.mode==ConvertEngine.VIDEO;
        // Preserve additional streams/cover art rather than silently discard them in audio compression.
        MediaInformation mi=FFprobeKit.getMediaInformation(in.getAbsolutePath()).getMediaInformation();
        if(mi==null || mi.getStreams()==null)return null;
        int videos=0,audios=0;String props=mi.getAllProperties().toString().toLowerCase(Locale.ROOT);
        for(com.arthenica.ffmpegkit.StreamInformation stream:mi.getStreams()){
            if("video".equals(stream.getType()))videos++;
            if("audio".equals(stream.getType()))audios++;
        }
        if(video && (videos!=1 || props.contains("smpte2084") || props.contains("arib-std-b67") || props.contains("dovi")))return null;
        if(!video && (audios!=1 || (cfg.mode==ConvertEngine.AUDIO && mi.getStreams().size()!=1)))return null;
        // VP9 / HEVC are probed by the encoder itself. No assumptions about optional native codecs.
        String[] codecs=video?new String[]{"libx265","libvpx-vp9","libx264"}:new String[]{"libopus","aac","flac"};
        Result best=null;
        try{
            for(String codec:codecs){
                String fmt=video?"mkv":"libopus".equals(codec)?"opus":"aac".equals(codec)?"m4a":"flac";
                int[] steps=video?(preview?new int[]{20,16}:new int[]{28,24,20,16}): "flac".equals(codec)?new int[]{8}:new int[]{160,192,256,320};
                for(int q:steps){
                    check(c);File out=ConvertEngine.outputTemp(ctx,fmt);
                    try{
                        status.update("ASC PRO · "+(video?"видео":"аудио")+": "+codec+" "+q+" · кодирование");
                        ArrayList<String> a=new ArrayList<>();seek(a,start);Collections.addAll(a,"-i",in.getAbsolutePath());duration(a,length);
                        if(video){
                            if(length>0)Collections.addAll(a,"-map","0:v:0","-map","0:a?");
                            else Collections.addAll(a,"-map","0");
                            Collections.addAll(a,"-c","copy","-c:v:0",codec,"-crf",String.valueOf(q));
                            if("libx265".equals(codec))Collections.addAll(a,"-preset",preview?"ultrafast":"fast","-x265-params","log-level=error");
                            else if("libx264".equals(codec))Collections.addAll(a,"-preset",preview?"ultrafast":"fast");
                            else Collections.addAll(a,"-b:v:0","0","-deadline","good","-cpu-used",preview?"6":"4");
                        }else{
                            Collections.addAll(a,"-map","0:a:0","-vn","-c:a",codec);
                            if("flac".equals(codec))Collections.addAll(a,"-compression_level","8");
                            else Collections.addAll(a,"-b:a",q+"k");
                        }
                        Collections.addAll(a,"-map_metadata","0",out.getAbsolutePath());
                        if(exec(c,a.toArray(new String[0]))==null || out.length()<=0)break;
                        if(length==0 && cfg.mode!=ConvertEngine.EXTRACT && out.length()>=in.length())continue;
                        if(best!=null && out.length()>=best.file.length())continue;
                        long target=length>0?length:inf.durationMs, actual=ConvertEngine.info(out).durationMs;
                        if(target>0 && (actual<=0 || Math.abs(actual-target)>Math.max(length>0?350:120,target/100)))continue;
                        status.update("ASC PRO · "+codec+" · проверка "+(video?"кадров":"звука"));
                        boolean pass=video?videoPass(ctx,in,out,start,length,c):audioPass(in,out,start,length,c,"flac".equals(codec));
                        if(!pass)continue;
                        if(best!=null)best.file.delete();best=new Result(out,fmt,false);out=null;
                        break;
                    }finally{if(out!=null)out.delete();}
                }
                // Full video evaluation is expensive; use the second codec as a fallback.
                if(video && best!=null)break;
            }
            return best;
        }catch(IOException|RuntimeException ex){if(best!=null)best.file.delete();throw ex;}
    }
    private static boolean videoPass(Context ctx,File in,File out,long start,long length,ConvertEngine.Canceled c)throws IOException{
        File stats=ConvertEngine.outputTemp(ctx,"txt");
        try{
            ArrayList<String> a=new ArrayList<>();seek(a,start);Collections.addAll(a,"-i",in.getAbsolutePath(),"-i",out.getAbsolutePath());
            Collections.addAll(a,"-filter_complex","[0:v:0]setpts=PTS-STARTPTS[x];[1:v:0]setpts=PTS-STARTPTS[y];[x][y]ssim=shortest=1:stats_file="+stats.getAbsolutePath(),
                    "-an");duration(a,length);Collections.addAll(a,"-f","null","-");
            if(exec(c,a.toArray(new String[0]))==null || !stats.isFile())return false;
            Pattern p=Pattern.compile("Y:([0-9.]+).*?All:([0-9.]+)");double sum=0;long count=0;
            try(BufferedReader r=new BufferedReader(new FileReader(stats))){
                String line;while((line=r.readLine())!=null){check(c);Matcher m=p.matcher(line);
                    if(!m.find())return false;double y=Double.parseDouble(m.group(1)),all=Double.parseDouble(m.group(2));
                    if(y<.94 || all<.93)return false;sum+=y;count++;
                }
            }
            return count>0 && sum/count>=.985;
        }finally{stats.delete();}
    }
    private static boolean audioPass(File in,File out,long start,long length,ConvertEngine.Canceled c,boolean lossless)throws IOException{
        ArrayList<String> a=new ArrayList<>();seek(a,start);Collections.addAll(a,"-i",in.getAbsolutePath(),"-i",out.getAbsolutePath(),
                "-filter_complex","[0:a:0][1:a:0]asdr","-vn");duration(a,length);Collections.addAll(a,"-f","null","-");
        FFmpegSession s=exec(c,a.toArray(new String[0]));if(s==null)return false;
        Matcher m=Pattern.compile("SDR ch\\d+:\\s*([+\\-]?(?:[0-9.]+|inf))\\s*dB",Pattern.CASE_INSENSITIVE).matcher(s.getAllLogsAsString());
        int n=0;while(m.find()){n++;String v=m.group(1);
            if("inf".equalsIgnoreCase(v)||"+inf".equalsIgnoreCase(v))continue;
            if(lossless || "-inf".equalsIgnoreCase(v) || Double.parseDouble(v)<20.0)return false;
        }
        return n>0;
    }
}
