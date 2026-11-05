package com.xarkon.medialab;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;

/** Optional scheduled notification checks; Android controls execution under battery limits. */
public final class MediaOtaSchedule {
    private MediaOtaSchedule(){}
    static final String PREF="media_ota";
    static final String KEY_ENABLED="background_enabled";
    static final String KEY_HOURS="background_hours";
    static final long[] HOURS={1,3,6,12,24,72,168};
    static final String[] LABELS={"Каждый час","Каждые 3 часа","Каждые 6 часов",
            "Каждые 12 часов","Каждые 24 часа","Каждые 3 дня","Каждые 7 дней"};
    private static final int JOB=0x4D454401;
    public static void configure(Context context) {
        JobScheduler scheduler=(JobScheduler)context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if(scheduler==null)return;
        SharedPreferences p=context.getSharedPreferences(PREF,Context.MODE_PRIVATE);
        if(!p.getBoolean(KEY_ENABLED,false)){scheduler.cancel(JOB);return;}
        long hours=p.getLong(KEY_HOURS,6);
        boolean valid=false;
        for(long x:HOURS)if(x==hours){valid=true;break;}
        if(!valid)hours=6;
        long interval=hours*3600000L;
        JobInfo current=scheduler.getPendingJob(JOB);
        if(current!=null && current.getIntervalMillis()==interval)return;
        JobInfo info=new JobInfo.Builder(JOB,new ComponentName(context,MediaOtaBackgroundJob.class))
                .setPersisted(true).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(interval,Math.min(interval/4,30*60*1000L)).build();
        scheduler.schedule(info);
    }
}
