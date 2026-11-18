package com.xarkon.medialab;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.FFmpegKitConfig;
import com.arthenica.ffmpegkit.FFmpegSession;
import com.arthenica.ffmpegkit.ReturnCode;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Independent foreground conversion engine: Activity destruction never cancels jobs. */
public final class ConversionService extends Service {
    private static final String TAG = "XARKON_Service";
    static final String ACTION_START = "com.xarkon.medialab.START";
    static final String ACTION_CANCEL = "com.xarkon.medialab.CANCEL";
    static final String CHANNEL = "xarkon_conversion";
    static final int NOTIFICATION_ID = 4401;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean cancellation = new AtomicBoolean(false);
    private final AtomicBoolean processing = new AtomicBoolean(false);
    private PowerManager.WakeLock wakeLock;
    private long lastNotify = 0;
    private int latestStartId;
    private final android.os.Handler mainHandler=new android.os.Handler(android.os.Looper.getMainLooper());
    // One cached source can be reused across quality-preview changes.
    // A large file is not copied again on every slider adjustment.
    private static File cachedPreviewInput;
    private static Uri cachedPreviewUri;
    private static long cachedPreviewSize;
    private static long cachedPreviewModified;
    private volatile int segmentIndex, segmentsCount;
    private volatile long activeClipMs;

    static final class Snapshot {
        volatile boolean running;
        volatile boolean error;
        volatile boolean exporting;
        volatile int progress;
        volatile int fileProgress;
        volatile int fileNumber;
        volatile int fileCount;
        volatile int parallelActive, completedFiles;
        final java.util.Map<String,Uri> replacements=Collections.synchronizedMap(new java.util.HashMap<>());
        volatile int quality;
        volatile int performance;
        volatile int resolution, bitrate;
        volatile boolean canceled;
        volatile String status = "Готов к обработке";
        volatile long revision;
        volatile long durationMs;
        volatile long samplesMs;
        volatile long earlySampleBytes;
        volatile long earlySampleDurationMs;
        volatile int earlySampleCount;
        volatile int earlySampleTotal;
        volatile boolean previewUnverified;
        volatile boolean previewSourceOnly;
        volatile boolean fullPreview;
        volatile String sourceKey = "";

        volatile String format = "mp4";
        volatile int mode;
        final List<File> parts = Collections.synchronizedList(new ArrayList<>());
        final List<File> compareParts = Collections.synchronizedList(new ArrayList<>());
        final List<Uri> results = Collections.synchronizedList(new ArrayList<>());
        volatile String lastMime = "application/octet-stream";
    }
    static final Snapshot state = new Snapshot();

