package com.xarkon.medialab;
import android.content.Context;
import android.os.Build;
import android.os.Process;
import org.json.*;
import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

/** Verified persistent FFmpeg runtime, independent of normal APK updates. */
public final class NativeLibraryPack {
    public interface Progress { void status(String message); }
    private static final String URL="https://github.com/INFEND/MEDIALAB/releases/download/v1.3.27/ffmpeg-arm64-8.1.9.zip";
    private static final String VERSION="8.1.9";
    private static final long MAX=120L*1024*1024;
    private static File installed;
    private static Map<String,Entry> manifest;
    private static final java.util.concurrent.atomic.AtomicBoolean restartRequested=new java.util.concurrent.atomic.AtomicBoolean();
    public static void restartPartialDownload(){restartRequested.set(true);}
    private static final Set<String> loaded=new HashSet<>(),loading=new HashSet<>();
    private static class Entry {
        String name,hash;long size;List<String> deps=new ArrayList<>();
        Entry(JSONObject j)throws JSONException{
            name=j.getString("name");hash=j.getString("sha256");size=j.getLong("bytes");
            if(!name.matches("lib[A-Za-z0-9_+.-]+\\.so")||!hash.matches("[0-9a-f]{64}")||size<=0||size>MAX)
                throw new JSONException("Invalid native file");
            JSONArray a=j.getJSONArray("dependencies");
            for(int i=0;i<a.length();i++)deps.add(a.getString(i));
        }
    }
    private NativeLibraryPack(){}
    private static String hash(File f)throws Exception{
        MessageDigest d=MessageDigest.getInstance("SHA-256");
        try(InputStream in=new FileInputStream(f)){byte[] b=new byte[131072];int n;while((n=in.read(b))!=-1)d.update(b,0,n);}
        StringBuilder s=new StringBuilder();for(byte b:d.digest())s.append(String.format(Locale.US,"%02x",b&255));return s.toString();
    }
    private static boolean valid(File folder,Map<String,Entry> entries)throws Exception{
        if(!folder.isDirectory())return false;
        String[] names=folder.list();if(names==null||names.length!=entries.size())return false;
        for(Entry e:entries.values()){
            File f=new File(folder,e.name);
            if(!f.isFile()||f.length()!=e.size||!hash(f).equals(e.hash))return false;
        }
        return true;
    }
    private static void delete(File f)throws IOException{
        if(f.isDirectory()){File[] list=f.listFiles();if(list!=null)for(File x:list)delete(x);}
        if(f.exists()&&!f.delete())throw new IOException("Не удалось удалить временный файл");
    }
    private static Map<String,Entry> readManifest(Context c)throws Exception{
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        try(InputStream in=c.getAssets().open("ffmpeg-pack.json")){
            byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){if(out.size()+n>65536)throw new IOException("Манифест слишком большой");out.write(b,0,n);}
        }
        JSONObject j=new JSONObject(out.toString("UTF-8"));
        if(!VERSION.equals(j.getString("version"))||!"arm64-v8a".equals(j.getString("abi"))||!URL.equals(j.getString("url")))
            throw new IOException("Неверный манифест FFmpeg");
        Map<String,Entry> result=new LinkedHashMap<>();long total=0;JSONArray a=j.getJSONArray("files");
        for(int i=0;i<a.length();i++){Entry e=new Entry(a.getJSONObject(i));if(result.put(e.name,e)!=null)throw new IOException("Повтор файла");total+=e.size;}
        if(total>MAX||!result.containsKey("libffmpegkit.so")||!result.containsKey("libffmpegkit_abidetect.so"))throw new IOException("Неполный пакет FFmpeg");
        for(Entry e:result.values())for(String dep:e.deps)if(!result.containsKey(dep))throw new IOException("Неизвестная зависимость");
        return result;
    }
    public static synchronized boolean isPrepared(){return installed!=null&&manifest!=null;}
    public static synchronized void prepare(Context c,Progress progress)throws Exception{
        if(isPrepared())return;
        if(!Process.is64Bit()||!Arrays.asList(Build.SUPPORTED_64_BIT_ABIS).contains("arm64-v8a"))throw new IOException("FFmpeg требует ARM64");
        Map<String,Entry> entries=readManifest(c);
        File root=new File(c.getNoBackupFilesDir(),"ffmpeg");
        if(!root.isDirectory()&&!root.mkdirs())throw new IOException("Не удалось создать хранилище FFmpeg");
        File target=new File(root,VERSION+"-arm64");
        progress.status("FFmpeg: проверка локальной библиотеки…");
        if(valid(target,entries)){installed=target;manifest=entries;return;}
        File temp=new File(root,VERSION+"-staging"),archive=new File(root,VERSION+".zip.part");
        delete(temp);
        long total=0;for(Entry e:entries.values())total+=e.size;
        File meta=new File(archive.getPath()+".meta");
        if(restartRequested.getAndSet(false)){delete(archive);delete(meta);}
        if(new android.os.StatFs(root.getAbsolutePath()).getAvailableBytes()<total+Math.max(0L,total+2L*1024*1024-archive.length())+16L*1024*1024)throw new IOException("Недостаточно места для библиотеки FFmpeg");
        boolean downloaded=false;
        try{
            progress.status("FFmpeg: докачка библиотеки "+VERSION+"…");
            download(archive,total+2L*1024*1024,progress);
            downloaded=true;
            if(!temp.mkdir())throw new IOException("Не удалось создать временный каталог");
            Set<String> seen=new HashSet<>();
            try(ZipInputStream zip=new ZipInputStream(new BufferedInputStream(new FileInputStream(archive)))){
                ZipEntry z;
                while((z=zip.getNextEntry())!=null){
                    Entry e=entries.get(z.getName());
                    if(z.isDirectory()||e==null||!seen.add(e.name))throw new IOException("Неизвестный файл в пакете");
                    File f=new File(temp,e.name);long bytes=0;
                    try(FileOutputStream out=new FileOutputStream(f)){
                        if(!f.setReadOnly())throw new IOException("Не удалось защитить библиотеку");
                        byte[] b=new byte[131072];int n;
                        while((n=zip.read(b))!=-1){bytes+=n;if(bytes>e.size)throw new IOException("Неверный размер библиотеки");out.write(b,0,n);}
                        out.getFD().sync();
                    }
                    if(bytes!=e.size||!hash(f).equals(e.hash))throw new IOException("SHA-256 не совпадает: "+e.name);
                    progress.status("FFmpeg: проверено "+seen.size()+"/"+entries.size());
                }
            }
            if(seen.size()!=entries.size()||!valid(temp,entries))throw new IOException("Пакет FFmpeg неполный");
            delete(target);
            if(!temp.renameTo(target))throw new IOException("Не удалось установить библиотеку");
            installed=target;manifest=entries;
        }finally{delete(temp);if(downloaded){delete(archive);delete(meta);}}
    }
    // A partial file is retained across process death, OTA and temporary network errors.
    // Only a verified complete archive reaches extraction. The manifest verifies every .so.
    private static void download(File f,long limit,Progress p)throws IOException{
        IOException failure=null;
        for(int attempt=0;attempt<4;attempt++){
            try{downloadOnce(f,limit,p);return;}
            catch(IOException e){
                failure=e;
                if(Thread.currentThread().isInterrupted())throw e;
                if(attempt<3){
                    p.status("FFmpeg: связь прервана, сохранено "+ConvertEngine.humanBytes(f.length())+"; повтор "+(attempt+1)+"/3");
                    try{Thread.sleep(1000L<<attempt);}
                    catch(InterruptedException stop){Thread.currentThread().interrupt();throw new InterruptedIOException("Докачка остановлена");}
                }
            }
        }
        throw new IOException("Докачка прервана; сохранено "+ConvertEngine.humanBytes(f.length())+". Повторите загрузку.",failure);
    }
    private static void downloadOnce(File f,long limit,Progress p)throws IOException{
        File meta=new File(f.getPath()+".meta");
        Properties saved=new Properties();
        if(meta.isFile()){
            try(InputStream in=new FileInputStream(meta)){saved.load(in);}
            catch(IOException|IllegalArgumentException bad){saved.clear();}
        }
        long offset=f.length(),expected=-1;
        try{expected=Long.parseLong(saved.getProperty("total","-1"));}catch(NumberFormatException ignored){}
        String validator=saved.getProperty("validator","");
        if(offset>limit||!URL.equals(saved.getProperty("url"))||expected<=0||expected>limit||offset>expected){
            delete(f);delete(meta);offset=0;expected=-1;validator="";
        }
        if(offset>0&&offset==expected){p.status("FFmpeg: архив загружен, проверка…");return;}
        HttpURLConnection conn=(HttpURLConnection)new URL(URL).openConnection();
        conn.setConnectTimeout(20000);conn.setReadTimeout(30000);conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent","MediaLAB-FFmpeg/2");
        conn.setRequestProperty("Accept-Encoding","identity");
        if(offset>0){
            conn.setRequestProperty("Range","bytes="+offset+"-");
            if(!validator.isEmpty())conn.setRequestProperty("If-Range",validator);
        }
        try{
            int code=conn.getResponseCode();
            if(!"https".equalsIgnoreCase(conn.getURL().getProtocol()))throw new IOException("Небезопасное перенаправление");
            long total=conn.getContentLengthLong(),responseBytes=total;
            String newValidator=conn.getHeaderField("ETag");
            if(newValidator==null||newValidator.startsWith("W/"))newValidator=conn.getHeaderField("Last-Modified");
            if(newValidator==null)newValidator="";
            if(code==206&&offset>0){
                String range=conn.getHeaderField("Content-Range");
                java.util.regex.Matcher m=java.util.regex.Pattern.compile("bytes (\\d+)-(\\d+)/(\\d+)").matcher(range==null?"":range);
                if(!m.matches()) {delete(f);delete(meta);throw new IOException("Неверный Content-Range");}
                long start=Long.parseLong(m.group(1)),end=Long.parseLong(m.group(2));total=Long.parseLong(m.group(3));
                if(start!=offset||end<start||end>=total||total!=expected||
                   (responseBytes>=0&&responseBytes!=end-start+1)||
                   (!validator.isEmpty()&&!newValidator.isEmpty()&&!validator.equals(newValidator))){
                    delete(f);delete(meta);throw new IOException("Пакет изменился; загрузка начнётся заново");
                }
                responseBytes=end-start+1;
            }else if(code==200){
                // A server ignoring Range or changing the asset must never be appended to.
                offset=0;delete(f);delete(meta);
            }else if(code==416&&offset>0){
                delete(f);delete(meta);throw new IOException("Диапазон устарел; загрузка начнётся заново");
            }else throw new IOException("Сервер библиотеки: HTTP "+code);
            if(total<=0||total>limit)throw new IOException("Неизвестный или неверный размер пакета");
            Properties next=new Properties();next.setProperty("url",URL);next.setProperty("total",Long.toString(total));
            next.setProperty("validator",newValidator.isEmpty()&&offset>0?validator:newValidator);
            File tmp=new File(meta.getPath()+".tmp");
            try(FileOutputStream out=new FileOutputStream(tmp)){next.store(out,null);out.getFD().sync();}
            if(!tmp.renameTo(meta))throw new IOException("Не удалось сохранить состояние докачки");
            long count=offset,last=0,lastSync=offset;
            p.status("FFmpeg: "+ConvertEngine.humanBytes(count)+" / "+ConvertEngine.humanBytes(total)+" ("+(count*100/total)+"%)");
            try(InputStream in=new BufferedInputStream(conn.getInputStream());RandomAccessFile out=new RandomAccessFile(f,"rw")){
                if(out.length()!=offset)throw new IOException("Размер частичного файла изменился");
                out.seek(offset);
                try{
                    byte[] b=new byte[131072];int n;
                    while((n=in.read(b))!=-1){
                        if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("Докачка остановлена");
                        if(count+n>total||count+n>limit)throw new IOException("Слишком большой пакет");
                        out.write(b,0,n);count+=n;
                        if(count-lastSync>=1024*1024){out.getFD().sync();lastSync=count;}
                        long now=android.os.SystemClock.elapsedRealtime();
                        if(now-last>300){last=now;p.status("FFmpeg: "+ConvertEngine.humanBytes(count)+" / "+ConvertEngine.humanBytes(total)+" ("+(count*100/total)+"%)");}
                    }
                }finally{out.getFD().sync();}
                if(count!=total)throw new IOException("Загрузка прервана");
            }
        }finally{conn.disconnect();}
    }
    public static synchronized void loadLibrary(String library){
        if(!isPrepared())throw new UnsatisfiedLinkError("FFmpeg pack is not prepared");
        load(System.mapLibraryName(library));
    }
    private static void load(String name){
        if(loaded.contains(name))return;
        Entry e=manifest.get(name);
        if(e==null||!loading.add(name))throw new UnsatisfiedLinkError("Invalid FFmpeg dependency: "+name);
        try{
            for(String dep:e.deps)load(dep);
            System.load(new File(installed,name).getAbsolutePath());
            loaded.add(name);
        }finally{loading.remove(name);}
    }
}
