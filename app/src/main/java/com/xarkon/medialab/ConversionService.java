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
    // One cached source can be reused across quality-preview changes.
    // A large file is not copied again on every slider adjustment.
    private static File cachedPreviewInput;
    private static Uri cachedPreviewUri;
    private static long cachedPreviewSize;
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
        volatile int quality;
        volatile int performance;
        volatile String status = "Готов к обработке";
        volatile long revision;
        volatile long durationMs;
        volatile long samplesMs;
        volatile long earlySampleBytes;
        volatile long earlySampleDurationMs;
        volatile int earlySampleCount;
        volatile int earlySampleTotal;
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
                          ConvertEngine.Config config, boolean export) {
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
        cancellation.set(false);
        state.running = true;
        state.progress = 0;
        state.fileProgress = 0;
        state.fileNumber = 0;
        state.fileCount = 0;
        state.quality = request.getIntExtra("quality", 78);
        state.performance = request.getIntExtra("performance", 1);
        state.error = false;
        state.exporting = request.getBooleanExtra("export", false);
        if (state.exporting) {
            synchronized (state.results) { state.results.clear(); }
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
        ArrayList<File> previews = new ArrayList<>();
        ArrayList<File> comparisons = new ArrayList<>();
        long sampledMs = 0, durationMs = 0, sampledBytes = 0;
        ConvertEngine.Config cfg = new ConvertEngine.Config(request.getIntExtra("mode", 1),
                request.getStringExtra("format"), request.getIntExtra("quality", 78),
                request.getIntExtra("resolution", 0), request.getIntExtra("bitrate", 2),
                request.getIntExtra("performance", 1));
        boolean exporting = request.getBooleanExtra("export", false);
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
            FFmpegKitConfig.enableStatisticsCallback(stat -> {
                long total = activeClipMs;
                if (total <= 0 || !state.running) return;
                double portion = Math.min(0.99, Math.max(0.0, stat.getTime() / (double) total));
                int part = Math.min(91, 8 + (int) (83.0 * portion));
                if (!state.exporting) part = (int) (8 + 83.0 *
                        (segmentIndex + portion) / Math.max(1, segmentsCount));
                markFile(state.status, part);
            });
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
            for (int n = 0; n < files.size() && !cancellation.get(); n++) {
                ConvertEngine.Source src = files.get(n);
                state.fileNumber = n + 1;
                state.fileProgress = 0;
                File input = null;
                try {
                    markFile("Подготовка: " + src.name + " (" + (n+1) + "/" + files.size() + ")", 0);
                    if (!exporting && cachedPreviewInput != null && cachedPreviewInput.isFile()
                            && src.uri.equals(cachedPreviewUri)
                            && (src.size <= 0 || src.size == cachedPreviewSize)) {
                        input = cachedPreviewInput;
                        markFile("Исходник уже подготовлен. Пересчёт качества…", 8);
                    } else {
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
                        }
                    }
                    if (cancellation.get()) break;
                    ConvertEngine.MediaInfo info = ConvertEngine.info(input);
                    durationMs = info.durationMs;
                    if (exporting || cfg.mode == ConvertEngine.IMAGE) {
                        segmentsCount = files.size(); segmentIndex = n;
                        activeClipMs = info.durationMs;
                        File output = ConvertEngine.outputTemp(this, cfg.format);
                        try {
                            markFile("Конвертация " + (n+1) + "/" + files.size() + ": " + src.name, 8);
                            FFmpegSession session = ConvertEngine.convert(input, output, cfg, info, 0, 0);
                            validate(session, output);
                            if (cancellation.get()) break;
                            if (exporting) {
                                Uri dst = ConvertEngine.save(this, output,
                                        ConvertEngine.fileName(src.name, cfg.format, n),
                                        ConvertEngine.mime(cfg.mode, cfg.format), cancellation::get);
                                state.results.add(dst);
                                state.lastMime = ConvertEngine.mime(cfg.mode, cfg.format);
                                saved++;
                                markFile("Сохранён: " + src.name, 100);
                            } else {
                                previews.add(output);
                                output = null;
                                sampledMs = info.durationMs;
                            }
                        } finally { if (output != null) output.delete(); }
                    } else {
                        final long clipMs = cfg.mode == ConvertEngine.VIDEO ? 2500 : 2500;
                        List<Long> positions = ConvertEngine.previewStarts(info.durationMs, clipMs);
                        segmentsCount = positions.size();
                        state.earlySampleTotal=positions.size();
                        state.durationMs=info.durationMs;
                        for (int p = 0; p < positions.size() && !cancellation.get(); p++) {
                            segmentIndex = p;
                            long start = positions.get(p);
                            long length = info.durationMs > 0 ? Math.min(clipMs, Math.max(250, info.durationMs - start)) : clipMs;
                            activeClipMs = length;
                            markFile("Предпросмотр: участок " + (p+1) + "/" + positions.size(),
                                    8 + 83 * p / Math.max(1, positions.size()));
                            File output = ConvertEngine.outputTemp(this, cfg.format);
                            try {
                                FFmpegSession session = ConvertEngine.convert(input, output, cfg, info, start, length);
                                validate(session, output);
                                if (cancellation.get()) break;
                                sampledMs += length;
                                sampledBytes += output.length();
                                // Publish a measured estimate after each converted segment.
                                // Do not wait for the expensive synchronized side-by-side composite.
                                state.earlySampleBytes=sampledBytes;
                                state.earlySampleDurationMs=sampledMs;
                                state.earlySampleCount=p+1;
                                markFile("Предпросмотр: участок " + (p+1) + "/" + positions.size(),
                                        8 + 83 * (p + 1) / Math.max(1, positions.size()));
                                // A second preview-only stream is paired with the ORIGINAL
                                // at exactly this timestamp. The existing converted clip
                                // remains the source for the size estimate.
                                if (cfg.mode == ConvertEngine.VIDEO) {
                                    File paired = ConvertEngine.outputTemp(this, "mp4");
                                    try {
                                        markFile("Синхронизация: участок " + (p+1) + "/" + positions.size(),
                                                8 + 83 * (p + 1) / Math.max(1, positions.size()));
                                        FFmpegSession match = ConvertEngine.comparisonSample(input, output, paired, start, length, info.height>info.width);
                                        validate(match, paired);
                                        comparisons.add(paired);
                                        paired = null;
                                    } finally { if (paired != null) paired.delete(); }
                                }
                                previews.add(output);
                                output = null;
                            } finally { if (output != null) output.delete(); }
                        }
                    }
                } finally {
                    // Preserve the staged source only for future previews of this URI.
                    if (input != null && (exporting || input != cachedPreviewInput)) input.delete();
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
            else if (exporting) mark("Готово. Сохранено файлов: " + saved, 100, false);
            else mark("Выборка готова: " + state.parts.size() + " участков", 100, false);
            if (failure == null && !cancellation.get()) state.fileProgress = 100;
            state.running = false;
            state.revision++;
            processing.set(false);
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
    }

    private void validate(FFmpegSession result, File output) throws Exception {
        if (cancellation.get()) return;
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
