package com.xarkon.medialab;

import android.content.Context;
import android.graphics.Bitmap;
import org.json.JSONObject;
import java.io.*;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/** Optional standalone JPEG XL + OxiPNG ARM64 runtime; no FFmpeg symbols. */
final class PhotoExtraPack {
    private static final String NAME="libmedialab_photo_extra.so";
    private static final String URL="https://github.com/INFEND/MEDIALAB/releases/download/v1.3.67/photo-extra-arm64-v1.so";
    private static final String FALLBACK_URL=URL.replace("/v1.3.67/","/v1.3.68/");
    private static final AtomicBoolean restart=new AtomicBoolean();
    private static volatile boolean loaded;
    private static volatile boolean checked;
    private static final class Spec {
        final long bytes;final String hash,url;
        Spec(Context c)throws Exception{
            ByteArrayOutputStream out=new ByteArrayOutputStream();
            try(InputStream in=c.getAssets().open("photo-extra.json")){
                byte[] b=new byte[4096];int n;
                while((n=in.read(b))!=-1){if(out.size()+n>8192)throw new IOException("Некорректный манифест");out.write(b,0,n);}
            }
            JSONObject j=new JSONObject(out.toString("UTF-8"));
            bytes=j.getLong("bytes");hash=j.getString("sha256");url=j.getString("url");
            if(j.getInt("api")!=1||!"arm64-v8a".equals(j.getString("abi"))||!(URL.equals(url)||FALLBACK_URL.equals(url))||
                bytes<=0||bytes>32L*1024*1024||!hash.matches("[0-9a-f]{64}"))throw new IOException("Некорректный манифест PHOTO EXTRA");
        }
    }
    private static File dir(Context c,Spec spec)throws IOException{
        File f=new File(c.getNoBackupFilesDir(),"photo-extra/"+spec.hash);
        if(!f.isDirectory()&&!f.mkdirs())throw new IOException("Нет хранилища PHOTO EXTRA");return f;
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
                +" / "+ConvertEngine.humanBytes(s.bytes)+" · JPEG XL + OxiPNG";
        }catch(Exception e){return "Модуль отсутствует в этой сборке";}
    }
    static boolean loadInstalled(Context c){
        if(loaded)return true;if(checked||PhotoExtraService.running)return false;
        synchronized(PhotoExtraPack.class){return loadVerified(c);}
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
            p.status("PHOTO EXTRA: докачка JPEG XL + OxiPNG");
            ResumableModuleDownload.download(s.url,"PHOTO EXTRA",part,s.bytes,restart,p);
            if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("Пауза");
            if(!valid(part,s)){part.delete();meta.delete();throw new IOException("SHA-256 PHOTO EXTRA не совпадает");}
            if(!part.setReadOnly())throw new IOException("Не удалось защитить библиотеку");
            if(!part.renameTo(f)){part.setWritable(true);throw new IOException("Не удалось установить PHOTO EXTRA");}
            meta.delete();
        }
        checked=false;
        if(!loadVerified(c))throw new IOException("Не удалось подключить JNI PHOTO EXTRA");
        p.status("PHOTO EXTRA подключён");
    }
    static boolean optimizePng(Context c,File input,File out,int preset){
        if(!loadInstalled(c))return false;
        try{return nativeOptimizePng(input.getAbsolutePath(),out.getAbsolutePath(),preset);}
        catch(RuntimeException|LinkageError e){return false;}
    }
    static boolean encodeJxl(Context c,Bitmap bitmap,File out,int quality){
        if(bitmap==null||bitmap.hasAlpha()||!loadInstalled(c))return false;
        Bitmap rgba=bitmap;
        try{
            if(bitmap.getConfig()!=Bitmap.Config.ARGB_8888){rgba=bitmap.copy(Bitmap.Config.ARGB_8888,false);if(rgba==null)return false;}
            return nativeEncodeJxl(rgba,out.getAbsolutePath(),quality);
        }catch(RuntimeException|LinkageError e){return false;}
        finally{if(rgba!=null&&rgba!=bitmap)rgba.recycle();}
    }
    static boolean convertManual(Context c,File input,File out,ConvertEngine.Config cfg,ConvertEngine.Canceled canceled)throws IOException{
        if(cfg.mode!=ConvertEngine.IMAGE)return false;
        boolean fromJxl="jxl".equals(ConvertEngine.extension(input.getName())),toJxl="jxl".equals(cfg.format);
        if(!fromJxl&&!toJxl)return false;
        if(!loadInstalled(c))throw new IOException("Сначала скачайте PHOTO EXTRA в настройках");
        if(canceled.canceled())throw new IOException("Операция отменена");
        Bitmap bitmap=SmartCompression.bitmap(c,input);
        File temp=null;
        try{
            if(toJxl){
                if(!encodeJxl(c,bitmap,out,cfg.quality))throw new IOException("JPEG XL: поддерживаются непрозрачные SDR RGB 8 бит");
                Bitmap verify=decodeJxl(c,out);
                if(verify==null)throw new IOException("JPEG XL не прошёл декодирование");
                try{if(verify.getWidth()!=bitmap.getWidth()||verify.getHeight()!=bitmap.getHeight())throw new IOException("Размеры JPEG XL не совпадают");}
                finally{verify.recycle();}
            }else{
                temp=ConvertEngine.outputTemp(c,"png");
                try(FileOutputStream stream=new FileOutputStream(temp)){if(!bitmap.compress(Bitmap.CompressFormat.PNG,100,stream))throw new IOException("PNG intermediate");}
                if(!ConvertEngine.convertNativeImage(temp,out,cfg,canceled)){
                    com.arthenica.ffmpegkit.FFmpegSession result=ConvertEngine.convert(temp,out,cfg,new ConvertEngine.MediaInfo(),0,0);
                    if(result==null||!com.arthenica.ffmpegkit.ReturnCode.isSuccess(result.getReturnCode()))throw new IOException("Не удалось конвертировать JPEG XL");
                }
            }
            if(canceled.canceled())throw new IOException("Операция отменена");
            return out.isFile()&&out.length()>0;
        }finally{bitmap.recycle();if(temp!=null)temp.delete();}
    }
    static Bitmap decodeJxl(Context c,File input){
        if(!loadInstalled(c))return null;
        try{return nativeDecodeJxl(input.getAbsolutePath());}
        catch(RuntimeException|LinkageError e){return null;}
    }
    static Bitmap decodeUri(Context c,android.net.Uri uri,int max){
        if(uri==null||!loadInstalled(c))return null;
        File temp=null;
        try{
            if("file".equals(uri.getScheme()))return scaled(decodeJxl(c,new File(uri.getPath())),max);
            temp=File.createTempFile("jxl-preview-",".jxl",c.getCacheDir());
            try(InputStream in=c.getContentResolver().openInputStream(uri);FileOutputStream out=new FileOutputStream(temp)){
                if(in==null)return null;
                byte[] b=new byte[65536];int n;long total=0;
                while((n=in.read(b))!=-1){total+=n;if(total>128L*1024*1024)return null;out.write(b,0,n);}
            }
            return scaled(decodeJxl(c,temp),max);
        }catch(Exception|OutOfMemoryError e){return null;}finally{if(temp!=null)temp.delete();}
    }
    private static Bitmap scaled(Bitmap bitmap,int max){
        if(bitmap==null)return null;
        int w=bitmap.getWidth(),h=bitmap.getHeight(),large=Math.max(w,h);
        if(large<=max)return bitmap;
        Bitmap result=Bitmap.createScaledBitmap(bitmap,Math.max(1,(int)((long)w*max/large)),Math.max(1,(int)((long)h*max/large)),true);
        if(result!=bitmap)bitmap.recycle();return result;
    }
    private static native int nativeVersion();
    private static native boolean nativeOptimizePng(String input,String output,int preset);
    private static native boolean nativeEncodeJxl(Bitmap b,String path,int quality);
    private static native Bitmap nativeDecodeJxl(String path);
}
