package com.xarkon.medialab;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.LinearLayout;
import android.view.Gravity;
import android.graphics.Color;
import org.json.JSONObject;
import org.json.JSONArray;
import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** User-initiated OTA for INFEND/MEDIALAB, with SHA-256 and signing-identity validation. */
public final class OtaUpdater {
    private OtaUpdater(){}
    private static final String MANIFEST="https://github.com/INFEND/MEDIALAB/releases/latest/download/update.json";
    private static final String REPO_PREFIX="https://github.com/INFEND/MEDIALAB/releases/download/";
    private static final long MAX_SIZE=450L*1024L*1024L;
    static final int MAX_MANIFEST_BYTES=512*1024;
    private static final ExecutorService worker=Executors.newSingleThreadExecutor();
    private static final Handler main=new Handler(Looper.getMainLooper());
    static File file(android.content.Context c){File dir=new File(c.getFilesDir(),"ota");return new File(dir,"MediaLAB-update.apk");}

    private static class Release {
        final String name,sha,url,notes;
        final JSONArray history;
        final long code,size;
        Release(JSONObject j) throws Exception {
            if(j.optInt("schema",1)!=1)throw new IOException("Версия манифеста OTA не поддерживается");
            if(!"com.xarkon.medialab".equals(j.getString("packageName"))) throw new IOException("Неверный пакет приложения");
            code=j.getLong("versionCode");name=j.getString("versionName");size=j.getLong("sizeBytes");
            url=j.getString("apkUrl");sha=j.getString("sha256").toLowerCase(Locale.US);notes=j.optString("notes","");
            history=j.optJSONArray("history");
            if(code<1 || size<100000 || size>MAX_SIZE || !sha.matches("[0-9a-f]{64}") ||
               !name.matches("[A-Za-z0-9._-]{1,40}") || notes.length()>6000 ||
               !url.equals(REPO_PREFIX+"v"+name+"/MediaLAB.apk"))
                throw new IOException("Манифест OTA повреждён или источник неофициальный");
        }
    }
    /** Render a complete, ordered OTA history; never label a partial list as complete. */
    private static String changelog(Release release,long installed) {
        if(release.history==null || release.history.length()==0)return release.notes;
        StringBuilder all=new StringBuilder();
        long previousCode=-1;
        long lastShown=installed;
        java.util.HashSet<String> names=new java.util.HashSet<>();
        try {
            for(int i=0;i<release.history.length();i++) {
                JSONObject e=release.history.getJSONObject(i);
                long code=e.getLong("versionCode");
                String title=e.getString("versionName"),body=e.getString("notes");
                if(code<=0 || code>release.code || code<=previousCode ||
                   (previousCode>0 && code!=previousCode+1) ||
                   !title.matches("[A-Za-z0-9._-]{1,40}") || !names.add(title) ||
                   body.trim().isEmpty() || body.length()>6000)return release.notes;
                previousCode=code;
                if(code<=installed)continue;
                if(code!=lastShown+1)return release.notes;
                if(all.length()>0)all.append("\n\n");
                all.append("Версия ").append(title).append("\n").append(body);
                lastShown=code;
            }
        }catch(Exception error){return release.notes;}
        // Latest entry must exactly match the signed release metadata.
        if(previousCode!=release.code || lastShown!=release.code ||
           !names.contains(release.name))return release.notes;
        try {
            JSONObject latest=release.history.getJSONObject(release.history.length()-1);
            if(!release.name.equals(latest.getString("versionName")) ||
               !release.notes.equals(latest.getString("notes")))return release.notes;
        }catch(Exception error){return release.notes;}
        return all.toString();
    }

