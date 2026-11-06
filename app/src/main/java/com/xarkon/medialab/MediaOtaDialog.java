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
 private final TextView title,detail,bytes,percent,notes,primary,secondary;
 private final ProgressBar progress;
 private final OtaRingView ring;
 private final ScrollView history;
 private final int accent;
 private android.animation.ValueAnimator numberAnimation;
 private int displayedProgress=0;
 private int dp(int n){return (int)(n*activity.getResources().getDisplayMetrics().density+.5f);}
 private GradientDrawable bg(int fill,int border){
  GradientDrawable d=new GradientDrawable();d.setColor(fill);d.setCornerRadius(dp(19));
  if(border!=0)d.setStroke(dp(1),border);return d;
 }
 private TextView label(String s,int size,int color){
  TextView v=new TextView(activity);v.setText(s);v.setTextColor(color);
  v.setTextSize(size);return v;
 }
 public MediaOtaDialog(Activity a,int tint){
  activity=a;accent=tint;int mode=a.getPreferences(0).getInt("theme",0);
  boolean light=mode==2;int fg=light?0xff182633:Color.WHITE;
  int muted=light?0xff607589:0xffa4b4c3;
  dialog=new Dialog(a);dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
  LinearLayout root=new LinearLayout(a);root.setOrientation(1);
  root.setPadding(dp(22),dp(22),dp(22),dp(20));
  root.setBackground(bg(light?0xfff9fbfd:mode==1?0xff101012:0xff15212d,light?0xffdce3ec:0xff304050));
  LinearLayout header=new LinearLayout(a);header.setGravity(Gravity.CENTER_VERTICAL);
  LinearLayout.LayoutParams spinLp=new LinearLayout.LayoutParams(dp(39),dp(39));spinLp.rightMargin=dp(15);
  FrameLayout ringFrame=new FrameLayout(a);
  ring=new OtaRingView(a,tint);
  ringFrame.addView(ring,new FrameLayout.LayoutParams(dp(39),dp(39),Gravity.CENTER));
  header.addView(ringFrame,spinLp);
  title=label("Обновление MediaLAB",20,fg);title.setTypeface(null,Typeface.BOLD);
  header.addView(title);root.addView(header);
  detail=label("Подключение к серверу…",13,muted);
  LinearLayout.LayoutParams dlp=new LinearLayout.LayoutParams(-1,-2);dlp.topMargin=dp(16);
  root.addView(detail,dlp);
  history=new ScrollView(a);history.setBackground(bg(light?0xffeff3f8:0xff0d141c,0));
  notes=label("",13,fg);notes.setPadding(dp(12),dp(12),dp(12),dp(12));notes.setLineSpacing(dp(3),1f);
  history.addView(notes);LinearLayout.LayoutParams hlp=new LinearLayout.LayoutParams(-1,dp(185));
  hlp.topMargin=dp(14);root.addView(history,hlp);history.setVisibility(View.GONE);
  LinearLayout numbers=new LinearLayout(a);
  LinearLayout.LayoutParams nlp=new LinearLayout.LayoutParams(-1,-2);nlp.topMargin=dp(17);
  root.addView(numbers,nlp);
  bytes=label("",12,muted);numbers.addView(bytes,new LinearLayout.LayoutParams(0,-2,1));
  percent=label("",18,tint);percent.setTypeface(null,Typeface.BOLD);numbers.addView(percent);
  progress=new ProgressBar(a,null,android.R.attr.progressBarStyleHorizontal);
  progress.setMax(1000);progress.setIndeterminate(true);
  progress.setProgressTintList(ColorStateList.valueOf(tint));
  progress.setIndeterminateTintList(ColorStateList.valueOf(tint));
  LinearLayout.LayoutParams plp=new LinearLayout.LayoutParams(-1,dp(6));plp.topMargin=dp(10);
  root.addView(progress,plp);
  LinearLayout actions=new LinearLayout(a);actions.setGravity(Gravity.END);
  LinearLayout.LayoutParams alp=new LinearLayout.LayoutParams(-1,-2);alp.topMargin=dp(20);
  root.addView(actions,alp);
  secondary=label("ЗАКРЫТЬ",12,muted);secondary.setPadding(dp(12),dp(12),dp(12),dp(12));
  secondary.setOnClickListener(v->dialog.dismiss());actions.addView(secondary);
  primary=label("ОБНОВИТЬ",12,Color.BLACK);primary.setTypeface(null,Typeface.BOLD);
  primary.setPadding(dp(14),dp(12),dp(14),dp(12));primary.setBackground(bg(tint,0));
  LinearLayout.LayoutParams blp=new LinearLayout.LayoutParams(-2,-2);blp.leftMargin=dp(9);
  actions.addView(primary,blp);primary.setVisibility(View.GONE);
  dialog.setContentView(root);
  dialog.setOnDismissListener(ignored->{
   if(numberAnimation!=null){numberAnimation.cancel();numberAnimation=null;}
   ring.setChecking(false);
  });
 }
 public void show(){
  dialog.show();Window w=dialog.getWindow();if(w==null)return;
  w.setBackgroundDrawableResource(android.R.color.transparent);
  w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
  WindowManager.LayoutParams p=w.getAttributes();p.dimAmount=.6f;
  p.width=Math.min(activity.getResources().getDisplayMetrics().widthPixels-dp(30),dp(460));
  p.height=-2;w.setAttributes(p);
 }
 public boolean isShowing(){return dialog.isShowing();}
 public void stage(String message,String hint){
  title.setText(message);detail.setText(hint);ring.setChecking(true);
  progress.setIndeterminate(true);history.setVisibility(View.GONE);secondary.setText("СКРЫТЬ");
  primary.setVisibility(View.GONE);bytes.setText("");percent.setText("");
  if(numberAnimation!=null){numberAnimation.cancel();numberAnimation=null;}displayedProgress=0;
  progress.setProgress(0);ring.setProgressFraction(0f);
 }
 public void offer(String version,String changes,Runnable action){
  title.setText("Доступно обновление");detail.setText("MediaLAB "+version);
  if(numberAnimation!=null){numberAnimation.cancel();numberAnimation=null;}
  displayedProgress=0;
  ring.setChecking(false);progress.setIndeterminate(false);progress.setProgress(0);ring.setProgressFraction(0f);
  notes.setText(formatNotes(changes));history.setVisibility(changes==null||changes.trim().isEmpty()?View.GONE:View.VISIBLE);history.scrollTo(0,0);
  primary.setText("ОБНОВИТЬ");primary.setVisibility(View.VISIBLE);
  primary.setOnClickListener(v->action.run());secondary.setText("ПОЗЖЕ");
  bytes.setText("");percent.setText("");
 }
 public void downloading(long done,long total){
  title.setText("Загрузка обновления");detail.setText("Защищённая загрузка MediaLAB");
  ring.setChecking(false);
  progress.setIndeterminate(total<=0);
  bytes.setText(String.format(Locale.US,"%.1f / %.1f МБ",done/1048576d,total/1048576d));
  if(total>0){int value=(int)Math.min(1000,done*1000/total);
   if(numberAnimation!=null){numberAnimation.cancel();numberAnimation=null;}
   int from=progress.getProgress();
   numberAnimation=android.animation.ValueAnimator.ofInt(from,value);
   numberAnimation.setDuration(170);
   numberAnimation.setInterpolator(new android.view.animation.DecelerateInterpolator());
   numberAnimation.addUpdateListener(animation->{
    int shown=(int)animation.getAnimatedValue();
    progress.setProgress(shown);ring.setProgressFraction(shown/1000f);percent.setText(shown/10+"%");
   });
   numberAnimation.start();displayedProgress=value;}
 }
 public void ready(String version,Runnable install){
  title.setText("Обновление загружено");detail.setText("MediaLAB "+version+" · APK проверен");
  if(numberAnimation!=null){numberAnimation.cancel();numberAnimation=null;}
  ring.setChecking(false);progress.setIndeterminate(false);progress.setProgress(1000);ring.setProgressFraction(1f);
  displayedProgress=1000;
  percent.setText("100%");bytes.setText("");history.setVisibility(View.GONE);
  secondary.setText("ПОЗЖЕ");primary.setText("УСТАНОВИТЬ");primary.setVisibility(View.VISIBLE);
  primary.setOnClickListener(v->install.run());
 }
 public void current(String version){
  title.setText("Установлена актуальная версия");detail.setText(version);
  if(numberAnimation!=null){numberAnimation.cancel();numberAnimation=null;}
  displayedProgress=1000;
  ring.setChecking(false);
  progress.setIndeterminate(false);progress.setProgress(1000);ring.setProgressFraction(1f);
  primary.setVisibility(View.GONE);history.setVisibility(View.GONE);secondary.setText("ЗАКРЫТЬ");
  bytes.setText("");percent.setText("");
 }
 public void fail(String message,Runnable retry){
  title.setText("Ошибка обновления");detail.setText(message);
  displayedProgress=0;
  ring.setChecking(false);progress.setIndeterminate(false);progress.setProgress(0);ring.setProgressFraction(0f);
  if(numberAnimation!=null){numberAnimation.cancel();numberAnimation=null;}
  progress.setProgress(0);ring.setProgressFraction(0f);
  history.setVisibility(View.GONE);bytes.setText("");percent.setText("");secondary.setText("ЗАКРЫТЬ");
  primary.setText("ПОВТОРИТЬ");primary.setVisibility(retry==null?View.GONE:View.VISIBLE);
  primary.setOnClickListener(v->{if(retry!=null)retry.run();});
 }
 private static CharSequence formatNotes(String input){
  android.text.SpannableStringBuilder result=new android.text.SpannableStringBuilder();
  if(input==null)return result;
  for(String line:input.split("\\n",-1)){
   if(result.length()>0)result.append("\n");
   int start=result.length();
   result.append(line);
   int tint=line.startsWith("[+]")?0xff4dd69c:line.startsWith("[-]")?0xfff07878:line.startsWith("[=]")?0xffffaa4d:0;
   if(tint!=0){
    result.setSpan(new android.text.style.ForegroundColorSpan(tint),start,start+3,33);
    result.setSpan(new android.text.style.StyleSpan(Typeface.BOLD),start,start+3,33);
   }else if(line.startsWith("Версия ")){
    result.setSpan(new android.text.style.StyleSpan(Typeface.BOLD),start,start+line.length(),33);
   }
  }
  return result;
 }

}