    static Intent request(MainActivity ctx, List<ConvertEngine.Source> sources,
                          ConvertEngine.Config config, boolean export,boolean fullPreview) {
        Intent i = new Intent(ctx, ConversionService.class).setAction(ACTION_START);
        ArrayList<Uri> uriList = new ArrayList<>();
        ArrayList<String> names = new ArrayList<>();
        long[] sizes = new long[sources.size()];
        for (int n = 0; n < sources.size(); n++) {
            uriList.add(sources.get(n).uri);
            names.add(sources.get(n).name);
            sizes[n] = sources.get(n).size;
        }
        i.putParcelableArrayListExtra("uris", uriList);
        i.putStringArrayListExtra("names", names);
        i.putExtra("sizes", sizes);
        i.putExtra("mode", config.mode);
        i.putExtra("format", config.format);
        i.putExtra("quality", config.quality);
        i.putExtra("resolution", config.resolution);
        i.putExtra("bitrate", config.audioBitrate);
        i.putExtra("performance", config.performance);
        i.putExtra("export", export);
        i.putExtra("fullPreview",fullPreview);
        return i;
    }

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, "Конвертация медиа", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Фоновая конвертация и предпросмотр файлов");
            manager.createNotificationChannel(channel);
        }
    }

    @Override public int onStartCommand(Intent request, int flags, int startId) {
        latestStartId=startId;
        if (request == null) return START_NOT_STICKY;
        if (ACTION_CANCEL.equals(request.getAction())) {
            cancellation.set(true);
            mark("Остановка операции…", state.progress, false);
            if (processing.get()) {
                try { FFmpegKit.cancel(); } catch (Throwable err) { Log.e(TAG, "Cancel", err); }
            } else stopSelf();
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(request.getAction()) || !processing.compareAndSet(false, true)) return START_NOT_STICKY;
        if(!NativeLibraryPack.isPrepared()){
            processing.set(false);state.running=false;mark("Сначала докачайте FFmpeg в MediaLAB",0,true);state.revision++;stopSelf();return START_NOT_STICKY;
        }
        cancellation.set(false);
        state.running = true;
        state.progress = 0;
        state.fileProgress = 0;
        state.fileNumber = 0;
        state.fileCount = 0;
        state.parallelActive=0;state.completedFiles=0;
        state.quality = request.getIntExtra("quality", 78);
        state.performance = request.getIntExtra("performance", 1);
        state.resolution=request.getIntExtra("resolution",0);
        state.bitrate=request.getIntExtra("bitrate",2);
        state.canceled=false;
        state.error = false;
        state.exporting = request.getBooleanExtra("export", false);
        if (state.exporting) {
            synchronized (state.results) { state.results.clear(); }
            state.replacements.clear();
        }
        state.status = "Подготовка задания…";
        state.mode = request.getIntExtra("mode", ConvertEngine.VIDEO);
        state.format = request.getStringExtra("format");
        if (state.format == null) state.format = "mp4";
        state.durationMs = 0;
        state.samplesMs = 0;
        state.earlySampleBytes=0;
        state.earlySampleDurationMs=0;
        state.earlySampleCount=0;
        state.earlySampleTotal=0;
        state.previewUnverified=false;
        state.previewSourceOnly=false;
        state.fullPreview=true;
        ArrayList<Uri> requestUris = request.getParcelableArrayListExtra("uris");
        state.sourceKey = requestUris != null && !requestUris.isEmpty() ?
                requestUris.get(0).toString() : "";

        state.revision++;
        // Called before any disk access. Foreground is required on modern Android.
        try {
            Notification initial = notification(state.status, 0, false);
            if (Build.VERSION.SDK_INT >= 35) startForeground(NOTIFICATION_ID, initial, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING);
            else if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, initial, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            else startForeground(NOTIFICATION_ID, initial);
        } catch (Throwable ex) {
            processing.set(false);
            state.running = false;
            mark("Не удалось запустить фоновую службу: " + ex.getMessage(), 0, true);
            state.revision++;
            stopSelf();
            return START_NOT_STICKY;
        }
        worker.execute(() -> process(request, startId));
        return START_NOT_STICKY;
    }

    @SuppressWarnings("deprecation")
    private void process(Intent request, int startId) {
        String failure = null;
        int saved = 0;
        int failed = 0, warnings=0;
        String firstFileError = null, firstWarning=null;
        ArrayList<File> previews = new ArrayList<>();
        ArrayList<File> comparisons = new ArrayList<>();
        long sampledMs = 0, durationMs = 0, sampledBytes = 0;
        ConvertEngine.Config cfg = new ConvertEngine.Config(request.getIntExtra("mode", 1),
                request.getStringExtra("format"), request.getIntExtra("quality", 78),
                request.getIntExtra("resolution", 0), request.getIntExtra("bitrate", 2),
                request.getIntExtra("performance", 1));
        boolean exporting = request.getBooleanExtra("export", false);
        String outputValue=request.getStringExtra("outputTree");
        Uri outputTree=outputValue==null?null:Uri.parse(outputValue);
        boolean originalNames=request.getBooleanExtra("originalNames",false);
        boolean replace=request.getBooleanExtra("replaceOriginals",false)&&cfg.mode!=ConvertEngine.EXTRACT;
        int requestedWorkers=Math.max(1,Math.min(ConvertEngine.maxParallelWorkers(),request.getIntExtra("parallelWorkers",1)));
        boolean fullPreview=true;
        state.fullPreview=fullPreview;
        if(cfg.performance==3)try{android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_MORE_FAVORABLE);}catch(Exception ignored){}
        ArrayList<Uri> uris = request.getParcelableArrayListExtra("uris");
        ArrayList<String> names = request.getStringArrayListExtra("names");
        long[] sizes = request.getLongArrayExtra("sizes");
        if (uris == null) uris = new ArrayList<>();
        if (names == null) names = new ArrayList<>();
        final ArrayList<ConvertEngine.Source> files = new ArrayList<>();
        for (int n = 0; n < uris.size() && n < names.size(); n++) {
            files.add(new ConvertEngine.Source(uris.get(n), names.get(n),
                    sizes != null && sizes.length > n ? sizes[n] : -1));
        }
        state.fileCount = exporting ? files.size() : 1;
        state.fileNumber = files.isEmpty() ? 0 : 1;
        try {
            if (files.isEmpty()) throw new IllegalArgumentException("Не переданы исходные файлы");
            acquireWakeLock();
            FFmpegKitConfig.enableStatisticsCallback(cfg.quality==SmartCompression.QUALITY?null:stat -> {
                long total = activeClipMs;
                if (total <= 0 || !state.running) return;
                double portion = Math.min(0.99, Math.max(0.0, stat.getTime() / (double) total));
                int part = Math.min(91, 8 + (int) (83.0 * portion));
                if (!state.exporting) part = (int) (8 + 83.0 *
                        (segmentIndex + portion) / Math.max(1, segmentsCount));
                markFile(state.status, part);
            });
            if(exporting&&cachedPreviewInput!=null){cachedPreviewInput.delete();cachedPreviewInput=null;cachedPreviewUri=null;}
            if (!exporting) {
                synchronized (state.parts) {
                    for (File prev : state.parts) prev.delete();
                    state.parts.clear();
                }
                synchronized (state.compareParts) {
                    for (File paired : state.compareParts) paired.delete();
                    state.compareParts.clear();
                }
            }
            if(exporting){
                FFmpegKitConfig.enableStatisticsCallback(null); // session-global callback cannot attribute concurrent jobs
                if(!replace&&outputTree!=null&&!OutputPolicy.hasWrite(this,outputTree))
                    throw new java.io.IOException("Нет доступа к выбранной папке сохранения. Выберите её заново");
                int workers=Math.min(files.size(),ConvertEngine.safeParallelWorkers(requestedWorkers,cfg));
                BatchResult batch=exportBatch(files,cfg,outputTree,originalNames,replace,workers);
                saved=batch.saved;failed=batch.failed;firstFileError=batch.firstError;warnings=batch.warnings;firstWarning=batch.firstWarning;
            }else for (int n = 0; n < files.size() && !cancellation.get(); n++) {
                ConvertEngine.Source src = files.get(n);
                state.fileNumber = n + 1;
                state.fileProgress = 0;
                File input = null;
                ConvertEngine.DirectInput direct=null;
                try {
                    ConvertEngine.Config itemCfg = ConvertEngine.forSource(cfg, src);
                    markFile("Подготовка: " + src.name + " (" + (n+1) + "/" + files.size() + ")", 0);
                    if(itemCfg.mode!=ConvertEngine.IMAGE){
                        direct=ConvertEngine.openDirect(this,src);
                        if(direct!=null){
                            if(ConvertEngine.canProbeDirect(direct.file)){input=direct.file;markFile("Прямое чтение исходника без копирования",8);}
                            else{direct.close();direct=null;}
                        }
                    }
                    if (input==null && !exporting && cachedPreviewInput != null && cachedPreviewInput.isFile()
                            && src.uri.equals(cachedPreviewUri)
                            && (src.size <= 0 || src.size == cachedPreviewSize) && cachedPreviewModified>0 &&
                            cachedPreviewModified==ConvertEngine.modified(this,src.uri)) {
                        input = cachedPreviewInput;
                        markFile("Исходник уже подготовлен. Пересчёт качества…", 8);
                    } else if(input==null) {
                        if (!exporting && cachedPreviewInput != null) {
                            cachedPreviewInput.delete();cachedPreviewInput=null;cachedPreviewUri=null;
                        }
                        input = ConvertEngine.stage(this, src, cancellation::get, (copied, total) -> {
                            if (total > 0) markFile("Копирование " + (int)(100.0 * copied / total) + "% (" +
                                    ConvertEngine.humanBytes(copied) + "/" + ConvertEngine.humanBytes(total) + ")",
                                    Math.min(8, (int)(8.0 * copied / total)));
                        });
                        if (!exporting) {
                            cachedPreviewInput = input;
                            cachedPreviewUri = src.uri;
                            cachedPreviewSize = src.size;
                            cachedPreviewModified=ConvertEngine.modified(this,src.uri);
                        }
                    }
                    if (cancellation.get()) break;
                    ConvertEngine.MediaInfo info = itemCfg.mode==ConvertEngine.IMAGE?new ConvertEngine.MediaInfo():direct!=null?ConvertEngine.info(this,src.uri):ConvertEngine.info(input);
                    if(direct!=null&&info.durationMs<=0)info=ConvertEngine.info(input);
                    durationMs = info.durationMs;
                    long modified=ConvertEngine.modified(this,src.uri);
                    if(modified<=0&&direct!=null)modified=direct.file.lastModified();
                    String sourceKey=modified>0&&src.size>0?src.uri+"|"+src.size+"|"+modified+"|"+itemCfg.mode+"|"+itemCfg.performance:null;
                    if (exporting || itemCfg.mode == ConvertEngine.IMAGE) {
                        segmentsCount = files.size(); segmentIndex = n;
                        activeClipMs = info.durationMs;
                        File output = ConvertEngine.outputTemp(this, itemCfg.format);
                        try {
                            markFile("Конвертация " + (n+1) + "/" + files.size() + ": " + src.name, 0);
                            String actualFormat=itemCfg.format;
                            boolean keptOriginal=false;
                            if(itemCfg.quality==SmartCompression.QUALITY){
                                output.delete(); output=null;
                                SmartCompression.Result result=SmartCompression.run(this,input,itemCfg,info,0,0,!exporting,sourceKey,cancellation::get,SmartCompression.reporting((msg,percent)->markFile(msg,percent)));
                                output=result.file;actualFormat=result.format;keptOriginal=result.original;
                                if(itemCfg.mode==ConvertEngine.VIDEO&&(keptOriginal||output.equals(input)||!result.verified||output.length()>=input.length()))
                                    throw new java.io.IOException("ASC PRO: видео не сжато. Исходный файл не изменён.");
                            } else {
                            boolean nativeImage = ConvertEngine.convertNativeImage(input, output, itemCfg, cancellation::get);
                            if(!nativeImage) {
                                FFmpegSession session = ConvertEngine.convert(input, output, itemCfg, info, 0, 0);
                                validate(session, output);
                            }
                            }
                            if (cancellation.get()) break;
                            if (exporting) {
                                String actualMime=keptOriginal?getContentResolver().getType(src.uri):ConvertEngine.mime(itemCfg.mode,actualFormat);
                                if(actualMime==null)actualMime=ConvertEngine.mime(itemCfg.mode,actualFormat);
                                Uri dst = ConvertEngine.save(this, output,
                                        ConvertEngine.fileName(src.name, actualFormat, n),actualMime, cancellation::get);
                                state.results.add(dst);
                                state.lastMime = actualMime;
                                saved++;
                                markFile((keptOriginal?"Сохранён без повторного сжатия: ":"Сохранён: ") + src.name, 100);
                            } else {
                                previews.add(output);
                                output = null;
                                sampledMs = info.durationMs;
                            }
                        } finally { if (output != null && !output.equals(input)) output.delete(); }
                    } else {
                        final long clipMs = 5000;
                        boolean smartVideo=itemCfg.mode==ConvertEngine.VIDEO&&itemCfg.quality==SmartCompression.QUALITY;
                        List<Long> positions = smartVideo?AscVideoOptions.sampleStarts(info.durationMs,clipMs):ConvertEngine.previewStarts(info.durationMs,clipMs);
                        final String previewKey=sourceKey!=null?sourceKey:(smartVideo?"preview:"+java.util.UUID.randomUUID():null);
                        if(info.durationMs<=0)throw new java.io.IOException("Не удалось определить длительность для центрального фрагмента");
                        segmentsCount = positions.size();
                        state.earlySampleTotal=positions.size();
                        state.durationMs=info.durationMs;
                        Exception lastSceneError=null;
                        for (int p = 0; p < positions.size() && !cancellation.get(); p++) {
                            segmentIndex = p;
                            long start = positions.get(p);
                            long length = info.durationMs > 0 ? Math.min(clipMs, Math.max(1, info.durationMs - start)) : clipMs;
                            activeClipMs = length;
                            markFile("Предпросмотр: участок " + (p+1) + "/" + positions.size(),
                                    8 + 83 * p / Math.max(1, positions.size()));
                            File output = ConvertEngine.outputTemp(this, itemCfg.format);
                            try {
                                if(itemCfg.quality==SmartCompression.QUALITY){
                                    output.delete();output=null;
                                    SmartCompression.Result result=SmartCompression.run(this,input,itemCfg,info,start,length,true,previewKey,cancellation::get,SmartCompression.reporting((msg,percent)->markFile(msg,percent)));
                                    output=result.file;
                                    if(!result.verified||result.original||output.equals(input))throw new java.io.IOException("ASC PRO: не получен корректно перекодированный образец");
                                }else{
                                    FFmpegSession session = ConvertEngine.convert(input, output, itemCfg, info, start, length);
                                    validate(session, output);
                                }
                                if (cancellation.get()) break;
                                // A second preview-only stream is paired with the ORIGINAL
                                // at exactly this timestamp. The existing converted clip
                                // remains the source for the size estimate.
                                if (itemCfg.mode == ConvertEngine.VIDEO && fullPreview) {
                                    File paired = ConvertEngine.outputTemp(this, "mp4");
                                    try {
                                        markFile("Синхронизация: участок " + (p+1) + "/" + positions.size(),
                                                8 + 83 * (p + 1) / Math.max(1, positions.size()));
                                        FFmpegSession match = ConvertEngine.comparisonSample(input, output, paired, start, length, info.height>info.width);
                                        // Never substitute the original for the actual encoded sample.
                                        validate(match, paired);
                                        if(cancellation.get())break;
                                        comparisons.add(paired);
                                        paired = null;
                                    } finally { if (paired != null) paired.delete(); }
                                }
                                long actualSampleMs=smartVideo?ConvertEngine.info(output).durationMs:length;
                                if(actualSampleMs<=0)actualSampleMs=length;
                                sampledMs += actualSampleMs;
                                sampledBytes += output.length();
                                state.earlySampleBytes=sampledBytes;
                                state.earlySampleDurationMs=sampledMs;
                                state.earlySampleCount++;
                                markFile("Предпросмотр: участок " + (p+1) + "/" + positions.size(),
                                        8 + 83 * (p + 1) / Math.max(1, positions.size()));
                                previews.add(output);
                                output = null;
                            } catch(Exception sceneError){
                                if(cancellation.get())break;
                                lastSceneError=sceneError;state.previewUnverified=true;
                                Log.w(TAG,"Skipped preview scene "+(p+1),sceneError);
                                // Keep previously successful scenes; the next scene can still play.
                            } finally { if (output != null) output.delete(); }
                        }
                        if(previews.isEmpty()&&!cancellation.get()&&lastSceneError!=null)throw lastSceneError;
                    }
                } catch (Exception itemError) {
                    if(cancellation.get())break;
                    if(!exporting)throw itemError;
                    failed++;
                    if(firstFileError==null)
                        firstFileError=src.name+": "+(itemError.getMessage()==null?"ошибка кодирования":itemError.getMessage());
                    Log.e(TAG,"Skipped failed batch item: "+src.name,itemError);
                    markFile("Не удалось обработать "+src.name+" ("+(n+1)+"/"+files.size()+")",100);
                } finally {
                    // Preserve the staged source only for future previews of this URI.
                    if(direct!=null)direct.close();
                    else if (input != null && (exporting || input != cachedPreviewInput || cachedPreviewModified<=0)) {
                        input.delete();if(input==cachedPreviewInput){cachedPreviewInput=null;cachedPreviewUri=null;}
                    }
                }
            }
        } catch (Throwable ex) {
            if (!cancellation.get()) {
                Log.e(TAG, "Conversion failed", ex);
                failure = ex.getClass().getSimpleName() + ": " + ex.getMessage();
            }
        } finally {
            try { FFmpegKitConfig.enableStatisticsCallback(null); }
            catch (Throwable logError) { Log.w(TAG, "Statistics callback cleanup", logError); }
            activeClipMs = 0;
            releaseWakeLock();
            state.durationMs = durationMs;
            state.samplesMs = sampledMs;
            if (failure == null && !cancellation.get() && !exporting) {
                synchronized (state.parts) { state.parts.addAll(previews); }
                synchronized (state.compareParts) { state.compareParts.addAll(comparisons); }
                previews.clear(); comparisons.clear();
            }
            for (File temp : previews) temp.delete();
            for (File temp : comparisons) temp.delete();
            if (failure != null) mark("Ошибка: " + failure, 0, true);
            else if (cancellation.get()) mark("Операция отменена. Сохранено: " + saved, 0, false);
            else if (exporting && failed > 0)
                mark("Завершено: сохранено "+saved+" из "+files.size()+
                     ". Ошибок: "+failed+". Первая: "+firstFileError,100,true);
            else if (exporting && warnings>0) mark("Готово. Сохранено: "+saved+". Предупреждений: "+warnings+". "+firstWarning,100,false);
            else if (exporting) mark("Готово. Сохранено файлов: " + saved, 100, false);
            else mark("Реальный предпросмотр готов",100,false);
            if (failure == null && !cancellation.get()) state.fileProgress = 100;
            // Serialize lifecycle completion with onStartCommand on the main thread.
            // Publish idle only after stopping the old foreground session.
            mainHandler.post(()->{
                state.canceled=cancellation.get();
                stopForeground(STOP_FOREGROUND_REMOVE);
                processing.set(false);
                state.running=false;
                state.revision++;
                stopSelfResult(latestStartId);
            });
        }
    }

    private static final class BatchResult {
        int saved,failed,warnings;String firstError,firstWarning;
    }
    private static final class ItemResult {
        final boolean success,warning;final String message;
        ItemResult(boolean ok,String msg){this(ok,msg,false);}
        ItemResult(boolean ok,String msg,boolean warning){success=ok;message=msg;this.warning=warning;}
    }
    private final Object batchLock=new Object();
    private int[] batchProgress;
    private void batchProgress(int index,int value,String message){
        synchronized(batchLock){
            if(batchProgress==null)return;
            batchProgress[index]=Math.max(batchProgress[index],Math.max(0,Math.min(100,value)));
            int total=0;for(int p:batchProgress)total+=p;
            state.fileProgress=batchProgress[index];state.fileNumber=index+1;
            mark(message,Math.min(99,total/Math.max(1,batchProgress.length)),false);
        }
    }
    private BatchResult exportBatch(ArrayList<ConvertEngine.Source> files,ConvertEngine.Config cfg,
            Uri tree,boolean names,boolean replace,int workers)throws Exception{
        BatchResult result=new BatchResult();
        java.util.concurrent.ExecutorService pool=Executors.newFixedThreadPool(Math.max(1,workers));
        java.util.concurrent.ExecutorCompletionService<ItemResult> done=new java.util.concurrent.ExecutorCompletionService<>(pool);
        batchProgress=new int[files.size()];
        state.parallelActive=workers;
        int cap=Math.max(1,ConvertEngine.threads(cfg.performance)/Math.max(1,workers));
        int submitted=0,finished=0;
        try{
            while(submitted<files.size()&&submitted<workers&&!cancellation.get()){
                final int index=submitted++;
                done.submit(()->exportOne(files.get(index),cfg,index,tree,names,replace,cap,workers));
            }
            while(finished<submitted){
                ItemResult item=done.take().get();finished++;
                if(item.success){result.saved++;if(item.warning){result.warnings++;if(result.firstWarning==null)result.firstWarning=item.message;}}
                else if(!cancellation.get()){result.failed++;if(result.firstError==null)result.firstError=item.message;}
                state.completedFiles=finished;
                if(submitted<files.size()&&!cancellation.get()){
                    final int index=submitted++;
                    done.submit(()->exportOne(files.get(index),cfg,index,tree,names,replace,cap,workers));
                }
                state.parallelActive=submitted-finished;
            }
        }finally{
            pool.shutdown();
            if(!pool.awaitTermination(5,java.util.concurrent.TimeUnit.SECONDS)){
                cancellation.set(true);try{FFmpegKit.cancel();}catch(Throwable ignored){}
                pool.shutdownNow();
            }
            batchProgress=null;state.parallelActive=0;
        }
        return result;
    }
    private ItemResult exportOne(ConvertEngine.Source src,ConvertEngine.Config cfg,int index,
            Uri tree,boolean names,boolean replace,int cap,int workers){
        ConvertEngine.DirectInput direct=null;File input=null,output=null;
        ConvertEngine.setWorkerLimit(cap,workers);
        try{
            if(cancellation.get())return new ItemResult(false,"Отменено");
            ConvertEngine.Config itemCfg=ConvertEngine.forSource(cfg,src);
            batchProgress(index,0,"Подготовка: "+src.name);
            if(replace&&!OutputPolicy.canReplace(this,src.uri))
                throw new java.io.IOException("Нет доступа к исходной папке. Предоставьте разрешение на запись; оригинал не изменён");
            // Replacement needs an independent snapshot for the final original-byte check.
            if(!replace&&itemCfg.mode!=ConvertEngine.IMAGE){
                direct=ConvertEngine.openDirect(this,src);
                if(direct!=null){
                    if(ConvertEngine.canProbeDirect(direct.file))input=direct.file;
                    else{direct.close();direct=null;}
                }
            }
            if(input==null)input=ConvertEngine.stage(this,src,cancellation::get,(copied,total)->{
                if(total>0)batchProgress(index,Math.min(8,(int)(8.0*copied/total)),"Чтение: "+src.name);
            });
            if(cancellation.get())return new ItemResult(false,"Отменено");
            ConvertEngine.MediaInfo info=itemCfg.mode==ConvertEngine.IMAGE?new ConvertEngine.MediaInfo():ConvertEngine.info(input);
            long modified=ConvertEngine.modified(this,src.uri);
            String key=modified>0&&src.size>0?src.uri+"|"+src.size+"|"+modified+"|"+itemCfg.mode+"|"+itemCfg.performance:null;
            String format=itemCfg.format;boolean kept=false;
            batchProgress(index,0,"Конвертация → "+format.toUpperCase(java.util.Locale.ROOT)+": "+src.name);
            if(itemCfg.quality==SmartCompression.QUALITY){
                SmartCompression.Result result=SmartCompression.run(this,input,itemCfg,info,0,0,false,key,cancellation::get,SmartCompression.reporting((msg,percent)->batchProgress(index,percent,msg+" · "+src.name)));
                output=result.file;format=result.format;kept=result.original;
                if(itemCfg.mode==ConvertEngine.VIDEO&&(kept||output.equals(input)||!result.verified||output.length()>=input.length()))
                    throw new java.io.IOException("ASC PRO: видео не сжато. Исходный файл не изменён.");
            }else{
                output=ConvertEngine.outputTemp(this,format);
                if(!ConvertEngine.convertNativeImage(input,output,itemCfg,cancellation::get)){
                    FFmpegSession session=ConvertEngine.convert(input,output,itemCfg,info,0,0);
                    validate(session,output);
                }
            }
            if(cancellation.get())return new ItemResult(false,"Отменено");
            batchProgress(index,85,"Кодирование завершено: "+src.name+" → "+format.toUpperCase(java.util.Locale.ROOT));
            String mime=kept?getContentResolver().getType(src.uri):ConvertEngine.mime(itemCfg.mode,format);
            if(mime==null)mime=ConvertEngine.mime(itemCfg.mode,format);
            if(replace&&!kept){
                batchProgress(index,85,"Проверка декодирования: "+src.name);
                ConvertEngine.verifyForReplacement(output,itemCfg.mode,info,cancellation::get);
            }
            batchProgress(index,90,"Сохранение и проверка: "+src.name);
            OutputPolicy.Saved saved=OutputPolicy.save(this,output,input,src,format,mime,index,tree,names,replace,kept,cancellation::get);
            synchronized(state.results){state.results.add(saved.uri);state.lastMime=mime;}
            if(saved.replaced)state.replacements.put(src.uri.toString(),saved.uri);
            String message=saved.warning.isEmpty()?(saved.replaced?"Заменён: ":"Сохранён: ")+src.name:saved.warning+" · "+src.name;
            batchProgress(index,100,message);
            return new ItemResult(true,message,!saved.warning.isEmpty());
        }catch(Exception | OutOfMemoryError error){
            String msg=src.name+": "+(error.getMessage()==null?"ошибка обработки":error.getMessage());
            Log.e(TAG,msg,error);batchProgress(index,100,msg);
            return new ItemResult(false,msg);
        }finally{
            if(output!=null&&!output.equals(input))output.delete();
            if(direct!=null)direct.close();else if(input!=null)input.delete();
            ConvertEngine.clearWorkerLimit();
        }
    }

    private void validate(FFmpegSession result, File output) throws Exception {
        if (cancellation.get()) throw new java.io.IOException("Операция отменена");
        if (result == null || !ReturnCode.isSuccess(result.getReturnCode()) || !output.exists() || output.length() == 0) {
            String logs = result == null ? "Отсутствует сессия" : result.getAllLogsAsString();
            if (logs == null) logs = "Нет сведений об ошибке";
            logs = logs.replaceAll("\\s+", " ");
            if (logs.length() > 450) logs = logs.substring(logs.length() - 450);
            throw new Exception("FFmpeg: " + logs);
        }
    }

    private void markFile(String message, int fileValue) {
        int file = Math.max(0, Math.min(100, fileValue));
        state.fileProgress = Math.max(state.fileProgress, file);
        int count = Math.max(1, state.fileCount);
        int index = Math.max(0, state.fileNumber - 1);
        int overall = Math.min(99, (int) ((index + state.fileProgress / 100.0) * 100 / count));
        mark(message, overall, false);
    }

    private void mark(String message, int value, boolean error) {
        state.status = message;
        state.error = error;
        int clamped = Math.max(0, Math.min(100, value));
        state.progress = state.running && !error ? Math.max(state.progress, clamped) : clamped;
        long now = android.os.SystemClock.elapsedRealtime();
        if (state.running && (now - lastNotify > 800 || error)) {
            lastNotify = now;
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTIFICATION_ID, notification(message, state.progress, false));
        }
    }

    private Notification notification(String message, int pct, boolean finished) {
        Intent open = new Intent(this, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent launch = PendingIntent.getActivity(this, 7, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stop = new Intent(this, ConversionService.class).setAction(ACTION_CANCEL);
        PendingIntent cancel = PendingIntent.getService(this, 8, stop, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle("MediaLAB")
                .setContentText(message + " • файл " + state.fileProgress + "%")
                .setContentIntent(launch)
                .setOngoing(!finished)
                .setOnlyAlertOnce(true)
                .setProgress(100, pct, pct == 0);
        if (!finished) b.addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "Отменить", cancel).build());
        return b.build();
    }

    private void acquireWakeLock() {
        PowerManager manager = (PowerManager) getSystemService(POWER_SERVICE);
        if (manager == null) return;
        wakeLock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "XARKON:Convert");
        wakeLock.acquire(6L * 60 * 60 * 1000);
    }
    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        wakeLock = null;
    }

    @Override public void onDestroy() {
        if (processing.get()) {
            cancellation.set(true);
            try { FFmpegKit.cancel(); } catch (Throwable ignored) { }
        }
        releaseWakeLock();
        worker.shutdownNow();
        super.onDestroy();
    }
    @Override public void onTimeout(int startId, int fgsType) {
        // Android 15+ caps mediaProcessing FGS time. Stop immediately to avoid an ANR.
        cancellation.set(true);
        state.error = true;
        state.status = "Время фоновой обработки исчерпано (системный лимит Android)";
        try { FFmpegKit.cancel(); } catch (Throwable ignored) { }
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
