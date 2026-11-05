package com.xarkon.medialab;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/** Lightweight OTA ring with an explicit determinate sector. */
public final class OtaRingView extends View {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int accent;
    private float progress=0f;
    public OtaRingView(Context context,int color){
        super(context);accent=color;
        setContentDescription("Индикатор загрузки обновления");
    }
    public void setProgressFraction(float value){
        progress=Math.max(0f,Math.min(1f,value));invalidate();
    }
    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);
        float density=getResources().getDisplayMetrics().density;
        float width=4f*density;
        float cx=getWidth()/2f,cy=getHeight()/2f;
        float radius=Math.max(0f,Math.min(cx,cy)-width);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeWidth(width);
        paint.setColor((accent&0x00ffffff)|0x33000000);
        canvas.drawCircle(cx,cy,radius,paint);
        paint.setColor(accent);
        canvas.drawArc(cx-radius,cy-radius,cx+radius,cy+radius,-90,360f*progress,false,paint);
    }
}
