package com.minefade.nativeengine;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.net.Uri;
import android.app.AlertDialog;
import android.text.InputType;
import android.widget.EditText;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Activity lifecycle and native engine host.
 *
 * The home screen is shown before loading any native library or creating EGL.
 * This prevents a missing GL resource/library from presenting as a blank screen.
 */
public final class MainActivity extends Activity {
    private static final long ENGINE_TIMEOUT_MS = 18000L;

    private FrameLayout root;
    private MinefadeHomeScreen homeScreen;
    private MinefadeSurfaceView surfaceView;
    private SoundEffects soundEffects;
    private LanMultiplayerService lanMultiplayer;
    private FrameLayout loadingOverlay;
    private FrameLayout gameContainer;
    private WorldManager worldManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean engineReady;
    private boolean activityResumed;
    private boolean requestedMultiplayer;
    private int requestedPlayerSlot;
    private final Runnable exitPoll = new Runnable() {
        @Override public void run() {
            if (surfaceView == null || isFinishing()) return;
            try {
                if (NativeBridge.nativeConsumeExitRequest()) {
                    finish();
                    return;
                }
            } catch (LinkageError | RuntimeException error) {
                android.util.Log.e("Minefade", "Exit polling failed", error);
            }
            mainHandler.postDelayed(this, 200L);
        }
    };
    private final Runnable engineTimeout = () -> {
        if (!engineReady && surfaceView != null) {
            showEngineError("No se pudo completar el arranque del mundo.",
                    "Android no terminó de crear la superficie gráfica dentro del tiempo esperado. Comprueba que el dispositivo admite OpenGL ES 3.0 y que hay memoria disponible. Puedes reintentar; tus mundos no se eliminan.");
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setBackgroundDrawable(new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{0xff0b1726, 0xff10283a, 0xff0b1422}));
        getWindow().setStatusBarColor(0xff0b1726);
        getWindow().setNavigationBarColor(0xff0b1118);
        applyImmersiveMode();

        root = new FrameLayout(this);
        root.setBackgroundColor(0xff0b1726);
        setContentView(root);
        soundEffects = new SoundEffects(this);
        worldManager = new WorldManager(this);
        lanMultiplayer = new LanMultiplayerService(this);

        // Deliberately do not touch NativeBridge here. The menu must still work
        // if the native library is missing on a device or an ABI is unsupported.
        showHome();
    }

