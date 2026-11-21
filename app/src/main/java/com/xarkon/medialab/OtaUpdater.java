package com.xarkon.medialab;

import android.app.Activity;
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
    static final int MAX_MANIFEST_BYTES=4*1024*1024; // cumulative history across skipped releases
    private static final ExecutorService worker=Executors.newSingleThreadExecutor();
    private static final Handler main=new Handler(Looper.getMainLooper());
    static File file(android.content.Context c){File dir=new File(c.getFilesDir(),"ota");return new File(dir,"MediaLAB-update.apk");}

    static final class Release {
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
               !name.matches("[A-Za-z0-9._-]{1,40}") || notes.length()>100_000 ||
               !url.equals(REPO_PREFIX+"v"+name+"/MediaLAB.apk"))
                throw new IOException("Манифест OTA повреждён или источник неофициальный");
        }
    }
    /** Render a complete, ordered OTA history; never label a partial list as complete. */
    private static String incompleteHistory(Release release,long installed){
        if(release.code<=installed+1)return release.notes;
        return "История пропущенных версий недоступна. Ниже изменения только последней версии.\n\nВерсия "+release.name+"\n"+release.notes;
    }
    private static String changelog(Release release,long installed) {
        if(release.history==null || release.history.length()==0)return incompleteHistory(release,installed);
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
                   body.trim().isEmpty() || body.length()>100_000)return incompleteHistory(release,installed);
                previousCode=code;
                if(code<=installed)continue;
                if(code!=lastShown+1)return incompleteHistory(release,installed);
                if(all.length()>0)all.append("\n\n");
                all.append("Версия ").append(title).append("\n").append(body);
                lastShown=code;
            }
        }catch(Exception error){return incompleteHistory(release,installed);}
        // Latest entry must exactly match the signed release metadata.
        if(previousCode!=release.code || lastShown!=release.code ||
           !names.contains(release.name))return incompleteHistory(release,installed);
        try {
            JSONObject latest=release.history.getJSONObject(release.history.length()-1);
            if(!release.name.equals(latest.getString("versionName")) ||
               !release.notes.equals(latest.getString("notes")))return incompleteHistory(release,installed);
        }catch(Exception error){return incompleteHistory(release,installed);}
        return all.toString();
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
    private static MediaOtaDialog active;
    /** Release the window on Activity destruction, but retain the transfer snapshot. */
    static void detach(Activity a){
        if(owner.get()!=a)return;
        if(active!=null)active.dismiss();
        active=null;owner.clear();
    }
    private static boolean busy;
    private static final java.util.concurrent.atomic.AtomicBoolean cancelDownload=new java.util.concurrent.atomic.AtomicBoolean(false);
    // Disconnect an active read immediately: an atomic flag alone cannot wake a
    // blocking InputStream.read() until the network timeout expires.
    private static volatile HttpURLConnection activeDownloadConnection;
    private static void requestCancel(){
        cancelDownload.set(true);
        HttpURLConnection connection=activeDownloadConnection;
        if(connection!=null)connection.disconnect();
    }
    private static final class DownloadCanceled extends IOException {
        DownloadCanceled(){super("Загрузка остановлена пользователем");}
    }
    private static void checkCanceled() throws IOException {
        if(cancelDownload.get())throw new DownloadCanceled();
    }
    private static String sessionNotes="";
    private static java.util.function.Consumer<MediaOtaDialog> snapshot;
    private static void publish(java.util.function.Consumer<MediaOtaDialog> state){
        snapshot=state;
        Activity a=owner.get(); MediaOtaDialog ui=active;
        if(a!=null&&!a.isFinishing()&&!a.isDestroyed()&&ui!=null)state.accept(ui);
    }
    private static void finish(java.util.function.Consumer<MediaOtaDialog> state){busy=false;publish(state);}
    private static void withOwner(java.util.function.BiConsumer<Activity,MediaOtaDialog> action){
        Activity a=owner.get(); MediaOtaDialog ui=active;
        if(a!=null&&!a.isFinishing()&&!a.isDestroyed()&&ui!=null)action.accept(a,ui);
    }
    public static void checkAndOffer(Activity a,int accent){
        if(a.isFinishing()||a.isDestroyed())return;
        MediaOtaDialog existing=active;
        if(owner.get()==a && existing!=null && existing.matchesAppearance(accent,a.getPreferences(0).getInt("theme",0))){
            existing.show();
            if(!busy && existing.needsRefresh())check(a,accent,existing);
            return;
        }
        if(existing!=null)existing.dismiss();
        MediaOtaDialog ui=new MediaOtaDialog(a,accent);
        ui.setCancelDownload(OtaUpdater::requestCancel);
        owner=new java.lang.ref.WeakReference<>(a);active=ui;
        ui.show();
        if(snapshot!=null){ui.setHistory(sessionNotes);snapshot.accept(ui);if(busy||!ui.needsRefresh())return;}
        check(a,accent,ui);
    }
    static Release readRelease() throws Exception {
                URLConnection conn=connection(MANIFEST);
                ByteArrayOutputStream data=new ByteArrayOutputStream();
                try(InputStream in=conn.getInputStream()){
                    byte[] b=new byte[4096];int n;
                    while((n=in.read(b))!=-1){
                        if(data.size()+n>MAX_MANIFEST_BYTES)throw new IOException("Слишком большой манифест");
                        data.write(b,0,n);
                    }
                } finally {((HttpURLConnection)conn).disconnect();}
                return new Release(new JSONObject(data.toString("UTF-8")));
    }
    private static void check(Activity a,int accent,MediaOtaDialog ui){
        if(busy)return;
        sessionNotes="";busy=true;publish(d->{d.setHistory("");d.stage("Проверка соединения…","Подключение к серверу…");});
        worker.execute(()->{
            try{
                Release release=readRelease();
                long installed=a.getPackageManager().getPackageInfo(a.getPackageName(),0).getLongVersionCode();
                main.post(()->{
                    if(release.code<=installed){finish(d->d.current("MediaLAB "+BuildConfig.VERSION_NAME));}
                    else {sessionNotes=changelog(release,installed);finish(d->d.offer(release.name,sessionNotes,()->withOwner((host,dialog)->download(host,release,dialog))));}
                });
            }catch(Exception e){
                String message=e.getMessage()==null?"Ошибка подключения":e.getMessage();
                main.post(()->finish(d->d.fail(message,()->withOwner((host,dialog)->check(host,accent,dialog)))));
            }
        });
    }
    private static void download(Activity a,Release release,MediaOtaDialog ui){
        download(a,release,ui,false);
    }
    private static void download(Activity a,Release release,MediaOtaDialog ui,boolean resumed){
        if(busy)return;
        cancelDownload.set(false);
        busy=true;
        if(!resumed)publish(d->d.stage("Подготовка загрузки…","MediaLAB "+release.name));
        worker.execute(()->{
            File target=file(a),temp=new File(target.getParentFile(),"MediaLAB-candidate-"+release.sha+".apk");
            try{
                if(!target.getParentFile().exists()&&!target.getParentFile().mkdirs())
                    throw new IOException("Не удалось создать папку OTA");
                // Reuse a completed download after reopening the updater or recreating the Activity.
                if(target.isFile() && target.length()==release.size){
                    main.post(()->publish(d->d.verifying("Проверка APK…","Проверяем ранее загруженное обновление")));
                    boolean validCache=release.sha.equals(hash(target));
                    if(validCache){
                        PackageInfo cached=a.getPackageManager().getPackageArchiveInfo(target.getAbsolutePath(),0);
                        validCache=cached!=null && cached.getLongVersionCode()==release.code
                            && release.name.equals(cached.versionName);
                        if(validCache){
                            try {
                                checkSigner(a,target);
                                main.post(()->finish(d->d.ready(release.name,()->withOwner((host,dialog)->verifyAndInstall(host,release,dialog)))));
                                return;
                            } catch(Exception invalidCache) {
                                validCache=false;
                            }
                        }
                    }
                    // A wrong hash, version or signature must never leave an orphaned
                    // full-sized APK consuming space during the replacement download.
                    if(!validCache && !target.delete())
                        throw new IOException("Не удалось удалить повреждённый APK");
                    // Reset VERIFY before reporting progress from a fresh transfer.
                    main.post(()->publish(d->d.stage("Подготовка загрузки…","MediaLAB "+release.name)));
                }
                // Partial files are bound to the exact manifest digest, never reused across releases.
                File[] stale=target.getParentFile().listFiles();
                if(stale!=null)for(File old:stale)
                    if(old.getName().startsWith("MediaLAB-candidate")&&!old.equals(temp))old.delete();
                long offset=temp.isFile()?temp.length():0;
                if(offset>release.size){
                    if(!temp.delete())throw new IOException("Не удалось сбросить повреждённую загрузку");
                    offset=0;
                }
                // A completed candidate with a bad digest must be discarded before
                // retrying. Otherwise Retry hashes the same corrupt file forever.
                if(offset==release.size && !release.sha.equals(hash(temp))){
                    if(!temp.delete())throw new IOException("Не удалось удалить повреждённую загрузку");
                    offset=0;
                }
                if(target.getParentFile().getUsableSpace()<release.size-offset+4L*1024*1024)
                    throw new IOException("Недостаточно места для обновления");
                long got=offset;
                checkCanceled();
                if(offset<release.size){
                    HttpURLConnection conn;
                    try {
                        conn=(HttpURLConnection)connection(release.url,offset);
                    } catch(IOException rangeError) {
                        // A stale partial file may no longer be accepted by the CDN.
                        // Retry once from byte zero rather than trapping every Retry on HTTP 416.
                        if(offset<=0 || !"Ошибка HTTP 416".equals(rangeError.getMessage()))
                            throw rangeError;
                        if(!temp.delete())throw new IOException("Не удалось сбросить устаревшую загрузку",rangeError);
                        got=0;
                        conn=(HttpURLConnection)connection(release.url,0);
                    }
                    activeDownloadConnection=conn;
                    try {
                        checkCanceled();
                        if(conn.getResponseCode()==206){
                            // Use the effective offset: HTTP 416 recovery may have restarted at zero.
                            // RFC 9110 allows a 206 response to contain only part of the
                            // remaining bytes. Validate its start/total, not a forced EOF.
                            if(!OtaContentRange.matches(conn.getHeaderField("Content-Range"),got,release.size)){
                                temp.delete();
                                throw new IOException("Неверный диапазон загрузки APK");
                            }
                        }else got=0; // Server ignored Range: restart safely.
                        final long initial=got;
                        // A CDN can ignore Range (200), or reject an obsolete partial
                        // with 416. Reset the dialog before reporting zero bytes;
                        // otherwise its monotonic guard rejects progress below the
                        // previously saved offset.
                        final boolean restartedFromZero=offset>0 && initial==0;
                        main.post(()->{
                            if(restartedFromZero)publish(d->d.restartingFromZero(release.size));
                            publish(d->d.downloading(initial,release.size));
                        });
                        long lastUi=0;int lastTenth=-1;
                        try(InputStream in=conn.getInputStream();OutputStream out=new FileOutputStream(temp,got>0)){
                            byte[] b=new byte[65536];int n;
                            while(true){
                                checkCanceled();
                                n=in.read(b);
                                if(n==-1)break;
                                checkCanceled();
                                if(n>release.size-got){temp.delete();throw new IOException("Неверный размер APK");}
                                out.write(b,0,n);got+=n;
                                // Report actual tenths of a percent to the themed OTA dialog.
                                int tenth=(int)Math.min(1000d,got*1000d/release.size);
                                long now=android.os.SystemClock.uptimeMillis();
                                if(tenth==1000||(tenth!=lastTenth&&now-lastUi>=120)){
                                    lastUi=now;lastTenth=tenth;long received=got;
                                    main.post(()->publish(d->d.downloading(received,release.size)));
                                }
                            }
                        }
                    }finally{
                        activeDownloadConnection=null;
                        conn.disconnect();
                    }
                }
                checkCanceled();
                if(got!=release.size)throw new IOException("Загрузка прервана. Повторите для продолжения.");
                main.post(()->publish(d->d.verifying("Проверка SHA-256 APK…","Проверяем целостность загруженного файла")));
                if(!release.sha.equals(hash(temp))){
                    temp.delete();
                    throw new IOException("SHA-256 APK не совпадает");
                }
                main.post(()->publish(d->d.verifying("Проверка подписи APK…","Сверяем сертификат")));
                PackageInfo archive=a.getPackageManager().getPackageArchiveInfo(temp.getAbsolutePath(),0);
                if(archive==null||archive.getLongVersionCode()!=release.code||!release.name.equals(archive.versionName)){
                    temp.delete();throw new IOException("Версия APK не совпадает с манифестом");
                }
                try{checkSigner(a,temp);}catch(Exception invalid){temp.delete();throw invalid;}
                try {
                    java.nio.file.Files.move(temp.toPath(),target.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                } catch(java.nio.file.AtomicMoveNotSupportedException unsupported) {
                    // Both files live in the same private OTA directory. Some Android
                    // filesystems cannot guarantee atomic rename; a verified APK
                    // must still be installable rather than trapping Retry forever.
                    java.nio.file.Files.move(temp.toPath(),target.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                main.post(()->finish(d->d.ready(release.name,()->withOwner((host,dialog)->verifyAndInstall(host,release,dialog)))));
            }catch(Exception e){
                if(e instanceof DownloadCanceled || cancelDownload.get()){
                    // The current output stream is closed by try-with-resources before this
                    // snapshot is measured. Never discard the digest-bound partial APK.
                    long saved=temp.isFile()?Math.min(temp.length(),release.size):0;
                    main.post(()->finish(d->d.paused(saved,release.size,
                        ()->withOwner((host,dialog)->download(host,release,dialog,true)))));
                }else{
                    String message=e.getMessage()==null?"Ошибка загрузки":e.getMessage();
                    main.post(()->finish(d->d.fail(message,
                        ()->withOwner((host,dialog)->download(host,release,dialog)))));
                }
            }
        });
    }
    private static void verifyAndInstall(Activity a,Release release,MediaOtaDialog ui){
        if(busy)return;
        busy=true;publish(d->d.verifying("Проверка APK…","Сверяем SHA-256 перед установкой"));
        worker.execute(()->{
            try{
                File apk=file(a);
                if(!apk.isFile()||apk.length()!=release.size||!release.sha.equals(hash(apk)))
                    throw new IOException("APK изменён или повреждён. Загрузите обновление повторно.");
                checkSigner(a,apk);
                PackageInfo archive=a.getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(),0);
                PackageInfo installed=a.getPackageManager().getPackageInfo(a.getPackageName(),0);
                if(archive==null||archive.getLongVersionCode()!=release.code||!release.name.equals(archive.versionName)||archive.getLongVersionCode()<=installed.getLongVersionCode())
                    throw new IOException("Обновление уже установлено или устарело");
                main.post(()->{finish(d->d.ready(release.name,()->withOwner((host,dialog)->verifyAndInstall(host,release,dialog))));withOwner((host,dialog)->{if(dialog.canLaunchInstaller())install(host,release,dialog);});});
            }catch(Exception e){
                main.post(()->finish(d->d.fail(e.getMessage()==null?"Ошибка проверки APK":e.getMessage(),()->withOwner((host,dialog)->download(host,release,dialog)))));
            }
        });
    }
    private static void install(Activity a,Release release,MediaOtaDialog ui){
        try{
            File apk=file(a);if(!apk.isFile())throw new IOException("APK отсутствует");
            if(Build.VERSION.SDK_INT>=26&&!a.getPackageManager().canRequestPackageInstalls()){
                finish(d->d.fail("Разрешите установку APK для MediaLAB в настройках Android",
                    ()->withOwner((host,dialog)->verifyAndInstall(host,release,dialog))));
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
            finish(d->d.ready(release.name,()->withOwner((host,dialog)->verifyAndInstall(host,release,dialog))));
        }catch(Exception e){
            String message=e.getMessage()==null?"Ошибка установки":e.getMessage();
            finish(d->d.fail(message,()->withOwner((host,dialog)->verifyAndInstall(host,release,dialog))));
        }
    }
}
