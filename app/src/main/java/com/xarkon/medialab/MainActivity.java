package com.xarkon.medialab;

import android.app.Activity;
import android.os.Build;
import android.util.LruCache;
import android.content.Intent;
import android.content.DialogInterface;
import android.content.pm.ActivityInfo;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Drawable;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.app.AlertDialog;
import android.content.ClipboardManager;
import android.content.ClipData;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.VideoView;
import android.widget.Toast;
import android.widget.AdapterView;

import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.FFmpegKitConfig;
import com.arthenica.ffmpegkit.FFmpegSession;
import com.arthenica.ffmpegkit.ReturnCode;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Main activity: MediaLAB 1.3.0 - QR LINK visual skin (business logic retained). */
public final class MainActivity extends Activity {
    private static final int PICK = 1217;
    private int BG = Color.rgb(9, 15, 27);
    private int CARD = Color.rgb(17, 27, 45);
    private int CARD2 = Color.rgb(26, 41, 64);
    private int BORDER = Color.rgb(42, 59, 84);
    private int WHITE = Color.rgb(240, 244, 255);
    private int SECONDARY = Color.rgb(157, 169, 192);
    private int BLUE = Color.rgb(133, 165, 255);
    private int GREEN = Color.rgb(112, 228, 170);
    private static final String[] MODES = {"Изображения", "Видео", "Аудио", "Извлечь звук"};
    private static final String[] THEME_NAMES = {"Тёмная", "AMOLED", "Светлая"};
    private static final String[] ACCENT_NAMES = QrLook.NAMES;
    private static final int[] ACCENT_COLORS = QrLook.COLORS;
    private static final String[] PERFORMANCE_NAMES = {"Экономный · 2 потока", "Сбалансированный · 4 потока", "Быстрый · почти все ядра", "Максимальный · все ядра"};
    private static final String[] BITRATES = {"96 Кбит/с", "128 Кбит/с", "192 Кбит/с", "320 Кбит/с"};

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ExecutorService thumbnailWorker = Executors.newSingleThreadExecutor();
    private final LruCache<String, Bitmap> thumbs = new LruCache<>(8);
    private final ArrayList<File> previewParts = new ArrayList<>();
    private final ArrayList<File> pairedParts = new ArrayList<>();
    private final Runnable pollJob = new Runnable() { @Override public void run() { pollState(); ui.postDelayed(this, 450); } };
    private long lastJobRevision = -1;
    private long previewSampledMs = 0;
    private int themeChoice = 0, accentChoice = 0;
    private int performanceChoice = 3;
    private boolean pendingAutoPreview = false;
    private boolean openPreviewWhenReady = false;
    // Declare state before the lambda field initializer to avoid illegal forward references.
    private final ArrayList<ConvertEngine.Source> queue = new ArrayList<>();
    private int visibleFiles=24;
    private volatile boolean ffmpegReady = false;
    private final java.util.concurrent.atomic.AtomicInteger estimateTicket = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.HashMap<String, ConvertEngine.MediaInfo> fastInfoCache = new java.util.HashMap<>();
    private volatile long fastEstimatedBytes = -1;
    private volatile boolean fastMetadataPending = false;
    private TextView estimatePhaseLabel;
    private TextView estimateAmountLabel;
    private TextView estimateHintLabel;
    private ProgressBar estimateSpinner;
    private boolean pickerAnalyzing = false;
    private final Runnable autoPreview = () -> {
        if (queue.isEmpty() || !ffmpegReady) return;
        if (ConversionService.state.running) {
            if (!ConversionService.state.exporting) pendingAutoPreview = true;
            return;
        }
        pendingAutoPreview = false;
        perform(false);
    };
    private final int[] savedQuality = {70, 70, 70, 70};
    private static final int[] QUALITY_STAGES={1,5,10,20,30,40,55,70,85,95};
    private static final String[] QUALITY_NAMES={"Экстремальное", "Минимальный объём", "Ультрасжатие", "Очень сильное", "Сильное", "Компактное", "Сбалансированное", "Хорошее", "Высокое", "Максимальное"};
    private static int stageIndex(int quality) {
        int nearest=0;
        for(int i=1;i<QUALITY_STAGES.length;i++)
            if(Math.abs(QUALITY_STAGES[i]-quality)<Math.abs(QUALITY_STAGES[nearest]-quality))nearest=i;
        return nearest;
    }
    private static String qualityName(int level) {
        return QUALITY_NAMES[level]+"  ["+QUALITY_STAGES[level]+"%]";
    }
    private final int[] savedFormat = {3, 0, 0, 0};
    private final int[] savedResolution = {0, 0, 0, 0};
    private final int[] savedBitrate = {2, 2, 2, 2};
    private VideoView activeVideo;
    private Runnable playbackAdvance;
    private boolean previewsBusy = false;
    private final AtomicBoolean cancel = new AtomicBoolean(false);
    private final ArrayList<Uri> exported = new ArrayList<>();
    private String lastExportMime = "application/octet-stream";
    private int mode = ConvertEngine.IMAGE, selected = 0;
    private boolean busy = false;
    private long totalProgressMs = 0;
    private long previewDurationMs = 0;
    private Spinner formatSpinner, resolutionSpinner, bitrateSpinner;
    private SeekBar qualitySeek;
    private TextView qualityLabel, statusLabel, originalSize, previewSize;
    private LinearLayout filesContainer, originalBox, resultBox, estimateBox, root;
    private ProgressBar progressBar, fileProgressBar;
    private TextView totalProgressLabel, fileProgressLabel;
    private Button previewButton, exportButton, cancelButton, shareButton;
    private File stagedInput, previewOutput;
    private Uri stagedUri;
    private ConvertEngine.Config previewSettings;
    private MediaPlayer audioPlayer;
    private static final String TAG = "XARKON_MediaLab";
    private volatile boolean ffmpegChecked = false;
    private volatile String ffmpegDiagnostic = "Идёт проверка движка FFmpeg…";

