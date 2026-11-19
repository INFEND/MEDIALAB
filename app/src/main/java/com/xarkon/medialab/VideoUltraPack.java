package com.xarkon.medialab;

import android.content.Context;
import org.json.JSONObject;
import java.io.*;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/** Optional standalone SVT-AV1 ARM64 runtime; isolated FFmpeg symbols. */
final class VideoUltraPack {
    private static final String NAME="libmedialab_video_ultra.so";
    private static final String URL="https://github.com/INFEND/MEDIALAB/releases/download/v1.3.68/video-ultra-arm64-v1.so";
    private static final AtomicBoolean restart=new AtomicBoolean();
    private static volatile boolean loaded;
    private static volatile boolean checked;
    private static final class Spec {
        final long bytes;final String hash;
        Spec(Context c)throws Exception{
            ByteArrayOutputStream out=new ByteArrayOutputStream();
            try(InputStream in=c.getAssets().open("video-ultra.json")){
                byte[] b=new byte[4096];int n;
                while((n=in.read(b))!=-1){if(out.size()+n>8192)throw new IOException("Некорректный манифест");out.write(b,0,n);}
            }
            JSONObject j=new JSONObject(out.toString("UTF-8"));
            bytes=j.getLong("bytes");hash=j.getString("sha256");
            if(j.getInt("api")!=1||!"arm64-v8a".equals(j.getString("abi"))||!URL.equals(j.getString("url"))||
                bytes<=0||bytes>64L*1024*1024||!hash.matches("[0-9a-f]{64}"))throw new IOException("Некорректный манифест VIDEO ULTRA");
        }
    }
    private static File dir(Context c,Spec spec)throws IOException{
        File f=new File(c.getNoBackupFilesDir(),"video-ultra/"+spec.hash);
        if(!f.isDirectory()&&!f.mkdirs())throw new IOException("Нет хранилища VIDEO ULTRA");return f;
    }
    private static boolean valid(File f,Spec spec)throws Exception{
        if(!f.isFile()||f.length()!=spec.bytes)return false;
        MessageDigest d=MessageDigest.getInstance("SHA-256");
        try(InputStream in=new FileInputStream(f)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)d.update(b,0,n);}
        StringBuilder h=new StringBuilder();for(byte b:d.digest())h.append(String.format(Locale.US,"%02x",b&255));
        return spec.hash.equals(h.toString());
    }
    static void restart(){restart.set(true);}
    static String info(Context c){
        try{
            Spec s=new Spec(c);File d=dir(c,s);File f=new File(d,NAME);File part=new File(d,NAME+".part");
            return (loaded?"Подключён":f.isFile()?"Установлен":part.isFile()?"Скачано "+ConvertEngine.humanBytes(part.length()):"Не установлен")
                +" / "+ConvertEngine.humanBytes(s.bytes)+" · SVT-AV1";
        }catch(Exception e){return "Модуль отсутствует в этой сборке";}
    }
    static boolean loadInstalled(Context c){
        if(loaded)return true;if(checked||VideoUltraService.running)return false;
        synchronized(VideoUltraPack.class){return loadVerified(c);}
    }
    private static boolean loadVerified(Context c){
        if(loaded)return true;if(checked)return false;checked=true;
        try{
            Spec s=new Spec(c);File f=new File(dir(c,s),NAME);
            if(valid(f,s)){System.load(f.getAbsolutePath());loaded=nativeVersion()==1;}
        }catch(Exception|LinkageError ignored){}
        return loaded;
    }
    static synchronized void prepare(Context c,ResumableModuleDownload.Progress p)throws Exception{
        if(loaded)return;
        Spec s=new Spec(c);File d=dir(c,s),f=new File(d,NAME),part=new File(d,NAME+".part"),meta=new File(part.getPath()+".meta");
        if(!valid(f,s)){
            p.status("VIDEO ULTRA: докачка SVT-AV1");
            ResumableModuleDownload.download(URL,"VIDEO ULTRA",part,s.bytes,restart,p);
            if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("Пауза");
            if(!valid(part,s)){part.delete();meta.delete();throw new IOException("SHA-256 VIDEO ULTRA не совпадает");}
            if(!part.setReadOnly())throw new IOException("Не удалось защитить библиотеку");
            if(!part.renameTo(f)){part.setWritable(true);throw new IOException("Не удалось установить VIDEO ULTRA");}
            meta.delete();
        }
        checked=false;
        if(!loadVerified(c))throw new IOException("Не удалось подключить JNI VIDEO ULTRA");
        p.status("VIDEO ULTRA подключён");
    }
    interface Progress { boolean update(long milliseconds,long frames); }
    static int encode(Context c,File in,File out,int quality,int performance,long cap,Progress p){
        if(!loadInstalled(c))return -1;
        try{return nativeEncode(in.getAbsolutePath(),out.getAbsolutePath(),quality,performance==3?8:6,
                Math.max(1,Math.min(8,ConvertEngine.threads(performance))),cap,p);}
        catch(RuntimeException|LinkageError e){return -1;}
    }
    private static native int nativeVersion();
    private static native int nativeEncode(String in,String out,int quality,int preset,int threads,long cap,Progress progress);
}
