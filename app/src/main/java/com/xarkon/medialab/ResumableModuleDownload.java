package com.xarkon.medialab;
import java.io.*;
import java.net.*;
import java.util.Properties;

/** Shared restartable transport for versioned downloadable modules. Integrity is verified by each installer. */
final class ResumableModuleDownload {
    interface Progress { void status(String message); }
    private ResumableModuleDownload(){}
    private static void delete(File f)throws IOException{
        if(f.exists()&&!f.delete())throw new IOException("Не удалось удалить частичный файл");
    }
    // A partial file is retained across process death, OTA and temporary network errors.
    // Only a verified complete archive reaches extraction. The manifest verifies every .so.
    static void download(String URL,String label,File f,long limit,java.util.concurrent.atomic.AtomicBoolean restartRequested,Progress p)throws IOException{
        IOException failure=null;
        for(int attempt=0;attempt<4;attempt++){
            try{
                if(restartRequested.getAndSet(false)){delete(f);delete(new File(f.getPath()+".meta"));}
                downloadOnce(URL,label,f,limit,restartRequested,p);
                if(restartRequested.get())throw new InterruptedIOException("Перезапуск докачки");
                return;
            }
            catch(IOException e){
                failure=e;
                if(Thread.currentThread().isInterrupted())throw e;
                if(restartRequested.get()){attempt=-1;continue;}
                if(attempt<3){
                    p.status(label+": связь прервана, сохранено "+ConvertEngine.humanBytes(f.length())+"; повтор "+(attempt+1)+"/3");
                    try{Thread.sleep(1000L<<attempt);}
                    catch(InterruptedException stop){Thread.currentThread().interrupt();throw new InterruptedIOException("Докачка остановлена");}
                }
            }
        }
        throw new IOException("Докачка прервана; сохранено "+ConvertEngine.humanBytes(f.length())+". Повторите загрузку.",failure);
    }
    private static void downloadOnce(String URL,String label,File f,long limit,java.util.concurrent.atomic.AtomicBoolean restartRequested,Progress p)throws IOException{
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
        if(offset>0&&offset==expected){p.status(label+": архив загружен, проверка…");return;}
        HttpURLConnection conn=(HttpURLConnection)new URL(URL).openConnection();
        conn.setConnectTimeout(20000);conn.setReadTimeout(30000);conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent","MediaLAB-Modules/1");
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
            p.status(label+": "+ConvertEngine.humanBytes(count)+" / "+ConvertEngine.humanBytes(total)+" ("+(count*100/total)+"%)");
            try(InputStream in=new BufferedInputStream(conn.getInputStream());RandomAccessFile out=new RandomAccessFile(f,"rw")){
                if(out.length()!=offset)throw new IOException("Размер частичного файла изменился");
                out.seek(offset);
                try{
                    byte[] b=new byte[131072];int n;
                    while((n=in.read(b))!=-1){
                        if(Thread.currentThread().isInterrupted()||restartRequested.get())throw new InterruptedIOException("Докачка остановлена или перезапущена");
                        if(count+n>total||count+n>limit)throw new IOException("Слишком большой пакет");
                        out.write(b,0,n);count+=n;
                        if(count-lastSync>=1024*1024){out.getFD().sync();lastSync=count;}
                        long now=android.os.SystemClock.elapsedRealtime();
                        if(now-last>300){last=now;p.status(label+": "+ConvertEngine.humanBytes(count)+" / "+ConvertEngine.humanBytes(total)+" ("+(count*100/total)+"%)");}
                    }
                }finally{out.getFD().sync();}
                if(count!=total)throw new IOException("Загрузка прервана");
            }
        }finally{conn.disconnect();}
    }
}
