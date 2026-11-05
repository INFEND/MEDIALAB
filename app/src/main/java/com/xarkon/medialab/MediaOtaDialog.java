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
 private final ProgressBar spinner,progress;
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
  spinner=new ProgressBar(a);spinner.setIndeterminateTintList(ColorStateList.valueOf(tint));
  LinearLayout.LayoutParams spinLp=new LinearLayout.LayoutParams(dp(39),dp(39));spinLp.rightMargin=dp(15);
  header.addView(spinner,spinLp);
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
  title.setText(message);detail.setText(hint);spinner.setVisibility(View.VISIBLE);
  progress.setIndeterminate(true);history.setVisibility(View.GONE);
  primary.setVisibility(View.GONE);bytes.setText("");percent.setText("");
  if(numberAnimation!=null)numberAnimation.cancel();displayedProgress=0;
 }
 public void offer(String version,String changes,Runnable action){
  title.setText("Доступно обновление");detail.setText("MediaLAB "+version);
  spinner.setVisibility(View.GONE);progress.setIndeterminate(false);progress.setProgress(0);
  notes.setText(changes==null?"":changes);history.setVisibility(changes==null||changes.isEmpty()?View.GONE:View.VISIBLE);
  primary.setText("ОБНОВИТЬ");primary.setVisibility(View.VISIBLE);
  primary.setOnClickListener(v->action.run());secondary.setText("ПОЗЖЕ");
  bytes.setText("");percent.setText("");
 }
 public void downloading(long done,long total){
  title.setText("Загрузка обновления");detail.setText("MediaLAB");
  history.setVisibility(View.GONE);spinner.setVisibility(View.VISIBLE);
  progress.setIndeterminate(total<=0);
  bytes.setText(String.format(Locale.US,"%.1f / %.1f МБ",done/1048576d,total/1048576d));
  if(total>0){int value=(int)Math.min(1000,done*1000/total);
   if(numberAnimation!=null)numberAnimation.cancel();
   numberAnimation=android.animation.ValueAnimator.ofInt(displayedProgress,value);
   numberAnimation.setDuration(170);
   numberAnimation.setInterpolator(new android.view.animation.DecelerateInterpolator());
   numberAnimation.addUpdateListener(animation->{
    int shown=(int)animation.getAnimatedValue();
    progress.setProgress(shown);percent.setText(shown/10+"%");
   });
   numberAnimation.start();displayedProgress=value;}
 }
 public void ready(String version,Runnable install){
  title.setText("Обновление загружено");detail.setText("MediaLAB "+version+" · APK проверен");
  spinner.setVisibility(View.GONE);progress.setIndeterminate(false);progress.setProgress(1000);
  if(numberAnimation!=null)numberAnimation.cancel();displayedProgress=1000;
  percent.setText("100%");bytes.setText("");history.setVisibility(View.GONE);
  primary.setText("УСТАНОВИТЬ");primary.setVisibility(View.VISIBLE);
  primary.setOnClickListener(v->install.run());
 }
 public void current(String version){
  title.setText("Установлена актуальная версия");detail.setText(version);
  spinner.setVisibility(View.GONE);progress.setIndeterminate(false);progress.setProgress(1000);
  primary.setVisibility(View.GONE);history.setVisibility(View.GONE);
  bytes.setText("");percent.setText("");
 }
 public void fail(String message,Runnable retry){
  title.setText("Ошибка обновления");detail.setText(message);
  spinner.setVisibility(View.GONE);progress.setIndeterminate(false);progress.setProgress(0);
  if(numberAnimation!=null)numberAnimation.cancel();
  history.setVisibility(View.GONE);bytes.setText("");percent.setText("");
  primary.setText("ПОВТОРИТЬ");primary.setVisibility(retry==null?View.GONE:View.VISIBLE);
  primary.setOnClickListener(v->{if(retry!=null)retry.run();});
 }
}
