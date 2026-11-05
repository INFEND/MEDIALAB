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
    private static String changelog(Release release,long installed) {
        if(release.history==null || release.history.length()==0)return release.notes;
        StringBuilder all=new StringBuilder();
        long previous=installed;
        try {
            for(int i=0;i<release.history.length();i++) {
                JSONObject e=release.history.getJSONObject(i);
                long code=e.getLong("versionCode");
                if(code<=installed)continue;
                if(code!=previous+1 || code>release.code)return release.notes;
                String title=e.getString("versionName"),body=e.getString("notes");
                if(body.isEmpty()||body.length()>6000)return release.notes;
                if(all.length()>0)all.append("\n\n");
                all.append("Версия ").append(title).append("\n").append(body);
                previous=code;
            }
        }catch(Exception error){return release.notes;}
        return previous==release.code ? all.toString() : release.notes;
    }

    private static URLConnection connection(String url) throws IOException {
        URL u=new URL(url);
        if(!"https".equalsIgnoreCase(u.getProtocol()))throw new IOException("Допускается только HTTPS");
        URLConnection c=u.openConnection();c.setConnectTimeout(15000);c.setReadTimeout(25000);
        c.setRequestProperty("User-Agent","MediaLAB-OTA/1.0");return c;
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
            return sig.length>0?sig[0].toByteArray():null;
        }
        return info.signatures!=null&&info.signatures.length>0?info.signatures[0].toByteArray():null;
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
    private static void show(Activity a,String title,String message) {main.post(()->{
        if(a.isFinishing()||a.isDestroyed())return;
        new AlertDialog.Builder(a).setTitle(title).setMessage(message).setPositiveButton("Понятно",null).show();
    });}
    public static void checkAndOffer(Activity activity,int accent){
        final AlertDialog waiting=new AlertDialog.Builder(activity).setTitle("MediaLAB · OTA")
            .setMessage("Проверяем официальный канал обновлений…")
            .setCancelable(false).create();waiting.show();
        worker.execute(()->{
            try {
                URLConnection c=connection(MANIFEST);ByteArrayOutputStream out=new ByteArrayOutputStream();
                try(InputStream in=c.getInputStream()) {byte[] buf=new byte[4096];int n;
                    while((n=in.read(buf))!=-1){if(out.size()+n>65536)throw new IOException("Слишком большой манифест");out.write(buf,0,n);}
                }
                Release rel=new Release(new JSONObject(out.toString("UTF-8")));
                long current=activity.getPackageManager().getPackageInfo(activity.getPackageName(),0).versionCode;
                main.post(()->{
                    waiting.dismiss();if(activity.isFinishing()||activity.isDestroyed())return;
                    if(rel.code<=current){new AlertDialog.Builder(activity).setTitle("Обновлений нет")
                        .setMessage("Установлена актуальная версия · "+BuildConfig.VERSION_NAME)
                        .setPositiveButton("Готово",null).show();return;}
                    android.widget.ScrollView scroll=new android.widget.ScrollView(activity);
                    TextView notes=new TextView(activity);
                    notes.setText("Размер: "+String.format(Locale.US,"%.1f МБ",rel.size/1048576.0)+"\n\n"+
                            changelog(rel,current));
                    notes.setTextSize(14);notes.setTextColor(activity.getPreferences(0).getInt("theme",0)==2 ? 0xff18293e : 0xffd7e5ff);
                    notes.setPadding(32,24,32,24);notes.setLineSpacing(6,1.12f);
                    scroll.addView(notes);scroll.setBackgroundColor(activity.getPreferences(0).getInt("theme",0)==2 ? 0xfff5f7fc : 0xff101927);
                    new AlertDialog.Builder(activity).setTitle("Обновление MediaLAB "+rel.name)
                        .setView(scroll)
                        .setNegativeButton("Позже",null)
                        .setPositiveButton("Скачать и установить",(d,w)->download(activity,rel,accent)).show();
                });
            }catch(Exception e){main.post(waiting::dismiss);show(activity,"Не удалось проверить OTA",e.getMessage());}
        });
    }
    private static void download(Activity a, Release release,int color){
        LinearLayout layout=new LinearLayout(a);layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(48,32,48,24);layout.setGravity(Gravity.CENTER_VERTICAL);
        TextView status=new TextView(a);status.setText("Загрузка APK…");status.setTextSize(14);
        ProgressBar progress=new ProgressBar(a,null,android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);progress.setProgressTintList(android.content.res.ColorStateList.valueOf(color));
        layout.addView(status);layout.addView(progress);
        AlertDialog dialog=new AlertDialog.Builder(a).setTitle("Загрузка обновления")
            .setView(layout).setCancelable(false).create();dialog.show();
        worker.execute(()->{
            File target=file(a),temp=new File(target.getParentFile(),"MediaLAB-candidate.apk");
            try {
                if(!target.getParentFile().exists()&&!target.getParentFile().mkdirs())throw new IOException("Нет папки OTA");
                URLConnection conn=connection(release.url);
                long got=0;MessageDigest digest=MessageDigest.getInstance("SHA-256");
                try(InputStream in=conn.getInputStream();OutputStream out=new FileOutputStream(temp)){
                    byte[] b=new byte[65536];int n;
                    while((n=in.read(b))!=-1){got+=n;if(got>MAX_SIZE||got>release.size)throw new IOException("Неправильный размер APK");
                        out.write(b,0,n);digest.update(b,0,n);
                        final long transferred=got;
                        main.post(()->{progress.setProgress((int)Math.min(100,transferred*100/release.size));
                            status.setText(String.format(Locale.US,"Получено %.1f / %.1f МБ",transferred/1048576.0,release.size/1048576.0));});
                    }
                }
                if(got!=release.size||!release.sha.equals(hex(digest.digest())))throw new IOException("Контрольная сумма APK не совпадает");
                checkSigner(a,temp);
                if(target.exists()&&!target.delete())throw new IOException("Не удалось заменить старое обновление");
                if(!temp.renameTo(target))throw new IOException("Не удалось сохранить APK");
                main.post(()->{dialog.dismiss();if(!a.isFinishing()&&!a.isDestroyed()){
                    new AlertDialog.Builder(a).setTitle("APK проверен")
                      .setMessage("Размер, SHA-256 и сертификат совпадают. Установить обновление?")
                      .setNegativeButton("Позже",null).setPositiveButton("Установить",(d,w)->install(a)).show();
                }});
            }catch(Exception e){temp.delete();main.post(dialog::dismiss);show(a,"Ошибка обновления",e.getMessage());}
        });
    }
    private static void install(Activity a){
        if(Build.VERSION.SDK_INT>=26&&!a.getPackageManager().canRequestPackageInstalls()){
            show(a,"Разрешение на установку","Разреши установку обновлений из MediaLAB, затем повтори действие в настройках.");
            Intent settings=new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:"+a.getPackageName()));a.startActivity(settings);return;
        }
        Intent i=new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(new Uri.Builder().scheme("content").authority("com.xarkon.medialab.ota").path("apk").build(),
            "application/vnd.android.package-archive");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_ACTIVITY_NEW_TASK);
        try{a.startActivity(i);}catch(Exception e){show(a,"Установка недоступна",e.getMessage());}
    }
}
