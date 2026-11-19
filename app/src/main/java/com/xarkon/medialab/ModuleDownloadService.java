package com.xarkon.medialab;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.os.*;
import com.arthenica.ffmpegkit.*;

/** Owns external runtime transfers independently of Activity life cycle. */
public final class ModuleDownloadService extends Service {
    static final String START="modules.start", PAUSE="modules.pause", RESTART="modules.restart";
    private static final String CHANNEL="medialab_modules";
    private static final int ID=8407;
    static volatile boolean running,ready,paused,checked;
    static volatile String status="Движок не проверен";
    private final Handler main=new Handler(Looper.getMainLooper());
    private volatile Thread worker;
    private volatile boolean pauseRequested;
    private PowerManager.WakeLock lock;
    static boolean isPaused(Context c){return c.getSharedPreferences("module_download",MODE_PRIVATE).getBoolean("paused",false);}
    static void request(Context c,String action){
        Intent i=new Intent(c,ModuleDownloadService.class).setAction(action);
        if(PAUSE.equals(action))c.startService(i);else c.startForegroundService(i);
    }
    @Override public IBinder onBind(Intent i){return null;}
    @Override public void onCreate(){
        super.onCreate();
        NotificationManager nm=getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL,"Докачка модулей MediaLAB",NotificationManager.IMPORTANCE_LOW));
    }
    private PendingIntent action(String name,int code){
        return PendingIntent.getService(this,code,new Intent(this,ModuleDownloadService.class).setAction(name),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    }
    private Notification notification(){
        Notification.Builder b=new Notification.Builder(this,CHANNEL)
          .setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle("MediaLAB · Библиотеки")
          .setContentText(status).setStyle(new Notification.BigTextStyle().bigText(status))
          .setOnlyAlertOnce(true).setOngoing(running)
          .setContentIntent(PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE));
        if(running){b.addAction(new Notification.Action.Builder(null,"Пауза",action(PAUSE,1)).build());
            b.addAction(new Notification.Action.Builder(null,"С нуля",action(RESTART,2)).build());}
        return b.build();
    }
    @Override public int onStartCommand(Intent i,int flags,int id){
        String cmd=i==null?START:i.getAction();
        if(PAUSE.equals(cmd)){
            pauseRequested=true;paused=true;
            getSharedPreferences("module_download",MODE_PRIVATE).edit().putBoolean("paused",true).commit();
            Thread t=worker;if(t!=null)t.interrupt();else stopSelf();
            return START_NOT_STICKY;
        }
        if(i==null&&isPaused(this)){stopSelf();return START_NOT_STICKY;}
        getSharedPreferences("module_download",MODE_PRIVATE).edit().putBoolean("paused",false).commit();
        if(RESTART.equals(cmd))NativeLibraryPack.restartPartialDownload();
        if(worker!=null){
            if(pauseRequested){status="Остановка текущей попытки…";return START_NOT_STICKY;}
            return START_STICKY;
        }
        pauseRequested=false;paused=false;running=true;checked=false;ready=false;status="Проверка / докачка FFmpeg…";
        startForeground(ID,notification(),ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        PowerManager pm=getSystemService(PowerManager.class);
        lock=pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"MediaLAB:modules");
        lock.setReferenceCounted(false);lock.acquire(30L*60*1000);
        worker=new Thread(()->{
            boolean ok=false;String end;
            try{
                NativeLibraryPack.prepare(getApplicationContext(),message->{
                    status=message;
                    main.post(()->{if(running)getSystemService(NotificationManager.class).notify(ID,notification());});
                });
                if(pauseRequested)throw new java.io.InterruptedIOException("Пауза");
                FFmpegSession test=FFmpegKit.executeWithArguments(new String[]{"-version"});
                if(test==null||!ReturnCode.isSuccess(test.getReturnCode()))throw new IllegalStateException("Тест FFmpeg -version не прошёл");
                ok=true;end="Движок FFmpeg готов к обработке файлов";
            }catch(Throwable err){
                end=pauseRequested?"Докачка приостановлена. Частичный файл сохранён.":err.getClass().getSimpleName()+": "+err.getMessage();
            }
            final boolean success=ok;final String result=end;
            main.post(()->finishTransfer(success,result));
        },"MediaLAB-modules");
        worker.start();return START_STICKY;
    }
    private void finishTransfer(boolean success,String result){
        worker=null;running=false;ready=success;paused=pauseRequested&&!success;checked=!paused;
        status=result;
        if(lock!=null&&lock.isHeld())lock.release();lock=null;
        stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();
    }
    @Override public void onTimeout(int startId,int fgsType){
        pauseRequested=true;Thread t=worker;if(t!=null)t.interrupt();
        getSharedPreferences("module_download",MODE_PRIVATE).edit().putBoolean("paused",true).commit();
        stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();
    }
    @Override public void onDestroy(){
        Thread t=worker;if(t!=null)t.interrupt();
        if(lock!=null&&lock.isHeld())lock.release();
        super.onDestroy();
    }
}
