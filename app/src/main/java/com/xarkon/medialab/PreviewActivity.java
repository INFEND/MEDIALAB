package com.xarkon.medialab;
// MediaLAB v1.2.5: CLEAN REPLACEMENT FILE. Do not append previous versions.

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Single-decoder, truly synchronized video comparison, default curtain at 50%. */
public final class PreviewActivity extends Activity {
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private ArrayList<String> parts, paired;
    private Uri source;
    private int mode, currentPart;
    private FrameLayout stage;
    private TextView status;
    private Button toggle;
    private MediaPlayer player;
    private SyncedCompareView liveView;
    private BeforeAfterView imageView;
    private boolean closed, pausedByUser;
    private volatile boolean ready;
    private int accent=0xff80a8ff;
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN);
        parts=getIntent().getStringArrayListExtra("parts");
        paired=getIntent().getStringArrayListExtra("paired");
        String sourceValue=getIntent().getStringExtra("source");
        source=sourceValue==null?null:Uri.parse(sourceValue);
        mode=getIntent().getIntExtra("mode",ConvertEngine.VIDEO);
        if(parts==null||parts.isEmpty()) {finish();return;}
        int[] accents=QrLook.COLORS;
        int color=getSharedPreferences("MainActivity",MODE_PRIVATE).getInt("accent",0);
        accent=accents[Math.max(0,Math.min(color,accents.length-1))];
        LinearLayout page=new LinearLayout(this);page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(Color.BLACK); setContentView(page);
        LinearLayout head=new LinearLayout(this);head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(16),dp(8),dp(16),dp(8));
        Button back=button("Назад",false);back.setOnClickListener(v->finish());
        head.addView(back,new LinearLayout.LayoutParams(dp(98),dp(43)));
        status=new TextView(this);status.setTextSize(12);status.setTextColor(0xffb6c4dc);
        status.setGravity(Gravity.CENTER);status.setText("Синхронное сравнение • 50 / 50");
        head.addView(status,new LinearLayout.LayoutParams(0,-1,1));
        // Top controls overlay the full-height preview, so the picture is never shrunk vertically.
        head.setBackgroundColor(0xBB070C16);
        stage=new FrameLayout(this);stage.setBackgroundColor(Color.BLACK);
        page.addView(stage,new LinearLayout.LayoutParams(-1,0,1));
        FrameLayout.LayoutParams topControls=new FrameLayout.LayoutParams(-1,dp(56),Gravity.TOP);
        stage.addView(head,topControls);
        LinearLayout foot=new LinearLayout(this);foot.setGravity(Gravity.CENTER);
        foot.setPadding(dp(16),dp(10),dp(16),dp(18));
        toggle=button("Пауза",true); toggle.setOnClickListener(v->togglePlayback());
        foot.addView(toggle,new LinearLayout.LayoutParams(0,dp(50),1));
        Button restart=button("Сначала",false);
        LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(0,dp(50),1);rp.leftMargin=dp(10);
        foot.addView(restart,rp);restart.setOnClickListener(v->{pausedByUser=false;playPart(0);});
        foot.setBackgroundColor(0xBB070C16);
        FrameLayout.LayoutParams controls=new FrameLayout.LayoutParams(-1,dp(72),Gravity.BOTTOM);
        stage.addView(foot,controls);
        if(mode==ConvertEngine.IMAGE) {
            toggle.setVisibility(View.GONE);restart.setVisibility(View.GONE);
            showImageCompare();
        } else if(mode==ConvertEngine.VIDEO) {
            if(paired==null || paired.isEmpty()) {
                status.setText("Синхронная выборка отсутствует. Пересоздайте предпросмотр.");
                toggle.setEnabled(false);restart.setEnabled(false);
            } else {
                // Paired video is always side-by-side; orientation follows the source.
                orientToSource();
                liveView=new SyncedCompareView(this);
                stage.addView(liveView,new FrameLayout.LayoutParams(-1,-1));
                liveView.onReady(surface->{ready=true;playPart(0);});
            }
        } else {
            TextView info=new TextView(this);info.setGravity(Gravity.CENTER);
            info.setTextColor(Color.WHITE);info.setTextSize(18);
            info.setText("Аудиопредпросмотр\nВыбранные участки файла");
            stage.addView(info,new FrameLayout.LayoutParams(-1,-1));
            playPart(0);
        }
        head.bringToFront();foot.bringToFront();
    }
    private void orientToSource() {
        if (source == null) return;
        io.execute(() -> {
            try {
                MediaMetadataRetriever r = new MediaMetadataRetriever();
                int w,h,rotation;
                try {
                    r.setDataSource(this, source);
                    w=Integer.parseInt(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
                    h=Integer.parseInt(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
                    String rot=r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
                    rotation=rot==null?0:Integer.parseInt(rot);
                } finally {r.release();}
                if(rotation%180!=0){int temp=w;w=h;h=temp;}
                boolean landscape=w>=h;
                runOnUiThread(()->{
                    if(!closed)setRequestedOrientation(landscape ?
                            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE :
                            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
                });
            } catch(Exception ignored) { }
        });
    }
    private int dp(int n) { return Math.round(n*getResources().getDisplayMetrics().density); }
    private Button button(String label,boolean primary) {
        Button b=new Button(this);b.setAllCaps(false);b.setText(label);b.setTextSize(14);
        b.setTextColor(primary?(Color.luminance(accent)>0.4?0xff081327:Color.WHITE):Color.WHITE);
        GradientDrawable d=new GradientDrawable();d.setColor(primary?accent:0xff192740);d.setCornerRadius(dp(16));
        b.setBackground(new android.graphics.drawable.RippleDrawable(
            ColorStateList.valueOf(0x44ffffff),d,null));
        b.setPadding(dp(10),0,dp(10),0);
        b.setOnTouchListener((v,event)->{
            if(event.getActionMasked()==android.view.MotionEvent.ACTION_DOWN)
                v.animate().scaleX(.97f).scaleY(.97f).setDuration(95).start();
            else if(event.getActionMasked()==android.view.MotionEvent.ACTION_UP ||
                    event.getActionMasked()==android.view.MotionEvent.ACTION_CANCEL)
                v.animate().scaleX(1f).scaleY(1f).setDuration(170).start();
            return false;
        });
        return b;
    }
    private void togglePlayback() {
        if(player==null) return;
        try {
            if(player.isPlaying()) { player.pause();pausedByUser=true;toggle.setText("Воспроизвести"); }
            else { pausedByUser=false;player.start();toggle.setText("Пауза"); }
        } catch(Exception e){status.setText("Ошибка управления плеером");}
    }
    private void releasePlayer(){
        if(player!=null){try{player.reset();player.release();}catch(Exception ignored){}player=null;}
    }
    private void playPart(int index){
        if(closed || (mode==ConvertEngine.VIDEO && !ready))return;
        releasePlayer();
        ArrayList<String> sequence=mode==ConvertEngine.VIDEO?paired:parts;
        if(sequence==null || index>=sequence.size()) {
            status.setText("Просмотр завершён");toggle.setText("Воспроизвести");
            pausedByUser=true;return;
        }
        File f=new File(sequence.get(index));
        if(!f.exists()||f.length()<1){playPart(index+1);return;}
        currentPart=index;
        status.setText("Сцена "+(index+1)+" / "+sequence.size()+" • До / после");
        toggle.setText("Пауза");
        try {
            MediaPlayer next=new MediaPlayer();player=next;
            next.setDataSource(f.getAbsolutePath());
            if(mode==ConvertEngine.VIDEO && liveView!=null){
                liveView.onReady(surface->{
                    if(next==player)try{next.setSurface(surface);}catch(Exception e){status.setText("Ошибка видеоповерхности");}
                });
            }
            next.setOnPreparedListener(mp->{if(!closed&&mp==player){
                if(mode==ConvertEngine.VIDEO&&liveView!=null)liveView.updateSize(mp.getVideoWidth(),mp.getVideoHeight());
                if(!pausedByUser)mp.start();
            }});
            next.setOnCompletionListener(mp->{if(!closed&&mp==player)playPart(index+1);});
            next.setOnErrorListener((mp,what,extra)->{
                status.setText("Ошибка декодера: "+what+"/"+extra);
                toggle.setText("Повторить");return true;
            });
            next.prepareAsync();
        } catch(Exception e) {status.setText("Не удалось открыть сцену: "+e.getMessage());}
    }
    private void showImageCompare() {
        imageView=new BeforeAfterView(this);
        stage.addView(imageView,new FrameLayout.LayoutParams(-1,-1));
        status.setText("Перемещайте разделитель по изображению");
        io.execute(()->{
            Bitmap left=decode(source),right=decode(Uri.fromFile(new File(parts.get(0))));
            runOnUiThread(()->{
                if(closed||imageView==null)return;
                if(left==null || right==null)status.setText("Изображение не открылось. Проверьте кодек и исходный файл.");
                imageView.setImages(left,right);
            });
        });
    }
    private Bitmap decode(Uri uri) {
        if(uri==null)return null;
        try(InputStream stream=getContentResolver().openInputStream(uri)) {
            BitmapFactory.Options opts=new BitmapFactory.Options();opts.inJustDecodeBounds=true;
            BitmapFactory.decodeStream(stream,null,opts);
            int size=1;while(Math.max(opts.outWidth,opts.outHeight)/size>1600)size*=2;
            opts.inSampleSize=size;opts.inJustDecodeBounds=false;
            try(InputStream again=getContentResolver().openInputStream(uri)){
                return BitmapFactory.decodeStream(again,null,opts);
            }
        }catch(Exception ignored){return null;}
    }
    @Override protected void onPause(){
        if(player!=null)try{player.pause();}catch(Exception ignored){}
        if(toggle!=null && player!=null)toggle.setText("Воспроизвести");
        pausedByUser=true;
        if(liveView!=null)liveView.pauseGL();
        super.onPause();
    }
    @Override protected void onResume(){
        super.onResume();if(liveView!=null)liveView.resumeGL();
    }
    @Override protected void onDestroy(){
        closed=true;releasePlayer();
        if(liveView!=null)liveView.close();
        io.shutdownNow();super.onDestroy();
    }
}
// MediaLAB v1.2.5 clean source: PreviewActivity.java END
