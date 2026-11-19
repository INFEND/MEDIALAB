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
import javax.net.ssl.HttpsURLConnection;
import java.net.URL;

/** Safe background check: notification only, never silent APK installation. */
public final class MediaOtaBackgroundJob extends JobService {
    @Override public boolean onStartJob(JobParameters params) {
        new Thread(()->{
            try {
                OtaUpdater.Release release=OtaUpdater.readRelease();
                long available=release.code;
                PackageInfo pkg=getPackageManager().getPackageInfo(getPackageName(),0);
                if(!Thread.currentThread().isInterrupted() && available>pkg.getLongVersionCode() && available>getSharedPreferences(MediaOtaSchedule.PREF,0).getLong("last_notified",0)) {
                    String name=release.name;
                    NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
                    if(nm!=null&&nm.areNotificationsEnabled()){
                        String channel="medialab_updates";
                        nm.createNotificationChannel(new NotificationChannel(channel,"Обновления MediaLAB",NotificationManager.IMPORTANCE_DEFAULT));
                        Intent intent=new Intent(this,MainActivity.class).setAction("com.xarkon.medialab.CHECK_OTA");
                        PendingIntent pi=PendingIntent.getActivity(this,480,intent,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
                        Notification n=new Notification.Builder(this,channel).setSmallIcon(android.R.drawable.stat_sys_download_done)
                                .setContentTitle("Обновление MediaLAB "+name).setContentText("Откройте приложение для установки")
                                .setAutoCancel(true).setContentIntent(pi).build();
                        nm.notify(8204,n);
                        getSharedPreferences(MediaOtaSchedule.PREF,0).edit().putLong("last_notified",available).apply();
                    }
                }
            }catch(Exception ignored){}
            finally {if(!Thread.currentThread().isInterrupted())jobFinished(params,false);}
        },"media-ota-check");
        running.start();
        return true;
    }
    @Override public boolean onStopJob(JobParameters params){Thread t=running;if(t!=null)t.interrupt();return true;}
}
