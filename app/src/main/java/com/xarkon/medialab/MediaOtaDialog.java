package com.xarkon.medialab;
import android.app.Activity;
import android.app.Dialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.*;
import java.util.Locale;

public final class MediaOtaDialog {
 private final Activity activity;
 private final Dialog dialog;
 private final TextView title,subtitle,stageLabel,detail,bytes,percent,notes,transferInfo;
 private final Button primary,secondary;
 private final ProgressBar progress;
 private final OtaRingView ring;
 private final ScrollView history;
 private final int accent, themeMode;
 private final boolean motionEnabled;
 private android.animation.ValueAnimator numberAnimation;
 private int displayedProgress=0;
 private long receivedBytes=0L;
 private long expectedBytes=-1L;
 private long displayedBytes=0L;
 private long rateBytes=-1L,rateTime=0L;
 private double rate=0d;
 private enum State { CHECK, OFFER, DOWNLOAD, PAUSED, VERIFY, READY, CURRENT, ERROR }
 private State state=State.CHECK;
 private Runnable cancelDownload;
 // A cancellation remains pending until the updater reports a new state.
 // Progress callbacks queued before cancellation must not re-enable the button.
 private boolean cancelRequested;
 private int dp(int n){return (int)(n*activity.getResources().getDisplayMetrics().density+.5f);}
 private GradientDrawable bg(int fill,int border){return bg(fill,border,15);}
 private GradientDrawable bg(int fill,int border,int radius){
  GradientDrawable d=new GradientDrawable();d.setColor(fill);d.setCornerRadius(dp(radius));
  if(border!=0)d.setStroke(dp(1),border);return d;
 }
 private TextView label(String s,int size,int color){
  TextView v=new TextView(activity);v.setText(s);v.setTextColor(color);
  v.setTextSize(size);v.setIncludeFontPadding(false);return v;
 }
 private Button actionButton(String text,int color){
  Button v=new Button(activity,null,android.R.attr.borderlessButtonStyle);
  v.setText(text);v.setTextColor(color);v.setTextSize(12);v.setIncludeFontPadding(false);
  v.setAllCaps(false);v.setMinWidth(0);v.setMinimumWidth(0);v.setStateListAnimator(null);
  v.setBackgroundColor(Color.TRANSPARENT);return v;
 }
 public MediaOtaDialog(Activity a,int tint){
  activity=a;accent=tint;int mode=a.getPreferences(0).getInt("theme",0);
  themeMode=mode;
  motionEnabled=a.getPreferences(0).getBoolean("motion",true);
  boolean light=mode==2;int fg=light?0xff182633:Color.WHITE;
  int muted=light?0xff607589:0xffa4b4c3;
  dialog=new Dialog(a);dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
  LinearLayout root=new LinearLayout(a);root.setOrientation(1);
  root.setPadding(dp(22),dp(22),dp(22),dp(19));
  root.setBackground(bg(light?0xffffffff:mode==1?0xff101013:0xff14212d,light?0xffdce4ed:mode==1?0xff303036:0xff304252,25));
  LinearLayout header=new LinearLayout(a);header.setGravity(Gravity.CENTER_VERTICAL);
  LinearLayout.LayoutParams spinLp=new LinearLayout.LayoutParams(dp(53),dp(53));spinLp.rightMargin=dp(14);
  FrameLayout ringFrame=new FrameLayout(a);
  ringFrame.setBackground(bg((tint&0x00ffffff)|0x22000000,0));
  ring=new OtaRingView(a,tint);
  ring.setMotionEnabled(motionEnabled);
  ringFrame.addView(ring,new FrameLayout.LayoutParams(dp(33),dp(33),Gravity.CENTER));
  header.addView(ringFrame,spinLp);
  title=label("Обновление MediaLAB",20,fg);title.setTypeface(null,Typeface.BOLD);
  LinearLayout titles=new LinearLayout(a);titles.setOrientation(LinearLayout.VERTICAL);
  titles.addView(title);header.addView(titles,new LinearLayout.LayoutParams(0,-2,1));root.addView(header);
  subtitle=label("БЕЗОПАСНОЕ ОБНОВЛЕНИЕ",10,muted);subtitle.setTypeface(null,Typeface.BOLD);
  LinearLayout.LayoutParams sublp=new LinearLayout.LayoutParams(-1,-2);sublp.topMargin=dp(7);titles.addView(subtitle,sublp);
  stageLabel=label("Проверка соединения…",15,fg);stageLabel.setTypeface(null,Typeface.BOLD);
  LinearLayout.LayoutParams stageLp=new LinearLayout.LayoutParams(-1,-2);stageLp.topMargin=dp(25);root.addView(stageLabel,stageLp);
  detail=label("Подключение к серверу…",12,muted);
  LinearLayout.LayoutParams dlp=new LinearLayout.LayoutParams(-1,-2);dlp.topMargin=dp(7);
  root.addView(detail,dlp);
  history=new ScrollView(a);history.setBackground(bg(light?0xfff3f6f9:0xff0c0f12,light?0xffdce4ed:mode==1?0xff303036:0xff304252));
  notes=label("",13,fg);notes.setPadding(dp(13),dp(12),dp(13),dp(13));notes.setLineSpacing(dp(4),1f);
  history.setVerticalScrollBarEnabled(false);
  history.setOnTouchListener((v,event)->{
   int action=event.getActionMasked();
   if(action==android.view.MotionEvent.ACTION_DOWN||action==android.view.MotionEvent.ACTION_MOVE)
    v.getParent().requestDisallowInterceptTouchEvent(true);
   else if(action==android.view.MotionEvent.ACTION_UP||action==android.view.MotionEvent.ACTION_CANCEL)
    v.getParent().requestDisallowInterceptTouchEvent(false);
   return false;
  });
  history.addView(notes);LinearLayout.LayoutParams hlp=new LinearLayout.LayoutParams(-1,dp(172));
  hlp.topMargin=dp(15);root.addView(history,hlp);history.setVisibility(View.GONE);
  LinearLayout numbers=new LinearLayout(a);numbers.setGravity(Gravity.CENTER_VERTICAL);
  LinearLayout.LayoutParams nlp=new LinearLayout.LayoutParams(-1,-2);nlp.topMargin=dp(22);
  root.addView(numbers,nlp);
  bytes=label("",13,fg);bytes.setTypeface(null,Typeface.BOLD);numbers.addView(bytes,new LinearLayout.LayoutParams(0,-2,1));
  percent=label("",18,tint);percent.setTypeface(null,Typeface.BOLD);numbers.addView(percent);
  progress=new ProgressBar(a,null,android.R.attr.progressBarStyleHorizontal);
  progress.setMax(1000);progress.setIndeterminate(true);
  progress.setProgressTintList(ColorStateList.valueOf(tint));
  progress.setIndeterminateTintList(ColorStateList.valueOf(tint));
  progress.setProgressBackgroundTintList(ColorStateList.valueOf(light?0xffe9eef3:0xff2a3139));
  LinearLayout.LayoutParams plp=new LinearLayout.LayoutParams(-1,dp(7));plp.topMargin=dp(12);
  root.addView(progress,plp);
  transferInfo=label("",12,muted);transferInfo.setVisibility(View.GONE);
  LinearLayout.LayoutParams rateLp=new LinearLayout.LayoutParams(-1,-2);rateLp.topMargin=dp(9);root.addView(transferInfo,rateLp);
  LinearLayout actions=new LinearLayout(a);actions.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);
  LinearLayout.LayoutParams alp=new LinearLayout.LayoutParams(-1,-2);alp.topMargin=dp(24);
  root.addView(actions,alp);
  secondary=actionButton("ЗАКРЫТЬ",muted);secondary.setTypeface(null,Typeface.BOLD);secondary.setGravity(Gravity.CENTER);secondary.setPadding(dp(16),dp(14),dp(16),dp(14));
  secondary.setBackground(new android.graphics.drawable.RippleDrawable(ColorStateList.valueOf((tint&0x00ffffff)|0x33000000),bg(Color.TRANSPARENT,0,13),null));
  secondary.setOnClickListener(v->dialog.dismiss());actions.addView(secondary);
  primary=actionButton("ОБНОВИТЬ",android.graphics.Color.luminance(tint)>.35?Color.BLACK:Color.WHITE);primary.setTypeface(null,Typeface.BOLD);
  primary.setGravity(Gravity.CENTER);primary.setPadding(dp(16),dp(13),dp(16),dp(13));primary.setBackground(new android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(0x33000000),bg(tint,0,13),null));
  LinearLayout.LayoutParams blp=new LinearLayout.LayoutParams(-2,-2);blp.leftMargin=dp(8);
  actions.addView(primary,blp);primary.setVisibility(View.GONE);
  // Keep the progress, notes and actions in one custom, theme-aware dialog.
  ScrollView viewport=new ScrollView(a);
  viewport.setFillViewport(false);
  viewport.setVerticalScrollBarEnabled(false);
  viewport.addView(root,new ScrollView.LayoutParams(-1,-2));
  dialog.setContentView(viewport);
  primary.setMinHeight(dp(48));secondary.setMinHeight(dp(48));
  primary.setFocusable(true);secondary.setFocusable(true);
  dialog.setOnDismissListener(ignored->{
   if(numberAnimation!=null){numberAnimation.cancel();numberAnimation=null;}
   ring.setChecking(false);
  });
 }
 public void show(){
  if(dialog.isShowing()||activity.isFinishing()||activity.isDestroyed())return;
  dialog.show();
  ring.setChecking(state==State.CHECK||state==State.VERIFY||(state==State.DOWNLOAD&&expectedBytes<=0));
  if(state==State.DOWNLOAD&&expectedBytes>0){
   if(numberAnimation!=null){numberAnimation.cancel();numberAnimation=null;}
   renderDownload(receivedBytes,expectedBytes);
  }
  Window w=dialog.getWindow();if(w==null)return;
  w.setBackgroundDrawableResource(android.R.color.transparent);
  w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
  WindowManager.LayoutParams p=w.getAttributes();p.dimAmount=.64f;p.gravity=Gravity.CENTER;
  p.width=Math.min(activity.getResources().getDisplayMetrics().widthPixels-dp(30),dp(450));
  p.height=-2;w.setAttributes(p);
  constrainWindowHeight();
 }
 private void constrainWindowHeight(){
  if(!dialog.isShowing())return;
  Window w=dialog.getWindow();if(w==null)return;
  View content=w.findViewById(android.R.id.content);
  if(content==null)return;
  content.post(()->{
   if(!dialog.isShowing())return;
   int maxHeight=Math.max(dp(120),activity.getResources().getDisplayMetrics().heightPixels-dp(96));
   View child=((android.view.ViewGroup)content).getChildAt(0);
   if(child instanceof ScrollView){
    View body=((ScrollView)child).getChildAt(0);
    if(body!=null){
     int desired=body.getHeight();
     if(desired>0)w.setLayout(w.getAttributes().width,desired>maxHeight?maxHeight:WindowManager.LayoutParams.WRAP_CONTENT);
    }
   }
  });
 }

 public boolean matchesAppearance(int tint,int mode){return accent==tint&&themeMode==mode;}
 public void dismiss(){dialog.dismiss();}
 public boolean isShowing(){return dialog.isShowing();}
 public boolean canLaunchInstaller(){Window w=dialog.getWindow();return dialog.isShowing()&&w!=null&&w.getDecorView().hasWindowFocus();}
 public boolean needsRefresh(){return state==State.CURRENT||state==State.OFFER;}
 public void stage(String message,String hint){
  renderStage(State.CHECK,message,hint);
 }
 public void preparing(String message,String hint){
  renderStage(State.DOWNLOAD,message,hint);
 }
 public void verifying(String message,String hint){
  renderStage(State.VERIFY,message,hint);
 }
 private void renderStage(State next,String message,String hint){
  state=next;resetRate();cancelRequested=false;primary.setEnabled(true);
  title.setText("Обновление MediaLAB");stageLabel.setText(message);detail.setText(hint);ring.setChecking(dialog.isShowing());
  bytes.setContentDescription(null);
  stageLabel.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
  receivedBytes=0L;expectedBytes=-1L;displayedBytes=0L;
  // Only a fresh server check invalidates the previous offer. Preserve the
  // verified release notes while preparing, downloading and verifying its APK.
  if(next==State.CHECK)history.setVisibility(View.GONE);
  progress.setIndeterminate(true);secondary.setText("СКРЫТЬ");
  primary.setVisibility(View.GONE);bytes.setText("");percent.setText("");
  constrainWindowHeight();
  if(numberAnimation!=null){numberAnimation.cancel();numberAnimation=null;}displayedProgress=0;
  progress.setProgress(0);ring.setProgressFraction(0f);
 }
 public void setHistory(String changes){
  CharSequence formatted=formatNotes(changes,themeMode==2);
  boolean changed=!android.text.TextUtils.equals(notes.getText(),formatted);
  if(changed)notes.setText(formatted);
  history.setVisibility(changes==null||changes.trim().isEmpty()?View.GONE:View.VISIBLE);
  if(changed)history.post(()->history.scrollTo(0,0));
  // A newly populated journal can increase the window height after show().
  constrainWindowHeight();
 }
 public void offer(String version,String changes,Runnable action){
  state=State.OFFER;resetRate();cancelRequested=false;primary.setEnabled(true);
  title.setText("Доступно обновление");stageLabel.setText("MediaLAB "+version);detail.setText("Новая версия готова к загрузке");
  if(numberAnimation!=null){numberAnimation.cancel();numberAnimation=null;}
  displayedProgress=0;receivedBytes=0L;expectedBytes=-1L;displayedBytes=0L;
  ring.setChecking(false);progress.setIndeterminate(false);progress.setProgress(0);ring.setProgressFraction(0f);
  setHistory(changes);
  primary.setText("ОБНОВИТЬ");primary.setVisibility(View.VISIBLE);
  primary.setOnClickListener(v->{
   // Guard the offer synchronously: the updater switches to DOWNLOAD on the
   // main thread, but a second tap must never dispatch a duplicate request.
   if(state!=State.OFFER||!primary.isEnabled()||action==null)return;
   primary.setEnabled(false);
   try{action.run();}catch(RuntimeException error){primary.setEnabled(true);throw error;}
  });secondary.setText("ПОЗЖЕ");
  bytes.setText("");percent.setText("");
 }
 public void setCancelDownload(Runnable action){cancelDownload=action;}
 public void downloading(long done,long total){
  // Progress is valid only after preparing()/reconnecting()/restartingFromZero().
  // A queued callback from an old transfer must not replace CHECK or OFFER.
  if(done<0||state!=State.DOWNLOAD)return;
  // Ignore stale callbacks from an earlier transfer and keep progress monotonic.
  // A server may supply the final length after an initially unknown total.
  if(state==State.DOWNLOAD && total>0 && expectedBytes>0 && total!=expectedBytes)return;
  if(state==State.DOWNLOAD && done<receivedBytes)return;
   if(total>0 && done>total)return;
  receivedBytes=done;expectedBytes=total;sampleRate(done,total);
  state=State.DOWNLOAD;
  primary.setEnabled(!cancelRequested);
  primary.setText("ОТМЕНИТЬ");primary.setVisibility(cancelDownload==null?View.GONE:View.VISIBLE);
  primary.setOnClickListener(v->{if(cancelDownload!=null&&!cancelRequested){cancelRequested=true;primary.setEnabled(false);cancelDownload.run();}});
  secondary.setText("СКРЫТЬ");
  title.setText("Обновление MediaLAB");stageLabel.setText("Загрузка обновления");detail.setText("Защищённая загрузка MediaLAB");
  // Keep the progress arc determinate, with the numeric indicator synchronized below.
  ring.setChecking(false);
  progress.setIndeterminate(total<=0);
  if(numberAnimation!=null){numberAnimation.cancel();numberAnimation=null;}
  if(total<=0){
   displayedBytes=done;bytes.setText(ConvertEngine.humanBytes(done));
   percent.setText("");ring.setChecking(dialog.isShowing());return;
  }
  final long fromBytes=Math.min(displayedBytes,done);
  if(!dialog.isShowing()||!motionEnabled||!android.animation.ValueAnimator.areAnimatorsEnabled()){
   renderDownload(done,total);return;
  }
  numberAnimation=android.animation.ValueAnimator.ofFloat(0f,1f);
  numberAnimation.setDuration(170);
  numberAnimation.setInterpolator(new android.view.animation.DecelerateInterpolator());
  numberAnimation.addUpdateListener(animation->{
   long shown=fromBytes+(long)((done-fromBytes)*(float)animation.getAnimatedValue());
   renderDownload(shown,total);
  });
  numberAnimation.start();
 }
 private void renderDownload(long loaded,long total){
  displayedBytes=loaded;
  int ratio=(int)Math.min(1000d,Math.max(0d,loaded*1000d/total));
  displayedProgress=ratio;
  progress.setProgress(ratio);ring.setProgressFraction(ratio/1000f);
  percent.setText(String.format(Locale.forLanguageTag("ru"),"%.1f%%",ratio/10.0));
  bytes.setContentDescription(ConvertEngine.humanBytes(loaded)+" / "+ConvertEngine.humanBytes(total));
  bytes.setText(ConvertEngine.humanBytes(loaded)+" / "+ConvertEngine.humanBytes(total));
 }

 /** A user cancellation is not a network error. Keep the exact verified-range
   * progress visible, and offer an explicit resume action. */
 public void paused(long saved,long total,Runnable resume){
  state=State.PAUSED;resetRate();cancelRequested=false;primary.setEnabled(true);
  if(numberAnimation!=null){numberAnimation.cancel();numberAnimation=null;}
  ring.setChecking(false);progress.setIndeterminate(false);
  title.setText("Загрузка приостановлена");
  stageLabel.setText("Загрузка остановлена");
  detail.setText("Скачанная часть APK сохранена. Можно продолжить с места остановки.");
  receivedBytes=Math.max(0,saved);expectedBytes=total;
  if(total>0&&saved<=total)renderDownload(saved,total);
  else {progress.setProgress(0);ring.setProgressFraction(0f);bytes.setText(ConvertEngine.humanBytes(saved));percent.setText("");}
  secondary.setText("ПОЗЖЕ");primary.setText("ПРОДОЛЖИТЬ");
  primary.setVisibility(resume==null?View.GONE:View.VISIBLE);
  primary.setOnClickListener(v->{
   if(resume==null||state!=State.PAUSED)return;
   // Explicitly leave PAUSED before the first resumed Range callback arrives.
   // Otherwise downloading() rejects every progress event from the resumed request.
   // Keep the saved byte count and ring while the Range request reconnects.
   reconnecting(saved,total);
   try {
    resume.run();
   } catch (RuntimeException failure) {
    // A synchronous start failure must not strand the dialog in DOWNLOAD
    // with a nonfunctional cancel action. Restore the resumable PAUSED UI.
    paused(saved,total,resume);
    throw failure;
   }
  });
  constrainWindowHeight();
 }
 /** Keep persisted partial progress visible until the server confirms HTTP Range. */
 public void reconnecting(long saved,long total){
  state=State.DOWNLOAD;resetRate();cancelRequested=false;
  if(numberAnimation!=null){numberAnimation.cancel();numberAnimation=null;}
  receivedBytes=Math.max(0,saved);expectedBytes=total;displayedBytes=receivedBytes;
  title.setText("Обновление MediaLAB");
  stageLabel.setText("Возобновление загрузки…");
  detail.setText("Подключение к серверу и проверка сохранённого диапазона");
  ring.setChecking(false);progress.setIndeterminate(total<=0);
  if(total>0&&saved<=total)renderDownload(saved,total);
  else {bytes.setText(ConvertEngine.humanBytes(saved));percent.setText("");}
  primary.setText("ОТМЕНИТЬ");primary.setEnabled(true);
  primary.setVisibility(cancelDownload==null?View.GONE:View.VISIBLE);
  primary.setOnClickListener(v->{if(cancelDownload!=null&&!cancelRequested){cancelRequested=true;primary.setEnabled(false);cancelDownload.run();}});
  secondary.setText("СКРЫТЬ");
 }
 /** The server ignored Range (HTTP 200), so the saved partial file is being
  * replaced from byte zero. Reset the old snapshot before fresh progress arrives. */
 public void restartingFromZero(long total){
  state=State.DOWNLOAD;resetRate();cancelRequested=false;
  if(numberAnimation!=null){numberAnimation.cancel();numberAnimation=null;}
  receivedBytes=0L;displayedBytes=0L;expectedBytes=total;displayedProgress=0;
  ring.setChecking(false);ring.setProgressFraction(0f);
  progress.setIndeterminate(total<=0);progress.setProgress(0);
  bytes.setText(total>0?"0 Б / "+ConvertEngine.humanBytes(total):"0 Б");
  percent.setText(total>0?"0.0%":"");
  title.setText("Обновление MediaLAB");
  stageLabel.setText("Повторная загрузка с начала");
  detail.setText("Сервер не поддержал продолжение. Сохранённый диапазон заменяется.");
  primary.setEnabled(true);primary.setText("ОТМЕНИТЬ");
  primary.setVisibility(cancelDownload==null?View.GONE:View.VISIBLE);
  primary.setOnClickListener(v->{if(cancelDownload!=null&&!cancelRequested){cancelRequested=true;primary.setEnabled(false);cancelDownload.run();}});
  secondary.setText("СКРЫТЬ");
 }
 public void ready(String version,Runnable install){
  state=State.READY;resetRate();cancelRequested=false;primary.setEnabled(true);
  title.setText("Обновление загружено");stageLabel.setText("Готово к установке");detail.setText("MediaLAB "+version+" · APK проверен");
  if(numberAnimation!=null){numberAnimation.cancel();numberAnimation=null;}
  ring.setChecking(false);progress.setIndeterminate(false);progress.setProgress(1000);ring.setProgressFraction(1f);
  displayedProgress=1000;
  percent.setText("100%");bytes.setText("");secondary.setText("ПОЗЖЕ");primary.setText("УСТАНОВИТЬ");primary.setVisibility(View.VISIBLE);
  primary.setOnClickListener(v->{
   if(state!=State.READY||!primary.isEnabled()||install==null)return;
   primary.setEnabled(false);
   try{install.run();}catch(RuntimeException error){primary.setEnabled(true);throw error;}
  });
 }
 public void current(String version){
  state=State.CURRENT;resetRate();cancelRequested=false;
  title.setText("MediaLAB обновлён");stageLabel.setText("Установлена актуальная версия");detail.setText(version);
  if(numberAnimation!=null){numberAnimation.cancel();numberAnimation=null;}
  displayedProgress=1000;
  ring.setChecking(false);
  progress.setIndeterminate(false);progress.setProgress(1000);ring.setProgressFraction(1f);
  primary.setVisibility(View.GONE);history.setVisibility(View.GONE);constrainWindowHeight();secondary.setText("ЗАКРЫТЬ");
  bytes.setText("");percent.setText("");
 }
 public void fail(String message,Runnable retry){
  state=State.ERROR;resetRate();cancelRequested=false;primary.setEnabled(true);
  title.setText("Обновление не выполнено");stageLabel.setText("Не удалось обновить MediaLAB");detail.setText(message);
  displayedProgress=0;
  ring.setChecking(false);progress.setIndeterminate(false);progress.setProgress(0);ring.setProgressFraction(0f);
  if(numberAnimation!=null){numberAnimation.cancel();numberAnimation=null;}
  progress.setProgress(0);ring.setProgressFraction(0f);
  bytes.setText("");percent.setText("");secondary.setText("ЗАКРЫТЬ");
  primary.setText("ПОВТОРИТЬ");primary.setVisibility(retry==null?View.GONE:View.VISIBLE);
  primary.setOnClickListener(v->{
   // A retry can enqueue a network task before the next state callback arrives.
   // Dispatch it once, even under rapid taps or a delayed main-thread update.
   if(state!=State.ERROR||!primary.isEnabled()||retry==null)return;
   primary.setEnabled(false);
   try{retry.run();}catch(RuntimeException error){primary.setEnabled(true);throw error;}
  });
 }
 /** Transfer rate excludes cached/resumed bytes and uses a monotonic clock. */
 private void sampleRate(long done,long total){
  long now=android.os.SystemClock.elapsedRealtime();
  if(rateBytes<0||done<rateBytes||now<rateTime){
   rateBytes=done;rateTime=now;rate=0d;transferInfo.setVisibility(View.GONE);return;
  }
  long elapsed=now-rateTime;if(elapsed<700)return;
  long delta=done-rateBytes;rateBytes=done;rateTime=now;
  if(delta<=0)return;
  double instant=delta*1000d/elapsed;
  if(!Double.isFinite(instant)||instant<=0)return;
  rate=rate<=0?instant:rate*.65d+instant*.35d;
  String message=ConvertEngine.humanBytes((long)rate)+"/с";
  if(total>done&&total>0){
   long seconds=(long)Math.ceil((total-done)/rate);
   if(seconds>0&&seconds<=86400){
    long minutes=seconds/60;
    message+=" · осталось ≈ "+(minutes>0?minutes+" мин ":"")+(seconds%60)+" с";
   }
  }
  transferInfo.setText(message);transferInfo.setVisibility(View.VISIBLE);
 }
 private void resetRate(){
  rateBytes=-1L;rateTime=0L;rate=0d;transferInfo.setText("");transferInfo.setVisibility(View.GONE);
 }
 private static CharSequence formatNotes(String input,boolean light){
  android.text.SpannableStringBuilder result=new android.text.SpannableStringBuilder();
  if(input==null)return result;
  for(String line:input.split("\\n",-1)){
   if(result.length()>0)result.append("\n");
   int start=result.length();
   java.util.regex.Matcher matcher=java.util.regex.Pattern.compile("\\*\\*(.+?)\\*\\*").matcher(line);
   int cursor=0;
   while(matcher.find()){
    result.append(line.substring(cursor,matcher.start()));
    int begin=result.length();
    result.append(matcher.group(1));
    result.setSpan(new android.text.style.StyleSpan(Typeface.BOLD),begin,result.length(),33);
    cursor=matcher.end();
   }
   result.append(line.substring(cursor));
   String shown=result.subSequence(start,result.length()).toString();
   int tint=shown.startsWith("[+]")?(light?0xff087443:0xff4dd69c):shown.startsWith("[-]")?(light?0xffb52d36:0xfff07878):shown.startsWith("[=]")?(light?0xff995300:0xffffaa4d):0;
   if(tint!=0){
    result.setSpan(new android.text.style.ForegroundColorSpan(tint),start,start+3,33);
    result.setSpan(new android.text.style.StyleSpan(Typeface.BOLD),start,start+3,33);
   }else if(line.startsWith("Версия ")){
    result.setSpan(new android.text.style.StyleSpan(Typeface.BOLD),start,start+shown.length(),33);
   }
  }
  return result;
 }

}