    /** Render release notes with distinct change markers and explicit **bold** phrases. */
    private static android.text.SpannableStringBuilder styledNotes(String source) {
        android.text.SpannableStringBuilder result=new android.text.SpannableStringBuilder();
        if(source==null)return result;
        for(String line:source.split("\\n",-1)){
            if(result.length()>0)result.append("\n");
            StringBuilder visible=new StringBuilder();
            java.util.ArrayList<int[]> bold=new java.util.ArrayList<>();
            int start=-1;
            for(int i=0;i<line.length();){
                if(i+1<line.length() && line.charAt(i)=='*' && line.charAt(i+1)=='*'){
                    if(start<0)start=visible.length();
                    else {if(visible.length()>start)bold.add(new int[]{start,visible.length()});start=-1;}
                    i+=2;
                }else visible.append(line.charAt(i++));
            }
            // Unclosed markup is rendered literally rather than silently dropping text.
            if(start>=0)visible.insert(start,"**");
            String shown=visible.toString();
            int offset=result.length();
            result.append(shown);
            int tint=shown.startsWith("[+]")?0xff4dd69c:
                shown.startsWith("[-]")?0xfff07878:
                shown.startsWith("[=]")?0xffffaa4d:0;
            if(tint!=0){
                result.setSpan(new android.text.style.ForegroundColorSpan(tint),offset,offset+3,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                result.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                    offset,offset+3,android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            for(int[] range:bold)if(range[1]<=shown.length()){
                result.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                    offset+range[0],offset+range[1],android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        return result;
    }

    private static URLConnection connection(String url) throws IOException {
        return connection(url,0);
    }
    private static URLConnection connection(String url,long offset) throws IOException {
        URL u=new URL(url);
        for(int redirects=0;redirects<=5;redirects++){
            String host=u.getHost().toLowerCase(Locale.US);
            if(!"https".equalsIgnoreCase(u.getProtocol()) || u.getUserInfo()!=null ||
               (u.getPort()!=-1 && u.getPort()!=443) ||
               !(host.equals("github.com") || host.equals("release-assets.githubusercontent.com") ||
                 host.equals("objects.githubusercontent.com")))
                throw new IOException("Недопустимый источник OTA");
            javax.net.ssl.HttpsURLConnection c=(javax.net.ssl.HttpsURLConnection)u.openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(15000);c.setReadTimeout(25000);
            c.setRequestProperty("User-Agent","MediaLAB-OTA/1.0");
            c.setRequestProperty("Accept-Encoding","identity");
            if(offset>0)c.setRequestProperty("Range","bytes="+offset+"-");
            boolean keep=false;
            try {
                int status=c.getResponseCode();
                if(status==200 || (offset>0 && status==206)){keep=true;return c;}
                if(status!=301 && status!=302 && status!=303 && status!=307 && status!=308)
                    throw new IOException("Ошибка HTTP "+status);
                String location=c.getHeaderField("Location");
                if(location==null || location.isEmpty())throw new IOException("Пустое перенаправление OTA");
                u=new URL(u,location);
            } finally {if(!keep)c.disconnect();}
        }
        throw new IOException("Слишком много перенаправлений OTA");
    }
    private static String hash(File file)throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try(InputStream in=new FileInputStream(file)) {byte[] b=new byte[65536];int n;
            while((n=in.read(b))!=-1)digest.update(b,0,n);
        }
        return hex(digest.digest());
    }
    private static String hex(byte[] bytes){StringBuilder s=new StringBuilder();for(byte b:bytes)s.append(String.format(Locale.US,"%02x",b&255));return s.toString();}
    private static byte[] signer(PackageInfo info) {
        if(info==null)return null;
        if(Build.VERSION.SDK_INT>=28 && info.signingInfo!=null){
            android.content.pm.Signature[] sig=info.signingInfo.getApkContentsSigners();
            return sig!=null&&sig.length==1?sig[0].toByteArray():null;
        }
        return info.signatures!=null&&info.signatures.length==1?info.signatures[0].toByteArray():null;
    }
    private static void checkSigner(Activity a, File downloaded) throws Exception {
        int flags=Build.VERSION.SDK_INT>=28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        PackageManager pm=a.getPackageManager();
        PackageInfo installed=pm.getPackageInfo(a.getPackageName(),flags);
        PackageInfo update=pm.getPackageArchiveInfo(downloaded.getAbsolutePath(),flags);
        if(update==null || !a.getPackageName().equals(update.packageName))throw new IOException("APK имеет неверный идентификатор приложения");
        byte[] current=signer(installed),future=signer(update);
        if(current==null || future==null)throw new IOException("Не удалось проверить подпись обновления");
        if(!MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(current),
                MessageDigest.getInstance("SHA-256").digest(future)))
            throw new IOException("Сертификат APK отличается от установленной версии. Для OTA нужна та же постоянная подпись.");
    }
    private static java.lang.ref.WeakReference<Activity> owner=new java.lang.ref.WeakReference<>(null);
    private static java.lang.ref.WeakReference<MediaOtaDialog> active=new java.lang.ref.WeakReference<>(null);
    public static void checkAndOffer(Activity a,int accent){
        if(a.isFinishing()||a.isDestroyed())return;
        MediaOtaDialog existing=active.get();
        if(owner.get()==a && existing!=null){existing.show();return;}
        MediaOtaDialog ui=new MediaOtaDialog(a,accent);
        owner=new java.lang.ref.WeakReference<>(a);active=new java.lang.ref.WeakReference<>(ui);
        ui.show();check(a,accent,ui);
    }
    private static void check(Activity a,int accent,MediaOtaDialog ui){
        ui.stage("Проверка соединения…","Подключение к серверу…");
        worker.execute(()->{
            try{
                URLConnection conn=connection(MANIFEST);
                ByteArrayOutputStream data=new ByteArrayOutputStream();
                try(InputStream in=conn.getInputStream()){
                    byte[] b=new byte[4096];int n;
                    while((n=in.read(b))!=-1){
                        if(data.size()+n>MAX_MANIFEST_BYTES)throw new IOException("Слишком большой манифест");
                        data.write(b,0,n);
                    }
                } finally {((HttpURLConnection)conn).disconnect();}
                Release release=new Release(new JSONObject(data.toString("UTF-8")));
                long installed=a.getPackageManager().getPackageInfo(a.getPackageName(),0).getLongVersionCode();
                main.post(()->{
                    if(a.isFinishing()||a.isDestroyed())return;
                    if(release.code<=installed){active.clear();ui.current("MediaLAB "+BuildConfig.VERSION_NAME);}
                    else ui.offer(release.name,changelog(release,installed),()->download(a,release,ui));
                });
            }catch(Exception e){
                String message=e.getMessage()==null?"Ошибка подключения":e.getMessage();
                main.post(()->{if(!a.isFinishing()&&!a.isDestroyed())
                    ui.fail(message,()->check(a,accent,ui));});
            }
        });
    }
    private static void download(Activity a,Release release,MediaOtaDialog ui){
        ui.stage("Подготовка загрузки…","MediaLAB "+release.name);
        worker.execute(()->{
            File target=file(a),temp=new File(target.getParentFile(),"MediaLAB-candidate-"+release.sha+".apk");
            try{
                if(!target.getParentFile().exists()&&!target.getParentFile().mkdirs())
                    throw new IOException("Не удалось создать папку OTA");
                // Reuse a completed download after reopening the updater or recreating the Activity.
                if(target.isFile() && target.length()==release.size){
                    main.post(()->{if(!a.isFinishing()&&!a.isDestroyed())
                        ui.stage("Проверка APK…","Проверяем ранее загруженное обновление");});
                    if(release.sha.equals(hash(target))){
                        PackageInfo cached=a.getPackageManager().getPackageArchiveInfo(target.getAbsolutePath(),0);
                        if(cached!=null && cached.getLongVersionCode()==release.code){
                            checkSigner(a,target);
                            main.post(()->{if(!a.isFinishing()&&!a.isDestroyed())
                                ui.ready(release.name,()->verifyAndInstall(a,release,ui));});
                            return;
                        }
                    }
                    // Reset VERIFY before reporting progress from a fresh transfer.
                    main.post(()->{if(!a.isFinishing()&&!a.isDestroyed())
                        ui.stage("Подготовка загрузки…","MediaLAB "+release.name);});
                }
                // Partial files are bound to the exact manifest digest, never reused across releases.
                File[] stale=target.getParentFile().listFiles();
                if(stale!=null)for(File old:stale)
                    if(old.getName().startsWith("MediaLAB-candidate")&&!old.equals(temp))old.delete();
                long offset=temp.isFile()?temp.length():0;
                if(offset>release.size){temp.delete();offset=0;}
                if(target.getParentFile().getUsableSpace()<release.size-offset+4L*1024*1024)
                    throw new IOException("Недостаточно места для обновления");
                long got=offset;
                if(offset<release.size){
                    HttpURLConnection conn=(HttpURLConnection)connection(release.url,offset);
                    try {
                        if(conn.getResponseCode()==206){
                            String expected="bytes "+offset+"-"+(release.size-1)+"/"+release.size;
                            if(!expected.equals(conn.getHeaderField("Content-Range"))){
                                temp.delete();
                                throw new IOException("Неверный диапазон загрузки APK");
                            }
                        }else got=0; // Server ignored Range: restart safely.
                        final long initial=got;
                        main.post(()->{if(!a.isFinishing()&&!a.isDestroyed())ui.downloading(initial,release.size);});
                        long lastUi=0;int lastPercent=-1;
                        try(InputStream in=conn.getInputStream();OutputStream out=new FileOutputStream(temp,got>0)){
                            byte[] b=new byte[65536];int n;
                            while((n=in.read(b))!=-1){
                                if(n>release.size-got){temp.delete();throw new IOException("Неверный размер APK");}
                                out.write(b,0,n);got+=n;
                                int pct=(int)Math.min(100,got*100/release.size);
                                long now=android.os.SystemClock.uptimeMillis();
                                if(pct==100||(pct!=lastPercent&&now-lastUi>=120)){
                                    lastUi=now;lastPercent=pct;long received=got;
                                    main.post(()->{if(!a.isFinishing()&&!a.isDestroyed())ui.downloading(received,release.size);});
                                }
                            }
                        }
                    }finally{conn.disconnect();}
                }
                if(got!=release.size)throw new IOException("Загрузка прервана. Повторите для продолжения.");
                if(!release.sha.equals(hash(temp))){
                    temp.delete();
                    throw new IOException("SHA-256 APK не совпадает");
                }
                main.post(()->{if(!a.isFinishing()&&!a.isDestroyed())ui.stage("Проверка подписи APK…","Сверяем сертификат");});
                PackageInfo archive=a.getPackageManager().getPackageArchiveInfo(temp.getAbsolutePath(),0);
                if(archive==null||archive.getLongVersionCode()!=release.code){
                    temp.delete();throw new IOException("Версия APK не совпадает с манифестом");
                }
                try{checkSigner(a,temp);}catch(Exception invalid){temp.delete();throw invalid;}
                java.nio.file.Files.move(temp.toPath(),target.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING,java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                main.post(()->{if(!a.isFinishing()&&!a.isDestroyed())
                    ui.ready(release.name,()->verifyAndInstall(a,release,ui));});
            }catch(Exception e){
                String message=e.getMessage()==null?"Ошибка загрузки":e.getMessage();
                main.post(()->{if(!a.isFinishing()&&!a.isDestroyed())
                    ui.fail(message,()->download(a,release,ui));});
            }
        });
    }
    private static void verifyAndInstall(Activity a,Release release,MediaOtaDialog ui){
        ui.stage("Проверка APK…","Сверяем SHA-256 перед установкой");
        worker.execute(()->{
            try{
                File apk=file(a);
                if(!apk.isFile()||apk.length()!=release.size||!release.sha.equals(hash(apk)))
                    throw new IOException("APK изменён или повреждён. Загрузите обновление повторно.");
                main.post(()->{if(!a.isFinishing()&&!a.isDestroyed())install(a,release,ui);});
            }catch(Exception e){
                main.post(()->{if(!a.isFinishing()&&!a.isDestroyed())
                    ui.fail(e.getMessage()==null?"Ошибка проверки APK":e.getMessage(),()->download(a,release,ui));});
            }
        });
    }
    private static void install(Activity a,Release release,MediaOtaDialog ui){
        try{
            File apk=file(a);if(!apk.isFile())throw new IOException("APK отсутствует");
            checkSigner(a,apk);
            PackageInfo archive=a.getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(),0);
            PackageInfo installed=a.getPackageManager().getPackageInfo(a.getPackageName(),0);
            if(archive==null||archive.getLongVersionCode()!=release.code||archive.getLongVersionCode()<=installed.getLongVersionCode())throw new IOException("Обновление уже установлено или устарело");
            if(Build.VERSION.SDK_INT>=26&&!a.getPackageManager().canRequestPackageInstalls()){
                ui.fail("Разрешите установку APK для MediaLAB в настройках Android",
                    ()->verifyAndInstall(a,release,ui));
                Intent settings=new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:"+a.getPackageName()));
                a.startActivity(settings);return;
            }
            Uri uri=new Uri.Builder().scheme("content").authority("com.xarkon.medialab.ota").path("apk").build();
            Intent i=new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri,"application/vnd.android.package-archive");
            i.setClipData(android.content.ClipData.newRawUri("MediaLAB",uri));
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_ACTIVITY_NEW_TASK);
            a.getApplicationContext().startActivity(i);
            // The installer may be cancelled; restore the verified offer instead of leaving VERIFY spinning forever.
            ui.ready(release.name,()->verifyAndInstall(a,release,ui));
        }catch(Exception e){
            ui.fail(e.getMessage()==null?"Ошибка установки":e.getMessage(),()->verifyAndInstall(a,release,ui));
        }
    }
}
