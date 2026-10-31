package com.xarkon.medialab;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.OpenableColumns;

import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.FFmpegSession;
import com.arthenica.ffmpegkit.FFprobeKit;
import com.arthenica.ffmpegkit.MediaInformation;
import com.arthenica.ffmpegkit.MediaInformationSession;
import com.arthenica.ffmpegkit.ReturnCode;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** The codec engine intentionally receives only local files, never arbitrary FFmpeg strings. */
final class ConvertEngine {
    static final int IMAGE = 0, VIDEO = 1, AUDIO = 2, EXTRACT = 3;
    static final String[][] FORMATS = {
            {"jpg", "png", "webp", "avif", "bmp", "tiff", "gif"},
            {"mp4", "mkv", "webm", "mov", "avi", "gif"},
            {"mp3", "m4a", "aac", "ogg", "opus", "flac", "wav", "wma", "aiff"},
            {"mp3", "m4a", "aac", "ogg", "opus", "flac", "wav", "wma", "aiff", "mka"}
    };
    static final String[] RESOLUTIONS = {"Исходное", "1080p", "720p", "480p", "360p"};

    static final class Source {
        final Uri uri;
        final String name;
        final long size;
        Source(Uri uri, String name, long size) { this.uri = uri; this.name = name; this.size = size; }
    }
    static final class Config {
        final int mode, quality, resolution, audioBitrate, performance;
        final String format;
        Config(int mode, String format, int quality, int resolution, int audioBitrate) {
            this(mode, format, quality, resolution, audioBitrate, 1);
        }
        Config(int mode, String format, int quality, int resolution, int audioBitrate, int performance) {
            this.performance = Math.max(0, Math.min(3, performance));
            this.mode = mode;
            this.format = format;
            this.quality = quality;
            this.resolution = resolution;
            this.audioBitrate = audioBitrate;
        }
    }
    static final class MediaInfo {
        long durationMs;
        int width, height;
    }