    @Override public void onCreate(Bundle state) {
        int initialTheme = getPreferences(MODE_PRIVATE).getInt("theme", 0);
        int initialAccent = Math.max(0, Math.min(QrLook.COLORS.length-1, getPreferences(MODE_PRIVATE).getInt("accent", 0)));
        setTheme(initialTheme == 2 ? R.style.Theme_MediaLab_Light : R.style.Theme_MediaLab);
        int[] systemAccents = {R.style.Accent_Lazur, R.style.Accent_Blue, R.style.Accent_Violet,
                R.style.Accent_Green, R.style.Accent_Amber, R.style.Accent_Pink, R.style.Accent_Red,
                R.style.Accent_Turquoise, R.style.Accent_Mint, R.style.Accent_Teal, R.style.Accent_Indigo, R.style.Accent_Lavender, R.style.Accent_Lilac, R.style.Accent_Purple, R.style.Accent_Fuchsia, R.style.Accent_Raspberry, R.style.Accent_Coral, R.style.Accent_Orange, R.style.Accent_Gold, R.style.Accent_Lime, R.style.Accent_Olive, R.style.Accent_Sky, R.style.Accent_Ice, R.style.Accent_Graphite};
        getTheme().applyStyle(systemAccents[initialAccent], true);
        super.onCreate(state);
        themeChoice = getPreferences(MODE_PRIVATE).getInt("theme", 0);
        accentChoice = getPreferences(MODE_PRIVATE).getInt("accent", 0);
        performanceChoice = Math.max(0, Math.min(3, getPreferences(MODE_PRIVATE).getInt("performance", 3)));
        for (int m = 0; m < 4; m++) {
            savedQuality[m] = QUALITY_STAGES[stageIndex(getPreferences(MODE_PRIVATE).getInt("q"+m, 70))];
            int previous=getPreferences(MODE_PRIVATE).getInt("f"+m, -1);
            savedFormat[m] = previous < 0 ? 0 : (getPreferences(MODE_PRIVATE).getBoolean("format_v2",false) ? previous : previous+1);
            savedFormat[m] = Math.max(0,Math.min(ConvertEngine.FORMATS[m].length-1,savedFormat[m]));
            savedResolution[m] = getPreferences(MODE_PRIVATE).getInt("r"+m, 0);
            savedBitrate[m] = getPreferences(MODE_PRIVATE).getInt("b"+m, 2);
        }
        applyColors();
        MediaOtaSchedule.configure(this);
        if (state != null) restoreInstance(state);
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().getDecorView().setSystemUiVisibility(themeChoice == 2 ?
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR : 0);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 402);
        }
        // UI must never depend on native FFmpeg or optional Java libraries being present.
        try {
            rebuild();
            if (ConversionService.state.running) {
                ffmpegReady = true;
                ffmpegChecked = true;
                ffmpegDiagnostic = "Фоновый движок активен";
                updateButtons();
            } else initializeFfmpegAsync();
            if("com.xarkon.medialab.CHECK_OTA".equals(getIntent().getAction()))
                ui.postDelayed(()->OtaUpdater.checkAndOffer(this,BLUE),550);
        } catch (Throwable startupError) {
            Log.e(TAG, "UI initialization failed", startupError);
            showEmergencyScreen(startupError);
        }
    }

    private void applyColors() {
        themeChoice = Math.max(0, Math.min(2, themeChoice));
        accentChoice = Math.max(0, Math.min(ACCENT_COLORS.length - 1, accentChoice));
        BLUE = ACCENT_COLORS[accentChoice];
        if (themeChoice == 2) {
            BG = 0xfff5f7fc; CARD = 0xffffffff; CARD2 = 0xffedf1f8;
            BORDER = 0xffdfe5ef; WHITE = 0xff19243b; SECONDARY = 0xff637089; GREEN = 0xff00865e;
        } else if (themeChoice == 1) {
            BG = 0xff000000; CARD = 0xff10151f; CARD2 = 0xff1a2232;
            BORDER = 0xff273449; WHITE = 0xfff5f8ff; SECONDARY = 0xffa4afc5; GREEN = 0xff65d8a1;
        } else {
            BG = 0xff090f1b; CARD = 0xff111b2d; CARD2 = 0xff1a2940;
            BORDER = 0xff2a3b54; WHITE = 0xffedf4ff; SECONDARY = 0xff96a8c4; GREEN = 0xff70e4aa;
        }
    }
    private void changeAppTheme(int value) {
        cacheSettings();
        themeChoice = value;
        getPreferences(MODE_PRIVATE).edit().putInt("theme", value).apply();
        applyColors();
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().getDecorView().setSystemUiVisibility(value == 2 ?
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR : 0);
        stopPlayback();
        recreate();
    }
    private void setAccent(int value) {
        cacheSettings();
        accentChoice = value;
        getPreferences(MODE_PRIVATE).edit().putInt("accent", value).apply();
        applyColors(); stopPlayback(); recreate();
    }
    private void themeMenu() {
        new AlertDialog.Builder(this).setTitle("◐  Тема оформления")
            .setSingleChoiceItems(THEME_NAMES, themeChoice, (d, id) -> { d.dismiss(); changeAppTheme(id); })
            .setNegativeButton("Закрыть", null).show();
    }
    private void accentMenu() {
        String[] labels = new String[ACCENT_NAMES.length];
        for (int n = 0; n < labels.length; n++) labels[n] = "●  " + ACCENT_NAMES[n];
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("◉  Акцентный цвет")
            .setSingleChoiceItems(labels, accentChoice, (d, id) -> { d.dismiss(); setAccent(id); })
            .setNegativeButton("Закрыть", null).create();
        dialog.setOnShowListener(v -> {
            android.widget.ListView list = dialog.getListView();
            for (int i = 0; i < list.getChildCount(); i++) {
                View child = list.getChildAt(i);
                if (child instanceof TextView) ((TextView) child).setTextColor(ACCENT_COLORS[i + list.getFirstVisiblePosition()]);
            }
        });
        dialog.show();
    }
    /** QR LINK-style settings: five independent groups, all closed by default. */
    private void openSettings() {
        if (openSettingsDialog != null && openSettingsDialog.isShowing()) return;
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setBackgroundColor(BG);
        LinearLayout body = vertical();
        body.setPadding(dp(16), dp(18), dp(16), dp(24));
        scroll.addView(body);

        LinearLayout top = horizontal();
        TextView title = txt("Настройки",22,WHITE,true);
        top.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        TextView finish = txt("ГОТОВО",12,BLUE,true);
        finish.setGravity(Gravity.CENTER);
        finish.setPadding(dp(14),dp(11),dp(14),dp(11));
        finish.setBackground(background(CARD2,BORDER,13));
        finish.setOnClickListener(v -> {if(openSettingsDialog!=null)openSettingsDialog.dismiss();});
        top.addView(finish);
        body.addView(top,boxLp(-1,dp(48),6));
        body.addView(txt("MEDIALAB  /  QR LINK STYLE",10,SECONDARY,true),boxLp(-1,-2,20));

        LinearLayout appearance = settingsGroup(body,"Тема оформления","Тёмная · AMOLED · Светлая", android.R.drawable.ic_menu_view);
        LinearLayout themes = horizontal();
        for(int k=0;k<THEME_NAMES.length;k++) {
            final int chosen=k;
            TextView chip=settingsChoice(THEME_NAMES[k],themeChoice==k);
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(44),1);
            if(k>0)lp.leftMargin=dp(7);
            themes.addView(chip,lp);
            chip.setOnClickListener(v->{
                if(chosen==themeChoice)return;
                if(openSettingsDialog!=null)openSettingsDialog.dismiss();
                changeAppTheme(chosen);
            });
        }
        appearance.addView(themes,boxLp(-1,dp(44),8));
        appearance.addView(txt("Оформление автоматически применяется ко всем карточкам и кнопкам.",11,SECONDARY,false));

        LinearLayout colors = settingsGroup(body,"Цвет акцента",ACCENT_NAMES[accentChoice],android.R.drawable.ic_menu_edit);
        TextView chosenLabel=txt("Выбран: "+ACCENT_NAMES[accentChoice],13,BLUE,true);
        colors.addView(chosenLabel,boxLp(-1,-2,12));
        final int columns=4;
        for(int line=0;line<ACCENT_COLORS.length;line+=columns) {
            LinearLayout row=horizontal();
            for(int at=line;at<Math.min(line+columns,ACCENT_COLORS.length);at++) {
                final int chosen=at;
                int tint=ACCENT_COLORS[at];
                LinearLayout item=vertical();item.setGravity(Gravity.CENTER);
                boolean active=chosen==accentChoice;
                item.setPadding(dp(2),dp(9),dp(2),dp(9));
                item.setBackground(background(active?CARD2:CARD,active?tint:BORDER,13));
                TextView dot=txt(active?"✓":"",17,android.graphics.Color.BLACK,true);
                dot.setGravity(Gravity.CENTER);
                android.graphics.drawable.GradientDrawable circle=new android.graphics.drawable.GradientDrawable();
                circle.setShape(android.graphics.drawable.GradientDrawable.OVAL);
                circle.setColor(tint);circle.setStroke(dp(2),active?WHITE:tint);
                dot.setBackground(circle);
                item.addView(dot,new LinearLayout.LayoutParams(dp(29),dp(29)));
                TextView label=txt(ACCENT_NAMES[at],10,active?BLUE:SECONDARY,active);
                label.setGravity(Gravity.CENTER);label.setMaxLines(2);
                label.setEllipsize(android.text.TextUtils.TruncateAt.END);
                item.addView(label,boxLp(-1,dp(32),0));
                LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(91),1);
                if(at>line)lp.leftMargin=dp(6);
                row.addView(item,lp);
                item.setOnClickListener(v->{
                    if(chosen==accentChoice)return;
                    if(openSettingsDialog!=null)openSettingsDialog.dismiss();
                    setAccent(chosen);
                });
            }
            colors.addView(row,boxLp(-1,dp(91),7));
        }
        colors.addView(txt("24 готовых варианта. Цвет подписан названием, без HEX-кодов.",11,SECONDARY,false));

        LinearLayout performance=settingsGroup(body,"Производительность","Режимы использования ресурсов",android.R.drawable.ic_menu_manage);
        final LinearLayout[] cards=new LinearLayout[4];
        String[] descriptions={"Минимальный нагрев", "Подходит для повседневной обработки",
            "Повышенная производительность", "Максимальная доступная нагрузка"};
        for(int k=0;k<PERFORMANCE_NAMES.length;k++) {
            final int chosen=k;
            boolean selected=k==performanceChoice;
            LinearLayout line=horizontal();line.setGravity(Gravity.CENTER_VERTICAL);
            line.setPadding(dp(14),dp(9),dp(14),dp(9));
            line.setBackground(background(selected?CARD2:CARD,selected?BLUE:BORDER,14));
            LinearLayout texts=vertical();
            texts.addView(txt(PERFORMANCE_NAMES[k],13,WHITE,true),boxLp(-1,-2,4));
            texts.addView(txt(descriptions[k],11,SECONDARY,false));
            line.addView(texts,new LinearLayout.LayoutParams(0,-2,1));
            TextView tick=txt(selected?"✓":"○",19,selected?BLUE:SECONDARY,true);
            line.addView(tick);cards[k]=line;
            performance.addView(line,boxLp(-1,dp(67),7));
            line.setOnClickListener(v->{
                performanceChoice=chosen;
                getPreferences(MODE_PRIVATE).edit().putInt("performance",chosen).apply();
                cacheSettings();
                for(int n=0;n<cards.length;n++) {
                    if(cards[n]==null)continue;
                    boolean on=n==chosen;
                    cards[n].setBackground(background(on?CARD2:CARD,on?BLUE:BORDER,14));
                    TextView check=(TextView)cards[n].getChildAt(1);
                    check.setText(on?"✓":"○");check.setTextColor(on?BLUE:SECONDARY);
                }
                if(!queue.isEmpty())schedulePreview();
            });
        }
        performance.addView(txt("Число потоков не гарантирует ускорения. Android ограничивает работу при перегреве.",11,SECONDARY,false));

        LinearLayout preview=settingsGroup(body,"Предпросмотр","Синхронное сравнение до / после",android.R.drawable.ic_menu_slideshow);
        preview.addView(txt("При изменении качества расчёт запускается после отпускания ползунка. Синхронный предпросмотр использует 15%, 30%, 50%, 70% и 80% длительности.",12,SECONDARY,false),boxLp(-1,-2,8));
        preview.addView(txt("Шторка начинается с 50% и двигается внутри кадра. Предварительный размер не равен точному размеру готового файла.",12,SECONDARY,false));

        LinearLayout otaPanel = settingsGroup(body,"Обновления приложения","GitHub OTA · официальный канал",android.R.drawable.stat_sys_download_done);
        otaPanel.addView(txt("Текущая версия: " + BuildConfig.VERSION_NAME + "  ·  проверка по официальному GitHub", 11, SECONDARY, false));
        Button otaButton = button("Проверить обновления", false);
        otaPanel.addView(otaButton, boxLp(-1,dp(48),9));
        otaButton.setOnClickListener(v -> {
            if (openSettingsDialog != null) openSettingsDialog.dismiss();
            OtaUpdater.checkAndOffer(this, BLUE);
        });
        android.content.SharedPreferences otaPrefs=getSharedPreferences(MediaOtaSchedule.PREF,MODE_PRIVATE);
        android.widget.Switch background=new android.widget.Switch(this);
        background.setText("Проверять обновления в фоне");background.setTextColor(WHITE);
        background.setChecked(otaPrefs.getBoolean(MediaOtaSchedule.KEY_ENABLED,false));
        background.setOnCheckedChangeListener((v,checked)->{
            otaPrefs.edit().putBoolean(MediaOtaSchedule.KEY_ENABLED,checked).apply();
            MediaOtaSchedule.configure(this);
        });
        otaPanel.addView(background,boxLp(-1,dp(49),9));
        otaPanel.addView(txt("Интервал фоновых проверок",12,SECONDARY,true),boxLp(-1,-2,7));
        long prefHours=otaPrefs.getLong(MediaOtaSchedule.KEY_HOURS,6L);
        int selectedInterval=2;
        for(int i=0;i<MediaOtaSchedule.HOURS.length;i++)if(MediaOtaSchedule.HOURS[i]==prefHours)selectedInterval=i;
        Spinner intervals=spinner(MediaOtaSchedule.LABELS,selectedInterval);
        intervals.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?>parent,View view,int position,long id){
                long hours=MediaOtaSchedule.HOURS[position];
                if(otaPrefs.getLong(MediaOtaSchedule.KEY_HOURS,6L)!=hours) {
                    otaPrefs.edit().putLong(MediaOtaSchedule.KEY_HOURS,hours).apply();
                    MediaOtaSchedule.configure(MainActivity.this);
                }
            }
            public void onNothingSelected(AdapterView<?>parent){}
        });
        otaPanel.addView(intervals,boxLp(-1,dp(46),8));
        otaPanel.addView(txt("Проверка выполняется по расписанию Android. При наличии обновления появляется уведомление; установка только по вашему действию.",11,SECONDARY,false));
        LinearLayout system=settingsGroup(body,"Система и диагностика","FFmpeg · приватность · хранилище",android.R.drawable.ic_menu_info_details);
        system.addView(txt("Всё обрабатывается локально. Готовые файлы сохраняются в папку загрузок MediaLAB.",12,SECONDARY,false),boxLp(-1,-2,12));
        Button diagnostics=button("Диагностика FFmpeg",false);
        diagnostics.setOnClickListener(v->showEngineDiagnostics());
        system.addView(diagnostics,boxLp(-1,dp(46),2));

        openSettingsDialog=new AlertDialog.Builder(this).setView(scroll).create();
        openSettingsDialog.setOnDismissListener(d->openSettingsDialog=null);
        openSettingsDialog.show();
        if(openSettingsDialog.getWindow()!=null) {
            android.view.Window window=openSettingsDialog.getWindow();
            window.setBackgroundDrawableResource(android.R.color.transparent);
            android.util.DisplayMetrics dm=getResources().getDisplayMetrics();
            window.setLayout(Math.min(dm.widthPixels-dp(20),dp(470)),
                             Math.min(dm.heightPixels-dp(32),dp(850)));
            window.setDimAmount(0.76f);
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }
    }

    private LinearLayout settingsGroup(LinearLayout parent,String name,String sub,int iconId) {
        LinearLayout wrapper=vertical();
        wrapper.setPadding(dp(11),dp(9),dp(11),dp(9));
        wrapper.setBackground(background(CARD,BORDER,18));
        parent.addView(wrapper,boxLp(-1,-2,10));
        LinearLayout header=horizontal();header.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon=new ImageView(this);icon.setImageResource(iconId);icon.setColorFilter(BLUE);
        icon.setPadding(dp(7),dp(7),dp(7),dp(7));
        icon.setBackground(background(CARD2,0,12));
        header.addView(icon,new LinearLayout.LayoutParams(dp(39),dp(39)));
        LinearLayout titleWrap=vertical();titleWrap.setPadding(dp(10),0,dp(6),0);
        titleWrap.addView(txt(name,14,WHITE,true),boxLp(-1,-2,4));
        titleWrap.addView(txt(sub,11,SECONDARY,false));
        header.addView(titleWrap,new LinearLayout.LayoutParams(0,-2,1));
        TextView arrow=txt("⌄",24,BLUE,true);arrow.setGravity(Gravity.CENTER);
        header.addView(arrow,new LinearLayout.LayoutParams(dp(31),dp(44)));
        wrapper.addView(header,boxLp(-1,dp(58),0));
        LinearLayout inside=vertical();inside.setPadding(dp(4),dp(10),dp(4),dp(8));
        inside.setVisibility(View.GONE);
        inside.setAlpha(0f);
        wrapper.addView(inside);
        header.setClickable(true);
        header.setContentDescription("Раздел настроек: "+name);
        header.setOnClickListener(v->{
            boolean opening=!Boolean.TRUE.equals(header.getTag());
            header.setTag(opening);
            inside.animate().cancel();
            arrow.animate().cancel();
            if(!opening){
                inside.animate().alpha(0f).translationY(-dp(5)).setDuration(210)
                    .withEndAction(()->{inside.setVisibility(View.GONE);inside.setTranslationY(0);}).start();
                arrow.animate().rotation(0f).setDuration(210).start();
            }else{
                inside.setVisibility(View.VISIBLE);
                inside.setAlpha(0f);inside.setTranslationY(-dp(8));
                inside.animate().alpha(1f).translationY(0).setDuration(245).start();
                arrow.animate().rotation(180f).setDuration(245).start();
            }
        });
        return inside;
    }

    private TextView settingsChoice(String name,boolean selected) {
        TextView choice=txt(name,12,selected?(android.graphics.Color.luminance(BLUE)>.4?0xff081327:android.graphics.Color.WHITE):WHITE,selected);
        choice.setGravity(Gravity.CENTER);
        choice.setBackground(background(selected?BLUE:CARD2,selected?BLUE:BORDER,13));
        return choice;
    }
    private AlertDialog openSettingsDialog;

    private void schedulePreview() {
        ui.removeCallbacks(autoPreview);
        if (queue.isEmpty()) {
            estimateTicket.incrementAndGet();
            fastEstimatedBytes = -1;
            fastMetadataPending = false;
            renderPreviews();
            return;
        }
        clearPreview();
        // Immediate estimate from source size, then an asynchronous SAF metadata read.
        // Expensive FFmpeg preview generation runs only after the debounce delay.
        startFastEstimate();
        if (ConversionService.state.running && ConversionService.state.exporting) {
            pendingAutoPreview = true;
            return;
        }
        if (ConversionService.state.running && !ConversionService.state.exporting) {
            pendingAutoPreview = true;
            Intent stop = new Intent(this, ConversionService.class).setAction(ConversionService.ACTION_CANCEL);
            startService(stop);
        }
        ui.postDelayed(autoPreview, 400);
    }

    private void startFastEstimate() {
        int ticket = estimateTicket.incrementAndGet();
        if (queue.isEmpty()) return;
        final ConvertEngine.Source source = queue.get(Math.min(selected, queue.size()-1));
        final ConvertEngine.Config cfg = config();
        final String key = source.uri.toString();
        ConvertEngine.MediaInfo cached = fastInfoCache.get(key);
        fastEstimatedBytes = ConvertEngine.totalQuickEstimate(queue, cfg, cached, source.uri);
        fastMetadataPending = cached == null && cfg.mode != ConvertEngine.IMAGE;
        renderPreviews();
        if (!fastMetadataPending) return;
        executor.execute(() -> {
            ConvertEngine.MediaInfo info = ConvertEngine.info(this, source.uri);
            ui.post(() -> {
                if (ticket != estimateTicket.get() || isFinishing() || isDestroyed()) return;
                fastInfoCache.put(key, info);
                fastMetadataPending = false;
                fastEstimatedBytes = ConvertEngine.totalQuickEstimate(queue, cfg, info, source.uri);
                renderPreviews();
            });
        });
    }

    private void openFullPreview() {
        if (queue.isEmpty()) return;
        if (previewParts.isEmpty() || previewOutput == null || !previewOutput.exists()) {
            openPreviewWhenReady = true;
            if (!(ConversionService.state.running && !ConversionService.state.exporting))
                schedulePreview();
            setStatus("Создаётся предпросмотр для полноэкранного просмотра…", false);
            return;
        }
        openPreviewWhenReady = false;
        Intent i = new Intent(this, PreviewActivity.class);
        i.putExtra("source", queue.get(selected).uri.toString());
        i.putExtra("mode", mode);
        i.putExtra("sourceName", queue.get(selected).name);
        ArrayList<String> paths = new ArrayList<>();
        for (File f : previewParts) if (f.exists()) paths.add(f.getAbsolutePath());
        i.putStringArrayListExtra("parts", paths);
        ArrayList<String> matches = new ArrayList<>();
        for (File f : pairedParts) if (f.exists()) matches.add(f.getAbsolutePath());
        i.putStringArrayListExtra("paired", matches);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { startActivity(i); }
        catch (Exception ex) { setStatus("Ошибка открытия предпросмотра: " + ex.getMessage(), true); }
    }

    private void restoreInstance(Bundle saved) {
        mode = saved.getInt("mode", mode);
        selected = saved.getInt("selected", 0);
        ArrayList<Uri> ids = saved.getParcelableArrayList("sourceUris");
        ArrayList<String> names = saved.getStringArrayList("sourceNames");
        long[] sizes = saved.getLongArray("sourceSizes");
        if (ids != null && names != null) {
            for (int i=0; i<ids.size() && i<names.size(); i++)
                queue.add(new ConvertEngine.Source(ids.get(i), names.get(i), sizes != null && sizes.length > i ? sizes[i] : -1));
        }
        selected = Math.min(selected, Math.max(0, queue.size()-1));
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putInt("mode", mode); state.putInt("selected", selected);
        ArrayList<Uri> ids = new ArrayList<>(); ArrayList<String> names = new ArrayList<>();
        long[] sizes = new long[queue.size()];
        for (int n=0; n<queue.size(); n++) { ids.add(queue.get(n).uri); names.add(queue.get(n).name); sizes[n]=queue.get(n).size; }
        state.putParcelableArrayList("sourceUris", ids);
        state.putStringArrayList("sourceNames", names); state.putLongArray("sourceSizes", sizes);
    }
    @Override protected void onResume() {
        super.onResume();
        ui.removeCallbacks(pollJob); ui.post(pollJob);
    }
    @Override protected void onPause() {
        cacheSettings();
        ui.removeCallbacks(pollJob);
        ui.removeCallbacks(autoPreview);
        stopPlayback();
        super.onPause();
    }
    private void pollState() {
        ConversionService.Snapshot s = ConversionService.state;
        boolean wasBusy = busy;
        busy = s.running;
        if (s.revision != lastJobRevision) {
            lastJobRevision = s.revision;
            if (!s.running && !s.exporting) {
                previewParts.clear(); pairedParts.clear();
                synchronized (s.parts) { for (File f : s.parts) if (f.exists()) previewParts.add(f); }
                synchronized (s.compareParts) { for (File f : s.compareParts) if (f.exists()) pairedParts.add(f); }
                previewOutput = previewParts.isEmpty() ? null : previewParts.get(previewParts.size()/2);
                previewSettings = previewParts.isEmpty() ? null : new ConvertEngine.Config(s.mode, s.format, s.quality, 0, 2, s.performance);
                previewDurationMs = s.durationMs;
                previewSampledMs = s.samplesMs;
                if (pendingAutoPreview) { previewParts.clear(); pairedParts.clear(); previewOutput = null; previewSettings = null; }
                if (!queue.isEmpty() && estimateBox != null) renderPreviews();
                if (!previewParts.isEmpty() && !pendingAutoPreview && openPreviewWhenReady) ui.post(this::openFullPreview);
            }
            if (!s.results.isEmpty()) {
                exported.clear(); synchronized (s.results) { exported.addAll(s.results); }
                lastExportMime = s.lastMime;
            }
        }
        if (wasBusy != busy) updateButtons();
        if (wasBusy && !busy && pendingAutoPreview) {
            pendingAutoPreview = false;
            ui.removeCallbacks(autoPreview);
            ui.postDelayed(autoPreview, 700);
        }
        updateEstimateAnimation();
        if (progressBar != null) progressBar.setProgress(s.progress);
        if (fileProgressBar != null) fileProgressBar.setProgress(s.fileProgress);
        if (totalProgressLabel != null) totalProgressLabel.setText("Общий прогресс   " + s.progress + "%");
        if (fileProgressLabel != null) fileProgressLabel.setText("Текущий файл " + s.fileNumber + "/" + s.fileCount + "   " + s.fileProgress + "%");
        if (statusLabel != null && (busy || s.progress > 0 || s.error || s.status.startsWith("Операция")))
            setStatus(s.status, s.error);
    }
    private void showEmergencyScreen(Throwable problem) {
        try {
            TextView t = new TextView(this);
            t.setText("XARKON Media Lab: ошибка запуска интерфейса\n\n" + formatFailure(problem));
            t.setTextColor(Color.WHITE);
            t.setTextSize(15);
            t.setPadding(dp(20), dp(40), dp(20), dp(20));
            t.setBackgroundColor(BG);
            setContentView(t);
        } catch (Throwable fatal) {
            Log.e(TAG, "Emergency screen failed", fatal);
        }
    }

    private static String formatFailure(Throwable problem) {
        if (problem == null) return "Неизвестная ошибка";
        String msg = problem.getMessage();
        String text = problem.getClass().getName() + (msg == null ? "" : ": " + msg);
        if (text.length() > 1700) text = text.substring(0, 1700);
        return text;
    }

    private void initializeFfmpegAsync() {
        executor.execute(() -> {
            boolean working = false;
            String diagnostic;
            try {
                FFmpegSession test = FFmpegKit.executeWithArguments(new String[]{"-version"});
                if (test == null || !ReturnCode.isSuccess(test.getReturnCode())) {
                    throw new IllegalStateException("Тест FFmpeg -version не прошёл: " +
                            (test == null ? "session=null" : test.getReturnCode()));
                }
                working = true;
                diagnostic = "FFmpegKit готов. Тест '-version' выполнен успешно.";
            } catch (Throwable ffmpegError) {
                Log.e(TAG, "FFmpeg initialization failed", ffmpegError);
                diagnostic = formatFailure(ffmpegError);
            }
            final boolean ok = working;
            final String diagnosticText = diagnostic;
            ui.post(() -> {
                ffmpegReady = ok;
                ffmpegChecked = true;
                ffmpegDiagnostic = diagnosticText;
                setStatus(ok ? "Движок FFmpeg готов к обработке файлов" :
                        "FFmpeg недоступен: " + diagnosticText, !ok);
                updateButtons();
                if (ok && !queue.isEmpty() && previewParts.isEmpty()) schedulePreview();
                else if (!ok && !queue.isEmpty()) renderPreviews();
            });
        });
    }

    private void showEngineDiagnostics() {
        ConversionService.Snapshot snapshot = ConversionService.state;
        String message = "Версия: 1.2.6\nAndroid SDK: " + android.os.Build.VERSION.SDK_INT +
                "\nАрхитектура: " + java.util.Arrays.toString(android.os.Build.SUPPORTED_ABIS) +
                "\nДвижок: " + (ffmpegReady ? "ГОТОВ" : ffmpegChecked ? "ОШИБКА" : "ПРОВЕРКА") +
                "\n\n" + ffmpegDiagnostic +
                "\n\nФоновое задание: " + (snapshot.running ? "ВЫПОЛНЯЕТСЯ" : "НЕ ЗАПУЩЕНО") +
                "\nПрогресс: " + snapshot.progress + "%" +
                "\nСтатус: " + snapshot.status;
        new AlertDialog.Builder(this)
                .setTitle("Диагностика Media Lab")
                .setMessage(message)
                .setPositiveButton("Копировать отчёт", (dialog, which) -> {
                    ClipboardManager c = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    if (c != null) c.setPrimaryClip(ClipData.newPlainText("Media Lab", message));
                })
                .setNegativeButton("Закрыть", null)
                .show();
    }

    private int dp(float n) { return (int) (getResources().getDisplayMetrics().density * n + .5f); }
    private GradientDrawable background(int fill, int stroke, int radius) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(fill);
        gd.setCornerRadius(dp(radius));
        if (stroke != 0) gd.setStroke(dp(1), stroke);
        return gd;
    }
    private TextView txt(String val, int size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(val);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setFontFeatureSettings("kern");
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }
    private LinearLayout vertical() {
        LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); return v;
    }
    private LinearLayout horizontal() {
        LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.HORIZONTAL);
        v.setGravity(Gravity.CENTER_VERTICAL); return v;
    }
    private LinearLayout.LayoutParams boxLp(int width, int height, int bottom) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(width, height);
        p.bottomMargin = dp(bottom); return p;
    }
    private LinearLayout card(int bottom) {
        LinearLayout l = vertical(); l.setPadding(dp(16), dp(16), dp(16), dp(16));
        l.setBackground(background(CARD, BORDER, 21));
        root.addView(l, boxLp(-1, -2, bottom)); return l;
    }
    private Button button(String label, boolean primary) {
        Button b = new Button(this);
        b.setText(label.replaceFirst("^[^\\p{L}\\p{N}]*\\s*", ""));
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setTextColor(primary ? (android.graphics.Color.luminance(BLUE) > 0.4 ? 0xff0b1422 : android.graphics.Color.WHITE) : WHITE);
        GradientDrawable base = background(primary ? BLUE : CARD2, primary ? 0 : BORDER, 15);
        b.setBackground(new android.graphics.drawable.RippleDrawable(
            ColorStateList.valueOf(primary ? 0x55000000 : 0x55ffffff), base, null));
        b.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == android.view.MotionEvent.ACTION_DOWN)
                view.animate().scaleX(.975f).scaleY(.975f).setDuration(95).start();
            else if (event.getActionMasked() == android.view.MotionEvent.ACTION_UP ||
                     event.getActionMasked() == android.view.MotionEvent.ACTION_CANCEL)
                view.animate().scaleX(1f).scaleY(1f).setDuration(170).start();
            return false;
        });
        b.setPadding(dp(13), 0, dp(13), 0);
        b.setMinHeight(0); b.setMinimumHeight(0);
        int iconId = 0;
        if (label.contains("Выбрать файлы")) iconId = android.R.drawable.ic_menu_add;
        else if (label.contains("предпросмотр") || label.contains("Фрагменты")) iconId = android.R.drawable.ic_menu_view;
        else if (label.contains("Конвертировать")) iconId = android.R.drawable.ic_menu_save;
        else if (label.contains("Диагностика")) iconId = android.R.drawable.ic_menu_info_details;
        else if (label.contains("Отменить")) iconId = android.R.drawable.ic_menu_close_clear_cancel;
        else if (label.contains("Поделиться")) iconId = android.R.drawable.ic_menu_share;
        else if (label.contains("Тема")) iconId = android.R.drawable.ic_menu_manage;
        else if (label.contains("Акцент")) iconId = android.R.drawable.ic_menu_edit;
        else if (label.contains("потока") || label.contains("авто")) iconId = android.R.drawable.ic_menu_manage;
        else if (label.contains("Готово") || label.contains("Баланс")) iconId = android.R.drawable.ic_menu_send;
        if (iconId != 0) {
            Drawable d = getDrawable(iconId);
            if (d != null) {
                d = d.mutate();
                d.setBounds(0, 0, dp(17), dp(17));
                d.setTint(primary ? (android.graphics.Color.luminance(BLUE) > 0.4 ? 0xff0b1422 : android.graphics.Color.WHITE) : BLUE);
                b.setCompoundDrawables(d, null, null, null);
                b.setCompoundDrawablePadding(dp(8));
            }
        }
        return b;
    }
    private void header(LinearLayout where, String title, String subtitle) {
        String number=title.length()>=2?title.substring(0,2):"01";
        String plain=title.replaceFirst("^\\d{2}\\s*[^\\p{L}\\p{N}]*\\s*", "");
        LinearLayout header=horizontal();
        TextView num=txt(number,12,BLUE,true);num.setGravity(Gravity.CENTER);
        num.setBackground(background(CARD2,BORDER,12));
        header.addView(num,new LinearLayout.LayoutParams(dp(40),dp(37)));
        LinearLayout labels=vertical();labels.setPadding(dp(11),0,0,0);
        labels.addView(txt(plain,17,WHITE,true),boxLp(-1,-2,4));
        if(subtitle!=null)labels.addView(txt(subtitle,11,SECONDARY,false));
        header.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
        where.addView(header,boxLp(-1,-2,15));
    }
    private void rebuild() {
        formatSpinner = null; resolutionSpinner = null; bitrateSpinner = null;
        qualitySeek = null; qualityLabel = null;
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.setFillViewport(true);
        root = vertical();
        root.setPadding(dp(16), dp(24), dp(16), dp(22));
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            scroll.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets system = insets.getInsets(android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.displayCutout());
                root.setPadding(dp(16), dp(17) + system.top, dp(16), dp(22) + system.bottom);
                return insets;
            });
        }
        scroll.addView(root);
        setContentView(scroll);

        LinearLayout brandRow = horizontal();
        LinearLayout brand = vertical();
        brand.addView(txt("◆  XARKON  /  MEDIALAB", 11, BLUE, true), boxLp(-1, -2, 8));
        brand.addView(txt("MediaLAB", 28, WHITE, true), boxLp(-1, -2, 5));
        brand.addView(txt("Конвертация • Сжатие • Предпросмотр", 12, SECONDARY, false), boxLp(-1, -2, 10));
        brandRow.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));
        ImageView gear = new ImageView(this);
        gear.setImageResource(android.R.drawable.ic_menu_manage);
        gear.setColorFilter(BLUE);
        gear.setPadding(dp(10),dp(10),dp(10),dp(10));
        gear.setBackground(new android.graphics.drawable.RippleDrawable(
            ColorStateList.valueOf(0x44ffffff),background(CARD2,BORDER,16),null));
        gear.setContentDescription("Настройки");
        gear.setOnClickListener(v -> {v.animate().rotationBy(45f).setDuration(245).start();openSettings();});
        LinearLayout.LayoutParams gearLp = new LinearLayout.LayoutParams(dp(49),dp(49));
        gearLp.leftMargin=dp(8);
        brandRow.addView(gear,gearLp);
        root.addView(brandRow, boxLp(-1, -2, 12));
        brandRow.setAlpha(0f);
        brandRow.setTranslationY(dp(5));
        brandRow.animate().alpha(1f).translationY(0).setDuration(240).start();

        HorizontalScrollView hsv = new HorizontalScrollView(this);
        hsv.setHorizontalScrollBarEnabled(false);
        LinearLayout tabs = horizontal();
        for (int m = 0; m < MODES.length; m++) {
            final int dest = m;
            TextView tab = txt(MODES[m], 11, m == mode ? (android.graphics.Color.luminance(BLUE)>.4?0xff081327:android.graphics.Color.WHITE) : WHITE, true);
            tab.setGravity(Gravity.CENTER);
            tab.setPadding(dp(12), dp(13), dp(12), dp(13));
            int[] tabIcons={android.R.drawable.ic_menu_gallery,android.R.drawable.ic_media_play,
                    android.R.drawable.ic_media_ff,android.R.drawable.ic_menu_slideshow};
            Drawable tabIcon=getDrawable(tabIcons[m]);
            if(tabIcon!=null){
                tabIcon=tabIcon.mutate();tabIcon.setBounds(0,0,dp(15),dp(15));
                tabIcon.setTint(m==mode?(android.graphics.Color.luminance(BLUE)>.4?0xff081327:android.graphics.Color.WHITE):BLUE);
                tab.setCompoundDrawables(tabIcon,null,null,null);tab.setCompoundDrawablePadding(dp(7));
            }
            tab.setBackground(background(m == mode ? BLUE : CARD2, m == mode ? 0 : BORDER, 14));
            LinearLayout.LayoutParams p = boxLp(-2, -2, 0); p.rightMargin = dp(7);
            tabs.addView(tab, p);
            tab.setOnClickListener(v -> {
                if (busy || mode == dest) return;
                cacheSettings();
                mode = dest;
                queue.clear(); selected = 0; clearPreview(); clearStaged(); exported.clear();
                rebuild();
            });
        }
        hsv.addView(tabs);
        root.addView(hsv, boxLp(-1, -2, 19));

        LinearLayout source = card(13);
        header(source, "01  ▣  Исходные файлы", "Выберите один или несколько файлов для обработки");
        Button pick = button("＋  Выбрать файлы", true);
        pick.setOnClickListener(v -> openPicker());
        source.addView(pick, boxLp(-1, dp(49), 12));
        filesContainer = vertical(); source.addView(filesContainer);
        renderFiles();

        LinearLayout settings = card(13);
        header(settings, "02  ⚙  Параметры экспорта", "Кодеки и пресеты можно менять до запуска");
        settings.addView(txt("Формат результата", 12, SECONDARY, true), boxLp(-1, -2, 6));
        formatSpinner = spinner(ConvertEngine.FORMATS[mode], savedFormat[mode]);
        settings.addView(formatSpinner, boxLp(-1, dp(52), 14));
        formatSpinner.setSelection(savedFormat[mode]);
        formatSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            boolean initialized;
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (!initialized) { initialized = true; return; }
                schedulePreview();
            }
            public void onNothingSelected(AdapterView<?> parent) { }
        });
        settings.addView(txt("Уровень качества и сжатия",12,SECONDARY,true),boxLp(-1,-2,8));
        qualityLabel=txt(qualityName(stageIndex(savedQuality[mode])),18,WHITE,true);
        settings.addView(qualityLabel,boxLp(-1,-2,8));
        qualitySeek=new SeekBar(this);
        qualitySeek.setMax(9);
        qualitySeek.setProgress(stageIndex(savedQuality[mode]));
        qualitySeek.setProgressTintList(ColorStateList.valueOf(BLUE));
        qualitySeek.setThumbTintList(ColorStateList.valueOf(BLUE));
        qualitySeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar bar,int p,boolean fromUser){
                int index=Math.max(0,Math.min(9,p));
                savedQuality[mode]=QUALITY_STAGES[index];
                if(qualityLabel!=null)qualityLabel.setText(qualityName(index));
                // Bitrate remains adjustable independently, but follows the quality profile until overridden.
                savedBitrate[mode] = Math.min(3,(index*4)/10);
            }
            public void onStartTrackingTouch(SeekBar bar){}
            public void onStopTrackingTouch(SeekBar bar){
                if(bitrateSpinner!=null && bitrateSpinner.getSelectedItemPosition()!=savedBitrate[mode])
                    bitrateSpinner.setSelection(savedBitrate[mode]);
                schedulePreview();
            }
        });
        settings.addView(qualitySeek,boxLp(-1,dp(45),6));
        settings.addView(txt("10 уровней · проценты обозначают параметр качества, не гарантированную экономию места",11,SECONDARY,false),boxLp(-1,-2,12));
        if (mode == ConvertEngine.VIDEO) {
            settings.addView(txt("Ограничение разрешения", 12, SECONDARY, true), boxLp(-1, -2, 6));
            resolutionSpinner = spinner(ConvertEngine.RESOLUTIONS, savedResolution[mode]);
            settings.addView(resolutionSpinner, boxLp(-1, dp(52), 0));
            resolutionSpinner.setOnItemSelectedListener(changeListener());
        }
        if (mode == ConvertEngine.AUDIO || mode == ConvertEngine.EXTRACT) {
            settings.addView(txt("Битрейт для форматов с потерями, Кбит/с", 12, SECONDARY, true), boxLp(-1, -2, 6));
            bitrateSpinner = spinner(BITRATES, savedBitrate[mode]);
            settings.addView(bitrateSpinner, boxLp(-1, dp(52), 0));
            bitrateSpinner.setOnItemSelectedListener(changeListener());
        }

        LinearLayout comparison = card(13);
        header(comparison, "03  ◧  Предпросмотр", "Сцены на 15% · 30% · 50% · 70% · 80%");
        originalBox = vertical(); resultBox = vertical();
        estimateBox = vertical();
        comparison.addView(estimateBox, boxLp(-1, -2, 12));
        previewButton = button("▶  Открыть полноэкранный предпросмотр", true);
        previewButton.setOnClickListener(v -> openFullPreview());
        comparison.addView(previewButton, boxLp(-1, dp(53), 8));
        comparison.addView(txt("В плеере: кодированные фрагменты и шторка сравнения кадров до/после.", 11, SECONDARY, false));
        renderPreviews();

        LinearLayout run = card(13);
        header(run, "04  ⇄  Конвертация", "Сохранение в Загрузки / MediaLAB");
        
        exportButton = button("⇢  Конвертировать и сохранить", true);
        exportButton.setOnClickListener(v -> perform(true));
        run.addView(exportButton, boxLp(-1, dp(52), 10));
        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100); progressBar.setProgress(0);
        progressBar.setProgressTintList(ColorStateList.valueOf(BLUE));
        progressBar.setProgressBackgroundTintList(ColorStateList.valueOf(CARD2));
        totalProgressLabel = txt("Общий прогресс   0%", 12, SECONDARY, true);
        run.addView(totalProgressLabel, boxLp(-1,-2,7));
        run.addView(progressBar, boxLp(-1, dp(7), 13));
        fileProgressLabel = txt("Текущий файл   0%", 12, SECONDARY, true);
        run.addView(fileProgressLabel, boxLp(-1,-2,7));
        fileProgressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        fileProgressBar.setMax(100); fileProgressBar.setProgress(0);
        fileProgressBar.setProgressTintList(ColorStateList.valueOf(GREEN));
        fileProgressBar.setProgressBackgroundTintList(ColorStateList.valueOf(CARD2));
        run.addView(fileProgressBar, boxLp(-1, dp(7), 12));
        statusLabel = txt("Проверка доступности FFmpeg…", 12, SECONDARY, false);
        run.addView(statusLabel, boxLp(-1, -2, 12));

        cancelButton = button("Отменить операцию", false);
        cancelButton.setOnClickListener(v -> {
            Intent stop = new Intent(this, ConversionService.class).setAction(ConversionService.ACTION_CANCEL);
            startService(stop);
            setStatus("Остановка операции…", false);
        });
        run.addView(cancelButton, boxLp(-1, dp(43), 9));
        shareButton = button("↗  Поделиться последним файлом", false);
        shareButton.setOnClickListener(v -> shareLast());
        run.addView(shareButton, boxLp(-1, dp(43), 0));

        LinearLayout footer = vertical();
        footer.addView(txt("LOCAL FIRST  •  БЕЗ ЗАГРУЗКИ НА СЕРВЕР  •  ARM64", 10, SECONDARY, true), boxLp(-1, -2, 8));
        footer.addView(txt("Предпросмотр использует реальные фрагменты разных сцен. Прогноз объёма приблизительный и зависит от сложности остальных сцен. Для тяжёлых файлов обеспечьте свободное место под временную копию.", 11, SECONDARY, false));
        root.addView(footer, boxLp(-1, -2, 0));
        updateButtons();
    }

    private void cacheSettings() {
        if (mode < 0 || mode >= 4) return;
        if (formatSpinner != null && formatSpinner.getSelectedItemPosition() >= 0)
            savedFormat[mode] = formatSpinner.getSelectedItemPosition();
        if (qualitySeek != null) savedQuality[mode] = QUALITY_STAGES[qualitySeek.getProgress()];
        if (resolutionSpinner != null && resolutionSpinner.getSelectedItemPosition() >= 0)
            savedResolution[mode] = resolutionSpinner.getSelectedItemPosition();
        if (bitrateSpinner != null && bitrateSpinner.getSelectedItemPosition() >= 0)
            savedBitrate[mode] = bitrateSpinner.getSelectedItemPosition();
        getPreferences(MODE_PRIVATE).edit()
            .putInt("f"+mode, savedFormat[mode]).putInt("q"+mode, savedQuality[mode]).putBoolean("format_v2",true)
            .putInt("r"+mode, savedResolution[mode]).putInt("b"+mode, savedBitrate[mode]).apply();
    }
    private AdapterView.OnItemSelectedListener changeListener() {
        return new AdapterView.OnItemSelectedListener() {
            boolean initialized;
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (!initialized) { initialized = true; return; }
                schedulePreview();
            }
            public void onNothingSelected(AdapterView<?> parent) { }
        };
    }
    private Spinner spinner(String[] items, int selectedValue) {
        Spinner sp = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, items) {
            @Override public View getView(int pos, View convertView, ViewGroup parent) {
                TextView t = txt(getItem(pos), 15, WHITE, true);
                t.setGravity(Gravity.CENTER_VERTICAL);
                t.setPadding(dp(15), 0, dp(15), 0);
                return t;
            }
            @Override public View getDropDownView(int pos, View convertView, ViewGroup parent) {
                TextView t = txt(getItem(pos), 15, WHITE, false);
                t.setPadding(dp(16), dp(13), dp(16), dp(13));
                t.setBackgroundColor(CARD2); return t;
            }
        };
        sp.setAdapter(adapter); sp.setSelection(selectedValue);
        sp.setBackground(background(CARD2, BORDER, 13));
        sp.setPopupBackgroundDrawable(background(CARD2, BORDER, 13));
        return sp;
    }
    private ConvertEngine.Config config() {
        return new ConvertEngine.Config(mode, String.valueOf(formatSpinner.getSelectedItem()),
                qualitySeek == null ? savedQuality[mode] : QUALITY_STAGES[qualitySeek.getProgress()], resolutionSpinner == null ? 0 : resolutionSpinner.getSelectedItemPosition(),
                bitrateSpinner == null ? 2 : bitrateSpinner.getSelectedItemPosition(), performanceChoice);
    }
    private void openPicker() {
        // Import remains available during a background preview. Only an actual export
        // locks the queue, otherwise the user would need to wait for five samples.
        if (ConversionService.state.running && ConversionService.state.exporting) {
            setStatus("Дождитесь окончания экспорта или отмените его", false);
            return;
        }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        // Use */* so unusual extensions (TIFF, WMA, Matroska, etc.) are visible in SAF providers.
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try { startActivityForResult(intent, PICK); }
        catch (Exception ex) { setStatus("Проводник файлов недоступен: " + ex.getMessage(), true); }
    }
    @Override protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (req != PICK || result != RESULT_OK || data == null || pickerAnalyzing ||
                (ConversionService.state.running && ConversionService.state.exporting)) return;
        ArrayList<Uri> uris = new ArrayList<>();
        android.content.ClipData clips = data.getClipData();
        if (clips != null) {
            for (int i=0; i<clips.getItemCount() && i<1000; i++) {
                Uri found=clips.getItemAt(i).getUri();
                if (found != null && !uris.contains(found)) uris.add(found);
            }
        } else if (data.getData()!=null) uris.add(data.getData());
        if (uris.isEmpty()) return;
        pickerAnalyzing = true;
        setStatus("Определяем тип выбранных файлов…", false);
        executor.execute(() -> {
            final ArrayList<ConvertEngine.Source> images = new ArrayList<>();
            final ArrayList<ConvertEngine.Source> videos = new ArrayList<>();
            final ArrayList<ConvertEngine.Source> audio = new ArrayList<>();
            final ArrayList<String> unsupported = new ArrayList<>();
            for (Uri uri : uris) {
                try {
                    ConvertEngine.Source source = ConvertEngine.describe(this, uri);
                    switch (ConvertEngine.classify(this, source)) {
                        case ConvertEngine.IMAGE: images.add(source);break;
                        case ConvertEngine.VIDEO: videos.add(source);break;
                        case ConvertEngine.AUDIO: audio.add(source);break;
                        default: unsupported.add(source.name);break;
                    }
                } catch (Exception failure) { unsupported.add(uri.getLastPathSegment()); }
            }
            ui.post(() -> {
                pickerAnalyzing=false;
                if (isFinishing() || isDestroyed()) return;
                chooseDetectedCategory(images, videos, audio, unsupported);
            });
        });
    }
    private void chooseDetectedCategory(ArrayList<ConvertEngine.Source> images,
             ArrayList<ConvertEngine.Source> videos, ArrayList<ConvertEngine.Source> audio,
             ArrayList<String> unsupported) {
        int kinds = (images.isEmpty()?0:1)+(videos.isEmpty()?0:1)+(audio.isEmpty()?0:1);
        if (kinds==0) {
            new AlertDialog.Builder(this).setTitle("Не удалось определить формат")
                .setMessage("Выбранные файлы не распознаны как фото, видео или аудио. Проверьте формат и повторите выбор.")
                .setPositiveButton("Понятно",null).show();
            setStatus("Неподдерживаемый тип файлов",true);
            return;
        }
        if (!unsupported.isEmpty()) {
            String msg="Не распознано файлов: "+unsupported.size()+". Например: "+unsupported.get(0)+
                ". Эти файлы не попадут в очередь конвертации.";
            new AlertDialog.Builder(this).setTitle("Есть неподдерживаемые файлы")
                .setMessage(msg).setPositiveButton("Продолжить", (d,w)->
                    chooseSupportedCategory(images,videos,audio,kinds))
                .setNegativeButton("Отмена",null).show();
        } else chooseSupportedCategory(images,videos,audio,kinds);
    }
    private void chooseSupportedCategory(ArrayList<ConvertEngine.Source> images,
             ArrayList<ConvertEngine.Source> videos, ArrayList<ConvertEngine.Source> audio,int kinds) {
        if (kinds==1) {
            if (!images.isEmpty()) applyDetectedFiles(images,ConvertEngine.IMAGE,"");
            else if (!videos.isEmpty()) applyDetectedFiles(videos,ConvertEngine.VIDEO,"");
            else applyDetectedFiles(audio,ConvertEngine.AUDIO,"");
            return;
        }
        final ArrayList<String> labels = new ArrayList<>();
        final ArrayList<Integer> type = new ArrayList<>();
        if (!images.isEmpty()) {labels.add("Изображения · "+images.size());type.add(ConvertEngine.IMAGE);}
        if (!videos.isEmpty()) {labels.add("Видео · "+videos.size());type.add(ConvertEngine.VIDEO);}
        if (!audio.isEmpty()) {labels.add("Аудио · "+audio.size());type.add(ConvertEngine.AUDIO);}
        new AlertDialog.Builder(this)
            .setTitle("Выбраны разные типы файлов")
            .setMessage("Фото, видео и аудио нельзя конвертировать одной очередью с общими настройками. Выберите группу. Остальные файлы не будут добавлены.")
            .setItems(labels.toArray(new String[0]), (dialog,which)->{
                int picked=type.get(which);
                ArrayList<ConvertEngine.Source> chosen=picked==ConvertEngine.IMAGE?images:
                        picked==ConvertEngine.VIDEO?videos:audio;
                applyDetectedFiles(chosen,picked,"Добавлены только "+ConvertEngine.kindLabel(picked).toLowerCase(Locale.ROOT)+
                        " ("+chosen.size()+"). Остальные типы пропущены.");
            }).setNegativeButton("Отмена",null).show();
    }
    private void applyDetectedFiles(ArrayList<ConvertEngine.Source> files,int detected,String note) {
        if (ConversionService.state.running && ConversionService.state.exporting) {
            setStatus("Дождитесь завершения экспорта перед выбором других файлов",true);return;
        }
        // Videos stay in the EXTRACT tab when the user explicitly requests audio extraction.
        int targetMode=detected==ConvertEngine.VIDEO && mode==ConvertEngine.EXTRACT ?
                ConvertEngine.EXTRACT : detected;
        cacheSettings();
        boolean changed=mode!=targetMode;
        mode=targetMode;
        if(changed){queue.clear();selected=0;visibleFiles=24;}
        int added=0;
        for(ConvertEngine.Source source:files){
            boolean exists=false;
            for(ConvertEngine.Source current:queue)
                if(current.uri.equals(source.uri)){exists=true;break;}
            if(!exists){queue.add(source);added++;}
        }
        if(added==0){setStatus("Выбранные файлы уже находятся в очереди",false);return;}
        exported.clear();clearPreview();clearStaged();
        for (ConvertEngine.Source source:files) {
            try {getContentResolver().takePersistableUriPermission(source.uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(Exception ignored){}
        }
        if (changed) rebuild();
        else {renderFiles();renderPreviews();updateButtons();}
        schedulePreview();
        String message=note.isEmpty()? "Определено: "+ConvertEngine.kindLabel(detected)+
                ". Добавлено файлов: "+added+" · всего: "+queue.size():note;
        setStatus(message,false);
        if (changed) Toast.makeText(this,"Автоматически открыта вкладка: "+MODES[targetMode],Toast.LENGTH_SHORT).show();
    }
    private void renderFiles() {
        if (filesContainer == null) return;
        filesContainer.removeAllViews();
        if (queue.isEmpty()) {
            filesContainer.addView(txt("Файлы ещё не выбраны", 12, SECONDARY, false));
            return;
        }
        Button clear=button("✕  Очистить весь список · "+queue.size(),false);
        clear.setOnClickListener(v->clearSelectedFiles());
        filesContainer.addView(clear,boxLp(-1,dp(43),8));
        for (int i = 0; i < Math.min(queue.size(),Math.max(visibleFiles,selected+1)); i++) {
            ConvertEngine.Source source = queue.get(i);
            int idx = i;
            LinearLayout line = horizontal();
            line.setPadding(dp(10), dp(11), dp(10), dp(11));
            line.setBackground(background(i == selected ? CARD2 : CARD, i == selected ? BLUE : BORDER, 11));
            TextView info = txt((i + 1) + ". " + source.name, 12, WHITE, true);
            info.setSingleLine(true); info.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            line.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
            line.addView(txt(ConvertEngine.humanBytes(source.size), 11, SECONDARY, false));
            Button remove=button("✕",false);
            remove.setContentDescription("Удалить из очереди "+source.name);
            LinearLayout.LayoutParams removeLp=new LinearLayout.LayoutParams(dp(43),dp(42));
            removeLp.leftMargin=dp(8);
            line.addView(remove,removeLp);
            remove.setOnClickListener(v->removeSelectedFile(idx));
            LinearLayout.LayoutParams p = boxLp(-1, dp(49), 5);
            filesContainer.addView(line, p);
            line.setOnClickListener(v -> {
                if (busy || selected == idx) return;
                selected = idx;
                clearPreview(); clearStaged();
                renderFiles(); renderPreviews(); updateButtons();
                schedulePreview();
            });
        }
        if(queue.size()>visibleFiles){
            Button more=button("Показать ещё · "+(queue.size()-Math.min(queue.size(),visibleFiles))+" файлов",false);
            more.setOnClickListener(v->{visibleFiles+=24;renderFiles();});
            filesContainer.addView(more,boxLp(-1,dp(42),9));
        }
    }
    /** Queue edits never delete originals, and are blocked during active export. */
    private void removeSelectedFile(int index) {
        if(ConversionService.state.running && ConversionService.state.exporting){
            setStatus("Для изменения списка дождитесь окончания экспорта или отмените его",true);
            return;
        }
        if(index<0 || index>=queue.size())return;
        String name=queue.remove(index).name;
        if(index<selected)selected--;
        selected=Math.max(0,Math.min(selected,queue.size()-1));
        afterSelectionEdited("Убрано из очереди: "+name);
    }
    private void clearSelectedFiles() {
        if(ConversionService.state.running && ConversionService.state.exporting){
            setStatus("Для очистки списка дождитесь окончания экспорта или отмените его",true);
            return;
        }
        queue.clear();selected=0;visibleFiles=24;
        afterSelectionEdited("Список выбранных файлов очищен");
    }
    private void afterSelectionEdited(String message) {
        ui.removeCallbacks(autoPreview);
        pendingAutoPreview=false;
        openPreviewWhenReady=false;
        clearPreview();clearStaged();exported.clear();
        fastEstimatedBytes=-1;fastMetadataPending=false;
        estimateTicket.incrementAndGet();
        if(queue.isEmpty()){
            if(ConversionService.state.running && !ConversionService.state.exporting)
                startService(new Intent(this,ConversionService.class).setAction(ConversionService.ACTION_CANCEL));
            renderFiles();renderPreviews();updateButtons();
        }else{
            renderFiles();updateButtons();schedulePreview();
        }
        setStatus(message,false);
    }
    private void clearPreview() {
        stopPlayback();
        previewParts.clear(); pairedParts.clear();
        previewOutput = null; previewSettings = null; previewDurationMs = 0; previewSampledMs = 0;
        lastJobRevision = ConversionService.state.revision;
    }
    private void clearStaged() {
        if (stagedInput != null) stagedInput.delete();
        stagedInput = null; stagedUri = null;
    }
    private void renderPreviews() {
        if (estimateBox == null) return;
        estimateBox.removeAllViews();
        estimatePhaseLabel=null;estimateAmountLabel=null;estimateHintLabel=null;estimateSpinner=null;
        if (queue.isEmpty()) {
            estimateBox.addView(txt("Выберите файлы: формат определится автоматически", 12, SECONDARY, false));
            return;
        }
        long original=ConvertEngine.totalSourceBytes(queue);
        estimateBox.addView(txt("Исходный объём  ·  "+ConvertEngine.humanBytes(original)+"  ·  "+queue.size()+" файл(ов)",12,SECONDARY,true),boxLp(-1,-2,9));
        long sampleSize=0;
        for (File item:previewParts) if(item.isFile())sampleSize+=item.length();
        if (ffmpegChecked && !ffmpegReady) {
            estimateBox.addView(txt("Движок конвертации недоступен",13,0xffff9199,true),boxLp(-1,-2,7));
            estimateBox.addView(txt("Невозможно уточнить размер без FFmpeg. Проверьте библиотеку в app/libs.",11,SECONDARY,false));
            return;
        }
        if (!ConversionService.state.running && ConversionService.state.error &&
                !queue.isEmpty() && queue.get(selected).uri.toString().equals(ConversionService.state.sourceKey) &&
                previewParts.isEmpty() && !pendingAutoPreview) {
            estimateBox.addView(txt("Не удалось подготовить образец",13,0xffff9199,true),boxLp(-1,-2,6));
            estimateBox.addView(txt(ConversionService.state.status,11,SECONDARY,false),boxLp(-1,-2,7));
            if (fastEstimatedBytes > 0) estimateBox.addView(txt("Предварительно ≈ "+
                    ConvertEngine.humanBytes(fastEstimatedBytes),15,BLUE,true));
            return;
        }
        if (!previewParts.isEmpty() && previewSettings!=null) {
            long bytes;
            String tag;
            if (mode==ConvertEngine.IMAGE) {
                if(queue.size()==1) {bytes=sampleSize;tag="Точный размер результата";}
                else {
                    long sourceBytes=queue.get(selected).size;
                    if(sourceBytes>0 && original>0 && sampleSize>0){
                        double predicted=original*(sampleSize/(double)sourceBytes);
                        bytes=(long)Math.min(Long.MAX_VALUE,Math.max(1.0,predicted));
                        tag="Оценка по реально сжатому образцу · "+queue.size()+" фото";
                    }else {bytes=fastEstimatedBytes;tag="Предварительный прогноз всей очереди";}
                    estimateBox.addView(txt("Выбранный файл после сжатия: "+ConvertEngine.humanBytes(sampleSize),
                            12,SECONDARY,false),boxLp(-1,-2,6));
                }
            } else if(queue.size()>1) {bytes=fastEstimatedBytes;tag="Оценка всей очереди";
            } else if (previewDurationMs>0 && previewSampledMs>0) {
                bytes=(long)(sampleSize*Math.max(1.0,previewDurationMs/(double)previewSampledMs));
                tag="Оценка по "+previewParts.size()+" сценам";
            } else {bytes=fastEstimatedBytes;tag="Предварительный прогноз";}
            estimateBox.addView(txt(tag,11,SECONDARY,true),boxLp(-1,-2,4));
            estimateBox.addView(txt(ConvertEngine.humanBytes(bytes),23,GREEN,true),boxLp(-1,-2,5));
            if (original>0 && bytes>0)estimateBox.addView(txt(reduction(original,bytes),12,SECONDARY,false));
            if (mode!=ConvertEngine.IMAGE)estimateBox.addView(txt("Значение приблизительное: размер всего файла может отличаться.",11,SECONDARY,false),boxLp(-1,-2,7));
            return;
        }
        LinearLayout estimateCard=horizontal();
        estimateCard.setGravity(Gravity.CENTER_VERTICAL);
        estimateCard.setPadding(dp(12),dp(12),dp(12),dp(12));
        estimateCard.setBackground(background(CARD2,BORDER,13));
        estimateSpinner=new ProgressBar(this,null,android.R.attr.progressBarStyleSmall);
        estimateSpinner.setIndeterminate(true);
        if (android.os.Build.VERSION.SDK_INT>=21)
            estimateSpinner.setIndeterminateTintList(ColorStateList.valueOf(BLUE));
        estimateCard.addView(estimateSpinner,new LinearLayout.LayoutParams(dp(29),dp(29)));
        LinearLayout text=vertical();
        text.setPadding(dp(12),0,0,0);
        estimatePhaseLabel=txt("Подготавливаем пробный образец…",13,WHITE,true);
        estimateHintLabel=txt("Изучаем фрагменты файла",11,SECONDARY,false);
        text.addView(estimatePhaseLabel);text.addView(estimateHintLabel);
        estimateCard.addView(text,new LinearLayout.LayoutParams(0,-2,1));
        estimateBox.addView(estimateCard,boxLp(-1,-2,9));
        estimateBox.addView(txt("БЫСТРЫЙ ПРОГНОЗ · БЕЗ ПЕРЕКОДИРОВАНИЯ",10,SECONDARY,true),boxLp(-1,-2,5));
        estimateAmountLabel=txt(fastEstimatedBytes>0?"≈ "+ConvertEngine.humanBytes(fastEstimatedBytes):"Определяем параметры…",21,BLUE,true);
        estimateBox.addView(estimateAmountLabel,boxLp(-1,-2,5));
        estimateBox.addView(txt("Предварительно по параметрам. Прогноз уточнится по реальным сценам.",11,SECONDARY,false));
        updateEstimateAnimation();
    }
    private void updateEstimateAnimation() {
        if (estimatePhaseLabel==null || estimateHintLabel==null)return;
        ConversionService.Snapshot state=ConversionService.state;
        boolean active=state.running && !state.exporting && !pendingAutoPreview &&
                !queue.isEmpty() && queue.get(selected).uri.toString().equals(state.sourceKey);
        if (active && state.earlySampleDurationMs > 0 && state.durationMs > 0 &&
                state.earlySampleBytes > 0) {
            // First real encoded clip already gives useful new information.
            long prediction=(long)(state.earlySampleBytes *
                    (state.durationMs/(double)state.earlySampleDurationMs));
            if (estimateAmountLabel != null) {
                estimateAmountLabel.setText("≈ " + ConvertEngine.humanBytes(prediction));
                estimateAmountLabel.setTextColor(GREEN);
            }
            estimatePhaseLabel.setText("Уточняем по сценам · "+state.earlySampleCount+"/"+
                    Math.max(1,state.earlySampleTotal));
            estimateHintLabel.setText("Реальная выборка · "+state.fileProgress+"% подготовлено");
        } else if (active) {
            estimatePhaseLabel.setText("Кодируем первый образец · "+state.fileProgress+"%");
            estimateHintLabel.setText(state.status);
        } else if(fastMetadataPending) {
            estimatePhaseLabel.setText("Читаем метаданные…");
            estimateHintLabel.setText("Без копирования исходного файла");
        } else {
            estimatePhaseLabel.setText("Уточняем оценку размера…");
            estimateHintLabel.setText("Подготовка образцов в фоне");
        }
    }
    private String reduction(long before, long after) {
        int diff = (int) Math.round((1.0 - after / (double) before) * 100);
        return diff >= 0 ? "Экономия: " + diff + "%" : "Размер увеличится примерно на " + (-diff) + "%";
    }
    private void displayMedia(LinearLayout slot, Uri uri, int kind, String placeholder, boolean original) {
        FrameLayout frame = new FrameLayout(this);
        frame.setBackground(background(CARD2, BORDER, 12));
        slot.addView(frame, boxLp(-1, dp(163), 8));
        if (uri == null) {
            TextView p = txt(placeholder, 12, SECONDARY, false);
            p.setGravity(Gravity.CENTER);
            p.setPadding(dp(9), 0, dp(9), 0);
            frame.addView(p, new FrameLayout.LayoutParams(-1, -1));
            return;
        }
        if (kind == ConvertEngine.AUDIO) {
            TextView art = txt("♫", 52, BLUE, true);
            art.setGravity(Gravity.CENTER);
            frame.addView(art, new FrameLayout.LayoutParams(-1, -1));
        } else {
            ImageView image = new ImageView(this);
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            String key = kind + "::" + uri.toString() + (original ? ":source" : ":preview");
            image.setTag(key);
            frame.addView(image, new FrameLayout.LayoutParams(-1, -1));
            Bitmap cache = thumbs.get(key);
            if (cache != null && !cache.isRecycled()) image.setImageBitmap(cache);
            else thumbnailWorker.execute(() -> {
                Bitmap thumb = kind == ConvertEngine.IMAGE ? picture(uri) : movieFrame(uri);
                if (thumb != null) {
                    synchronized (thumbs) { thumbs.put(key, thumb); }
                    ui.post(() -> { if (key.equals(image.getTag())) image.setImageBitmap(thumb); });
                }
            });
        }
        if (kind == ConvertEngine.VIDEO || kind == ConvertEngine.AUDIO) {
            Button play = button(kind == ConvertEngine.AUDIO ? "▶  Фрагменты" : "▶  Фрагменты", false);
            slot.addView(play, boxLp(-1, dp(40), 0));
            play.setOnClickListener(v -> {
                if (kind == ConvertEngine.AUDIO) playAudioSamples(uri, original);
                else if (original) playOriginalVideo(frame, uri);
                else playResultVideo(frame);
            });
        }
    }

    private void stopPlayback() {
        if (playbackAdvance != null) { ui.removeCallbacks(playbackAdvance); playbackAdvance = null; }
        if (activeVideo != null) {
            try { activeVideo.stopPlayback(); } catch (Throwable ignored) { }
            activeVideo = null;
        }
        if (audioPlayer != null) {
            try { audioPlayer.release(); } catch (Throwable ignored) { }
            audioPlayer = null;
        }
    }

    /** The original is never allowed to run to its end: only five 2.5-second windows. */
    private void playOriginalVideo(FrameLayout frame, Uri original) {
        stopPlayback();
        frame.removeAllViews();
        VideoView player = new VideoView(this);
        activeVideo = player;
        frame.addView(player, new FrameLayout.LayoutParams(-1, -1));
        player.setVideoURI(original);
        player.setOnPreparedListener(mp -> {
            if (player != activeVideo) return;
            long duration = Math.max(0, mp.getDuration());
            final List<Long> positions = ConvertEngine.previewStarts(duration, 1200);
            final int[] next = {0};
            final Runnable[] advance = new Runnable[1];
            advance[0] = () -> {
                if (player != activeVideo || next[0] >= positions.size()) {
                    try { player.pause(); } catch (Throwable ignored) { }
                    return;
                }
                long start = positions.get(next[0]++);
                player.seekTo((int) Math.min(Integer.MAX_VALUE, start));
                player.start();
                playbackAdvance = advance[0];
                ui.postDelayed(advance[0], Math.min(2500, duration > 0 ? Math.max(250, duration - start) : 2500));
            };
            advance[0].run();
        });
        player.setOnErrorListener((mp, what, extra) -> {
            setStatus("Встроенный плеер Android не поддерживает кодек оригинала", true);
            return true;
        });
    }

    /** Encoded parts are each 2.5 seconds. Playback advances to the next real sampled scene. */
    private void playResultVideo(FrameLayout frame) {
        if (previewParts.isEmpty()) return;
        stopPlayback();
        frame.removeAllViews();
        VideoView player = new VideoView(this);
        activeVideo = player;
        frame.addView(player, new FrameLayout.LayoutParams(-1, -1));
        final ArrayList<File> parts = new ArrayList<>(previewParts);
        final int[] cursor = {0};
        final Runnable[] advance = new Runnable[1];
        advance[0] = () -> {
            if (player != activeVideo || cursor[0] >= parts.size()) {
                player.stopPlayback();
                return;
            }
            File file = parts.get(cursor[0]++);
            if (!file.exists()) { advance[0].run(); return; }
            player.setVideoURI(Uri.fromFile(file));
            player.setOnPreparedListener(mp -> { if (player == activeVideo) player.start(); });
        };
        player.setOnCompletionListener(mp -> advance[0].run());
        player.setOnErrorListener((mp, what, extra) -> {
            setStatus("Формат предпросмотра не поддерживается системным плеером", true);
            return true;
        });
        advance[0].run();
    }

    private void playAudioSamples(Uri original, boolean source) {
        stopPlayback();
        if (source) {
            MediaPlayer player = new MediaPlayer();
            audioPlayer = player;
            try {
                player.setDataSource(this, original);
                player.setOnPreparedListener(mp -> {
                    if (mp != audioPlayer) return;
                    long duration = Math.max(0, mp.getDuration());
                    List<Long> positions = ConvertEngine.previewStarts(duration, 1500);
                    final int[] next = {0};
                    final Runnable[] advance = new Runnable[1];
                    advance[0] = () -> {
                        if (player != audioPlayer || next[0] >= positions.size()) {
                            if (player == audioPlayer) player.pause();
                            return;
                        }
                        long start = positions.get(next[0]++);
                        player.seekTo((int) Math.min(Integer.MAX_VALUE, start));
                        player.start();
                        playbackAdvance = advance[0];
                        ui.postDelayed(advance[0], Math.min(2500, duration > 0 ? Math.max(250, duration - start) : 2500));
                    };
                    advance[0].run();
                });
                player.prepareAsync();
            } catch (Throwable ex) {
                setStatus("Не удалось воспроизвести звук: " + ex.getMessage(), true);
                stopPlayback();
            }
        } else {
            final ArrayList<File> parts = new ArrayList<>(previewParts);
            final int[] next = {0};
            final Runnable[] advance = new Runnable[1];
            advance[0] = () -> {
                if (audioPlayer != null) {
                    try { audioPlayer.release(); } catch (Throwable ignored) { }
                    audioPlayer = null;
                }
                if (next[0] >= parts.size()) return;
                File file = parts.get(next[0]++);
                MediaPlayer mp = new MediaPlayer();
                audioPlayer = mp;
                try {
                    mp.setDataSource(this, Uri.fromFile(file));
                    mp.setOnPreparedListener(MediaPlayer::start);
                    mp.setOnCompletionListener(done -> advance[0].run());
                    mp.prepareAsync();
                } catch (Throwable ex) {
                    setStatus("Не удалось прочитать аудиофрагмент: " + ex.getMessage(), true);
                    stopPlayback();
                }
            };
            advance[0].run();
        }
    }
    private Bitmap picture(Uri uri) {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) return null;
            BitmapFactory.Options opt = new BitmapFactory.Options();
            opt.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(in, null, opt);
            int max = Math.max(opt.outWidth, opt.outHeight);
            int scale = 1;
            while (max / scale > 680) scale *= 2;
            opt.inSampleSize = scale;
            opt.inJustDecodeBounds = false;
            try (InputStream second = getContentResolver().openInputStream(uri)) {
                return BitmapFactory.decodeStream(second, null, opt);
            }
        } catch (Exception ignored) { return null; }
    }
    private Bitmap movieFrame(Uri uri) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(this, uri);
            long durationMs = 0;
            try { durationMs = Long.parseLong(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)); }
            catch (Throwable ignored) { }
            long middleUs = durationMs > 0 ? (durationMs / 2) * 1000 : 1_000_000;
            Bitmap b = r.getFrameAtTime(middleUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (b == null) return null;
            if (b.getWidth() <= 680 && b.getHeight() <= 680) return b;
            float scale = 680f / Math.max(b.getWidth(), b.getHeight());
            Bitmap small = Bitmap.createScaledBitmap(b, Math.max(1, (int) (b.getWidth() * scale)),
                    Math.max(1, (int) (b.getHeight() * scale)), true);
            if (small != b) b.recycle();
            return small;
        } catch (Exception ignored) { return null; }
        finally { try { r.release(); } catch (Exception ignored) { } }
    }
    private void updateButtons() {
        boolean has = !queue.isEmpty();
        busy = ConversionService.state.running;
        if (previewButton != null) previewButton.setEnabled(has && ffmpegReady && !(busy && ConversionService.state.exporting));
        if (exportButton != null) exportButton.setEnabled(has && !busy && ffmpegReady);
        if (cancelButton != null) cancelButton.setVisibility(busy ? View.VISIBLE : View.GONE);
        if (shareButton != null) shareButton.setVisibility(exported.isEmpty() ? View.GONE : View.VISIBLE);
        boolean exportingNow = busy && ConversionService.state.exporting;
        if (qualitySeek != null) qualitySeek.setEnabled(!exportingNow);
        if (formatSpinner != null) formatSpinner.setEnabled(!exportingNow);
        if (resolutionSpinner != null) resolutionSpinner.setEnabled(!exportingNow);
        if (bitrateSpinner != null) bitrateSpinner.setEnabled(!exportingNow);
    }
    private void setStatus(String status, boolean error) {
        if (statusLabel == null) return;
        statusLabel.setText(status);
        statusLabel.setTextColor(error ? Color.rgb(255, 148, 154) : SECONDARY);
    }
    private void perform(boolean exporting) {
        if (busy || ConversionService.state.running || queue.isEmpty()) return;
        if (!ffmpegReady) {
            setStatus("Движок FFmpeg не готов: " + ffmpegDiagnostic, true);
            showEngineDiagnostics(); return;
        }
        ConvertEngine.Config cfg = config();
        ArrayList<ConvertEngine.Source> chosen = new ArrayList<>();
        if (exporting) chosen.addAll(queue); else chosen.add(queue.get(selected));
        stopPlayback();
        if (exporting) {
            ui.removeCallbacks(autoPreview);
            openPreviewWhenReady = false;
        } else clearPreview();
        try {
            Intent intent = ConversionService.request(this, chosen, cfg, exporting);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent);
            else startService(intent);
            busy = true;
            if (progressBar != null) progressBar.setProgress(0);
            setStatus(exporting ? "Запущена фоновая конвертация. Можно свернуть приложение." :
                    "Начинается выборочный предпросмотр из разных частей файла…", false);
            updateButtons();
        } catch (Throwable ex) {
            busy = false;
            setStatus("Не удалось запустить службу: " + formatFailure(ex), true);
            updateButtons();
        }
    }
    private String tail(String s, int max) {
        if (s.length() > max) s = s.substring(s.length() - max);
        return s.replaceAll("\\s+", " ").trim();
    }
    private void shareLast() {
        if (exported.isEmpty()) return;
        Uri uri = exported.get(exported.size() - 1);
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType(lastExportMime);
        intent.putExtra(Intent.EXTRA_STREAM, uri);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(intent, "Поделиться готовым файлом"));
    }
    @Override protected void onDestroy() {
        ui.removeCallbacks(pollJob);
        ui.removeCallbacks(autoPreview);
        stopPlayback();
        executor.shutdownNow(); thumbnailWorker.shutdownNow();
        if (audioPlayer != null) { try { audioPlayer.release(); } catch (Exception ignored) { } }
        // Background service owns temporary media and conversion; do not cancel it here.
        super.onDestroy();
    }
}
