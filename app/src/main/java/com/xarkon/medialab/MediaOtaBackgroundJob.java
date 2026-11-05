package com.xarkon.medialab;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobParameters;
import android.app.job.JobService;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.os.Build;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpsURLConnection;
import java.net.URL;

/** Safe background check: notification only, never silent APK installation. */
public final class MediaOtaBackgroundJob extends JobService {
    @Override public boolean onStartJob(JobParameters params) {
        new Thread(()->{
            try {
                URL u=new URL("https://github.com/INFEND/MEDIALAB/releases/latest/download/update.json");
                HttpsURLConnection c=(HttpsURLConnection)u.openConnection();
                c.setConnectTimeout(12000);c.setReadTimeout(16000);
                ByteArrayOutputStream out=new ByteArrayOutputStream();
                try(InputStream input=c.getInputStream()) {
                    byte[] buffer=new byte[4096];int n;
                    while((n=input.read(buffer))!=-1) {
                        if(out.size()+n>65536)throw new java.io.IOException("Invalid OTA manifest size");
                        out.write(buffer,0,n);
                    }
                } finally {c.disconnect();}
                JSONObject j=new JSONObject(out.toString("UTF-8"));
                if(!"com.xarkon.medialab".equals(j.optString("packageName")))return;
                long available=j.optLong("versionCode",0);
                PackageInfo pkg=getPackageManager().getPackageInfo(getPackageName(),0);
                if(available>pkg.versionCode && available>getSharedPreferences(MediaOtaSchedule.PREF,0).getLong("last_notified",0)) {
                    String name=j.optString("versionName","Новая версия");
                    NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
                    if(nm!=null){
                        String channel="medialab_updates";
                        nm.createNotificationChannel(new NotificationChannel(channel,"Обновления MediaLAB",NotificationManager.IMPORTANCE_DEFAULT));
                        Intent intent=new Intent(this,MainActivity.class).setAction("com.xarkon.medialab.CHECK_OTA");
                        PendingIntent pi=PendingIntent.getActivity(this,480,intent,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
                        Notification n=new Notification.Builder(this,channel).setSmallIcon(android.R.drawable.stat_sys_download_done)
                                .setContentTitle("Обновление MediaLAB "+name).setContentText("Откройте приложение для установки")
                                .setAutoCancel(true).setContentIntent(pi).build();
                        nm.notify(8204,n);
                    }
                    getSharedPreferences(MediaOtaSchedule.PREF,0).edit().putLong("last_notified",available).apply();
                }
            }catch(Exception ignored){}
            finally {jobFinished(params,false);}
        },"media-ota-check").start();
        return true;
    }
    @Override public boolean onStopJob(JobParameters params){return true;}
}
