package com.xarkon.medialab;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.os.*;

/** Optional video codec transfer, separate from the base FFmpeg runtime. */
public final class VideoUltraService extends Service {
    static final String START="videoultra.start", PAUSE="videoultra.pause", RESTART="videoultra.restart";
    private static final String PREF="video_ultra_module", CHANNEL="medialab_video_ultra";
    private static final int ID=8410;
    static volatile boolean running;
    static volatile String status="";
    private final Handler main=new Handler(Looper.getMainLooper());
    private Thread worker;
    private volatile boolean paused;
    private boolean resumePending;
    private PowerManager.WakeLock lock;
    static boolean pending(Context c){return c.getSharedPreferences(PREF,MODE_PRIVATE).getBoolean("pending",false);}
    static void request(Context c,String cmd){
        Intent i=new Intent(c,VideoUltraService.class).setAction(cmd);
        if(PAUSE.equals(cmd))c.startService(i);else c.startForegroundService(i);
    }
    private void persist(boolean active){getSharedPreferences(PREF,MODE_PRIVATE).edit().putBoolean("pending",active).commit();}
    @Override public IBinder onBind(Intent i){return null;}
    @Override public void onCreate(){
        super.onCreate();
        getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel(CHANNEL,"VIDEO ULTRA · докачка",NotificationManager.IMPORTANCE_LOW));
    }
    private PendingIntent action(String name,int code){
        return PendingIntent.getService(this,code,new Intent(this,VideoUltraService.class).setAction(name),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    }
    private Notification notification(){
        Notification.Builder b=new Notification.Builder(this,CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle("MediaLAB · VIDEO ULTRA")
            .setContentText(status).setStyle(new Notification.BigTextStyle().bigText(status)).setOnlyAlertOnce(true).setOngoing(running)
            .setContentIntent(PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE));
        if(running){
            b.addAction(new Notification.Action.Builder(null,"Пауза",action(PAUSE,1)).build());
            b.addAction(new Notification.Action.Builder(null,"С нуля",action(RESTART,2)).build());
        }
        return b.build();
    }
    @Override public int onStartCommand(Intent i,int flags,int id){
        String cmd=i==null?START:i.getAction();
        if(PAUSE.equals(cmd)){
            paused=true;resumePending=false;persist(false);
            if(worker!=null)worker.interrupt();else stopSelf();
            return START_NOT_STICKY;
        }
        if(i==null&&!pending(this)){stopSelf();return START_NOT_STICKY;}
        persist(true);
        if(RESTART.equals(cmd))VideoUltraPack.restart();
        if(worker!=null){if(paused)resumePending=true;return START_STICKY;}
        launch();return START_STICKY;
    }
    private void launch(){
        paused=false;resumePending=false;running=true;status="Проверка VIDEO ULTRA…";
        startForeground(ID,notification(),ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        PowerManager pm=getSystemService(PowerManager.class);
        lock=pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"MediaLAB:video-ultra");
        lock.setReferenceCounted(false);lock.acquire(30L*60*1000);
        worker=new Thread(()->{
            boolean ok=false;String end;
            try{
                VideoUltraPack.prepare(getApplicationContext(),message->{
                    status=message;
                    main.post(()->{if(running)getSystemService(NotificationManager.class).notify(ID,notification());});
                });
                ok=true;end="VIDEO ULTRA готов · SVT-AV1";
            }catch(Throwable e){end=paused?"Пауза · частичный файл сохранён":e.getClass().getSimpleName()+": "+e.getMessage();}
            final boolean success=ok;final String result=end;
            main.post(()->finishTransfer(success,result));
        },"MediaLAB-video-ultra");
        worker.start();
    }
    private void finishTransfer(boolean success,String result){
        worker=null;running=false;status=result;
        if(lock!=null&&lock.isHeld())lock.release();lock=null;
        if(resumePending&&!success){launch();return;}
        if(success||paused)persist(false);
        stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();
    }
    @Override public void onTimeout(int startId,int fgsType){
        paused=true;resumePending=false;persist(false);
        if(worker!=null)worker.interrupt();
        stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();
    }
    @Override public void onDestroy(){
        if(worker!=null)worker.interrupt();
        if(lock!=null&&lock.isHeld())lock.release();
        super.onDestroy();
    }
}
