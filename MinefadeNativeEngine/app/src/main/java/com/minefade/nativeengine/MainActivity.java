package com.minefade.nativeengine;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

/** Native engine host. Rendering and game systems are implemented in C++20. */
public final class MainActivity extends Activity {
    private MinefadeSurfaceView surfaceView;
    private SoundEffects soundEffects;
    private final Handler exitHandler = new Handler(Looper.getMainLooper());
    private final Runnable exitPoll = new Runnable() {
        @Override public void run() {
            if (NativeBridge.nativeConsumeExitRequest()) {
                finish();
                return;
            }
            exitHandler.postDelayed(this, 200L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(0xFF111923);
        getWindow().setNavigationBarColor(0xFF0B1118);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
                View.SYSTEM_UI_FLAG_FULLSCREEN |
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);

        NativeBridge.nativeSetStoragePath(getFilesDir().getAbsolutePath());
        soundEffects = new SoundEffects(this);
        surfaceView = new MinefadeSurfaceView(this, soundEffects,
                getResources().getDisplayMetrics().density);
        setContentView(surfaceView);
    }

    @Override
    protected void onPause() {
        exitHandler.removeCallbacks(exitPoll);
        if (surfaceView != null) {
            surfaceView.pauseNativeEngine();
            surfaceView.onPause();
        }
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (surfaceView != null) {
            surfaceView.onResume();
            surfaceView.resumeNativeEngine();
            exitHandler.post(exitPoll);
        }
    }

    @Override
    protected void onDestroy() {
        exitHandler.removeCallbacks(exitPoll);
        if (soundEffects != null) soundEffects.release();
        super.onDestroy();
    }
}
