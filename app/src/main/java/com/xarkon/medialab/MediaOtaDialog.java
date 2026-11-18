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
 private final TextView title,subtitle,stageLabel,detail,bytes,percent,notes,primary,secondary;
 private final ProgressBar progress;
 private final OtaRingView ring;
 private final ScrollView history;
 private final int accent, themeMode;
 private android.animation.ValueAnimator numberAnimation;
 private int displayedProgress=0;
 private long receivedBytes=0L;
 private long expectedBytes=-1L;
 private long displayedBytes=0L;
 private enum State { CHECK, OFFER, DOWNLOAD, VERIFY, READY, CURRENT, ERROR }
 private State state=State.CHECK;
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
 public MediaOtaDialog(Activity a,int tint){
  activity=a;accent=tint;int mode=a.getPreferences(0).getInt("theme",0);
  themeMode=mode;boolean light=mode==2;int fg=light?0xff182633:Color.WHITE;
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
  history.setVerticalScrollBarEnabled(false);history.addView(notes);LinearLayout.LayoutParams hlp=new LinearLayout.LayoutParams(-1,dp(172));
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
  LinearLayout actions=new LinearLayout(a);actions.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);
  LinearLayout.LayoutParams alp=new LinearLayout.LayoutParams(-1,-2);alp.topMargin=dp(24);
  root.addView(actions,alp);
  secondary=label("ЗАКРЫТЬ",12,muted);secondary.setTypeface(null,Typeface.BOLD);secondary.setGravity(Gravity.CENTER);secondary.setPadding(dp(16),dp(14),dp(16),dp(14));
  secondary.setOnClickListener(v->dialog.dismiss());actions.addView(secondary);
  primary=label("ОБНОВИТЬ",12,Color.BLACK);primary.setTypeface(null,Typeface.BOLD);
  primary.setGravity(Gravity.CENTER);primary.setPadding(dp(16),dp(13),dp(16),dp(13));primary.setBackground(bg(tint,0,13));
  LinearLayout.LayoutParams blp=new LinearLayout.LayoutParams(-2,-2);blp.leftMargin=dp(8);
  actions.addView(primary,blp);primary.setVisibility(View.GONE);
  // Keep the progress, notes and actions in one custom, theme-aware dialog.
  ScrollView viewport=new ScrollView(a);
  viewport.setFillViewport(false);
  viewport.setVerticalScrollBarEnabled(false);
  viewport.addView(root,new ScrollView.LayoutParams(-1,-2));
  dialog.setContentView(viewport);
  dialog.setOnDismissListener(ignored->{
   if(numberAnimation!=null){numberAnimation.cancel();numberAnimation=null;}
   ring.setChecking(false);
  });
 }
 public void show(){
  if(dialog.isShowing())return;
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
  // Keep actions reachable on short screens and at large font scales.
  final int maxHeight=Math.max(dp(120),activity.getResources().getDisplayMetrics().heightPixels-dp(96));
  View content=w.findViewById(android.R.id.content);
  if(content!=null)content.post(()->{
   if(dialog.isShowing()&&content.getHeight()>maxHeight)w.setLayout(p.width,maxHeight);
  });
 }
 public boolean matchesAppearance(int tint,int mode){return accent==tint&&themeMode==mode;}
 public void dismiss(){dialog.dismiss();}
 public boolean isShowing(){return dialog.isShowing();}
 public boolean needsRefresh(){return state==State.CURRENT||state==State.ERROR;}
 public void stage(String message,String hint){
  state=(message.toLowerCase(new Locale("ru")).contains("провер") && (message.toLowerCase(new Locale("ru")).contains("apk") || message.toLowerCase(new Locale("ru")).contains("подпис") || message.toLowerCase(new Locale("ru")).contains("sha"))) ? State.VERIFY : State.CHECK;
  title.setText("Обновление MediaLAB");stageLabel.setText(message);detail.setText(hint);ring.setChecking(dialog.isShowing());
  receivedBytes=0L;expectedBytes=-1L;displayedBytes=0L;
  progress.setIndeterminate(true);secondary.setText("СКРЫТЬ");
  primary.setVisibility(View.GONE);bytes.setText("");percent.setText("");
  if(numberAnimation!=null){numberAnimation.cancel();numberAnimation=null;}displayedProgress=0;
  progress.setProgress(0);ring.setProgressFraction(0f);
 }
 public void setHistory(String changes){
  notes.setText(formatNotes(changes));history.setVisibility(changes==null||changes.trim().isEmpty()?View.GONE:View.VISIBLE);history.scrollTo(0,0);
 }
 public void offer(String version,String changes,Runnable action){
  state=State.OFFER;
  title.setText("Доступно обновление");stageLabel.setText("MediaLAB "+version);detail.setText("Новая версия готова к загрузке");
  if(numberAnimation!=null){numberAnimation.cancel();numberAnimation=null;}
  displayedProgress=0;receivedBytes=0L;expectedBytes=-1L;displayedBytes=0L;
  ring.setChecking(false);progress.setIndeterminate(false);progress.setProgress(0);ring.setProgressFraction(0f);
  setHistory(changes);
  primary.setText("ОБНОВИТЬ");primary.setVisibility(View.VISIBLE);
  primary.setOnClickListener(v->action.run());secondary.setText("ПОЗЖЕ");
  bytes.setText("");percent.setText("");
 }
 public void downloading(long done,long total){
  if(done<0||state==State.VERIFY||state==State.READY||state==State.ERROR||state==State.CURRENT)return;
  // Ignore stale callbacks from an earlier transfer and keep progress monotonic.
  if(state==State.DOWNLOAD && total>0 && expectedBytes>0 && total!=expectedBytes)return;
  if(state==State.DOWNLOAD && done<receivedBytes)return;
  receivedBytes=done;expectedBytes=total;
  state=State.DOWNLOAD;
  primary.setVisibility(View.GONE);secondary.setText("СКРЫТЬ");
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
  if(!dialog.isShowing()||!android.animation.ValueAnimator.areAnimatorsEnabled()){
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
  percent.setText(ratio/10+"%");
  bytes.setText(ConvertEngine.humanBytes(loaded)+" / "+ConvertEngine.humanBytes(total));
 }

 public void ready(String version,Runnable install){
  state=State.READY;
  title.setText("Обновление загружено");stageLabel.setText("Готово к установке");detail.setText("MediaLAB "+version+" · APK проверен");
  if(numberAnimation!=null){numberAnimation.cancel();numberAnimation=null;}
  ring.setChecking(false);progress.setIndeterminate(false);progress.setProgress(1000);ring.setProgressFraction(1f);
  displayedProgress=1000;
  percent.setText("100%");bytes.setText("");secondary.setText("ПОЗЖЕ");primary.setText("УСТАНОВИТЬ");primary.setVisibility(View.VISIBLE);
  primary.setOnClickListener(v->install.run());
 }
 public void current(String version){
  state=State.CURRENT;
  title.setText("MediaLAB обновлён");stageLabel.setText("Установлена актуальная версия");detail.setText(version);
  if(numberAnimation!=null){numberAnimation.cancel();numberAnimation=null;}
  displayedProgress=1000;
  ring.setChecking(false);
  progress.setIndeterminate(false);progress.setProgress(1000);ring.setProgressFraction(1f);
  primary.setVisibility(View.GONE);history.setVisibility(View.GONE);secondary.setText("ЗАКРЫТЬ");
  bytes.setText("");percent.setText("");
 }
 public void fail(String message,Runnable retry){
  state=State.ERROR;
  title.setText("Обновление не выполнено");stageLabel.setText("Не удалось обновить MediaLAB");detail.setText(message);
  displayedProgress=0;
  ring.setChecking(false);progress.setIndeterminate(false);progress.setProgress(0);ring.setProgressFraction(0f);
  if(numberAnimation!=null){numberAnimation.cancel();numberAnimation=null;}
  progress.setProgress(0);ring.setProgressFraction(0f);
  bytes.setText("");percent.setText("");secondary.setText("ЗАКРЫТЬ");
  primary.setText("ПОВТОРИТЬ");primary.setVisibility(retry==null?View.GONE:View.VISIBLE);
  primary.setOnClickListener(v->{if(retry!=null)retry.run();});
 }
 private static CharSequence formatNotes(String input){
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
   int tint=shown.startsWith("[+]")?0xff4dd69c:shown.startsWith("[-]")?0xfff07878:shown.startsWith("[=]")?0xffffaa4d:0;
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
