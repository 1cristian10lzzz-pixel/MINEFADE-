package com.minefade.nativeengine;

import android.content.Context;
import android.content.res.AssetManager;
import android.opengl.GLSurfaceView;
import android.view.MotionEvent;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Owns EGL ES 3 and serialises native input/render calls on the GL thread. */
public final class MinefadeSurfaceView extends GLSurfaceView {
    private volatile boolean nativeReady;
    public MinefadeSurfaceView(Context context, SoundEffects soundEffects, float displayDensity) {
        super(context);
        setEGLContextClientVersion(3);
        setEGLConfigChooser(8, 8, 8, 8, 16, 0);
        setPreserveEGLContextOnPause(true);
        setRenderer(new EngineRenderer(context, soundEffects, displayDensity));
        setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
        setFocusable(true);
        setFocusableInTouchMode(true);
    }

    /** Pause and save on the GL thread; Engine/world state is not thread-safe. */
    public void pauseNativeEngine() {
        if (!nativeReady) return;
        final CountDownLatch completed = new CountDownLatch(1);
        queueEvent(() -> {
            try {
                NativeBridge.nativePause();
            } finally {
                completed.countDown();
            }
        });
        try {
            if (!completed.await(5, TimeUnit.SECONDS)) {
                android.util.Log.w("Minefade", "Timed out waiting for native save on GL thread");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            android.util.Log.w("Minefade", "Interrupted while waiting for native pause", interrupted);
        }
    }

    /** Resume on the same GL thread used by render, resize and touch callbacks. */
    public void resumeNativeEngine() {
        if (nativeReady) queueEvent(NativeBridge::nativeResume);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        final int action = event.getActionMasked();
        final int actionIndex = event.getActionIndex();
        if (action == MotionEvent.ACTION_MOVE) {
            for (int i = 0; i < event.getPointerCount(); ++i) {
                final int pointerId = event.getPointerId(i);
                final float x = event.getX(i);
                final float y = event.getY(i);
                queueEvent(() -> NativeBridge.nativeTouch(2, pointerId, x, y));
            }
            return true;
        }

        final int pointerId = event.getPointerId(actionIndex);
        final float x = event.getX(actionIndex);
        final float y = event.getY(actionIndex);
        final int nativeAction;
        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
                nativeAction = 0;
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
                nativeAction = 1;
                break;
            case MotionEvent.ACTION_CANCEL:
                nativeAction = 3;
                break;
            default:
                return true;
        }
        queueEvent(() -> NativeBridge.nativeTouch(nativeAction, pointerId, x, y));
        return true;
    }

    private static byte[] readBlockAtlas(AssetManager assets) {
        try (InputStream input = assets.open("textures/block_atlas.rgba");
             ByteArrayOutputStream output = new ByteArrayOutputStream(900_000)) {
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
            byte[] result = output.toByteArray();
            return result.length == 576 * 486 * 4 ? result : null;
        } catch (IOException error) {
            android.util.Log.e("Minefade", "Block atlas load failed", error);
            return null;
        }
    }

    private final class EngineRenderer implements GLSurfaceView.Renderer {
        private final SoundEffects soundEffects;
        private final float displayDensity;
        private final AssetManager assetManager;
        EngineRenderer(Context context, SoundEffects soundEffects, float displayDensity) {
            this.assetManager = context.getAssets();
            this.soundEffects = soundEffects;
            this.displayDensity = displayDensity;
        }

        @Override
        public void onSurfaceCreated(javax.microedition.khronos.opengles.GL10 gl,
                                     javax.microedition.khronos.egl.EGLConfig config) {
            byte[] atlas = readBlockAtlas(assetManager);
            if (atlas == null) {
                android.util.Log.e("Minefade", "Could not read textures/block_atlas.rgba");
                return;
            }
            nativeReady = NativeBridge.nativeInit(displayDensity, atlas, 576, 486);
        }

        @Override
        public void onSurfaceChanged(javax.microedition.khronos.opengles.GL10 gl, int width, int height) {
            NativeBridge.nativeResize(width, height);
        }

        @Override
        public void onDrawFrame(javax.microedition.khronos.opengles.GL10 gl) {
            NativeBridge.nativeRender();
            if (soundEffects != null) soundEffects.playEvents(NativeBridge.nativeConsumeSoundEvents());
        }
    }
}
