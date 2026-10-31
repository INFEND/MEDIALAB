package com.xarkon.medialab;
// MediaLAB v1.2.5: CLEAN REPLACEMENT FILE. Do not append previous versions.

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/** Fixed 1:1 geometry for still images; the slider is relative to the picture, not the screen. */
public final class BeforeAfterView extends View {
    private Bitmap original, converted;
    private float split = 0.5f;
    private final int accent;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint marker = new Paint(Paint.ANTI_ALIAS_FLAG);
    private RectF frame = new RectF();
    public BeforeAfterView(Context context) {
        super(context);
        accent=QrLook.accent(context.getSharedPreferences("MainActivity",Context.MODE_PRIVATE).getInt("accent",0));
    }
    public void setImages(Bitmap left, Bitmap right) { original = left; converted = right; invalidate(); }
    public void setSplit(float value) { split = Math.max(0, Math.min(1, value)); invalidate(); }
    @Override protected void onDraw(Canvas c) {
        c.drawColor(Color.BLACK);
        int width=getWidth(), height=getHeight();
        if (width<=0 || height<=0) return;
        if (original==null || converted==null) {
            marker.setColor(0xffc1cadc); marker.setTextSize(dp(15));
            c.drawText("Подготовка сравнения…",dp(20),height/2f,marker); return;
        }
        float ratio=Math.min(width/(float)original.getWidth(),height/(float)original.getHeight());
        float w=original.getWidth()*ratio, h=original.getHeight()*ratio;
        frame.set((width-w)/2f,(height-h)/2f,(width+w)/2f,(height+h)/2f);
        float x=frame.left+frame.width()*split;
        c.save(); c.clipRect(frame.left,frame.top,x,frame.bottom); c.drawBitmap(original,null,frame,paint); c.restore();
        c.save(); c.clipRect(x,frame.top,frame.right,frame.bottom); c.drawBitmap(converted,null,frame,paint); c.restore();
        drawHandle(c,frame,x);
    }
    private void drawHandle(Canvas c,RectF box,float x) {
        marker.setColor(accent); marker.setStrokeWidth(dp(2));
        c.drawLine(x,box.top,x,box.bottom,marker);
        c.drawCircle(x,box.centerY(),dp(20),marker);
        marker.setColor(0xff10203b); marker.setStrokeWidth(dp(2.5f));
        c.drawLine(x-dp(7),box.centerY(),x-dp(2),box.centerY(),marker);
        c.drawLine(x+dp(2),box.centerY(),x+dp(7),box.centerY(),marker);
        marker.setTextSize(dp(11)); marker.setColor(Color.WHITE);
        marker.setShadowLayer(dp(3),0,dp(1),Color.BLACK);
        c.drawText("ДО",box.left+dp(10),box.top+dp(19),marker);
        c.drawText("ПОСЛЕ",box.right-dp(53),box.top+dp(19),marker);
        marker.clearShadowLayer();
    }
    private float dp(float p) { return p*getResources().getDisplayMetrics().density; }
    @Override public boolean onTouchEvent(MotionEvent e) {
        switch(e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: case MotionEvent.ACTION_MOVE:
                if(frame.width()>0) setSplit((e.getX()-frame.left)/frame.width());
                return true;
            case MotionEvent.ACTION_UP: performClick(); return true;
            default: return true;
        }
    }
    @Override public boolean performClick() { super.performClick(); return true; }
}
// MediaLAB v1.2.5 clean source: BeforeAfterView.java END
