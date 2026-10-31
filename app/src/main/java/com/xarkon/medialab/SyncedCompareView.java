package com.xarkon.medialab;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.widget.FrameLayout;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * Live interactive curtain over ONE timestamp-matched packed video.
 * The left and right images are sampled from the same decoded frame.
 * Video decoder -> SurfaceTexture -> GLES external texture, no per-frame bitmap copies.
 */
public final class SyncedCompareView extends FrameLayout {
    public interface Ready { void ready(Surface surface); }
    private final PictureRenderer renderer = new PictureRenderer();
    private final GLSurfaceView display;
    private final SliderOverlay overlay;
    private Surface playbackSurface;
    private Ready callback;
    private volatile boolean closed;

    public SyncedCompareView(Context context) {
        super(context);
        setBackgroundColor(Color.BLACK);
        display = new GLSurfaceView(context);
        display.setEGLContextClientVersion(2);
        display.setPreserveEGLContextOnPause(true);
        display.setRenderer(renderer);
        display.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
        overlay = new SliderOverlay(context);
        addView(display, new LayoutParams(-1,-1));
        addView(overlay, new LayoutParams(-1,-1));
        overlay.onSplit = value -> { renderer.split = value; display.requestRender(); };
    }
    public void onReady(Ready listener) {
        callback = listener;
        if (playbackSurface != null && listener != null) listener.ready(playbackSurface);
    }
    /** Width is the combined frame width. A displayed half has width/2. */
    public void updateSize(int packedW,int packedH) {
        if (packedW < 2 || packedH < 1) return;
        float halfAspect = (packedW / 2f) / packedH;
        renderer.aspect = halfAspect;
        overlay.aspect = halfAspect;
        overlay.invalidate();
        display.requestRender();
    }
    public void pauseGL() { display.onPause(); }
    public void resumeGL() { display.onResume(); }
    public void close() {
        closed = true;
        callback = null;
        try { if (playbackSurface != null) playbackSurface.release(); } catch (Exception ignored) { }
        playbackSurface = null;
        // SurfaceTexture owns the GL-backed texture: renderer releases it on GL thread.
        try { display.queueEvent(renderer::release); } catch (Exception ignored) { }
    }
    private static FloatBuffer floatBuffer(float[] values) {
        FloatBuffer out = ByteBuffer.allocateDirect(values.length*4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        out.put(values).position(0); return out;
    }
    private final class PictureRenderer implements GLSurfaceView.Renderer {
        private final FloatBuffer vertices = floatBuffer(new float[]{-1,-1, 1,-1, -1,1, 1,1});
        private final FloatBuffer texcoords = floatBuffer(new float[]{0,0, 1,0, 0,1, 1,1});
        private final float[] textureMatrix = new float[16];
        private final AtomicBoolean hasFrame = new AtomicBoolean(false);
        private SurfaceTexture texture;
        private int program, textureId, positionLoc, coordsLoc, matrixLoc, splitLoc;
        private volatile float split=0.5f, aspect=16f/9f;
        private int surfaceW, surfaceH;
        @Override public void onSurfaceCreated(GL10 ignored, EGLConfig config) {
            GLES20.glClearColor(0,0,0,1);
            String vertex = "attribute vec4 p; attribute vec2 t; varying vec2 uv; " +
                    "void main(){gl_Position=p; uv=t;}";
            String fragment = "#extension GL_OES_EGL_image_external : require\n" +
                    "precision highp float; varying vec2 uv; uniform samplerExternalOES video; " +
                    "uniform mat4 st; uniform float cut; " +
                    "void main(){ float px = (uv.x + step(cut,uv.x)) * 0.5; " +
                    "vec4 loc=st * vec4(px,uv.y,0.0,1.0); gl_FragColor=texture2D(video,loc.xy); }";
            program=GLES20.glCreateProgram();
            int vs=shader(GLES20.GL_VERTEX_SHADER,vertex), fs=shader(GLES20.GL_FRAGMENT_SHADER,fragment);
            GLES20.glAttachShader(program,vs); GLES20.glAttachShader(program,fs);
            GLES20.glLinkProgram(program);
            GLES20.glDeleteShader(vs); GLES20.glDeleteShader(fs);
            int[] linked = new int[1]; GLES20.glGetProgramiv(program,GLES20.GL_LINK_STATUS,linked,0);
            if (linked[0] != GLES20.GL_TRUE) throw new IllegalStateException("Shader link: " + GLES20.glGetProgramInfoLog(program));
            positionLoc=GLES20.glGetAttribLocation(program,"p");
            coordsLoc=GLES20.glGetAttribLocation(program,"t");
            matrixLoc=GLES20.glGetUniformLocation(program,"st");
            splitLoc=GLES20.glGetUniformLocation(program,"cut");
            int[] ids=new int[1]; GLES20.glGenTextures(1,ids,0); textureId=ids[0];
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,textureId);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE);
            texture = new SurfaceTexture(textureId);
            Matrix.setIdentityM(textureMatrix,0);
            texture.setOnFrameAvailableListener(st -> { hasFrame.set(true); if (!closed) display.requestRender(); });
            Surface surf = new Surface(texture);
            post(() -> {
                if (closed) { surf.release(); return; }
                if (playbackSurface != null) playbackSurface.release();
                playbackSurface = surf;
                if (callback != null) callback.ready(surf);
            });
        }
        private int shader(int type,String code) {
            int shader=GLES20.glCreateShader(type);
            GLES20.glShaderSource(shader,code); GLES20.glCompileShader(shader);
            int[] ok=new int[1]; GLES20.glGetShaderiv(shader,GLES20.GL_COMPILE_STATUS,ok,0);
            if(ok[0]!=GLES20.GL_TRUE) throw new IllegalStateException("Shader: "+GLES20.glGetShaderInfoLog(shader));
            return shader;
        }
        @Override public void onSurfaceChanged(GL10 ignored,int width,int height) {
            surfaceW=width;surfaceH=height;
        }
        @Override public void onDrawFrame(GL10 ignored) {
            GLES20.glViewport(0,0,surfaceW,surfaceH); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            if (texture == null || program == 0) return;
            if (hasFrame.getAndSet(false)) {
                try { texture.updateTexImage(); texture.getTransformMatrix(textureMatrix); }
                catch (RuntimeException ignoredError) { return; }
            }
            float width=Math.min(surfaceW,surfaceH*aspect), height=Math.min(surfaceH,surfaceW/aspect);
            GLES20.glViewport(Math.round((surfaceW-width)/2f),Math.round((surfaceH-height)/2f),Math.round(width),Math.round(height));
            GLES20.glUseProgram(program);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,textureId);
            GLES20.glUniformMatrix4fv(matrixLoc,1,false,textureMatrix,0);
            GLES20.glUniform1f(splitLoc,split);
            GLES20.glEnableVertexAttribArray(positionLoc); GLES20.glEnableVertexAttribArray(coordsLoc);
            GLES20.glVertexAttribPointer(positionLoc,2,GLES20.GL_FLOAT,false,0,vertices);
            GLES20.glVertexAttribPointer(coordsLoc,2,GLES20.GL_FLOAT,false,0,texcoords);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4);
            GLES20.glDisableVertexAttribArray(positionLoc); GLES20.glDisableVertexAttribArray(coordsLoc);
        }
        private void release() {
            if (texture != null) { texture.release(); texture=null; }
            if (textureId != 0) { int[] t={textureId}; GLES20.glDeleteTextures(1,t,0); textureId=0; }
            if (program != 0) { GLES20.glDeleteProgram(program); program=0; }
        }
    }
    private static final class SliderOverlay extends View {
        interface SplitListener { void changed(float value); }
        SplitListener onSplit;
        float aspect=16f/9f;
        float split=0.5f;
        final int accent;
        final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        final RectF r=new RectF();
        SliderOverlay(Context context) { super(context);
            accent=QrLook.accent(context.getSharedPreferences("MainActivity",Context.MODE_PRIVATE).getInt("accent",0));
            setContentDescription("Сравнение оригинала и результата, перемещайте разделитель"); }
        float dp(float n) { return n*getResources().getDisplayMetrics().density; }
        @Override protected void onDraw(Canvas c) {
            float w=Math.min(getWidth(),getHeight()*aspect), h=w/aspect;
            r.set((getWidth()-w)/2,(getHeight()-h)/2,(getWidth()+w)/2,(getHeight()+h)/2);
            float x=r.left+r.width()*split;
            p.setColor(accent);p.setStrokeWidth(dp(2));c.drawLine(x,r.top,x,r.bottom,p);
            p.setShadowLayer(dp(8),0,dp(1),0x77000000);
            c.drawCircle(x,r.centerY(),dp(21),p);p.clearShadowLayer();
            p.setColor(0xff162745);p.setStrokeWidth(dp(3));
            c.drawLine(x-dp(10),r.centerY(),x-dp(4),r.centerY(),p);
            c.drawLine(x+dp(4),r.centerY(),x+dp(10),r.centerY(),p);
            p.setTextSize(dp(12));p.setTypeface(android.graphics.Typeface.create("sans-serif-medium",0));
            p.setColor(Color.WHITE);p.setShadowLayer(dp(4),0,dp(2),Color.BLACK);
            c.drawText("ДО",r.left+dp(10),r.top+dp(22),p);
            c.drawText("ПОСЛЕ",r.right-dp(63),r.top+dp(22),p);p.clearShadowLayer();
        }
        @Override public boolean onTouchEvent(MotionEvent e) {
            if(e.getActionMasked()==MotionEvent.ACTION_DOWN || e.getActionMasked()==MotionEvent.ACTION_MOVE) {
                if(r.width()>0) {
                    split=Math.max(0,Math.min(1,(e.getX()-r.left)/r.width()));
                    if(onSplit!=null) onSplit.changed(split);
                    invalidate();
                }
                return true;
            }
            if(e.getActionMasked()==MotionEvent.ACTION_UP) { performClick();return true; }
            return true;
        }
        @Override public boolean performClick() { super.performClick();return true; }
    }
}