    interface Canceled { boolean canceled(); }
    interface CopyProgress { void update(long current, long total); }
    static Source describe(Context ctx, Uri uri) {
        String name = "media";
        long size = -1;
        try (Cursor c = ctx.getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int z = c.getColumnIndex(OpenableColumns.SIZE);
                if (n >= 0 && !c.isNull(n)) name = c.getString(n);
                if (z >= 0 && !c.isNull(z)) size = c.getLong(z);
            }
        } catch (Exception ignored) { }
        if (name == null || name.trim().isEmpty()) name = "media";
        return new Source(uri, name, size);
    }
    // File classification never trusts the active tab. A mismatched selected file
    // must redirect to its actual media category, not fail inside FFmpeg.
    static final int UNSUPPORTED = -1;
    static int classify(String mimeType, String filename) {
        String mime = mimeType == null ? "" : mimeType.toLowerCase(Locale.ROOT).trim();
        String ext = extension(filename == null ? "" : filename);
        // Audio-first prevents audio/mp4 and audio/webm being mistaken for videos.
        if (mime.startsWith("audio/")) return AUDIO;
        if (mime.startsWith("image/")) return IMAGE;
        if (mime.startsWith("video/")) return VIDEO;
        if (among(ext, "jpg", "jpeg", "jpe", "png", "webp", "avif", "bmp", "tif", "tiff", "gif",
                "heic", "heif", "dng", "cr2", "nef", "arw", "raf", "orf")) return IMAGE;
        if (among(ext, "mp3", "m4a", "aac", "ogg", "oga", "opus", "flac", "wav", "wma", "aiff",
                "aif", "mka", "alac", "ape", "amr", "mid", "midi")) return AUDIO;
        if (among(ext, "mp4", "mkv", "webm", "mov", "avi", "3gp", "m4v", "flv", "wmv", "ts",
                "mts", "m2ts", "mpeg", "mpg", "vob", "ogv", "asf")) return VIDEO;
        return UNSUPPORTED;
    }
    private static boolean among(String word, String... supported) {
        for (String item : supported) if (item.equals(word)) return true;
        return false;
    }
    static int classify(Context ctx, Source src) {
        String mime = null;
        try { mime = ctx.getContentResolver().getType(src.uri); }
        catch (Exception ignored) { }
        return classify(mime, src.name);
    }
    static String kindLabel(int kind) {
        if (kind == IMAGE) return "Изображения";
        if (kind == VIDEO) return "Видео";
        if (kind == AUDIO) return "Аудио";
        return "Неизвестный формат";
    }
    // Lightweight media information by SAF URI, no 0.9 GB disk copy required.
    static MediaInfo info(Context ctx, Uri uri) {
        MediaInfo inf = new MediaInfo();
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(ctx, uri);
            inf.durationMs = parseLong(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
            inf.width = (int) parseLong(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
            inf.height = (int) parseLong(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
            int rotation = (int) parseLong(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION));
            if (rotation % 180 != 0) { int temp=inf.width; inf.width=inf.height; inf.height=temp; }
        } catch (Exception ignored) { }
        finally { try { r.release(); } catch (Exception ignored) { } }
        return inf;
    }

    // Cheap prediction. This is *not* called a measured file size.
    // Video: same target bitrate expression as the actual encoder.
    static long quickEstimate(Source source, Config cfg, MediaInfo info) {
        long original = Math.max(0, source.size);
        long duration = info == null ? 0 : info.durationMs;
        if (cfg.mode == VIDEO && duration > 0 && info.width > 0 && info.height > 0 && !"gif".equals(cfg.format)) {
            int width = info.width, height = info.height;
            int limit = cfg.resolution == 1 ? 1080 : cfg.resolution == 2 ? 720 :
                    cfg.resolution == 3 ? 480 : cfg.resolution == 4 ? 360 : 0;
            if (limit > 0 && height > limit) {
                width = Math.max(2, (int)(width * limit / (float)height) / 2 * 2);
                height = limit;
            }
            double q = 0.40 + cfg.quality / 100.0 * 1.8;
            long bitrate = (long)Math.max(220_000, Math.min(24_000_000, width * (double)height * 30 * .042 * q));
            long audio = "avi".equals(cfg.format) ? 160_000 : 128_000;
            return Math.max(1, (long)((bitrate+audio) * (duration / 8000.0) * 1.03));
        }
        if ((cfg.mode == AUDIO || cfg.mode == EXTRACT) && duration > 0) {
            if (among(cfg.format, "wav", "aiff")) return (long)(duration * 176.4); // stereo 44.1k/16 bit
            if ("flac".equals(cfg.format)) return (long)(duration * 90); // very rough lossless estimate
            if ("mka".equals(cfg.format)) return original > 0 ? original : -1;
            int[] rates = {96,128,192,320};
            return (long)(duration * (rates[Math.max(0,Math.min(3,cfg.audioBitrate))] / 8.0) * 1.02);
        }
        if (original <= 0) return -1;
        if (cfg.mode == IMAGE) {
            double factor;
            switch (cfg.format) {
                case "jpg": factor=.25 + cfg.quality*.008; break;
                case "webp": factor=.15 + cfg.quality*.006; break;
                case "avif": factor=.10 + cfg.quality*.005; break;
                case "png": factor=1.1; break;
                case "bmp": factor=2.0; break;
                case "tiff": factor=1.0; break;
                default: factor=1.0;
            }
            return Math.max(1,(long)(original*factor));
        }
        // No duration metadata yet: display a deliberately rough interim value.
        return Math.max(1, (long)(original*(.4 + cfg.quality*.006)));
    }

    static String extension(String name) {
        int dot = name.lastIndexOf('.');
        if (dot <= 0 || dot + 1 >= name.length()) return "bin";
        String e = name.substring(dot + 1).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return e.isEmpty() ? "bin" : (e.length() > 10 ? e.substring(0, 10) : e);
    }
    static File stage(Context ctx, Source source, Canceled canceled) throws IOException {
        return stage(ctx, source, canceled, (current, total) -> {});
    }
    static File stage(Context ctx, Source source, Canceled canceled, CopyProgress progress) throws IOException {
        File dir = new File(ctx.getCacheDir(), "working");
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("Не удалось создать временный каталог");
        if (source.size > 0) {
            android.os.StatFs storage = new android.os.StatFs(dir.getAbsolutePath());
            long available = storage.getAvailableBytes();
            if (available < source.size + 64L * 1024 * 1024)
                throw new IOException("Недостаточно места для временной копии: доступно " +
                        humanBytes(available) + ", требуется минимум " +
                        humanBytes(source.size + 64L * 1024 * 1024));
        }
        File result = new File(dir, "in_" + System.nanoTime() + "." + extension(source.name));
        try (InputStream in = ctx.getContentResolver().openInputStream(source.uri);
             OutputStream out = new FileOutputStream(result)) {
            if (in == null) throw new IOException("Провайдер файлов не открыл документ");
            byte[] buffer = new byte[128 * 1024];
            int read;
            long copied = 0;
            while ((read = in.read(buffer)) != -1) {
                if (canceled.canceled()) throw new IOException("Операция отменена");
                out.write(buffer, 0, read);
                copied += read;
                progress.update(copied, source.size);
            }
        } catch (IOException ex) {
            result.delete();
            throw ex;
        }
        return result;
    }
    static MediaInfo info(File path) {
        MediaInfo inf = new MediaInfo();
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(path.getAbsolutePath());
            inf.durationMs = parseLong(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
            inf.width = (int) parseLong(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
            inf.height = (int) parseLong(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
            int rotation=(int)parseLong(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION));
            if (rotation%180!=0) {int tmp=inf.width;inf.width=inf.height;inf.height=tmp;}
        } catch (Exception ignored) { }
        finally { try { r.release(); } catch (Exception ignored) { } }
        if (inf.durationMs <= 0) {
            try {
                MediaInformationSession probe = FFprobeKit.getMediaInformation(path.getAbsolutePath());
                MediaInformation metadata = probe.getMediaInformation();
                if (metadata != null && metadata.getDuration() != null) {
                    inf.durationMs = (long) (Double.parseDouble(metadata.getDuration()) * 1000.0);
                }
            } catch (Exception ignored) { }
        }
        return inf;
    }
    /** Five non-overlapping points, including the very beginning and the final scene. */
    static List<Long> previewStarts(long durationMs, long clipMs) {
        ArrayList<Long> positions = new ArrayList<>();
        if (durationMs <= clipMs || durationMs <= 0) {
            positions.add(0L);
            return positions;
        }
        long last = Math.max(0, durationMs - clipMs);
        int count = (int) Math.min(5, Math.max(1, durationMs / clipMs));
        for (int n = 0; n < count; n++) {
            long proposed = count == 1 ? 0 : Math.round(last * (double) n / (count - 1));
            if (positions.isEmpty() || proposed >= positions.get(positions.size() - 1) + clipMs) positions.add(proposed);
        }
        return positions;
    }

    private static long parseLong(String v) {
        try { return Long.parseLong(v); } catch (Exception ignored) { return 0L; }
    }
    static File outputTemp(Context context, String ext) throws IOException {
        File directory = new File(context.getCacheDir(), "working");
        if (!directory.exists() && !directory.mkdirs()) throw new IOException("Не удалось создать каталог");
        // FFmpeg requires the output path not to exist when using certain muxers.
        return new File(directory, "out_" + System.nanoTime() + "." + ext);
    }

    static List<String> command(File input, File output, Config cfg, MediaInfo inf, long startMs, long lengthMs) {
        ArrayList<String> args = new ArrayList<>();
        add(args, "-hide_banner", "-nostdin", "-y");
        // Resource profile applies to software decoders, filters and codecs that honor thread count.
        // 0 means automatic thread selection; it does not circumvent Android thermal limits.
        int processors = Math.max(2, Runtime.getRuntime().availableProcessors());
        int threads = cfg.performance == 0 ? 2 : cfg.performance == 1 ? 4 :
                cfg.performance == 2 ? Math.max(2, processors - 2) : processors;
        String threadCount = String.valueOf(Math.min(16, threads));
        add(args, "-threads", threadCount);
        if (startMs > 0 && cfg.mode != IMAGE)
            add(args, "-ss", String.format(Locale.US, "%.3f", startMs / 1000.0));
        add(args, "-i", input.getAbsolutePath());
        if (lengthMs > 0 && cfg.mode != IMAGE)
            add(args, "-t", String.format(Locale.US, "%.3f", lengthMs / 1000.0));

        if (cfg.mode == IMAGE) {
            add(args, "-map", "0:v:0", "-frames:v", "1", "-an", "-sn");
            switch (cfg.format) {
                case "jpg":
                    add(args, "-c:v", "mjpeg", "-q:v", String.valueOf(Math.max(2, Math.min(20, 22 - cfg.quality / 5))));
                    break;
                case "png": add(args, "-c:v", "png", "-compression_level", "9"); break;
                case "webp": add(args, "-c:v", "libwebp", "-quality", String.valueOf(cfg.quality)); break;
                case "avif":
                    add(args, "-c:v", "libaom-av1", "-still-picture", "1", "-crf",
                            String.valueOf(56 - cfg.quality * 38 / 100), "-b:v", "0");
                    break;
                case "bmp": add(args, "-c:v", "bmp"); break;
                case "tiff": add(args, "-c:v", "tiff", "-compression_algo", "deflate"); break;
                case "gif": add(args, "-c:v", "gif"); break;
                default: throw new IllegalArgumentException("Неизвестный формат");
            }
        } else if (cfg.mode == AUDIO || cfg.mode == EXTRACT) {
            add(args, "-map", "0:a:0", "-vn", "-sn", "-dn");
            audioCodec(args, cfg);
        } else {
            add(args, "-map", "0:v:0", "-map", "0:a?", "-sn", "-dn");
            int limit = cfg.resolution == 1 ? 1080 : cfg.resolution == 2 ? 720 :
                    cfg.resolution == 3 ? 480 : cfg.resolution == 4 ? 360 : 0;
            String filter = limit == 0 ? null : "scale=w='trunc(iw*min(1," + limit + "/ih)/2)*2':h='trunc(ih*min(1," + limit + "/ih)/2)*2'";
            if ("gif".equals(cfg.format)) {
                String gifs = "fps=10" + (filter == null ? "" : "," + filter);
                add(args, "-vf", gifs, "-c:v", "gif", "-an", "-loop", "0");
            } else {
                if (filter != null) add(args, "-vf", filter);
                int width = inf.width > 0 ? inf.width : 1280;
                int height = inf.height > 0 ? inf.height : 720;
                if (limit > 0 && height > limit) {
                    width = Math.max(2, (int) (width * limit / (float) height) / 2 * 2);
                    height = limit;
                }
                double q = 0.40 + cfg.quality / 100.0 * 1.8;
                long b = (long) Math.max(220_000, Math.min(24_000_000, width * (double) height * 30 * .042 * q));
                if ("webm".equals(cfg.format)) {
                    add(args, "-c:v", "libvpx-vp9", "-deadline", "realtime", "-cpu-used", "5",
                            "-b:v", String.valueOf(b), "-c:a", "libopus", "-b:a", "128k");
                } else if ("avi".equals(cfg.format)) {
                    add(args, "-c:v", "mpeg4", "-b:v", String.valueOf(b),
                            "-c:a", "libmp3lame", "-b:a", "160k");
                } else {
                    add(args, "-c:v", "h264_mediacodec", "-b:v", String.valueOf(b),
                            "-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", "128k");
                    if ("mp4".equals(cfg.format) || "mov".equals(cfg.format)) add(args, "-movflags", "+faststart");
                }
            }
        }
        // Output encoder thread request. MediaCodec may ignore it; software codecs usually honor it.
        add(args, "-threads", threadCount);
        args.add(output.getAbsolutePath());
        return args;
    }
    private static void audioCodec(List<String> args, Config cfg) {
        String rate = cfg.audioBitrate == 0 ? "96k" : cfg.audioBitrate == 1 ? "128k" :
                cfg.audioBitrate == 2 ? "192k" : "320k";
        switch (cfg.format) {
            case "mp3": add(args, "-c:a", "libmp3lame", "-b:a", rate); break;
            case "m4a": case "aac": add(args, "-c:a", "aac", "-b:a", rate); break;
            case "ogg": add(args, "-c:a", "libvorbis", "-b:a", rate); break;
            case "opus": add(args, "-c:a", "libopus", "-b:a", rate); break;
            case "wma": add(args, "-c:a", "wmav2", "-b:a", rate); break;
            case "flac": add(args, "-c:a", "flac", "-compression_level", "8"); break;
            case "wav": add(args, "-c:a", "pcm_s16le"); break;
            case "aiff": add(args, "-c:a", "pcm_s16be"); break;
            case "mka": add(args, "-c:a", "copy"); break;
            default: throw new IllegalArgumentException("Неизвестный аудиоформат");
        }
    }
    private static void add(List<String> into, String... words) {
        for (String word : words) into.add(word);
    }
    static FFmpegSession convert(File input, File output, Config config, MediaInfo info, long startMs, long lengthMs) {
        List<String> args = command(input, output, config, info, startMs, lengthMs);
        FFmpegSession result = FFmpegKit.executeWithArguments(args.toArray(new String[0]));
        // Android hardware video encoder availability varies. Use software MPEG-4 for supported containers.
        if (!ReturnCode.isSuccess(result.getReturnCode()) &&
                config.mode == VIDEO && !"avi".equals(config.format) &&
                !"webm".equals(config.format) && !"gif".equals(config.format) &&
                !ReturnCode.isCancel(result.getReturnCode())) {
            output.delete();
            int at = args.indexOf("h264_mediacodec");
            if (at >= 0) {
                args.set(at, "mpeg4");
                args.remove("-pix_fmt");
                args.remove("yuv420p");
                result = FFmpegKit.executeWithArguments(args.toArray(new String[0]));
            }
        }
        return result;
    }
    /**
     * Both halves are timestamp-matched by FFmpeg before hstack. One encoded video,
     * one decoder and one GL texture eliminate independent player drift.
     * Preview-only composite, never used to estimate encoded output size.
     */
    static FFmpegSession comparisonSample(File original, File encoded, File paired,
                                         long startMs, long lengthMs, boolean portrait) {
        String start = String.format(Locale.US,"%.3f", startMs/1000.0);
        String seconds = String.format(Locale.US,"%.3f",lengthMs/1000.0);
        // Portrait and landscape both keep the source's display orientation.
        // One half is at most 768x432, or 432x768 for portrait.
        String size = portrait ? "432:768" : "768:432";
        String common = "fps=24,scale="+size+":force_original_aspect_ratio=decrease," +
                "pad="+size+":(ow-iw)/2:(oh-ih)/2,setsar=1,setpts=N/(24*TB)";
        String graph = "[0:v:0]" + common + "[left];[1:v:0]" + common +
                "[right];[left][right]hstack=inputs=2:shortest=1[paired]";
        List<String> args = new ArrayList<>();
        add(args,"-hide_banner","-nostdin","-y","-ss",start,"-i",original.getAbsolutePath(),
                "-i",encoded.getAbsolutePath(),"-filter_complex",graph,
                "-map","[paired]","-map","1:a?","-t",seconds,
                "-c:v","mpeg4","-q:v","2","-pix_fmt","yuv420p",
                "-c:a","aac","-b:a","128k","-movflags","+faststart",paired.getAbsolutePath());
        return FFmpegKit.executeWithArguments(args.toArray(new String[0]));
    }

    static Uri save(Context ctx, File result, String displayName, String mime, Canceled canceled) throws IOException {
        if (canceled.canceled()) throw new IOException("Операция отменена");
        ContentResolver resolver = ctx.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, displayName);
        values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/MediaLAB");
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri target = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (target == null) throw new IOException("Android отказал в создании файла в Загрузках");
        boolean success = false;
        try (InputStream in = new FileInputStream(result); OutputStream out = resolver.openOutputStream(target)) {
            if (out == null) throw new IOException("Не удалось открыть выходной документ");
            byte[] buffer = new byte[128 * 1024];
            int n;
            while ((n = in.read(buffer)) != -1) {
                if (canceled.canceled()) throw new IOException("Операция отменена");
                out.write(buffer, 0, n);
            }
            success = true;
        } finally {
            if (!success) resolver.delete(target, null, null);
        }
        values.clear();
        values.put(MediaStore.MediaColumns.IS_PENDING, 0);
        resolver.update(target, values, null, null);
        return target;
    }
    static String mime(int mode, String format) {
        if (mode == IMAGE) {
            switch (format) {
                case "jpg": return "image/jpeg";
                case "png": return "image/png";
                case "webp": return "image/webp";
                case "avif": return "image/avif";
                case "bmp": return "image/bmp";
                case "tiff": return "image/tiff";
                case "gif": return "image/gif";
            }
        }
        if (mode == VIDEO) {
            switch (format) {
                case "mp4": return "video/mp4";
                case "mov": return "video/quicktime";
                case "mkv": return "video/x-matroska";
                case "webm": return "video/webm";
                case "avi": return "video/x-msvideo";
                case "gif": return "image/gif";
            }
        }
        switch (format) {
            case "mp3": return "audio/mpeg";
            case "m4a": return "audio/mp4";
            case "aac": return "audio/aac";
            case "ogg": return "audio/ogg";
            case "opus": return "audio/opus";
            case "flac": return "audio/flac";
            case "wav": return "audio/wav";
            case "wma": return "audio/x-ms-wma";
            case "aiff": return "audio/aiff";
            case "mka": return "audio/x-matroska";
            default: return "application/octet-stream";
        }
    }
    static String fileName(String source, String format, int index) {
        String stem = source;
        int dot = stem.lastIndexOf('.');
        if (dot > 0) stem = stem.substring(0, dot);
        stem = stem.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        if (stem.isEmpty()) stem = "media";
        if (stem.length() > 72) stem = stem.substring(0, 72);
        return stem + "_MediaLAB_" + System.currentTimeMillis() + (index > 0 ? "_" + index : "") + "." + format;
    }
    static String humanBytes(long size) {
        if (size < 0) return "Неизвестно";
        if (size < 1024) return size + " Б";
        String[] units = {"КБ", "МБ", "ГБ", "ТБ"};
        double s = size;
        int i = -1;
        do { s /= 1024.0; i++; } while (s >= 1024 && i < units.length - 1);
        return String.format(Locale.forLanguageTag("ru"), "%.2f %s", s, units[i]);
    }
}
