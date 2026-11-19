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
    static final int QUALITY = 101, ULTRA = 102;
    static boolean isSmart(int q){return q==QUALITY||q==ULTRA;}
    static boolean isUltra(int q){return q==ULTRA;}
    interface Status {
        void update(String message);
        default void progress(String message,int percent){update(message);}
    }
    static Status reporting(java.util.function.BiConsumer<String,Integer> callback){
        return new Status(){
            public void update(String message){callback.accept(message,0);}
            public void progress(String message,int percent){callback.accept(message,percent);}
        };
    }
    static final class Result {
        final File file; final String format; final boolean original; final boolean verified;
        Result(File f,String x,boolean o){this(f,x,o,true);}
        Result(File f,String x,boolean o,boolean v){file=f;format=x;original=o;verified=v;}
    }
    /** Keep real failure categories instead of hiding native errors behind a quality message. */
    private static final class Diagnostics {
        int encodes, durationFailures, qualityFailures, nativeFailures, sizeFailures, fullAttempts;
        boolean fullAttempted, lastOversize;
        long fullDurationMs;
        int sourceAudioStreams;
        Status status;
        final Set<String> failedHardware=new HashSet<>();
        final Set<String> triedFull=new HashSet<>();
        String last="";
        final LinkedHashSet<String> recent=new LinkedHashSet<>();
        void note(String msg){
            last=msg;recent.add(msg);while(recent.size()>4)recent.remove(recent.iterator().next());
            android.util.Log.w("MediaLAB_ASC",msg);
        }
        String details(){
            return "Кодирование: "+encodes+"; ошибки FFmpeg: "+nativeFailures+
                    "; длительность: "+durationFailures+"; качество: "+qualityFailures+
                    "; размер: "+sizeFailures+"; полных попыток: "+fullAttempts+
                    (last.isEmpty()?"":". "+String.join(" | ",recent));
        }
    }
    private static void check(ConvertEngine.Canceled c) throws IOException {
        if(c.canceled()) throw new IOException("Операция отменена");
    }
    private static String ext(File f){return ConvertEngine.extension(f.getName());}
    private static final class Plan {
        final String codec;final int q;final long bitrate;
        Plan(String codec,int q,long bitrate){this.codec=codec;this.q=q;this.bitrate=bitrate;}
    }
    private static final Map<String,Plan> plans=new LinkedHashMap<String,Plan>(16,.75f,true){
        @Override protected boolean removeEldestEntry(Map.Entry<String,Plan> e){return size()>32;}
    };
    private static synchronized Plan plan(String key){return key==null?null:plans.get(key);}
    private static synchronized void remember(String key,Plan p){if(key!=null)plans.put(key,p);}
    private static synchronized void forget(String key){if(key!=null)plans.remove(key);}
    static Result run(Context ctx, File in, ConvertEngine.Config cfg, ConvertEngine.MediaInfo info,
                      long start,long length,boolean preview,String sourceKey,ConvertEngine.Canceled canceled,Status status) throws IOException {
        check(canceled);
        if(isUltra(cfg.quality)){
            final Status downstream=status;
            status=new Status(){public void update(String m){downstream.update(m.replace("ASC PRO","ASC ULTRA"));}public void progress(String m,int p){downstream.progress(m.replace("ASC PRO","ASC ULTRA"),p);}};
        }
        Result best=null;
        Diagnostics diagnostics=new Diagnostics();diagnostics.status=status;
        if(cfg.mode==ConvertEngine.IMAGE) best=image(ctx,in,cfg,canceled,status);
        else if(cfg.mode==ConvertEngine.VIDEO || cfg.mode==ConvertEngine.AUDIO || cfg.mode==ConvertEngine.EXTRACT)
            best=av(ctx,in,cfg,info,start,length,preview,sourceKey,canceled,status,diagnostics);
        if(canceled.canceled()){if(best!=null)best.file.delete();check(canceled);}
        // An explicit format must match the bytes, not merely the filename. Changing
        // containers can legitimately require more space; same-format ASC never grows.
        boolean imageFormatChange=cfg.mode==ConvertEngine.IMAGE&&!cfg.format.equals(normalizedImageExt(in));
        if(best!=null && (imageFormatChange || length>0 || cfg.mode==ConvertEngine.EXTRACT || best.file.length()<in.length())) return best;
        if(best!=null) best.file.delete();
        if(imageFormatChange)
            throw new IOException("ASC PRO: не удалось получить проверенное изображение в формате "+cfg.format.toUpperCase(Locale.ROOT)+". Исходный файл не изменён.");
        if(cfg.mode==ConvertEngine.VIDEO)
            throw new IOException("ASC PRO: не удалось получить проверенное видео меньшего размера. Копия оригинала не создана. "+diagnostics.details()+". Исходный файл не изменён.");
        if(length>0 || cfg.mode==ConvertEngine.EXTRACT)
            throw new IOException("ASC PRO: проверенный результат не найден. "+diagnostics.details()+". Исходный файл не изменён.");
        status.update("ASC PRO: исходник уже оптимален или проверка качества не пройдена");
        if(!preview)return new Result(in,ext(in),true); // caller owns source; no redundant temporary copy
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
    static Bitmap bitmap(Context ctx,File f) throws IOException {
        if("jxl".equalsIgnoreCase(ext(f))){Bitmap b=PhotoExtraPack.decodeJxl(ctx,f);if(b==null)throw new IOException("JPEG XL: установите PHOTO EXTRA или проверьте формат (поддерживается SDR RGB 8 бит)");return b;}
        return ImageDecoder.decodeBitmap(ImageDecoder.createSource(f),(d,i,s)->{
            if(i.isAnimated() || (long)i.getSize().getWidth()*i.getSize().getHeight()>24_000_000L
                    || (i.getColorSpace()!=null && i.getColorSpace().isWideGamut()))
                throw new IllegalArgumentException("Animated, wide-gamut or very large image");
            d.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            d.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB));
        });
    }
    private static String normalizedImageExt(File f){
        return ConvertEngine.normalizedImageExtension(f.getName());
    }
    private static Result image(Context ctx,File in,ConvertEngine.Config cfg,ConvertEngine.Canceled c,Status status) throws IOException {
        int performance=cfg.performance;
        boolean changed=!cfg.format.equals(normalizedImageExt(in));
        Bitmap a=null; Result best=null; File avifInput=null;
        try{
            if(isUltra(cfg.quality)&&"png".equals(cfg.format)&&!changed&&PhotoExtraPack.loadInstalled(ctx)){
                File out=ConvertEngine.outputTemp(ctx,"png");
                try{
                    status.update("ASC ULTRA · OxiPNG · сжатие без потерь");
                    check(c);
                    if(PhotoExtraPack.optimizePng(ctx,in,out,performance==3?4:6)&&out.length()>0&&out.length()<in.length()){
                        check(c);Result result=new Result(out,"png",false);out=null;return result;
                    }
                    check(c);return null; // preserve the entire original if strict optimization is unavailable
                }finally{if(out!=null)out.delete();}
            }
            a=bitmap(ctx,in);
            // Lossless candidate also protects alpha, text and line art.
            // An unsupported original-format encoder must never fall through to WebP.
            if(!Arrays.asList("jpg","png","webp","avif","bmp","tiff","gif","jxl").contains(cfg.format))return null;
            String[] formats={cfg.format};
            for(String fmt:formats){
                int[] qualities=("png".equals(fmt)||"bmp".equals(fmt)||"tiff".equals(fmt)||"gif".equals(fmt))?new int[]{100}:a.hasAlpha()?new int[]{100}:
                    performance==3?("avif".equals(fmt)?new int[]{94,98}:new int[]{84,94,98}):"avif".equals(fmt)?new int[]{94,98}:new int[]{84,94,98,100};
                if(isUltra(cfg.quality)&&!a.hasAlpha()&&Arrays.asList("jpg","webp","avif").contains(fmt)) qualities="avif".equals(fmt)?new int[]{80,84,88,92,94,96,98,100}:new int[]{72,76,80,84,88,90,92,94,96,98,100};
                if("jxl".equals(fmt))qualities=new int[]{88,92,96,98,100};
                for(int q:qualities){
                    check(c);
                    if(isUltra(cfg.quality)&&("jpg".equals(fmt)||"webp".equals(fmt))&&PhotoUltraPack.loadInstalled(ctx)){
                        File nativeOut=ConvertEngine.outputTemp(ctx,fmt);Bitmap decoded=null;
                        try{
                            status.update("ASC ULTRA · "+("jpg".equals(fmt)?"JPEGli":"libwebp")+" · "+q);
                            if(PhotoUltraPack.encode(ctx,a,nativeOut,fmt,q)&&nativeOut.length()>0&&
                                (changed||nativeOut.length()<in.length())&&(best==null||nativeOut.length()<best.file.length())){
                                decoded=bitmap(ctx,nativeOut);
                                if(photoPass(a,decoded,performance,c)){
                                    if(best!=null)best.file.delete();
                                    best=new Result(nativeOut,fmt,false);nativeOut=null;
                                }
                            }
                        }catch(IllegalArgumentException|IOException ex){check(c);}
                        finally{if(decoded!=null)decoded.recycle();if(nativeOut!=null)nativeOut.delete();}
                    }
                    File out=ConvertEngine.outputTemp(ctx,fmt); Bitmap b=null;
                    try{
                        status.update("ASC PRO · фото: "+fmt.toUpperCase(Locale.ROOT)+" "+q+" · контроль деталей");
                        boolean ok;
                        if("jxl".equals(fmt)){
                            ok=PhotoExtraPack.encodeJxl(ctx,a,out,q);
                        }else if("avif".equals(fmt)||"bmp".equals(fmt)||"tiff".equals(fmt)||"gif".equals(fmt)){
                            // The oriented bitmap is the reference; compare dimensions and pixels after decode.
                            if(avifInput==null){
                                avifInput=ConvertEngine.outputTemp(ctx,"png");
                                try(OutputStream stream=new FileOutputStream(avifInput)){
                                    if(!a.compress(Bitmap.CompressFormat.PNG,100,stream)){avifInput.delete();avifInput=null;throw new IOException("PNG intermediate failed");}
                                }
                            }
                            if("avif".equals(fmt))
                                ok=exec(c,"-i",avifInput.getAbsolutePath(),"-frames:v","1","-c:v","libaom-av1","-still-picture","1","-cpu-used","8","-threads",String.valueOf(ConvertEngine.threads(performance)),"-row-mt","1","-crf",String.valueOf(63-q*45/100),"-b:v","0",out.getAbsolutePath())!=null;
                            else{
                                ConvertEngine.Config target=new ConvertEngine.Config(ConvertEngine.IMAGE,fmt,q,0,cfg.audioBitrate,performance);
                                FFmpegSession session=ConvertEngine.convert(avifInput,out,target,new ConvertEngine.MediaInfo(),0,0);
                                ok=session!=null&&ReturnCode.isSuccess(session.getReturnCode());
                            }
                        } else {
                            Bitmap.CompressFormat codec="jpg".equals(fmt)?Bitmap.CompressFormat.JPEG:
                                "png".equals(fmt)?Bitmap.CompressFormat.PNG:
                                Build.VERSION.SDK_INT>=30?(q==100?Bitmap.CompressFormat.WEBP_LOSSLESS:Bitmap.CompressFormat.WEBP_LOSSY):Bitmap.CompressFormat.WEBP;
                            if(a.hasAlpha() && "webp".equals(fmt) && Build.VERSION.SDK_INT<30)continue;
                            try(OutputStream stream=new FileOutputStream(out)){ok=a.compress(codec,q,stream);}
                        }
                        if(!ok || out.length()<=0 || (!changed && out.length()>=in.length()) || (best!=null && out.length()>=best.file.length()))continue;
                        b=bitmap(ctx,out);
                        if(!photoPass(a,b,performance,c))continue;
                        if(best!=null)best.file.delete();
                        best=new Result(out,fmt,false);out=null;
                        if(!isUltra(cfg.quality)&&performance==3 && best.file.length()<=in.length()*0.75)return best; // fast exit only after full quality gate and substantial saving
                        if(!isUltra(cfg.quality))break; // ULTRA compares all passing candidates
                    }catch(IllegalArgumentException|IOException ex){check(c);}
                    finally{if(b!=null)b.recycle();if(out!=null)out.delete();}
                }
            }
            return best;
        }catch(IllegalArgumentException|OutOfMemoryError ex){return best;}
        catch(IOException ex){if(c.canceled()){if(best!=null)best.file.delete();throw ex;}return best;}
        finally{if(avifInput!=null)avifInput.delete();if(a!=null)a.recycle();}
    }
    // Full-resolution, non-overlapping 32x32 local SSIM, Y/Cb/Cr error and exact alpha.
    private static final class PhotoScore { double sum,chroma;long pixels,bad; }
    private static boolean photoPass(Bitmap a,Bitmap b,int performance,ConvertEngine.Canceled c)throws IOException{
        int w=a.getWidth(),h=a.getHeight();if(w!=b.getWidth()||h!=b.getHeight())return false;
        int workers=Math.max(1,Math.min(ConvertEngine.threads(performance),(h+31)/32));
        java.util.concurrent.ForkJoinPool pool=new java.util.concurrent.ForkJoinPool(workers);
        try{
            PhotoScore[] scores=pool.submit(()->java.util.stream.IntStream.range(0,workers).parallel().mapToObj(k->photoStripe(a,b,c,k,workers)).toArray(PhotoScore[]::new)).join();
            check(c);double sum=0,chroma=0;long pixels=0,bad=0;
            for(PhotoScore score:scores){if(score==null)return false;sum+=score.sum;chroma+=score.chroma;pixels+=score.pixels;bad+=score.bad;}
            return pixels>0&&sum/pixels>=.990&&chroma/(2*pixels)<=2.5&&bad/(2.0*pixels)<=.05;
        }finally{pool.shutdown();}
    }
    private static PhotoScore photoStripe(Bitmap a,Bitmap b,ConvertEngine.Canceled c,int index,int workers){
        int w=a.getWidth(),h=a.getHeight();
        int[] x=new int[w*32],y=new int[w*32];double sum=0,chroma=0;long pixels=0,bad=0;
        for(int j=index*32;j<h;j+=workers*32){
            if(c.canceled())return null;
            int th=Math.min(32,h-j);
            a.getPixels(x,0,w,0,j,w,th);b.getPixels(y,0,w,0,j,w,th);
            for(int i=0;i<w;i+=32){
            if(c.canceled())return null;int tw=Math.min(32,w-i),n=tw*th;
            double sa=0,sb=0,saa=0,sbb=0,sab=0;
            for(int row=0;row<th;row++)for(int col=0;col<tw;col++){
                int k=row*w+i+col;
                if((x[k]>>>24)!=(y[k]>>>24))return null;
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
            if(score<.965)return null;
            sum+=score*n;pixels+=n;
            }
        }
        PhotoScore r=new PhotoScore();r.sum=sum;r.chroma=chroma;r.pixels=pixels;r.bad=bad;return r;
    }

    private static FFmpegSession exec(ConvertEngine.Canceled c,String... tail)throws IOException{
        check(c);ArrayList<String> args=new ArrayList<>(Arrays.asList("-hide_banner","-nostdin","-y"));
        Collections.addAll(args,tail);FFmpegSession s=FFmpegKit.executeWithArguments(args.toArray(new String[0]));
        check(c);return s!=null&&ReturnCode.isSuccess(s.getReturnCode())?s:null;
    }
    private static FFmpegSession videoExec(ConvertEngine.Canceled c,Diagnostics d,String stage,long expectedMs,int from,int to,String... tail)throws IOException{
        check(c);
        ArrayList<String> args=new ArrayList<>(Arrays.asList("-hide_banner","-nostdin","-y"));
        Collections.addAll(args,tail);
        // Per-session callbacks: a global callback cannot identify parallel exports.
        java.util.concurrent.CountDownLatch done=new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicLong mediaMs=new java.util.concurrent.atomic.AtomicLong();
        java.util.concurrent.atomic.AtomicLong frames=new java.util.concurrent.atomic.AtomicLong();
        java.util.concurrent.atomic.AtomicLong lastAdvance=new java.util.concurrent.atomic.AtomicLong(android.os.SystemClock.elapsedRealtime());
        java.util.concurrent.ExecutorService executor=java.util.concurrent.Executors.newSingleThreadExecutor();
        FFmpegSession session;
        try{
            session=FFmpegKit.executeWithArgumentsAsync(args.toArray(new String[0]),
                    finished->done.countDown(),null,stat->{
                        long time=Math.max(0L,(long)stat.getTime());
                        long frame=Math.max(0L,(long)stat.getVideoFrameNumber());
                        long previous=mediaMs.getAndAccumulate(time,Math::max);
                        long previousFrame=frames.getAndAccumulate(frame,Math::max);
                        if(time>previous||frame>previousFrame)lastAdvance.set(android.os.SystemClock.elapsedRealtime());
                    },executor);
        }finally{executor.shutdown();} // each batch worker owns its session, no shared executor queue
        if(session==null){d.nativeFailures++;d.note(stage+": нет сессии FFmpeg");return null;}
        boolean hardware=(stage.startsWith("Кодек ")||stage.startsWith("Проба "))&&stage.contains("_mediacodec");
        long started=android.os.SystemClock.elapsedRealtime();
        long stallLimit=hardware?90_000L:300_000L;
        boolean stalled=false;
        try{
            while(!done.await(1,java.util.concurrent.TimeUnit.SECONDS)){
                long now=android.os.SystemClock.elapsedRealtime(),time=mediaMs.get();
                if(c.canceled()||now-lastAdvance.get()>stallLimit){
                    stalled=!c.canceled();
                    FFmpegKit.cancel(session.getSessionId());
                    if(d.status!=null)d.status.progress(stalled?"ASC PRO · остановка зависшей сессии: "+stage:"ASC PRO · отмена",from);
                    if(!done.await(30,java.util.concurrent.TimeUnit.SECONDS))
                        throw new IOException("ASC PRO: FFmpeg не подтвердил остановку сессии. Оригинал не изменён.");
                    break;
                }
                double fraction=expectedMs>0?Math.min(.99,time/(double)expectedMs):0;
                int percent=from+(int)((to-from)*fraction);
                String position=expectedMs>0?clock(time)+" / "+clock(expectedMs):clock(time);
                if(d.status!=null)d.status.progress("ASC PRO · "+stage+" · "+position+
                        " · "+frames.get()+" кадров · "+(now-started)/1000+" с"+(from>0?" · "+(int)(fraction*100)+"% этапа":""),percent);
            }
        }catch(InterruptedException e){
            FFmpegKit.cancel(session.getSessionId());Thread.currentThread().interrupt();
            throw new IOException("ASC PRO: обработка прервана",e);
        }
        check(c);
        if(stalled){
            d.nativeFailures++;d.note(stage+": нет продвижения кадров "+stallLimit/1000+" с; сессия отменена");
            if(hardware)for(String codec:new String[]{"h264_mediacodec","hevc_mediacodec"})if(stage.contains(codec))d.failedHardware.add(codec);
            return null;
        }
        if(ReturnCode.isSuccess(session.getReturnCode()))return session;
        d.nativeFailures++;
        String logs=session==null?"нет сессии":session.getAllLogsAsString();
        if(logs==null)logs="";
        String compact=logs.trim().replaceAll("\\s+"," ");
        if(compact.length()>380)compact=compact.substring(compact.length()-380);
        d.note(stage+": "+(compact.isEmpty()?"FFmpeg завершился с ошибкой":compact));
        return null;
    }
    private static String clock(long ms){
        long sec=Math.max(0,ms)/1000;
        return String.format(Locale.US,"%02d:%02d:%02d",sec/3600,(sec/60)%60,sec%60);
    }
    private static void seek(List<String> a,long start){if(start>0)Collections.addAll(a,"-ss",sec(start));}
    private static String sec(long ms){return String.format(Locale.US,"%.3f",ms/1000.0);}
    private static void duration(List<String> a,long length){if(length>0)Collections.addAll(a,"-t",sec(length));}
    private static Result av(Context ctx,File in,ConvertEngine.Config cfg,ConvertEngine.MediaInfo inf,long start,long length,
                             boolean preview,String sourceKey,ConvertEngine.Canceled c,Status status,Diagnostics d)throws IOException{
        boolean video=cfg.mode==ConvertEngine.VIDEO;
        d.fullDurationMs=inf.durationMs;
        // Preserve additional streams/cover art rather than silently discard them in audio compression.
        com.arthenica.ffmpegkit.MediaInformationSession probe=FFprobeKit.getMediaInformation(in.getAbsolutePath());
        check(c);
        MediaInformation mi=probe==null?null:probe.getMediaInformation();
        if(mi==null || mi.getStreams()==null || mi.getAllProperties()==null){d.note("FFprobe не прочитал метаданные исходного файла");return null;}
        int videos=0,audios=0;String props=mi.getAllProperties().toString().toLowerCase(Locale.ROOT);
        for(com.arthenica.ffmpegkit.StreamInformation stream:mi.getStreams()){
            if("video".equals(stream.getType()))videos++;
            if("audio".equals(stream.getType()))audios++;
        }
        if(video && (videos!=1 || props.contains("smpte2084") || props.contains("arib-std-b67") || props.contains("dovi"))){
            throw new IOException(videos!=1?"ASC PRO: требуется одна видеодорожка; исходный файл не изменён.":"ASC PRO: HDR / Dolby Vision требует сохранения расширенного цветового диапазона. Сжатие не подтверждено; исходный файл не изменён.");
        }
        d.sourceAudioStreams=audios;
        if(!video && (audios<1 || (cfg.mode==ConvertEngine.AUDIO && (audios!=1 || mi.getStreams().size()!=1))))return null;
        // Exactly the same codec settings for the real 5-second sample and export.
        // A cached plan is keyed by URI, size, modification date and performance mode.
        boolean nativeSvt=video&&isUltra(cfg.quality)&&VideoUltraPack.loadInstalled(ctx);
        if(sourceKey!=null&&video)sourceKey+="|svt="+nativeSvt;
        Plan cached=plan(sourceKey);
        if(video&&preview&&cached!=null){
            status.update("ASC PRO · кодирование образца "+cached.codec);
            Result sample=encodeCandidate(ctx,in,cfg,cached.codec,cached.q,cached.bitrate,start,length,c,d);
            if(sample!=null){
                boolean accepted=false;
                try{
                    accepted=validVideo(sample.file,length,d.sourceAudioStreams,d,c);
                    if(accepted)return sample;
                }finally{if(!accepted)sample.file.delete();}
            }
            // Do not mix different encoder plans in the same measured estimate.
            throw new IOException("ASC PRO: не удалось закодировать образец с выбранными параметрами. "+d.details());
        }
        if(!preview&&cached!=null){
            status.update("ASC PRO · используем параметры предпросмотра");
            Result full=encodeFull(ctx,in,cfg,inf,cached,video,c,status,d);
            if(full!=null)return full;
            forget(sourceKey);
            // A cached sample is not a guarantee for the entire video. Try other plans.
            if(!video&&cfg.mode!=ConvertEngine.EXTRACT)return null;
        }
        Set<String> availableEncoders=video?new HashSet<>(ConvertEngine.videoEncoders()):Collections.emptySet();
        if(nativeSvt)availableEncoders.add("svt_native");
        String[] codecs=video?(isUltra(cfg.quality)?AscVideoOptions.ultraCodecs(availableEncoders):AscVideoOptions.codecs(availableEncoders,cfg.performance)):new String[]{"libopus","aac","flac"};
        if(video&&codecs.length==0){d.note("В установленном FFmpeg нет совместимых видеокодировщиков");return null;}
        if(video)android.util.Log.i("MediaLAB_ASC","Available ASC encoders: "+Arrays.toString(codecs));
        long probeLength=length>0?length:Math.min(video?5000:5000,inf.durationMs);
        long probeStart=length>0?start:Math.max(0,(inf.durationMs-probeLength)/2);
        if(probeLength<=0)return null;
        long bitrate=inf.durationMs>0?Math.max(100000,Math.min(120000000,(long)(in.length()*8000.0/inf.durationMs))):4000000;
        Result found=searchCandidates(ctx,in,in,cfg,inf,preview,sourceKey,c,status,d,
                video,codecs,bitrate,probeStart,probeLength);
        if(found!=null || !video || d.fullAttempted)return found;
        // A common, losslessly decoded five-second clip avoids repeating ambiguous
        // seeks in long-GOP / variable timestamp inputs. Only genuine encodes of it
        // are accepted, never a source-only or demonstration preview.
        File reference=ConvertEngine.outputTemp(ctx,"mkv");
        try{
            status.update("ASC PRO · точная повторная выборка без потери качества");
            String n=String.valueOf(ConvertEngine.threads(cfg.performance));
            ArrayList<String> args=new ArrayList<>(Arrays.asList("-threads",n));
            seek(args,probeStart);
            Collections.addAll(args,"-i",in.getAbsolutePath(),"-ss","0");
            duration(args,probeLength);
            Collections.addAll(args,"-map","0:v:0","-map","0:a?","-c","copy",
                    "-c:v:0","ffv1","-level","3","-slicecrc","1","-pix_fmt","+",
                    "-fps_mode:v:0","passthrough","-threads",n,reference.getAbsolutePath());
            // Do not start a potentially very large lossless temporary clip
            // on an almost full volume.
            long available=new android.os.StatFs(reference.getParent()).getAvailableBytes();
            long reserve=128L*1024*1024;
            long pixels=Math.max(1L,(long)inf.width*inf.height);
            long conservative=Math.min(6L*1024*1024*1024,Math.max(reserve,pixels*480L));
            if(available<conservative){d.note("Недостаточно свободного места для точной резервной выборки");return null;}
            if(videoExec(c,d,"Точная выборка",probeLength,0,0,args.toArray(new String[0]))==null||reference.length()<=0)return null;
            long actual=ConvertEngine.info(reference).durationMs;
            if(actual<=0||Math.abs(actual-probeLength)>Math.max(350,probeLength/100)){
                d.durationFailures++;d.note("Точная выборка: "+actual+" мс вместо "+probeLength+" мс");return null;
            }
            return searchCandidates(ctx,in,reference,cfg,inf,preview,sourceKey,c,status,d,
                    true,codecs,bitrate,0,probeLength);
        }finally{reference.delete();}
    }
    private static Result searchCandidates(Context ctx,File original,File sampleInput,ConvertEngine.Config cfg,
            ConvertEngine.MediaInfo inf,boolean preview,String sourceKey,ConvertEngine.Canceled c,Status status,
            Diagnostics d,boolean video,String[] codecs,long bitrate,long probeStart,long probeLength)throws IOException{
        if(isUltra(cfg.quality))return searchUltra(ctx,original,sampleInput,cfg,inf,preview,sourceKey,c,status,d,video,codecs,bitrate,probeStart,probeLength);
        for(String codec:codecs){
            if(d.failedHardware.contains(codec))continue;
            int[] steps=video?AscVideoOptions.steps(codec,cfg.performance):
                    "flac".equals(codec)?new int[]{5}:new int[]{192,320};
            for(int q:steps){
                check(c);
                if(d.failedHardware.contains(codec))break;
                if(!preview&&d.triedFull.contains(codec+":"+q))continue;
                Plan candidate=new Plan(codec,q,bitrate);
                status.update("ASC PRO · проверка реального фрагмента "+codec);
                d.encodes++;
                Result test=encodeCandidate(ctx,sampleInput,cfg,codec,q,bitrate,probeStart,probeLength,c,d);
                if(test==null)break;
                boolean pass=false;
                try{
                    long actual=ConvertEngine.info(test.file).durationMs;
                    boolean durationOk=actual>0&&Math.abs(actual-probeLength)<=Math.max(350,probeLength/100);
                    if(!durationOk){d.durationFailures++;d.note(codec+": "+actual+" мс вместо "+probeLength+" мс");}
                    else pass=video?validVideo(test.file,probeLength,d.sourceAudioStreams,d,c):audioPass(sampleInput,test.file,probeStart,probeLength,c,"flac".equals(test.format));
                    if(pass){
                        remember(sourceKey,candidate);
                        if(preview){Result result=test;test=null;return result;}
                    }
                }finally{if(test!=null)test.file.delete();}
                if(!pass)continue;
                // Bound expensive whole-video retries, but do not stop at the first rejected plan.
                if(video&&d.fullAttempts>=4)return null;
                d.fullAttempted=true;
                Result full=encodeFull(ctx,original,cfg,inf,candidate,video,c,status,d);
                if(full!=null)return full;
                forget(sourceKey);
                if(!video&&cfg.mode!=ConvertEngine.EXTRACT)return null;
                if(video&&(d.lastOversize||d.failedHardware.contains(codec)))break; // higher-quality steps of this codec are unlikely to be smaller
            }
        }
        return null;
    }
    private static final class RankedPlan{
        final Plan plan;final long bytes;
        RankedPlan(Plan p,long b){plan=p;bytes=b;}
    }
    /** Encode real samples with different codecs; rank by size at conservative settings.
     * Video has structural checks only, not a visual comparison or second quality pass. */
    private static Result searchUltra(Context ctx,File original,File sampleInput,ConvertEngine.Config cfg,
            ConvertEngine.MediaInfo inf,boolean preview,String sourceKey,ConvertEngine.Canceled c,Status status,
            Diagnostics d,boolean video,String[] codecs,long bitrate,long start,long length)throws IOException{
        ArrayList<RankedPlan> ranked=new ArrayList<>();
        Result best=null;Plan bestPlan=null;
        try{
            for(String codec:codecs){
                check(c);
                if(d.failedHardware.contains(codec))continue;
                int[] qualities=video?new int[]{AscVideoOptions.ultraQuality(codec)}:"flac".equals(codec)?new int[]{5}:new int[]{160,192,256,320};
                for(int q:qualities){
                Plan p=new Plan(codec,q,bitrate);
                status.update("ASC ULTRA · проба "+codec);
                d.encodes++;
                Result sample=encodeCandidate(ctx,sampleInput,cfg,codec,q,bitrate,start,length,c,d);
                if(sample==null)continue;
                try{
                    if(video){if(!validVideo(sample.file,length,d.sourceAudioStreams,d,c))continue;}
                    else {long actual=ConvertEngine.info(sample.file).durationMs;if(actual<=0||Math.abs(actual-length)>Math.max(350,length/100)||!audioPass(sampleInput,sample.file,start,length,c,"flac".equals(sample.format)))continue;}
                    ranked.add(new RankedPlan(p,sample.file.length()));
                    if(preview&&(best==null||sample.file.length()<best.file.length())){
                        if(best!=null)best.file.delete();
                        best=sample;bestPlan=p;sample=null;
                    }
                }finally{if(sample!=null)sample.file.delete();}
                }
            }
            if(preview&&best!=null){remember(sourceKey,bestPlan);Result result=best;best=null;return result;}
            ranked.sort(Comparator.comparingLong(p->p.bytes));
            for(RankedPlan entry:ranked){
                check(c);
                if(d.fullAttempts>=4)break;
                d.fullAttempted=true;
                status.update("ASC ULTRA · выбран "+entry.plan.codec);
                Result result=encodeFull(ctx,original,cfg,inf,entry.plan,video,c,status,d);
                if(result!=null){remember(sourceKey,entry.plan);return result;}
            }
            return null;
        }finally{if(best!=null)best.file.delete();}
    }
    private static Result encodeFull(Context ctx,File in,ConvertEngine.Config cfg,ConvertEngine.MediaInfo inf,
                                     Plan plan,boolean video,ConvertEngine.Canceled c,Status status,Diagnostics d)throws IOException{
        d.fullAttempts++;
        d.triedFull.add(plan.codec+":"+plan.q);
        d.lastOversize=false;
        status.update("ASC PRO · основное кодирование "+plan.codec+" · попытка "+d.fullAttempts);
        Result full=encodeCandidate(ctx,in,cfg,plan.codec,plan.q,plan.bitrate,0,0,c,d);
        if(full==null)return null;
        try{
            if(cfg.mode!=ConvertEngine.EXTRACT&&full.file.length()>=in.length()){
                d.sizeFailures++;d.lastOversize=true;
                d.note(plan.codec+": результат не меньше оригинала ("+full.file.length()+" / "+in.length()+" байт)");return null;
            }
            long actual=ConvertEngine.info(full.file).durationMs;
            if(inf.durationMs>0&&(actual<=0||Math.abs(actual-inf.durationMs)>Math.max(120,inf.durationMs/100))){
                d.durationFailures++;d.note(plan.codec+": полная длительность "+actual+" мс вместо "+inf.durationMs+" мс");return null;
            }
            if(video){
                status.update("ASC PRO · проверка контейнера и дорожек");
                if(!validVideo(full.file,inf.durationMs,d.sourceAudioStreams,d,c))return null;
            }else{
                status.update("ASC PRO · полный контроль качества "+plan.codec);
                if(!audioPass(in,full.file,0,0,c,"flac".equals(full.format)))return null;
            }
            Result result=full;full=null;return result;
        }finally{if(full!=null)full.file.delete();}
    }
    private static Result encodeCandidate(Context ctx,File in,ConvertEngine.Config cfg,String codec,int q,long bitrate,long start,long length,ConvertEngine.Canceled c,Diagnostics d)throws IOException{
        boolean video=cfg.mode==ConvertEngine.VIDEO;
        String fmt=video?"mkv":"libopus".equals(codec)?"opus":"aac".equals(codec)?"m4a":"flac";
        File out=ConvertEngine.outputTemp(ctx,fmt);
        try{
            if(video&&"svt_native".equals(codec)){
                if(!encodeNativeSvt(ctx,in,out,q,cfg.performance,start,length,c,d))return null;
                Result result=new Result(out,fmt,false);out=null;return result;
            }
            String n=String.valueOf(ConvertEngine.threads(cfg.performance));
            ArrayList<String> a=new ArrayList<>(Arrays.asList("-threads",n,"-filter_threads",n,"-filter_complex_threads",n));
            seek(a,start);Collections.addAll(a,"-i",in.getAbsolutePath());
            // Input-side seek decodes video accurately but copied audio can retain
            // packets from the previous keyframe. Output-side zero seek discards
            // negative preroll without decoding the entire preceding movie.
            if(video&&length>0)Collections.addAll(a,"-ss","0");
            duration(a,length);
            if(video){
                // Preserve original frame cadence; Matroska rounds timestamps to milliseconds.
                Collections.addAll(a,"-fps_mode:v:0","passthrough");
                if(length>0)Collections.addAll(a,"-map","0:v:0","-map","0:a?");
                else Collections.addAll(a,"-map","0");
                Collections.addAll(a,"-c","copy","-c:v:0",codec);
                // Audio is copied in both sample and export; only the comparison player converts it.
                AscVideoOptions.append(a,codec,q,bitrate,cfg.performance);
            }else{
                Collections.addAll(a,"-map","0:a:0","-vn","-c:a",codec);
                if("flac".equals(codec))Collections.addAll(a,"-compression_level","5");
                else Collections.addAll(a,"-b:a",q+"k");
            }
            if(length==0&&cfg.mode!=ConvertEngine.EXTRACT&&in.length()>0&&in.length()<Long.MAX_VALUE-1048576L)
                Collections.addAll(a,"-fs",String.valueOf(in.length()+1048576L)); // stop a clearly oversized encode early
            Collections.addAll(a,"-threads",n,"-map_metadata","0",out.getAbsolutePath());
            if(videoExec(c,d,(length==0?"Кодек ":"Проба ")+codec+" / "+q+(length==0?" · попытка "+d.fullAttempts:""),length>0?length:d.fullDurationMs,length==0?8:0,length==0?75:0,a.toArray(new String[0]))==null||out.length()<=0)return null;
            Result result=new Result(out,fmt,false);out=null;return result;
        }finally{if(out!=null)out.delete();}
    }
    /** Use an exact lossless sample for preview; full export streams from original without RAW files. */
    private static boolean encodeNativeSvt(Context ctx,File input,File output,int q,int performance,
            long start,long length,ConvertEngine.Canceled c,Diagnostics d)throws IOException{
        if(!VideoUltraPack.loadInstalled(ctx))return false;
        File sample=null;
        try{
            File source=input;
            if(length>0){
                ConvertEngine.MediaInfo info=ConvertEngine.info(input);
                long pixels=Math.max(1L,(long)info.width*info.height);
                long reserve=Math.min(8L*1024*1024*1024,Math.max(128L*1024*1024,pixels*480L));
                sample=ConvertEngine.outputTemp(ctx,"mkv");
                if(new android.os.StatFs(sample.getParent()).getAvailableBytes()<reserve){d.note("SVT-AV1: недостаточно места для образца");return false;}
                String n=String.valueOf(ConvertEngine.threads(performance));
                ArrayList<String> args=new ArrayList<>(Arrays.asList("-threads",n));
                seek(args,start);Collections.addAll(args,"-i",input.getAbsolutePath(),"-ss","0");duration(args,length);
                Collections.addAll(args,"-map","0:v:0","-map","0:a?","-c","copy","-c:v:0","ffv1","-level","3",
                        "-pix_fmt","+","-fps_mode:v:0","passthrough","-threads",n,sample.getAbsolutePath());
                if(videoExec(c,d,"SVT-AV1 · точный образец",length,0,0,args.toArray(new String[0]))==null||sample.length()<=0)return false;
                source=sample;
            }
            check(c);
            long total=length>0?length:d.fullDurationMs;
            long limit=length>0?0:input.length()+1048576L;
            long[] last={0};
            int rc=VideoUltraPack.encode(ctx,source,output,q,performance,limit,(ms,frames)->{
                if(c.canceled()||Thread.currentThread().isInterrupted())return false;
                long now=android.os.SystemClock.elapsedRealtime();
                if(now-last[0]>=800){
                    last[0]=now;double fraction=total>0?Math.min(.99,ms/(double)total):0;
                    if(d.status!=null)d.status.progress("ASC ULTRA · SVT-AV1 · "+clock(ms)+" / "+clock(total)+" · "+frames+" кадров",length>0?0:8+(int)(fraction*67));
                }
                return true;
            });
            check(c);
            if(rc<0||output.length()<=0){d.nativeFailures++;d.note("SVT-AV1: "+rc+" (неподдерживаемый поток или ошибка кодирования)");return false;}
            return true;
        }finally{if(sample!=null)sample.delete();}
    }
    /** Structural check only: no frame comparison, quality scoring or second video encode. */
    private static boolean validVideo(File output,long expectedMs,int expectedAudio,Diagnostics d,ConvertEngine.Canceled c)throws IOException{
        check(c);
        if(!output.isFile()||output.length()<=0)return false;
        com.arthenica.ffmpegkit.MediaInformationSession probe=FFprobeKit.getMediaInformation(output.getAbsolutePath());
        MediaInformation mi=probe==null?null:probe.getMediaInformation();
        check(c);
        if(mi==null||mi.getStreams()==null){d.note("Не читается контейнер результата");return false;}
        int videos=0,audios=0;
        for(com.arthenica.ffmpegkit.StreamInformation stream:mi.getStreams()){
            if("video".equals(stream.getType()))videos++;
            if("audio".equals(stream.getType()))audios++;
        }
        if(videos!=1||audios!=expectedAudio){
            d.note("Дорожки результата: видео "+videos+", аудио "+audios+" (ожидалось "+expectedAudio+")");return false;
        }
        long actual=ConvertEngine.info(output).durationMs;
        if(expectedMs>0&&(actual<=0||Math.abs(actual-expectedMs)>Math.max(350,expectedMs/100))){
            d.durationFailures++;d.note("Длительность результата "+actual+" мс вместо "+expectedMs+" мс");return false;
        }
        return true;
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
