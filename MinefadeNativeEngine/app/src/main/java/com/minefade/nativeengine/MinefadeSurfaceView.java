package com.minefade.nativeengine;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.util.Log;
import android.view.MotionEvent;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Owns EGL ES 3 and serialises native input/render calls on the GL thread. */
public final class MinefadeSurfaceView extends GLSurfaceView {
    public interface InitializationListener {
        void onEngineReady();
        void onEngineError(String message);
    }

    private static final String TAG = "Minefade";
    private static final int ATLAS_WIDTH = 576;
    private static final int ATLAS_HEIGHT = 522;
    private static final int ATLAS_TILE_COUNT = 918;
    private volatile boolean nativeReady;
    private final InitializationListener initializationListener;
    private final SharedPreferences preferences;

    public MinefadeSurfaceView(Context context, SoundEffects soundEffects, float displayDensity,
                               boolean multiplayerMode, int playerSlot, String storagePath,
                               LanMultiplayerService multiplayer, InitializationListener listener) {
        super(context);
        initializationListener = listener;
        preferences = context.getSharedPreferences("minefade_ui", Context.MODE_PRIVATE);
        setEGLContextClientVersion(3);
        setEGLConfigChooser(8, 8, 8, 8, 16, 0);
        setPreserveEGLContextOnPause(true);
        setRenderer(new EngineRenderer(context, soundEffects, displayDensity,
                multiplayerMode, playerSlot, storagePath, multiplayer));
        setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
        setFocusable(true);
        setFocusableInTouchMode(true);
    }

    private void notifyReady() {
        if (initializationListener != null) post(initializationListener::onEngineReady);
    }

    private void notifyError(String message) {
        if (initializationListener != null) {
            post(() -> initializationListener.onEngineError(message));
        }
    }