    private void applyImmersiveMode() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
                View.SYSTEM_UI_FLAG_FULLSCREEN |
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    private void showHome() {
        mainHandler.removeCallbacks(engineTimeout);
        mainHandler.removeCallbacks(exitPoll);
        if (surfaceView != null) {
            try {
                surfaceView.pauseNativeEngine();
                surfaceView.onPause();
            } catch (RuntimeException error) {
                android.util.Log.w("Minefade", "Could not pause surface while returning home", error);
            }
        }
        engineReady = false;
        loadingOverlay = null;
        surfaceView = null;
        gameContainer = null;
        root.removeAllViews();

        if (lanMultiplayer != null) {
            lanMultiplayer.close();
            lanMultiplayer = new LanMultiplayerService(this);
        }
        homeScreen = new MinefadeHomeScreen(this, new MinefadeHomeScreen.Listener() {
            @Override public void onPlay() {
                java.util.List<WorldManager.WorldInfo> worlds = worldManager.listWorlds();
                if (worlds.isEmpty()) {
                    WorldManager.WorldInfo created = worldManager.createWorld("Mi mundo", System.currentTimeMillis(),
                            WorldManager.SURVIVAL, false, true);
                    if (created != null) startWorld(created);
                } else startWorld(worlds.get(0));
            }
            @Override public void onPlayWorld(WorldManager.WorldInfo world) { startWorld(world); }
            @Override public void onCreateWorld(WorldManager.WorldInfo world) { startWorld(world); }
            @Override public void onImportMod() { pickModFile(); }
            @Override public void onHostMultiplayer() { startLanHost(); }
            @Override public void onSearchMultiplayer() { searchLanGames(); }
            @Override public void onJoinMultiplayer(LanMultiplayerService.GameInfo game) { joinLanGame(game); }
        }, this::playMenuClick);
        root.addView(homeScreen, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        applyImmersiveMode();
    }

    private void playMenuClick() {
        if (soundEffects != null) soundEffects.playMenuClick();
    }

    private void startGame() {
        startGame(false, 0);
    }

    private void startWorld(WorldManager.WorldInfo world) {
        if (world == null) return;
        worldManager.markPlayed(world);
        startGame(false, 0, world);
    }

    private void startGame(boolean multiplayer, int playerSlot) {
        startGame(multiplayer, playerSlot, null);
    }

    private void startGame(boolean multiplayer, int playerSlot, WorldManager.WorldInfo world) {
        mainHandler.removeCallbacks(engineTimeout);
        mainHandler.removeCallbacks(exitPoll);
        if (surfaceView != null) {
            try {
                surfaceView.pauseNativeEngine();
                surfaceView.onPause();
            } catch (RuntimeException ignored) { }
        }
        root.removeAllViews();
        homeScreen = null;
        surfaceView = null;
        loadingOverlay = null;
        engineReady = false;

        showLoading("PREPARANDO MINEFADE", "Comprobando el motor gráfico y los recursos…");

        requestedMultiplayer = multiplayer;
        requestedPlayerSlot = Math.max(0, Math.min(31, playerSlot));

        // Native setup now happens on the GLSurfaceView thread immediately before
        // nativeInit. This keeps all Engine/JNI state changes serialised with GL.
        String worldPath = world == null ? getFilesDir().getAbsolutePath() : world.getPath();
        long worldSeed = world == null ? 0x4D464445L : world.seed;
        int gameMode = world == null ? WorldManager.SURVIVAL : world.gameMode;
        boolean allowCheats = world != null && world.allowCheats;
        boolean starterChest = world != null && world.starterChest;
        String modsPath = new java.io.File(getFilesDir(), "mods").getAbsolutePath();
        surfaceView = new MinefadeSurfaceView(this, soundEffects,
                getResources().getDisplayMetrics().density,
                multiplayer, requestedPlayerSlot, worldPath, modsPath, worldSeed,
                gameMode, allowCheats, starterChest,
                multiplayer ? lanMultiplayer : null,
                new MinefadeSurfaceView.InitializationListener() {
                    @Override public void onEngineReady() {
                        engineReady = true;
                        mainHandler.removeCallbacks(engineTimeout);
                        mainHandler.postDelayed(exitPoll, 200L);
                        hideLoadingOverlay();
                    }

                    @Override public void onEngineError(String message) {
                        engineReady = false;
                        showEngineError("No se pudo iniciar el mundo.", message);
                    }
                });
        gameContainer = new FrameLayout(this);
        gameContainer.addView(surfaceView, new FrameLayout.LayoutParams(-1, -1));
        TextView commandButton = statusButton("⌘  COMANDOS", false);
        FrameLayout.LayoutParams commandParams = new FrameLayout.LayoutParams(dp(126), dp(34),
                Gravity.TOP | Gravity.LEFT);
        commandParams.leftMargin = dp(10);
        commandParams.topMargin = dp(10);
        gameContainer.addView(commandButton, commandParams);
        commandButton.setOnClickListener(v -> showCommandDialog());
        root.addView(gameContainer, new FrameLayout.LayoutParams(-1, -1));
        // The GL view is created lazily after Activity.onResume(). Activate it now:
        // otherwise GLSurfaceView remains paused and onSurfaceCreated() never runs.
        if (activityResumed) {
            surfaceView.onResume();
            android.util.Log.i("Minefade", "Activated newly-created GL surface");
        }
        if (loadingOverlay != null) root.bringChildToFront(loadingOverlay);
        mainHandler.postDelayed(engineTimeout, ENGINE_TIMEOUT_MS);
        applyImmersiveMode();
    }

    private void showCommandDialog() {
        if (surfaceView == null || !engineReady) {
            Toast.makeText(this, "El mundo todavía se está preparando.", Toast.LENGTH_SHORT).show();
            return;
        }
        EditText command = new EditText(this);
        command.setSingleLine(true);
        command.setText("/");
        command.setSelection(command.length());
        command.setTextColor(Color.WHITE);
        command.setHintTextColor(0xff9fb4c1);
        command.setHint("/oveja");
        command.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        new AlertDialog.Builder(this)
                .setTitle("Comandos de Minefade")
                .setMessage("Prueba /oveja, /vaca, /pollo o /ayuda")
                .setView(command)
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Ejecutar", (dialog, which) -> {
                    String result = surfaceView == null ? "No hay un mundo activo."
                            : surfaceView.executeCommand(command.getText().toString());
                    Toast.makeText(this, result, Toast.LENGTH_LONG).show();
                }).show();
    }

    private void pickModFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, 7401);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != 7401 || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        ModManager manager = new ModManager(this);
        ModManager.ModInfo imported = manager.importMod(data.getData());
        if (imported == null) {
            Toast.makeText(this, "No se pudo importar. Usa un paquete .mfmod válido.", Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(this, "Mod importado: " + imported.name, Toast.LENGTH_LONG).show();
            if (homeScreen != null) homeScreen.refreshMods();
        }
    }

    private LanMultiplayerService.Listener createLanListener(boolean host) {
        return new LanMultiplayerService.Listener() {
            @Override public void onStatus(String status) {
                if (homeScreen != null) homeScreen.setMultiplayerStatus(status);
            }
            @Override public void onGamesChanged(java.util.List<LanMultiplayerService.GameInfo> games) {
                if (homeScreen != null) homeScreen.setNearbyGames(games);
            }
            @Override public void onConnected(int localPlayerId) {
                if (host || localPlayerId > 0) startGame(true, localPlayerId);
            }
            @Override public void onDisconnected(String reason) {
                if (homeScreen != null) homeScreen.setMultiplayerStatus(reason);
            }
        };
    }

    private void startLanHost() {
        if (lanMultiplayer == null) return;
        lanMultiplayer.startHost("Jugador", createLanListener(true));
        if (homeScreen != null) homeScreen.setMultiplayerStatus("Creando partida y publicándola en la red local…");
    }

    private void searchLanGames() {
        if (lanMultiplayer == null) return;
        lanMultiplayer.discover(createLanListener(false));
        if (homeScreen != null) homeScreen.setNearbyGames(java.util.Collections.emptyList());
    }

    private void joinLanGame(LanMultiplayerService.GameInfo game) {
        if (lanMultiplayer == null) return;
        lanMultiplayer.join(game, "Jugador", createLanListener(false));
        if (homeScreen != null) homeScreen.setMultiplayerStatus("Conectando a " + game.name + "…");
    }

    private void showLoading(String title, String detail) {
        FrameLayout overlay = new FrameLayout(this);
        overlay.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xf20b1726, 0xf2122a3b, 0xf20b1726}));
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        card.setPadding(dp(24), dp(22), dp(24), dp(22));
        GradientDrawable cardBg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xff1c3548, 0xff13283a});
        cardBg.setCornerRadius(dp(20));
        cardBg.setStroke(dp(1), 0x77658da1);
        card.setBackground(cardBg);
        card.setElevation(dp(8));

        ProgressBar spinner = new ProgressBar(this);
        spinner.setIndeterminate(true);
        LinearLayout.LayoutParams spinnerParams = new LinearLayout.LayoutParams(dp(38), dp(38));
        spinnerParams.bottomMargin = dp(12);
        card.addView(spinner, spinnerParams);

        TextView titleView = statusText(title, 14, 0xffffffff, true);
        titleView.setGravity(Gravity.CENTER);
        card.addView(titleView);
        TextView detailView = statusText(detail, 10, 0xffb5c8d3, false);
        detailView.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(-1, -2);
        detailParams.topMargin = dp(7);
        card.addView(detailView, detailParams);

        FrameLayout.LayoutParams cardParams = new FrameLayout.LayoutParams(
                Math.min(getResources().getDisplayMetrics().widthPixels - dp(40), dp(420)),
                -2, Gravity.CENTER);
        overlay.addView(card, cardParams);
        loadingOverlay = overlay;
        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        root.bringChildToFront(overlay);
    }

    private void showEngineError(String title, String detail) {
        mainHandler.removeCallbacks(engineTimeout);
        if (loadingOverlay == null) showLoading(title, detail);
        loadingOverlay.removeAllViews();
        loadingOverlay.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xf20b1726, 0xf2122a3b, 0xf20b1726}));

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        card.setPadding(dp(22), dp(20), dp(22), dp(18));
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xff23384a, 0xff15283a});
        bg.setCornerRadius(dp(19));
        bg.setStroke(dp(1), 0xffa56e65);
        card.setBackground(bg);
        card.setElevation(dp(8));

        TextView symbol = statusText("!", 29, 0xffffb19a, true);
        symbol.setGravity(Gravity.CENTER);
        card.addView(symbol);
        TextView heading = statusText(title, 15, Color.WHITE, true);
        heading.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-1, -2);
        hp.topMargin = dp(7);
        card.addView(heading, hp);
        TextView message = statusText(detail, 10, 0xffc4d1d9, false);
        message.setGravity(Gravity.CENTER);
        message.setTextIsSelectable(true);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(-1, -2);
        mp.topMargin = dp(8);
        card.addView(message, mp);

        TextView copyDiagnostics = statusButton("COPIAR DIAGNÓSTICO", false);
        LinearLayout.LayoutParams copyParams = new LinearLayout.LayoutParams(-1, dp(34));
        copyParams.topMargin = dp(9);
        card.addView(copyDiagnostics, copyParams);
        copyDiagnostics.setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("Minefade - diagnóstico de arranque", detail));
                Toast.makeText(this, "Diagnóstico copiado", Toast.LENGTH_SHORT).show();
            }
        });

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-1, dp(42));
        ap.topMargin = dp(16);
        card.addView(actions, ap);
        TextView retry = statusButton("REINTENTAR", true);
        retry.setOnClickListener(v -> startGame(requestedMultiplayer, requestedPlayerSlot));
        actions.addView(retry, new LinearLayout.LayoutParams(0, -1, 1f));
        TextView back = statusButton("VOLVER", false);
        back.setOnClickListener(v -> showHome());
        LinearLayout.LayoutParams backParams = new LinearLayout.LayoutParams(0, -1, 1f);
        backParams.leftMargin = dp(8);
        actions.addView(back, backParams);

        FrameLayout.LayoutParams cardParams = new FrameLayout.LayoutParams(
                Math.min(getResources().getDisplayMetrics().widthPixels - dp(36), dp(440)),
                -2, Gravity.CENTER);
        loadingOverlay.addView(card, cardParams);
        root.bringChildToFront(loadingOverlay);
    }

    private TextView statusText(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(android.graphics.Typeface.create("sans-serif",
                bold ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL));
        view.setIncludeFontPadding(false);
        return view;
    }

    private TextView statusButton(String value, boolean primary) {
        TextView view = statusText(value, 10, primary ? 0xff112219 : 0xffedf4f7, true);
        view.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                primary ? new int[]{0xffb8f27b, 0xff81d15b}
                        : new int[]{0xff314b60, 0xff20384a});
        bg.setCornerRadius(dp(10));
        bg.setStroke(dp(1), primary ? 0xffd3ffac : 0xff55738a);
        view.setBackground(bg);
        return view;
    }

    private void hideLoadingOverlay() {
        if (loadingOverlay == null) return;
        FrameLayout old = loadingOverlay;
        loadingOverlay = null;
        old.animate().alpha(0f).setDuration(220L).withEndAction(() -> {
            if (old.getParent() == root) root.removeView(old);
        }).start();
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onBackPressed() {
        if (surfaceView != null) {
            showHome();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onPause() {
        activityResumed = false;
        mainHandler.removeCallbacks(exitPoll);
        mainHandler.removeCallbacks(engineTimeout);
        if (surfaceView != null) {
            surfaceView.pauseNativeEngine();
            surfaceView.onPause();
        }
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        activityResumed = true;
        applyImmersiveMode();
        if (surfaceView != null) {
            surfaceView.onResume();
            surfaceView.resumeNativeEngine();
            if (engineReady) mainHandler.post(exitPoll);
        }
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacks(exitPoll);
        mainHandler.removeCallbacks(engineTimeout);
        if (lanMultiplayer != null) lanMultiplayer.close();
        if (soundEffects != null) soundEffects.release();
        super.onDestroy();
    }
}