    /** Pause and save on the GL thread; Engine/world state is not thread-safe. */
    public void pauseNativeEngine() {
        if (!nativeReady) return;
        final CountDownLatch completed = new CountDownLatch(1);
        queueEvent(() -> {
            try {
                NativeBridge.nativePause();
            } catch (LinkageError | RuntimeException error) {
                Log.e(TAG, "Could not pause native engine", error);
            } finally {
                completed.countDown();
            }
        });
        try {
            if (!completed.await(5, TimeUnit.SECONDS)) {
                Log.w(TAG, "Timed out waiting for native save on GL thread");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            Log.w(TAG, "Interrupted while waiting for native pause", interrupted);
        }
    }

    /** Resume on the same GL thread used by render, resize and touch callbacks. */
    public void resumeNativeEngine() {
        if (nativeReady) {
            queueEvent(() -> {
                try {
                    NativeBridge.nativeResume();
                } catch (LinkageError | RuntimeException error) {
                    Log.e(TAG, "Could not resume native engine", error);
                    nativeReady = false;
                    notifyError("No se pudo reanudar el motor gráfico. Vuelve a intentarlo.");
                }
            });
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!nativeReady) return true;
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
        queueEvent(() -> {
            if (nativeReady) NativeBridge.nativeTouch(nativeAction, pointerId, x, y);
        });
        return true;
    }

    private static byte[] readBlockAtlas(AssetManager assets) {
        final int expected = ATLAS_WIDTH * ATLAS_HEIGHT * 4;
        try (InputStream input = assets.open("textures/block_atlas.rgba");
             ByteArrayOutputStream output = new ByteArrayOutputStream(expected)) {
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
            byte[] result = output.toByteArray();
            if (result.length == expected) return result;
            Log.e(TAG, "Block atlas has an invalid size: " + result.length + "; using fallback atlas");
        } catch (IOException error) {
            Log.e(TAG, "Block atlas load failed; using fallback atlas", error);
        }
        return createFallbackBlockAtlas();
    }

    /**
     * A deterministic emergency atlas. The game can still start if an optional
     * texture asset is absent or damaged; the visible colours make the failure
     * recoverable instead of leaving the player on a black screen.
     */
    private static byte[] createFallbackBlockAtlas() {
        final int width = ATLAS_WIDTH, height = ATLAS_HEIGHT, tile = 16, cell = 18, columns = 32;
        byte[] atlas = new byte[width * height * 4];
        for (int index = 0; index < ATLAS_TILE_COUNT; index++) {
            int block = index / 3;
            int face = index % 3;
            int hue = block % 8;
            int[][] palettes = {
                    {104, 145, 79}, {126, 130, 136}, {145, 103, 68}, {190, 159, 92},
                    {68, 153, 177}, {155, 91, 67}, {105, 101, 157}, {148, 158, 161}
            };
            int[] base = palettes[hue];
            int shade = face == 0 ? 12 : (face == 2 ? -22 : 0);
            int ox = (index % columns) * cell + 1;
            int oy = (index / columns) * cell + 1;
            for (int y = 0; y < tile; y++) {
                for (int x = 0; x < tile; x++) {
                    int grain = ((x * 7 + y * 11 + block * 13) % 13) - 6;
                    int at = ((oy + y) * width + ox + x) * 4;
                    atlas[at] = (byte) clamp(base[0] + shade + grain);
                    atlas[at + 1] = (byte) clamp(base[1] + shade + grain);
                    atlas[at + 2] = (byte) clamp(base[2] + shade + grain);
                    atlas[at + 3] = (byte) 255;
                }
            }
        }
        return atlas;
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }

    private final class EngineRenderer implements GLSurfaceView.Renderer {
        private final SoundEffects soundEffects;
        private final float displayDensity;
        private final AssetManager assetManager;
        private final boolean multiplayerMode;
        private final int playerSlot;
        private final String storagePath;
        private final LanMultiplayerService multiplayer;

        EngineRenderer(Context context, SoundEffects soundEffects, float displayDensity,
                       boolean multiplayerMode, int playerSlot, String storagePath,
                       LanMultiplayerService multiplayer) {
            assetManager = context.getAssets();
            this.soundEffects = soundEffects;
            this.displayDensity = displayDensity;
            this.multiplayerMode = multiplayerMode;
            this.playerSlot = Math.max(0, Math.min(31, playerSlot));
            this.storagePath = storagePath;
            this.multiplayer = multiplayer;
        }

        @Override
        public void onSurfaceCreated(javax.microedition.khronos.opengles.GL10 gl,
                                     javax.microedition.khronos.egl.EGLConfig config) {
            nativeReady = false;
            GLES20.glClearColor(0.035f, 0.055f, 0.08f, 1.0f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            try {
                byte[] atlas = readBlockAtlas(assetManager);
                // Set mode and storage on the same GL thread as nativeInit/render.
                NativeBridge.nativeSetMultiplayerMode(multiplayerMode, playerSlot);
                NativeBridge.nativeSetStoragePath(storagePath);
                int quality = Math.max(0, Math.min(2, preferences.getInt("graphics_quality", 1)));
                int renderDistance = Math.max(1, Math.min(3,
                        preferences.getInt("render_distance", 1) + 1));
                boolean waterWaves = preferences.getBoolean("water_waves", true);
                float sensitivity = preferences.getInt("touch_sensitivity", 100) / 100.0f;
                boolean invertLookY = preferences.getBoolean("invert_look_y", false);
                NativeBridge.nativeSetGraphicsOptions(quality, renderDistance, waterWaves);
                NativeBridge.nativeSetInputOptions(sensitivity, invertLookY);
                nativeReady = NativeBridge.nativeInit(displayDensity, atlas, ATLAS_WIDTH, ATLAS_HEIGHT);
                if (nativeReady) {
                    Log.i(TAG, "Native renderer initialised successfully");
                    notifyReady();
                } else {
                    final String glVersion = GLES20.glGetString(GLES20.GL_VERSION);
                    final String glRenderer = GLES20.glGetString(GLES20.GL_RENDERER);
                    Log.e(TAG, "Native engine initialisation returned false; GL_VERSION="
                            + glVersion + "; GL_RENDERER=" + glRenderer);
                    notifyError("El motor gráfico no pudo iniciarse.\nOpenGL ES: "
                            + (glVersion != null ? glVersion : "no disponible")
                            + "\nGPU: " + (glRenderer != null ? glRenderer : "no identificada")
                            + "\nComprueba la compatibilidad con OpenGL ES 3.0 y vuelve a intentarlo.");
                }
            } catch (LinkageError | RuntimeException error) {
                nativeReady = false;
                Log.e(TAG, "Native engine is unavailable", error);
                notifyError("No se pudo cargar el motor nativo: " + error.getClass().getSimpleName());
            }
        }

        @Override
        public void onSurfaceChanged(javax.microedition.khronos.opengles.GL10 gl, int width, int height) {
            if (!nativeReady) return;
            try {
                NativeBridge.nativeResize(width, height);
            } catch (LinkageError | RuntimeException error) {
                nativeReady = false;
                Log.e(TAG, "Native resize failed", error);
                notifyError("Falló la configuración de la pantalla. Vuelve a intentarlo.");
            }
        }

        @Override
        public void onDrawFrame(javax.microedition.khronos.opengles.GL10 gl) {
            if (!nativeReady) {
                // Always paint a known colour while the Java loading/error surface is visible.
                GLES20.glClearColor(0.035f, 0.055f, 0.08f, 1.0f);
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
                return;
            }
            try {
                if (multiplayer != null && multiplayer.isConnected()) {
                    int[] incomingEdits = multiplayer.drainRemoteBlockEdits();
                    if (incomingEdits.length > 0) NativeBridge.nativeApplyRemoteBlockEdits(incomingEdits);
                    NativeBridge.nativeSetRemotePlayers(multiplayer.getRemotePlayerSnapshot());
                } else if (multiplayer != null) {
                    NativeBridge.nativeSetRemotePlayers(new float[0]);
                    // Discard edits while the peer is disconnected, preventing an unbounded queue.
                    NativeBridge.nativeConsumeBlockEdits();
                }
                NativeBridge.nativeRender();
                if (NativeBridge.nativeConsumeEngineFailure()) {
                    nativeReady = false;
                    Log.e(TAG, "Native engine reported a recoverable render failure");
                    notifyError("El motor encontró un error al actualizar el mundo.\nLa sesión se detuvo de forma segura para evitar un cierre inesperado. Vuelve al menú e inténtalo otra vez.");
                    return;
                }
                if (multiplayer != null && multiplayer.isConnected()) {
                    multiplayer.publishLocalState(NativeBridge.nativeGetPlayerState());
                    int[] localEdits = NativeBridge.nativeConsumeBlockEdits();
                    if (localEdits.length > 0) multiplayer.publishBlockEdits(localEdits);
                }
                if (soundEffects != null)
                    soundEffects.playEvents(NativeBridge.nativeConsumeSoundEvents());
            } catch (LinkageError | RuntimeException error) {
                nativeReady = false;
                Log.e(TAG, "Native render failed", error);
                notifyError("El renderizador dejó de responder. Vuelve al menú y reintenta.");
            }
        }
    }
}
