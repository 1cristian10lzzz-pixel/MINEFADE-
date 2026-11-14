package com.minefade.nativeengine;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.LinearGradient;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.HorizontalScrollView;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Native Android front-end for Minefade.
 *
 * This view is deliberately independent of the GL engine: the home menu remains
 * visible even when the native renderer or a graphics asset fails to initialise.
 */
public final class MinefadeHomeScreen extends FrameLayout {
    public interface Listener {
        void onPlay();
        void onHostMultiplayer();
        void onSearchMultiplayer();
        void onJoinMultiplayer(LanMultiplayerService.GameInfo game);
    }

    private final Listener listener;
    private final Runnable clickSound;
    private final SharedPreferences preferences;
    private final int white = Color.rgb(241, 247, 250);
    private final int muted = Color.rgb(157, 177, 190);
    private final int panel = Color.rgb(17, 31, 45);
    private final int[] avatarStyles = new int[6];
    private final String[] avatarModules = {"piel", "cabello", "ojos", "ropa", "armadura", "accesorios"};
    private int avatarModuleIndex = 1;
    private String settingsModule = "video";
    private AvatarPreview avatarPreview;
    private TextView multiplayerStatus;
    private LinearLayout multiplayerGames;

    public MinefadeHomeScreen(Context context, Listener listener, Runnable clickSound) {
        super(context);
        this.listener = listener;
        this.clickSound = clickSound;
        preferences = context.getSharedPreferences("minefade_ui", Context.MODE_PRIVATE);
        for (int i = 0; i < avatarModules.length; i++)
            avatarStyles[i] = preferences.getInt("avatar_" + avatarModules[i], 0);
        avatarModuleIndex = Math.max(0, Math.min(5, preferences.getInt("avatar_module", 1)));
        settingsModule = preferences.getString("settings_module", "video");
        setClipChildren(false);
        setClipToPadding(false);
        addView(new Backdrop(context), new LayoutParams(-1, -1));
        showHome();
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private GradientDrawable shape(int[] colors, int radius, int strokeColor) {
        GradientDrawable drawable = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR, colors);
        drawable.setCornerRadius(dp(radius));
        boolean contrast = preferences.getBoolean("high_contrast", false);
        drawable.setStroke(dp(contrast ? 2 : 1),
                contrast ? 0xffa9c9d8 : strokeColor);
        return drawable;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(getContext());
        view.setText(value);
        float textScale = preferences.getInt("menu_text_scale", 100) / 100.0f;
        view.setTextSize(size * Math.max(0.85f, Math.min(1.25f, textScale)));
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setTypeface(Typeface.create(bold ? "sans-serif" : "sans-serif-medium",
                bold ? Typeface.BOLD : Typeface.NORMAL));
        view.setIncludeFontPadding(false);
        return view;
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(getContext());
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setClipChildren(false);
        layout.setClipToPadding(false);
        return layout;
    }

    private LinearLayout row() {
        LinearLayout layout = new LinearLayout(getContext());
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        layout.setClipChildren(false);
        layout.setClipToPadding(false);
        return layout;
    }

    private void animateIn(View view, int index) {
        if (!preferences.getBoolean("menu_animations", true)
                || preferences.getBoolean("reduce_motion", false)) {
            view.setAlpha(1f);
            return;
        }
        view.setAlpha(0f);
        view.setScaleX(0.985f);
        view.setScaleY(0.985f);
        view.setTranslationY(dp(10));
        view.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
                .setStartDelay(index * 36L).setDuration(340L)
                .setInterpolator(new DecelerateInterpolator()).start();
    }

    private void animateAvatarIn(View view, int index) {
        if (!preferences.getBoolean("avatar_transition_animations", true)) {
            view.setAlpha(1f);
            view.setScaleX(1f);
            view.setScaleY(1f);
            view.setTranslationY(0f);
            return;
        }
        animateIn(view, index);
    }

    private void haptic(View view) {
        if (clickSound != null) clickSound.run();
        if (!preferences.getBoolean("menu_haptics", true)) return;
        try {
            view.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
        } catch (RuntimeException ignored) { }
    }

    private TextView pill(String label, int accent) {
        TextView view = text(label, 10, accent, true);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(10), 0, dp(10), 0);
        view.setBackground(shape(new int[]{0x332c4658, 0x22202f40}, 18, 0x444d6979));
        return view;
    }

    private View menuButton(String icon, String title, String subtitle,
                            int[] colors, int accent, int height, Runnable click, int index) {
        FrameLayout outer = new FrameLayout(getContext());
        GradientDrawable bg = shape(colors, 15, 0x554b6478);
        if (Build.VERSION.SDK_INT >= 21) {
            outer.setBackground(new RippleDrawable(ColorStateList.valueOf(0x44ffffff),
                    bg, null));
        } else {
            outer.setBackground(bg);
        }
        outer.setElevation(dp(2));
        LinearLayout content = row();
        content.setPadding(dp(10), dp(5), dp(12), dp(5));
        outer.addView(content, new FrameLayout.LayoutParams(-1, -1));

        FrameLayout iconBox = new FrameLayout(getContext());
        iconBox.setBackground(shape(new int[]{accent | 0x55000000, accent | 0x22000000},
                11, accent | 0x66000000));
        iconBox.setContentDescription(title);
        iconBox.addView(new MenuGlyph(getContext(), index, accent),
                new FrameLayout.LayoutParams(-1, -1));
        content.addView(iconBox, new LinearLayout.LayoutParams(dp(34), dp(34)));

        LinearLayout labels = column();
        labels.setGravity(Gravity.CENTER_VERTICAL);
        labels.setPadding(dp(10), 0, 0, 0);
        TextView name = text(title, 13, white, true);
        TextView detail = text(subtitle, 9, 0xffb4c5d0, false);
        labels.addView(name);
        LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(-1, -2);
        detailParams.topMargin = dp(3);
        labels.addView(detail, detailParams);
        content.addView(labels, new LinearLayout.LayoutParams(0, -1, 1f));

        TextView arrow = text("›", 24, accent, true);
        arrow.setGravity(Gravity.CENTER);
        content.addView(arrow, new LinearLayout.LayoutParams(dp(22), -1));

        outer.setOnClickListener(v -> {
            haptic(v);
            if (click != null) click.run();
        });
        animateIn(outer, index);
        return outer;
    }

    private void showHome() {
        removeAllViews();
        addView(new Backdrop(getContext()), new LayoutParams(-1, -1));

        LinearLayout shell = column();
        shell.setPadding(dp(18), dp(11), dp(18), dp(12));
        addView(shell, new LayoutParams(-1, -1));

        LinearLayout header = row();
        TextView mark = text("◆", 23, 0xffa9ee70, true);
        header.addView(mark, new LinearLayout.LayoutParams(dp(28), dp(34)));
        LinearLayout brand = column();
        TextView name = text("MINEFADE", 18, white, true);
        TextView sub = text("VOXEL ADVENTURE", 8, 0xff8fa5b3, true);
        brand.addView(name);
        LinearLayout.LayoutParams subParams = new LinearLayout.LayoutParams(-1, -2);
        subParams.topMargin = dp(2);
        brand.addView(sub, subParams);
        header.addView(brand, new LinearLayout.LayoutParams(0, -2, 1f));
        shell.addView(header, new LinearLayout.LayoutParams(-1, dp(38)));

        LinearLayout body = row();
        LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(-1, 0, 1f);
        bodyParams.topMargin = dp(7);
        shell.addView(body, bodyParams);

        LinearLayout left = column();
        left.setPadding(dp(2), dp(2), dp(10), dp(1));
        body.addView(left, new LinearLayout.LayoutParams(0, -1, 0.94f));

        // The home actions start directly below the brand: removing the oversized
        // slogan frees space for the actual game navigation on short screens.
        List<View> buttons = new ArrayList<>();
        buttons.add(menuButton("▶", "JUGAR", "Continuar tu aventura",
                new int[]{0xff275d3b, 0xff183e35}, 0xffa9ee70, 48,
                () -> showWorlds(), 0));
        buttons.add(menuButton("◈", "AVATAR", "Aspecto y personalización",
                new int[]{0xff43345d, 0xff282943}, 0xffc3a4ff, 42,
                () -> showSection("avatar"), 1));
        buttons.add(menuButton("⌘", "MULTIJUGADOR", "Red local y servidores",
                new int[]{0xff24506a, 0xff1d344e}, 0xff74d5ee, 42,
                () -> showSection("multiplayer"), 2));
        buttons.add(menuButton("☷", "AJUSTES", "Gráficos, interfaz y controles",
                new int[]{0xff51432c, 0xff302d2c}, 0xffffce72, 42,
                () -> showSection("settings"), 3));
        buttons.add(menuButton("✦", "LOGROS", "Retos y progreso",
                new int[]{0xff4d3540, 0xff322b3d}, 0xffff9eaa, 42,
                () -> showSection("achievements"), 4));

        for (int i = 0; i < buttons.size(); i++) {
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, dp(i == 0 ? 48 : 42));
            if (i > 0) bp.topMargin = dp(5);
            left.addView(buttons.get(i), bp);
        }

        FrameLayout showcase = new FrameLayout(getContext());
        showcase.setBackground(shape(new int[]{0xff132b43, 0xff0c1727}, 20, 0x665a7b92));
        showcase.setClipToOutline(true);
        showcase.setElevation(dp(4));
        body.addView(showcase, new LinearLayout.LayoutParams(0, -1, 1.06f));

        CoverArtView cover = new CoverArtView(getContext());
        showcase.addView(cover, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout overlay = column();
        overlay.setGravity(Gravity.BOTTOM);
        overlay.setPadding(dp(14), dp(10), dp(14), dp(12));
        overlay.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xee081321, 0x00081321}));
        showcase.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        TextView feature = text("MINEFADE VOXEL", 15, white, true);
        overlay.addView(feature);
        TextView tagline = text("UN UNIVERSO HECHO DE BLOQUES", 8, 0xffc1d7e4, true);
        LinearLayout.LayoutParams taglineParams = new LinearLayout.LayoutParams(-1, -2);
        taglineParams.topMargin = dp(4);
        overlay.addView(tagline, taglineParams);
        TextView footer = text("Biomas · cuevas · montañas · mundo persistente", 9, 0xffa5bac8, false);
        LinearLayout.LayoutParams footParams = new LinearLayout.LayoutParams(-1, -2);
        footParams.topMargin = dp(9);
        overlay.addView(footer, footParams);

        // No engine/version/debug banner on the start screen; build metadata stays
        // in the APK and release notes rather than consuming game-menu space.
    }

    private void showWorlds() {
        final boolean hasSavedWorld = new File(getContext().getFilesDir(),
                "worlds/default.mfworld").isFile();
        LinearLayout root = pageRoot("JUGAR", "Continúa tu aventura o empieza una nueva.");
        LinearLayout card = column();
        card.setPadding(dp(17), dp(15), dp(17), dp(15));
        card.setBackground(shape(new int[]{0xff1b453a, 0xff122c35}, 18, 0x6676b88d));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, 0, 1f);
        cp.topMargin = dp(13);
        root.addView(card, cp);
        LinearLayout top = row();
        FrameLayout icon = new FrameLayout(getContext());
        icon.setBackground(shape(new int[]{0xff315d48, 0xff1b473c}, 13, 0x557acb91));
        icon.addView(new WorldMarkView(getContext()),
                new FrameLayout.LayoutParams(-1, -1));
        top.addView(icon, new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout identity = column();
        identity.setPadding(dp(11), 0, 0, 0);
        identity.addView(text(hasSavedWorld ? "TU PARTIDA" : "NUEVA AVENTURA", 15, white, true));
        TextView subtitle = text(hasSavedWorld ? "Retoma tu exploración" : "Una aventura nueva te espera",
                9, 0xffb7d1c5, false);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2);
        sp.topMargin = dp(4);
        identity.addView(subtitle, sp);
        top.addView(identity, new LinearLayout.LayoutParams(0, -2, 1f));
        card.addView(top);
        TextView description = text(hasSavedWorld
                ? "Vuelve a tus construcciones, descubre nuevos lugares y sigue explorando."
                : "Recorre nuevos biomas, encuentra cuevas y construye tu propio mundo.",
                10, 0xffc0d3d9, false);
        description.setGravity(Gravity.TOP);
        LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(-1, -2);
        dp.topMargin = this.dp(15);
        card.addView(description, dp);
        View spacer = new View(getContext());
        card.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1f));
        TextView enter = actionButton(hasSavedWorld ? "CONTINUAR PARTIDA" : "CREAR MUNDO Y JUGAR", () -> listener.onPlay());
        LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(-1, this.dp(46));
        ep.topMargin = this.dp(12);
        card.addView(enter, ep);
        animateIn(card, 0);
    }

    private void showSection(String section) {
        if ("avatar".equals(section)) {
            showAvatarPage();
        } else if ("settings".equals(section)) {
            showSettingsPage();
        } else if ("multiplayer".equals(section)) {
            showMultiplayerPage();
        } else {
            showAchievementsPage();
        }
    }

    private LinearLayout pageRoot(String title, String subtitle) {
        removeAllViews();
        addView(new Backdrop(getContext()), new LayoutParams(-1, -1));
        LinearLayout root = column();
        root.setPadding(dp(16), dp(10), dp(16), dp(12));
        addView(root, new LayoutParams(-1, -1));

        LinearLayout header = row();
        TextView back = text("‹", 29, white, true);
        back.setGravity(Gravity.CENTER);
        back.setBackground(shape(new int[]{0x55364f62, 0x33202d3b}, 13, 0x886b879b));
        back.setOnClickListener(v -> showHome());
        header.addView(back, new LinearLayout.LayoutParams(dp(42), dp(40)));
        LinearLayout titleBox = column();
        titleBox.setPadding(dp(10), 0, 0, 0);
        titleBox.addView(text(title, 16, white, true));
        TextView desc = text(subtitle, 8, muted, false);
        LinearLayout.LayoutParams descParams = new LinearLayout.LayoutParams(-1, -2);
        descParams.topMargin = dp(3);
        titleBox.addView(desc, descParams);
        header.addView(titleBox, new LinearLayout.LayoutParams(0, -2, 1f));
        root.addView(header, new LinearLayout.LayoutParams(-1, dp(44)));
        return root;
    }

    private void showSimplePage(String title, String subtitle, String icon, int[] colors,
                                String cardTitle, String cardDescription, String action,
                                Runnable onAction) {
        LinearLayout root = pageRoot(title, subtitle);
        FrameLayout card = new FrameLayout(getContext());
        card.setBackground(shape(colors, 19, 0x665a7b92));
        LinearLayout content = column();
        content.setPadding(dp(18), dp(16), dp(18), dp(16));
        content.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(content, new FrameLayout.LayoutParams(-1, -1));
        TextView symbol = text(icon, 26, 0xffa9ee70, true);
        content.addView(symbol);
        TextView name = text(cardTitle, 17, white, true);
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(-1, -2);
        nameParams.topMargin = dp(9);
        content.addView(name, nameParams);
        TextView details = text(cardDescription, 11, muted, false);
        LinearLayout.LayoutParams detailsParams = new LinearLayout.LayoutParams(-1, -2);
        detailsParams.topMargin = dp(5);
        content.addView(details, detailsParams);
        TextView button = actionButton(action, onAction);
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(-2, dp(42));
        buttonParams.topMargin = dp(16);
        content.addView(button, buttonParams);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(-1, 0, 1f);
        cardParams.topMargin = dp(14);
        root.addView(card, cardParams);
        animateIn(card, 0);
    }

    private TextView actionButton(String label, Runnable action) {
        TextView button = text(label + "   ›", 11, 0xff102119, true);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(18), 0, dp(18), 0);
        button.setBackground(shape(new int[]{0xffb9f27c, 0xff82d75d}, 12, 0xffd6ffad));
        button.setElevation(dp(3));
        button.setOnClickListener(v -> {
            haptic(v);
            if (action != null) action.run();
        });
        return button;
    }

    private void showAvatarPage() {
        LinearLayout root = pageRoot("AVATAR", "Elige estilo, equipo y detalles del personaje.");

        FrameLayout previewCard = new FrameLayout(getContext());
        previewCard.setBackground(shape(new int[]{0xff1c3b50, 0xff0d1a2a}, 18, 0x77668ba0));
        previewCard.setClipToOutline(true);
        LinearLayout.LayoutParams previewCardParams = new LinearLayout.LayoutParams(-1, 0, 1.08f);
        previewCardParams.topMargin = dp(8);
        root.addView(previewCard, previewCardParams);

        LinearLayout previewOverlay = column();
        previewOverlay.setPadding(dp(10), dp(8), dp(10), dp(7));
        previewOverlay.setGravity(Gravity.CENTER_HORIZONTAL);
        previewCard.addView(previewOverlay, new FrameLayout.LayoutParams(-1, -1));
        TextView previewState = text("VISTA EN TIEMPO REAL", 9, 0xffd8eaf0, true);
        previewState.setGravity(Gravity.CENTER);
        previewOverlay.addView(previewState, new LinearLayout.LayoutParams(-1, dp(19)));
        FrameLayout previewFrame = new FrameLayout(getContext());
        LinearLayout.LayoutParams avatarParams = new LinearLayout.LayoutParams(-1, 0, 1f);
        avatarParams.topMargin = dp(2);
        avatarParams.bottomMargin = dp(1);
        previewOverlay.addView(previewFrame, avatarParams);
        avatarPreview = new AvatarPreview(getContext());
        previewFrame.addView(avatarPreview, new FrameLayout.LayoutParams(-1, -1));
        TextView rotateHint = text("ARRASTRA PARA GIRAR  ·  ANIMACIÓN DE REPOSO", 8, 0xff9db7c6, true);
        rotateHint.setGravity(Gravity.CENTER);
        previewOverlay.addView(rotateHint, new LinearLayout.LayoutParams(-1, dp(18)));

        String[] moduleLabels = {"PIEL", "CABELLO", "OJOS", "ROPA", "ARMADURA", "ACCESORIOS"};
        String[] moduleIcons = {"◉", "✦", "◌", "▣", "⬟", "◇"};
        HorizontalScrollView moduleScroller = new HorizontalScrollView(getContext());
        moduleScroller.setHorizontalScrollBarEnabled(false);
        LinearLayout.LayoutParams tabsParams = new LinearLayout.LayoutParams(-1, dp(42));
        tabsParams.topMargin = dp(8);
        tabsParams.bottomMargin = dp(7);
        root.addView(moduleScroller, tabsParams);
        LinearLayout tabs = row();
        tabs.setPadding(dp(1), 0, dp(1), 0);
        moduleScroller.addView(tabs, new HorizontalScrollView.LayoutParams(-2, -1));
        for (int i = 0; i < moduleLabels.length; i++) {
            final int module = i;
            boolean selected = avatarModuleIndex == module;
            TextView tab = text(moduleIcons[i] + "  " + moduleLabels[i], 9,
                    selected ? 0xffe2ffd1 : 0xffb8cbd5, true);
            tab.setGravity(Gravity.CENTER);
            tab.setPadding(dp(12), 0, dp(12), 0);
            tab.setBackground(shape(selected
                    ? new int[]{0xff3a624e, 0xff1c403b}
                    : new int[]{0xff20374a, 0xff142638},
                    11, selected ? 0xff9be17e : 0x66516c7f));
            tab.setOnClickListener(v -> {
                haptic(v);
                avatarModuleIndex = module;
                preferences.edit().putInt("avatar_module", module).apply();
                showAvatarPage();
            });
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-2, -1);
            if (i > 0) tp.leftMargin = dp(6);
            tabs.addView(tab, tp);
        }

        String[][] optionNames = {
                {"Marfil", "Arena", "Miel", "Cobre", "Ébano", "Oliva"},
                {"Corto", "Despeinado", "Ondulado", "Largo", "Trenzado", "Cresta"},
                {"Avellana", "Azules", "Verdes", "Grises", "Oscuros", "Ámbar"},
                {"Aventurero", "Explorador", "Artesano", "Nómada", "Guardián", "Real"},
                {"Sin placas", "Cuero", "Hierro", "Cobalto", "Cristal", "Élite"},
                {"Ninguno", "Hombrera", "Capa", "Amuleto", "Diadema", "Mochila"}
        };
        LinearLayout optionShell = column();
        optionShell.setPadding(dp(8), dp(7), dp(8), dp(8));
        optionShell.setBackground(shape(new int[]{0x44243f52, 0x22101d2b}, 15, 0x55466378));
        LinearLayout.LayoutParams optionShellParams = new LinearLayout.LayoutParams(-1, 0, 1.12f);
        root.addView(optionShell, optionShellParams);

        LinearLayout optionHeading = row();
        optionHeading.addView(text(moduleLabels[avatarModuleIndex] + "  ·  6 ESTILOS", 10, 0xffd8eaf0, true),
                new LinearLayout.LayoutParams(0, dp(22), 1f));
        TextView selectedName = text(optionNames[avatarModuleIndex][avatarStyles[avatarModuleIndex]], 9,
                0xffb9f27c, true);
        selectedName.setGravity(Gravity.CENTER);
        optionHeading.addView(selectedName, new LinearLayout.LayoutParams(-2, dp(22)));
        optionShell.addView(optionHeading);

        ScrollView optionScroll = new ScrollView(getContext());
        optionScroll.setFillViewport(false);
        optionScroll.setVerticalScrollBarEnabled(false);
        optionScroll.setClipToPadding(false);
        LinearLayout.LayoutParams optionScrollParams = new LinearLayout.LayoutParams(-1, 0, 1f);
        optionScrollParams.topMargin = dp(3);
        optionShell.addView(optionScroll, optionScrollParams);
        LinearLayout grid = column();
        grid.setClipChildren(false);
        grid.setClipToPadding(false);
        optionScroll.addView(grid, new ScrollView.LayoutParams(-1, -2));

        for (int r = 0; r < 2; r++) {
            LinearLayout line = row();
            LinearLayout.LayoutParams lineParams = new LinearLayout.LayoutParams(-1, dp(94));
            if (r > 0) lineParams.topMargin = dp(6);
            grid.addView(line, lineParams);
            for (int col = 0; col < 3; col++) {
                final int choice = r * 3 + col;
                boolean selected = avatarStyles[avatarModuleIndex] == choice;
                LinearLayout tile = column();
                tile.setGravity(Gravity.CENTER);
                tile.setPadding(dp(4), dp(4), dp(4), dp(4));
                tile.setBackground(shape(selected
                        ? new int[]{0xff365a48, 0xff1d3e39}
                        : new int[]{0xff263d51, 0xff172b3f},
                        12, selected ? 0xff9ae17c : 0x554a687c));
                FrameLayout icon = new FrameLayout(getContext());
                icon.addView(new AvatarOptionView(getContext(), avatarModuleIndex, choice),
                        new FrameLayout.LayoutParams(-1, -1));
                tile.addView(icon, new LinearLayout.LayoutParams(-1, 0, 1f));
                TextView label = text(optionNames[avatarModuleIndex][choice], 9,
                        selected ? 0xffe4f8d7 : 0xffc2d2dc, selected);
                label.setGravity(Gravity.CENTER);
                label.setMaxLines(1);
                LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(-1, dp(18));
                labelParams.topMargin = dp(2);
                tile.addView(label, labelParams);
                tile.setOnClickListener(v -> {
                    haptic(v);
                    avatarStyles[avatarModuleIndex] = choice;
                    preferences.edit().putInt("avatar_" + avatarModules[avatarModuleIndex], choice).apply();
                    showAvatarPage();
                });
                LinearLayout.LayoutParams cell = new LinearLayout.LayoutParams(0, -1, 1f);
                if (col > 0) cell.leftMargin = dp(6);
                line.addView(tile, cell);
            }
        }
        animateAvatarIn(previewCard, 0);
        animateAvatarIn(optionShell, 1);
    }

    private void showSettingsPage() {
        LinearLayout root = pageRoot("AJUSTES", "Ajusta gráficos, controles y comportamiento.");

        String[][] modules = {
                {"video", "VÍDEO", "◈"},
                {"audio", "AUDIO", "♫"},
                {"controls", "CONTROLES", "⌖"},
                {"interface", "HUD", "▤"},
                {"animation", "ANIMACIÓN", "✧"},
                {"accessibility", "ACCESO", "◉"}
        };
        HorizontalScrollView moduleScroller = new HorizontalScrollView(getContext());
        moduleScroller.setHorizontalScrollBarEnabled(false);
        LinearLayout.LayoutParams tabsParams = new LinearLayout.LayoutParams(-1, dp(42));
        tabsParams.topMargin = dp(8);
        tabsParams.bottomMargin = dp(7);
        root.addView(moduleScroller, tabsParams);
        LinearLayout tabs = row();
        tabs.setPadding(dp(1), 0, dp(1), 0);
        moduleScroller.addView(tabs, new HorizontalScrollView.LayoutParams(-2, -1));
        for (String[] module : modules) {
            boolean active = settingsModule.equals(module[0]);
            TextView tab = text(module[2] + "  " + module[1], 9,
                    active ? 0xffe4ffd6 : 0xffb8cbd5, true);
            tab.setGravity(Gravity.CENTER);
            tab.setPadding(dp(12), 0, dp(12), 0);
            tab.setBackground(shape(active
                    ? new int[]{0xff355b48, 0xff1d403a}
                    : new int[]{0xff20374a, 0xff142638},
                    11, active ? 0xff91d77a : 0x55496379));
            tab.setOnClickListener(v -> {
                haptic(v);
                settingsModule = module[0];
                preferences.edit().putString("settings_module", settingsModule).apply();
                showSettingsPage();
            });
            LinearLayout.LayoutParams itemParams = new LinearLayout.LayoutParams(-2, -1);
            if (tabs.getChildCount() > 0) itemParams.leftMargin = dp(6);
            tabs.addView(tab, itemParams);
        }

        ScrollView scroll = new ScrollView(getContext());
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(true);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(-1, 0, 1f);
        root.addView(scroll, scrollParams);
        LinearLayout content = column();
        content.setPadding(dp(10), dp(9), dp(10), dp(14));
        content.setBackground(shape(new int[]{0x33243e52, 0x22101d2b}, 15, 0x44506b80));
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));

        switch (settingsModule) {
            case "audio":
                settingsHeading(content, "SONIDO DEL JUEGO", "Ajusta la mezcla que reproduce el dispositivo.");
                content.addView(soundVolumeControl());
                content.addView(settingSwitch("Efectos de juego", "Rotura, colocación, pasos, salto y recogida.",
                        "sound_effects_enabled", true));
                content.addView(settingSwitch("Sonidos de interfaz", "Confirmaciones y pulsaciones del menú.",
                        "ui_sounds_enabled", true));
                break;
            case "controls":
                settingsHeading(content, "CONTROLES TÁCTILES", "Ajusta cada eje y la disposición de movimiento.");
                int legacyTouch = preferences.getInt("touch_sensitivity", 100);
                content.addView(settingSlider("Sensibilidad horizontal", "Velocidad de giro de izquierda a derecha.",
                        "touch_sensitivity_x", 35, 150, legacyTouch, "%"));
                content.addView(settingSlider("Sensibilidad vertical", "Velocidad de giro de arriba abajo.",
                        "touch_sensitivity_y", 35, 150, legacyTouch, "%"));
                content.addView(settingSlider("Zona muerta del joystick", "Filtra movimientos accidentales cerca del centro.",
                        "joystick_deadzone", 5, 30, 12, "%"));
                content.addView(settingSwitch("Invertir eje vertical", "Invierte el movimiento arriba y abajo.",
                        "invert_look_y", false));
                content.addView(settingSwitch("Controles para mano izquierda", "Mueve el joystick al lado derecho y refleja el salto.",
                        "left_handed_controls", false));
                content.addView(settingSwitch("Respuesta háptica", "Vibración breve al tocar elementos del menú.",
                        "menu_haptics", true));
                content.addView(menuButton("↺", "RESTABLECER CONTROLES", "Recupera los valores predeterminados.",
                        new int[]{0xff3a4b5d, 0xff263648}, 0xffa9c6d8, 38, () -> {
                            preferences.edit().putInt("touch_sensitivity", 100)
                                    .putInt("touch_sensitivity_x", 100).putInt("touch_sensitivity_y", 100)
                                    .putInt("joystick_deadzone", 12)
                                    .putBoolean("invert_look_y", false).putBoolean("left_handed_controls", false)
                                    .putBoolean("menu_haptics", true).apply();
                            showSettingsPage();
                        }, 4));
                break;
            case "interface":
                settingsHeading(content, "INTERFAZ Y HUD", "Preferencias visuales de menús y pantalla de juego.");
                content.addView(settingSwitch("Animaciones de interfaz", "Entradas y transiciones suaves.",
                        "menu_animations", true));
                content.addView(settingSwitch("Mostrar información superior", "FPS, chunks y datos de estado en el HUD.",
                        "show_hud_stats", true));
                content.addView(settingSlider("Escala del HUD", "Tamaño de elementos durante la partida.",
                        "hud_scale", 80, 120, 100, "%"));
                content.addView(settingSwitch("Animar el fondo de menús", "Partículas ambientales y movimiento sutil.",
                        "menu_background_animations", true));
                break;
            case "animation":
                settingsHeading(content, "ANIMACIÓN DEL JUEGO", "Controla las poses que calcula el núcleo Rust.");
                content.addView(settingSwitch("Animaciones del mundo", "Movimiento de jugador, criaturas, alas, cabeza y reposo.",
                        "world_animations", true));
                content.addView(settingChoice("Calidad de animación", "Más detalle de pose; menor carga en ahorro.",
                        "animation_quality", new String[]{"Ahorro", "Equilibrada", "Completa"}, 2));
                content.addView(settingSwitch("Movimiento de reposo del avatar", "Respiración, parpadeo y giro automático de la vista previa.",
                        "avatar_idle_animation", true));
                content.addView(settingSwitch("Suavizar transiciones de apariencia", "Intercambios visuales al seleccionar estilos.",
                        "avatar_transition_animations", true));
                break;
            case "accessibility":
                settingsHeading(content, "ACCESIBILIDAD", "Adapta movimiento, contraste y lectura.");
                content.addView(settingSwitch("Reducir movimiento", "Desactiva movimiento decorativo y transiciones.",
                        "reduce_motion", false));
                content.addView(settingSwitch("Contraste reforzado", "Aumenta la separación de paneles y controles.",
                        "high_contrast", false));
                content.addView(settingSlider("Tamaño del texto", "Escala preferida para los menús.",
                        "menu_text_scale", 85, 125, 100, "%"));
                content.addView(settingSwitch("Animaciones de avatar", "Permite movimiento de reposo en el editor.",
                        "avatar_idle_animation", true));
                break;
            default:
                settingsHeading(content, "VÍDEO Y RENDIMIENTO", "Ajustes gráficos que se aplican al entrar al mundo.");
                content.addView(settingChoice("Calidad gráfica", "Iluminación y coste de renderizado.",
                        "graphics_quality", new String[]{"Ahorro", "Equilibrado", "Alta"}, 1));
                content.addView(settingChoice("Distancia de visión", "Más chunks consumen memoria y GPU.",
                        "render_distance", new String[]{"Cercana · 3×3", "Normal · 5×5", "Amplia · 7×7"}, 1));
                content.addView(settingSlider("Campo de visión", "Ángulo de cámara en primera y tercera persona.",
                        "field_of_view", 60, 100, 68, "°"));
                content.addView(settingSwitch("Ondulación del agua", "Movimiento suave de la superficie.",
                        "water_waves", true));
                content.addView(settingSwitch("Sombras de contacto", "Oscurecimiento suave bajo jugador y criaturas.",
                        "contact_shadows", true));
                content.addView(settingSwitch("Limitar efectos de alto coste", "Deja los efectos avanzados para dispositivos compatibles.",
                        "adaptive_graphics", true));
                break;
        }
        animateIn(content, 0);
    }

    private void settingsHeading(LinearLayout parent, String title, String subtitle) {
        TextView heading = text(title, 11, 0xffd8edf6, true);
        parent.addView(heading);
        TextView detail = text(subtitle, 9, muted, false);
        detail.setGravity(Gravity.TOP);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(4);
        params.bottomMargin = dp(9);
        parent.addView(detail, params);
    }

    private View settingChoice(String title, String subtitle, String key,
                               String[] entries, int defaultValue) {
        LinearLayout card = column();
        card.setPadding(dp(12), dp(9), dp(12), dp(9));
        card.setBackground(shape(new int[]{0xff20394d, 0xff142638}, 14, 0x665b788d));
        card.addView(text(title, 11, white, true));
        TextView detail = text(subtitle, 8, muted, false);
        LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(-1, -2);
        detailParams.topMargin = dp(3);
        card.addView(detail, detailParams);

        int initial = Math.max(0, Math.min(entries.length - 1, preferences.getInt(key, defaultValue)));
        LinearLayout selector = row();
        selector.setGravity(Gravity.CENTER_VERTICAL);
        selector.setPadding(dp(11), 0, dp(10), 0);
        selector.setBackground(shape(new int[]{0xff304a5e, 0xff1e3447}, 10, 0x77617e91));
        TextView current = text(entries[initial], 10, 0xffe6f4f7, true);
        selector.addView(current, new LinearLayout.LayoutParams(0, dp(38), 1f));
        TextView arrow = text("⌄", 18, 0xffb9f27c, true);
        arrow.setGravity(Gravity.CENTER);
        selector.addView(arrow, new LinearLayout.LayoutParams(dp(28), dp(38)));
        selector.setOnClickListener(v -> {
            haptic(v);
            PopupMenu menu = new PopupMenu(getContext(), selector);
            for (int i = 0; i < entries.length; i++) menu.getMenu().add(0, i, i, entries[i]);
            menu.setOnMenuItemClickListener(item -> {
                int index = Math.max(0, Math.min(entries.length - 1, item.getItemId()));
                current.setText(entries[index]);
                preferences.edit().putInt(key, index).apply();
                return true;
            });
            menu.show();
        });
        LinearLayout.LayoutParams selectorParams = new LinearLayout.LayoutParams(-1, dp(38));
        selectorParams.topMargin = dp(8);
        card.addView(selector, selectorParams);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(8);
        card.setLayoutParams(params);
        return card;
    }

    private View settingSlider(String title, String subtitle, String key,
                               int min, int max, int defaultValue, String suffix) {
        LinearLayout card = column();
        card.setPadding(dp(10), dp(7), dp(10), dp(6));
        card.setBackground(shape(new int[]{0xff1c3043, 0xff142334}, 11, 0x554b6478));
        LinearLayout heading = row();
        heading.addView(text(title, 10, white, true),
                new LinearLayout.LayoutParams(0, dp(19), 1f));
        TextView amount = text("", 9, 0xffb9f27c, true);
        amount.setGravity(Gravity.CENTER);
        heading.addView(amount, new LinearLayout.LayoutParams(dp(45), dp(19)));
        card.addView(heading);
        TextView detail = text(subtitle, 8, muted, false);
        LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(-1, -2);
        detailParams.topMargin = dp(2);
        card.addView(detail, detailParams);
        SeekBar seek = new SeekBar(getContext());
        seek.setMax(max - min);
        int initial = Math.max(min, Math.min(max, preferences.getInt(key, defaultValue)));
        seek.setProgress(initial - min);
        amount.setText(initial + suffix);
        seek.setProgressTintList(ColorStateList.valueOf(0xffa9ee70));
        seek.setThumbTintList(ColorStateList.valueOf(0xffc7f59a));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                int value = min + progress;
                amount.setText(value + suffix);
                if (fromUser) preferences.edit().putInt(key, value).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        });
        LinearLayout.LayoutParams seekParams = new LinearLayout.LayoutParams(-1, dp(31));
        seekParams.topMargin = dp(1);
        card.addView(seek, seekParams);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(7);
        card.setLayoutParams(params);
        return card;
    }

    private View soundVolumeControl() {
        LinearLayout card = column();
        card.setPadding(dp(12), dp(9), dp(12), dp(8));
        card.setBackground(shape(new int[]{0xff20394d, 0xff142638}, 14, 0x665b788d));
        LinearLayout heading = row();
        heading.addView(text("Volumen de efectos", 12, white, true),
                new LinearLayout.LayoutParams(0, dp(22), 1f));
        TextView amount = text("", 10, 0xffb9f27c, true);
        amount.setGravity(Gravity.CENTER);
        heading.addView(amount, new LinearLayout.LayoutParams(dp(44), dp(22)));
        card.addView(heading);
        SeekBar volume = new SeekBar(getContext());
        volume.setMax(100);
        int initial = Math.round(preferences.getFloat("sound_volume", 0.82f) * 100f);
        volume.setProgress(Math.max(0, Math.min(100, initial)));
        amount.setText(volume.getProgress() + "%");
        volume.setProgressTintList(ColorStateList.valueOf(0xffa9ee70));
        volume.setThumbTintList(ColorStateList.valueOf(0xffc7f59a));
        volume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                amount.setText(progress + "%");
                if (fromUser) preferences.edit().putFloat("sound_volume", progress / 100.0f).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        });
        LinearLayout.LayoutParams seek = new LinearLayout.LayoutParams(-1, dp(31));
        seek.topMargin = dp(2);
        card.addView(volume, seek);
        card.addView(text("Rotura · colocación · recogida · salto · pasos · interfaz", 8, muted, false));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(7);
        card.setLayoutParams(params);
        return card;
    }

    private View settingSwitch(String title, String subtitle, String key, boolean defaultValue) {
        LinearLayout card = row();
        card.setPadding(dp(12), dp(8), dp(11), dp(8));
        card.setBackground(shape(new int[]{0xff1c3043, 0xff142334}, 13, 0x554b6478));
        LinearLayout labels = column();
        labels.addView(text(title, 12, white, true));
        TextView detail = text(subtitle, 9, muted, false);
        LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(-1, -2);
        detailParams.topMargin = dp(3);
        labels.addView(detail, detailParams);
        card.addView(labels, new LinearLayout.LayoutParams(0, -2, 1f));
        Switch toggle = new Switch(getContext());
        toggle.setButtonTintList(ColorStateList.valueOf(0xffa9ee70));
        toggle.setChecked(preferences.getBoolean(key, defaultValue));
        toggle.setOnCheckedChangeListener((button, checked) ->
                preferences.edit().putBoolean(key, checked).apply());
        card.addView(toggle);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(58));
        p.bottomMargin = dp(7);
        card.setOnClickListener(v -> toggle.setChecked(!toggle.isChecked()));
        return wrapWithParams(card, p);
    }

    private View wrapWithParams(View view, LinearLayout.LayoutParams params) {
        FrameLayout wrapper = new FrameLayout(getContext());
        wrapper.addView(view, new FrameLayout.LayoutParams(-1, -1));
        wrapper.setLayoutParams(params);
        return wrapper;
    }

    private void showMultiplayerPage() {
        LinearLayout root = pageRoot("LOCAL", "Juega con otros dispositivos conectados a tu Wi-Fi.");
        LinearLayout actionRow = row();
        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(-1, dp(48));
        actionParams.topMargin = dp(12);
        root.addView(actionRow, actionParams);

        TextView host = actionButton("CREAR PARTIDA", () -> {
            if (listener != null) listener.onHostMultiplayer();
        });
        actionRow.addView(host, new LinearLayout.LayoutParams(0, -1, 1f));
        TextView search = actionButton("BUSCAR PARTIDAS", () -> {
            if (listener != null) listener.onSearchMultiplayer();
        });
        LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(0, -1, 1f);
        searchParams.leftMargin = dp(8);
        actionRow.addView(search, searchParams);

        multiplayerStatus = text("Conecta los teléfonos a la misma red Wi-Fi y busca partidas.", 9, 0xffb9cbd6, false);
        multiplayerStatus.setGravity(Gravity.CENTER_VERTICAL);
        multiplayerStatus.setPadding(dp(10), 0, dp(10), 0);
        multiplayerStatus.setBackground(shape(new int[]{0x332c4658, 0x22172535}, 9, 0x444d6979));
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(-1, dp(34));
        statusParams.topMargin = dp(9);
        root.addView(multiplayerStatus, statusParams);

        TextView nearbyTitle = text("PARTIDAS ENCONTRADAS", 9, 0xffa9ee70, true);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(-1, dp(26));
        titleParams.topMargin = dp(7);
        root.addView(nearbyTitle, titleParams);

        ScrollView scroll = new ScrollView(getContext());
        scroll.setFillViewport(true);
        multiplayerGames = column();
        scroll.addView(multiplayerGames, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        setNearbyGames(new ArrayList<>());
        // Discover Wi-Fi LAN sessions as soon as the LOCAL module is opened.
        // The search button remains available as a manual refresh action.
        if (listener != null) listener.onSearchMultiplayer();

        LinearLayout serverCard = row();
        serverCard.setPadding(dp(10), dp(6), dp(10), dp(6));
        serverCard.setBackground(shape(new int[]{0xff263141, 0xff182333}, 10, 0x444b6074));
        serverCard.addView(text("SERVIDORES PÚBLICOS", 8, 0xffd5e0e8, true),
                new LinearLayout.LayoutParams(0, dp(25), 1f));
        serverCard.addView(pill("NO DISPONIBLE", 0xffffce72), new LinearLayout.LayoutParams(-2, dp(23)));
        LinearLayout.LayoutParams serverParams = new LinearLayout.LayoutParams(-1, dp(36));
        serverParams.topMargin = dp(8);
        root.addView(serverCard, serverParams);
    }

    public void setMultiplayerStatus(String status) {
        if (multiplayerStatus != null) multiplayerStatus.setText(status == null ? "" : status);
    }

    public void setNearbyGames(List<LanMultiplayerService.GameInfo> games) {
        if (multiplayerGames == null) return;
        multiplayerGames.removeAllViews();
        if (games == null || games.isEmpty()) {
            TextView empty = text("No se encontraron partidas todavía. Pulsa BUSCAR PARTIDAS.", 9, muted, false);
            empty.setGravity(Gravity.CENTER);
            multiplayerGames.addView(empty, new LinearLayout.LayoutParams(-1, dp(62)));
            return;
        }
        for (LanMultiplayerService.GameInfo game : games) {
            LinearLayout item = row();
            item.setPadding(dp(10), dp(7), dp(10), dp(7));
            item.setBackground(shape(new int[]{0xff20384b, 0xff14263a}, 11, 0x554d6b80));
            LinearLayout details = column();
            details.addView(text(game.name, 11, white, true));
            TextView address = text(game.host + ":" + game.port, 8, muted, false);
            LinearLayout.LayoutParams addressParams = new LinearLayout.LayoutParams(-1, -2);
            addressParams.topMargin = dp(3);
            details.addView(address, addressParams);
            item.addView(details, new LinearLayout.LayoutParams(0, -2, 1f));
            TextView join = actionButton("UNIRSE", () -> {
                if (listener != null) listener.onJoinMultiplayer(game);
            });
            item.addView(join, new LinearLayout.LayoutParams(dp(88), dp(36)));
            LinearLayout.LayoutParams itemParams = new LinearLayout.LayoutParams(-1, dp(58));
            itemParams.bottomMargin = dp(6);
            multiplayerGames.addView(item, itemParams);
        }
    }

    private void showAchievementsPage() {
        LinearLayout root = pageRoot("LOGROS", "Retos y recompensas de tu aventura.");
        LinearLayout grid = column();
        LinearLayout.LayoutParams gridParams = new LinearLayout.LayoutParams(-1, 0, 1f);
        gridParams.topMargin = dp(12);
        root.addView(grid, gridParams);
        String[] icons = {"✦", "◆", "⚒", "⌂", "◇", "☼", "◈", "✧"};
        String[] names = {"Primer paso", "Constructor", "Minero", "Explorador",
                "Artesano", "Aventura", "Coleccionista", "Superviviente"};
        for (int row = 0; row < 2; row++) {
            LinearLayout line = row();
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 0, 1f);
            lp.bottomMargin = dp(7);
            grid.addView(line, lp);
            for (int col = 0; col < 4; col++) {
                final int index = row * 4 + col;
                LinearLayout tile = column();
                tile.setGravity(Gravity.CENTER);
                tile.setPadding(dp(5), dp(5), dp(5), dp(5));
                tile.setBackground(shape(new int[]{0xff203449, 0xff142438}, 12, 0x444b6b80));
                tile.addView(text(icons[index], 20, 0xffa9ee70, true));
                TextView label = text(names[index], 8, white, true);
                label.setGravity(Gravity.CENTER);
                LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, -2);
                tlp.topMargin = dp(4);
                tile.addView(label, tlp);
                TextView locked = text("POR LOGRAR", 7, muted, true);
                locked.setGravity(Gravity.CENTER);
                LinearLayout.LayoutParams klp = new LinearLayout.LayoutParams(-1, -2);
                klp.topMargin = dp(3);
                tile.addView(locked, klp);
                LinearLayout.LayoutParams cell = new LinearLayout.LayoutParams(0, -1, 1f);
                if (col > 0) cell.leftMargin = dp(6);
                line.addView(tile, cell);
            }
        }
    }

    /** Vector-drawn menu pictograms; these do not depend on device font glyph coverage. */
    private static final class MenuGlyph extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final int kind;
        private final int tint;
        MenuGlyph(Context context, int kind, int tint) {
            super(context);
            this.kind = kind;
            this.tint = tint;
        }
        private void line(Canvas c, float x1, float y1, float x2, float y2, float width) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(width);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            c.drawLine(x1, y1, x2, y2, paint);
            paint.setStyle(Paint.Style.FILL);
        }
        private void polygon(Canvas c, float... p) {
            path.reset();
            path.moveTo(p[0], p[1]);
            for (int i = 2; i < p.length; i += 2) path.lineTo(p[i], p[i + 1]);
            path.close();
            c.drawPath(path, paint);
        }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float s = Math.min(getWidth(), getHeight()) * 0.28f;
            float x = getWidth() * 0.5f, y = getHeight() * 0.5f;
            paint.setColor(tint);
            switch (kind) {
                case 0: // play
                    polygon(canvas, x - s * 0.50f, y - s * 0.72f,
                            x + s * 0.78f, y, x - s * 0.50f, y + s * 0.72f);
                    break;
                case 1: // voxel/cube
                    polygon(canvas, x, y - s * 0.82f, x + s * 0.78f, y - s * 0.38f,
                            x + s * 0.78f, y + s * 0.42f, x, y + s * 0.86f,
                            x - s * 0.78f, y + s * 0.42f, x - s * 0.78f, y - s * 0.38f);
                    paint.setColor(0x66404b58);
                    line(canvas, x, y, x, y + s * 0.82f, s * 0.075f);
                    line(canvas, x, y, x + s * 0.78f, y - s * 0.38f, s * 0.075f);
                    line(canvas, x, y, x - s * 0.78f, y - s * 0.38f, s * 0.075f);
                    break;
                case 2: // local network
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(s * 0.13f);
                    line(canvas, x, y, x - s * 0.72f, y - s * 0.55f, s * 0.12f);
                    line(canvas, x, y, x + s * 0.72f, y - s * 0.55f, s * 0.12f);
                    line(canvas, x, y, x, y + s * 0.77f, s * 0.12f);
                    paint.setStyle(Paint.Style.FILL);
                    canvas.drawCircle(x, y, s * 0.25f, paint);
                    canvas.drawCircle(x - s * 0.72f, y - s * 0.55f, s * 0.20f, paint);
                    canvas.drawCircle(x + s * 0.72f, y - s * 0.55f, s * 0.20f, paint);
                    canvas.drawCircle(x, y + s * 0.77f, s * 0.20f, paint);
                    break;
                case 3: // settings sliders
                    for (int i = -1; i <= 1; i++) {
                        float yy = y + i * s * 0.62f;
                        line(canvas, x - s * 0.9f, yy, x + s * 0.9f, yy, s * 0.12f);
                        paint.setColor(0xffeaf4f4);
                        canvas.drawCircle(x + ((i % 2 == 0) ? -s * 0.25f : s * 0.42f),
                                yy, s * 0.19f, paint);
                        paint.setColor(tint);
                    }
                    break;
                default: // achievement star
                    float[] pts = new float[20];
                    for (int i = 0; i < 10; i++) {
                        double a = -Math.PI / 2.0 + i * Math.PI / 5.0;
                        float radius = (i % 2 == 0) ? s * 0.94f : s * 0.42f;
                        pts[i * 2] = x + (float) Math.cos(a) * radius;
                        pts[i * 2 + 1] = y + (float) Math.sin(a) * radius;
                    }
                    polygon(canvas, pts);
                    break;
            }
        }
    }

    /** Small illustrated appearance pictograms, arranged four across without text labels. */
    /** Small original voxel-island emblem replacing a generic square glyph. */
    private final class WorldMarkView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        WorldMarkView(Context context) { super(context); }
        private void face(Canvas canvas, int color, float... points) {
            path.reset();
            path.moveTo(points[0], points[1]);
            for(int i=2;i<points.length;i+=2)path.lineTo(points[i],points[i+1]);
            path.close();
            paint.setColor(color);
            canvas.drawPath(path,paint);
        }
        private void cube(Canvas canvas,float x,float y,float s,int top,int left,int right) {
            float d=s*0.43f;
            face(canvas,left,x-s*0.5f,y,x,y+d,x,y+s*0.74f,x-s*0.5f,y+s*0.38f);
            face(canvas,right,x,y+d,x+s*0.5f,y,x+s*0.5f,y+s*0.38f,x,y+s*0.74f);
            face(canvas,top,x-s*0.5f,y,x,y-d,x+s*0.5f,y,x,y+d);
        }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float cx=getWidth()*0.5f,cy=getHeight()*0.52f;
            float s=Math.min(getWidth(),getHeight())*0.31f;
            paint.setColor(0x5569d7ac);
            canvas.drawOval(cx-s*1.4f,cy+s*0.56f,cx+s*1.4f,cy+s*0.95f,paint);
            cube(canvas,cx-s*0.48f,cy+s*0.05f,s,0xffa8ed6a,0xff26844b,0xff3aa65d);
            cube(canvas,cx+s*0.48f,cy+s*0.05f,s,0xff83d95a,0xff2d8f4e,0xff47b967);
            cube(canvas,cx,cy-s*0.45f,s*0.78f,0xffc0f47d,0xff328c4d,0xff55bd65);
        }
    }

    private static final class Backdrop extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final long createdAt = SystemClock.uptimeMillis();
        Backdrop(Context context) { super(context); }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth(), h = getHeight();
            boolean animate = getContext().getSharedPreferences("minefade_ui", Context.MODE_PRIVATE)
                    .getBoolean("menu_background_animations", true)
                    && !getContext().getSharedPreferences("minefade_ui", Context.MODE_PRIVATE)
                    .getBoolean("reduce_motion", false);
            float time = (SystemClock.uptimeMillis() - createdAt) / 1000.0f;
            float driftX = animate ? (float) Math.sin(time * 0.23f) * w * 0.012f : 0.0f;
            float driftY = animate ? (float) Math.cos(time * 0.19f) * h * 0.010f : 0.0f;
            paint.setShader(new LinearGradient(0, 0, w, h,
                    new int[]{0xff0b1726, 0xff10283a, 0xff0b1422},
                    null, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, paint);
            paint.setShader(null);
            // A layered atmospheric backdrop replaces the old visible square grid.
            // Broad arcs, diagonal light strokes and sparse particles provide depth
            // without competing with cards, text or the voxel cover illustration.
            float density = getResources().getDisplayMetrics().density;
            paint.setColor(0x164c9cba);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(1f, density));
            canvas.drawCircle(w * 0.82f + driftX, h * 0.25f + driftY, Math.max(w, h) * 0.22f, paint);
            canvas.drawCircle(w * 0.82f + driftX, h * 0.25f + driftY, Math.max(w, h) * 0.34f, paint);
            canvas.drawCircle(w * 0.82f + driftX, h * 0.25f + driftY, Math.max(w, h) * 0.46f, paint);
            canvas.drawArc(new RectF(-w * 0.42f, h * 0.52f, w * 0.55f, h * 1.45f),
                    205, 115, false, paint);
            paint.setColor(0x124c9cba);
            canvas.drawLine(w * 0.05f, h * 0.86f, w * 0.42f, h * 0.49f, paint);
            canvas.drawLine(w * 0.16f, h, w * 0.62f, h * 0.54f, paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0x337ed6bd);
            for (int i = 0; i < 24; i++) {
                float px = (i * 79 % 997) / 997f * w
                        + (animate ? (float) Math.sin(time * 0.28f + i * 0.7f) * density * 5.0f : 0.0f);
                float py = (i * 137 % 991) / 991f * h
                        + (animate ? (float) Math.cos(time * 0.22f + i * 0.47f) * density * 7.0f : 0.0f);
                float radius = density * (i % 5 == 0 ? 2.0f : 0.8f);
                canvas.drawCircle(px, py, radius, paint);
            }
            paint.setShader(new RadialGradient(w * 0.76f, h * 0.22f,
                    Math.max(w, h) * 0.65f,
                    new int[]{0x3342bb8a, 0x00152c3d},
                    null, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, paint);
            paint.setShader(null);
            if (animate && isAttachedToWindow()) postInvalidateDelayed(42L);
        }
    }

    /** A deterministic, local voxel-island cover; never depends on a remote thumbnail. */
    private static final class CoverArtView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        CoverArtView(Context context) { super(context); setLayerType(View.LAYER_TYPE_SOFTWARE, null); }

        private void polygon(Canvas c, int color, float... points) {
            path.reset();
            path.moveTo(points[0], points[1]);
            for (int i = 2; i < points.length; i += 2) path.lineTo(points[i], points[i + 1]);
            path.close();
            paint.setColor(color);
            paint.setStyle(Paint.Style.FILL);
            c.drawPath(path, paint);
        }

        private void cube(Canvas c, float x, float y, float s, int top, int left, int right) {
            float hw = s * 0.52f, hh = s * 0.28f, depth = s * 0.52f;
            polygon(c, left, x - hw, y, x, y + hh, x, y + hh + depth, x - hw, y + depth);
            polygon(c, right, x, y + hh, x + hw, y, x + hw, y + depth, x, y + hh + depth);
            polygon(c, top, x - hw, y, x, y - hh, x + hw, y, x, y + hh);
            paint.setColor(0x22ffffff);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1f);
            c.drawLine(x - hw, y, x, y + hh, paint);
            c.drawLine(x, y - hh, x + hw, y, paint);
            paint.setStyle(Paint.Style.FILL);
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth(), h = getHeight();
            float size = Math.min(w, h) * 0.14f;
            paint.setShader(new RadialGradient(w * 0.52f, h * 0.38f, Math.max(w, h) * 0.65f,
                    new int[]{0xff24516a, 0xff13283f, 0xff0c1829}, null, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, paint);
            paint.setShader(null);

            paint.setColor(0x77d7f7ff);
            for (int i = 0; i < 42; i++) {
                float x = (i * 73 % 997) / 997f * w;
                float y = (i * 131 % 991) / 991f * h * 0.77f;
                canvas.drawCircle(x, y, (i % 3 == 0) ? 2.0f : 1.0f, paint);
            }

            float cx = w * 0.52f, cy = h * 0.44f;
            paint.setShader(new RadialGradient(cx, cy + size * 1.8f, size * 5.4f,
                    new int[]{0x8849dbdb, 0x0049dbdb}, null, Shader.TileMode.CLAMP));
            canvas.drawCircle(cx, cy + size, size * 5.4f, paint);
            paint.setShader(null);

            // Floating stone underside.
            for (int row = 0; row < 4; row++) {
                int count = 5 - Math.min(row, 2);
                for (int col = 0; col < count; col++) {
                    float x = cx + (col - (count - 1) * 0.5f) * size * 0.88f;
                    float y = cy + size * (1.45f + row * 0.43f);
                    cube(canvas, x, y, size, 0xff536678, 0xff29394a, 0xff3a4d60);
                }
            }

            // Green voxel terrain top, layered as a small floating island.
            int[][] heights = {
                    {0,1,1,1,0},
                    {1,1,1,1,1},
                    {1,1,2,1,1},
                    {0,1,1,1,0}
            };
            for (int r = 0; r < heights.length; r++) {
                int count = heights[r].length;
                for (int col = 0; col < count; col++) {
                    if (heights[r][col] == 0) continue;
                    float x = cx + (col - 2) * size * 0.84f + (r % 2 == 0 ? 0 : size * 0.14f);
                    float y = cy + (r - 1.5f) * size * 0.39f;
                    int top = ((r + col) % 3 == 0) ? 0xff9be94d : 0xff62c744;
                    cube(canvas, x, y, size, top, 0xff247e3e, 0xff32994a);
                    if ((r + col) % 4 == 0)
                        cube(canvas, x + size * 0.22f, y - size * 0.24f, size * 0.63f,
                                0xffa8ee5d, 0xff307f3e, 0xff3eaa50);
                }
            }

            // Waterfall on the right and a glowing lava seam on the left.
            paint.setColor(0x9952e7ff);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(size * 0.36f);
            paint.setStrokeCap(Paint.Cap.ROUND);
            canvas.drawLine(cx + size * 1.02f, cy + size * 0.62f,
                    cx + size * 1.55f, cy + size * 2.42f, paint);
            paint.setColor(0xff9af4ff);
            paint.setStrokeWidth(size * 0.08f);
            canvas.drawLine(cx + size * 1.04f, cy + size * 0.65f,
                    cx + size * 1.56f, cy + size * 2.38f, paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(new RadialGradient(cx - size * 1.45f, cy + size * 1.4f,
                    size * 1.4f, new int[]{0xffffa22d, 0x00ff4d19},
                    null, Shader.TileMode.CLAMP));
            canvas.drawCircle(cx - size * 1.45f, cy + size * 1.4f, size * 1.4f, paint);
            paint.setShader(null);
            cube(canvas, cx - size * 1.37f, cy + size * 1.0f, size * 0.68f,
                    0xffffad37, 0xff883a24, 0xffb64b23);
            paint.setColor(0x88e3faff);
            canvas.drawCircle(cx + size * 1.85f, cy - size * 1.0f, size * 0.10f, paint);
            canvas.drawCircle(cx - size * 1.9f, cy - size * 0.85f, size * 0.08f, paint);
        }
    }

    /** A small perspective voxel-item thumbnail, drawn as geometry rather than emoji. */
    private final class AvatarOptionView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final int module;
        private final int choice;
        AvatarOptionView(Context context, int module, int choice) {
            super(context);
            this.module = module;
            this.choice = choice;
        }
        private void polygon(Canvas canvas, int color, float... points) {
            path.reset();
            path.moveTo(points[0], points[1]);
            for (int i = 2; i < points.length; i += 2) path.lineTo(points[i], points[i + 1]);
            path.close();
            paint.setColor(color);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawPath(path, paint);
        }
        private void cube(Canvas canvas, float x, float y, float w, float h, float depth,
                          int front, int side, int top) {
            float left = x - w * 0.5f, right = x + w * 0.5f;
            float upper = y - h * 0.5f, lower = y + h * 0.5f;
            float dx = depth * 0.55f, dy = depth * 0.38f;
            polygon(canvas, side, right, upper, right + dx, upper - dy,
                    right + dx, lower - dy, right, lower);
            polygon(canvas, top, left, upper, left + dx, upper - dy,
                    right + dx, upper - dy, right, upper);
            polygon(canvas, front, left, upper, right, upper, right, lower, left, lower);
            paint.setColor(0x44ffffff);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(1f, dp(0.6f)));
            canvas.drawLine(left + dp(2), upper + dp(2), right - dp(2), upper + dp(2), paint);
            paint.setStyle(Paint.Style.FILL);
        }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float cx = getWidth() * 0.5f, cy = getHeight() * 0.53f;
            float unit = Math.min(getWidth(), getHeight()) * 0.34f;
            int skin = new int[]{0xffe8c09c, 0xffc58d69, 0xff9b654c,
                    0xfff0c9a8, 0xffad7659, 0xff76513f}[choice % 6];
            int[] palette = {0xff8a5739, 0xff44313b, 0xffc49a57,
                    0xff4b7890, 0xff6e9f79, 0xffd0d2d0};
            int accent = palette[choice % palette.length];
            switch (module) {
                case 0: // skin material swatch
                    paint.setColor(skin);
                    canvas.drawCircle(cx - unit * 0.08f, cy, unit * 0.74f, paint);
                    paint.setColor(0x55ffffff);
                    canvas.drawArc(new RectF(cx - unit * 0.82f, cy - unit * 0.75f,
                            cx + unit * 0.68f, cy + unit * 0.75f), 205, 125, false, paint);
                    break;
                case 1: // hair silhouettes as shaded voxel locks
                    cube(canvas, cx, cy + unit * 0.20f, unit * 1.05f, unit * 1.0f,
                            unit * 0.35f, 0xffb87e5d, 0xff80533d, 0xffd6a17a);
                    if (choice == 3 || choice == 4) {
                        cube(canvas, cx, cy - unit * 0.33f, unit * 1.0f, unit * 0.48f,
                                unit * 0.34f, accent, 0xff49382f, 0xffd19d67);
                        cube(canvas, cx + unit * 0.38f, cy + unit * 0.32f,
                                unit * 0.30f, unit * 1.05f, unit * 0.23f, accent, 0xff34272a, 0xffc28b59);
                    } else {
                        cube(canvas, cx, cy - unit * 0.37f, unit * 1.14f,
                                choice == 5 ? unit * 0.82f : unit * 0.50f,
                                unit * 0.42f, accent, 0xff302a34, 0xffd5a66c);
                    }
                    break;
                case 2: // eye pair with pupil and highlight
                    for (int side = -1; side <= 1; side += 2) {
                        float x = cx + side * unit * 0.40f;
                        paint.setColor(0xffe9e5dc);
                        canvas.drawOval(new RectF(x - unit * 0.27f, cy - unit * 0.28f,
                                x + unit * 0.27f, cy + unit * 0.28f), paint);
                        paint.setColor(accent);
                        canvas.drawCircle(x, cy, unit * 0.18f, paint);
                        paint.setColor(0xff17212b);
                        canvas.drawCircle(x, cy, unit * 0.09f, paint);
                        paint.setColor(0xffffffff);
                        canvas.drawCircle(x - unit * 0.045f, cy - unit * 0.07f, unit * 0.045f, paint);
                    }
                    break;
                case 3: // clothing torso
                case 4: // armour chest plate
                    cube(canvas, cx, cy, unit * 1.18f, unit * 1.34f, unit * 0.42f,
                            module == 3 ? accent : 0xff718c9d,
                            module == 3 ? 0xff334a63 : 0xff34495a,
                            module == 3 ? 0xffd4b078 : 0xffc9dce4);
                    if (module == 4) {
                        cube(canvas, cx, cy - unit * 0.05f, unit * 0.24f, unit * 0.92f,
                                unit * 0.13f, 0xffd9e5e8, 0xff718b9b, 0xfff0f6f5);
                        for (int side = -1; side <= 1; side += 2)
                            cube(canvas, cx + side * unit * 0.55f, cy - unit * 0.34f,
                                    unit * 0.34f, unit * 0.65f, unit * 0.22f,
                                    0xff91aab8, 0xff40596a, 0xffe6eff1);
                    }
                    break;
                default: // accessory: amulet, cape clasp, crown or pack
                    if (choice == 2) {
                        cube(canvas, cx, cy + unit * 0.25f, unit * 0.72f, unit * 1.2f,
                                unit * 0.22f, accent, 0xff374c70, 0xffd4a56e);
                    } else if (choice == 4) {
                        cube(canvas, cx, cy - unit * 0.12f, unit * 1.15f, unit * 0.35f,
                                unit * 0.35f, 0xffd4a84c, 0xff83572c, 0xffffdf7b);
                        cube(canvas, cx, cy - unit * 0.42f, unit * 0.26f, unit * 0.58f,
                                unit * 0.22f, 0xffe9c36e, 0xff83572c, 0xffffe7a1);
                    } else {
                        cube(canvas, cx, cy, unit * 0.88f, unit * 0.92f,
                                unit * 0.48f, accent, 0xff344c60, 0xffd3b46c);
                        paint.setColor(0xffffe2a0);
                        canvas.drawCircle(cx, cy + unit * 0.12f, unit * 0.16f, paint);
                    }
                    break;
            }
        }
    }

    /** Rotatable, shaded voxel preview built from 3D box parts and perspective projection. */
    private final class AvatarPreview extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private float rotation = -24.0f;
        private float previousX;
        private float idleTime;
        private long lastFrameMs;
        private long suppressAutoRotationUntilMs;
        private float projectionCenterX;
        private float projectionGround;
        private float projectionUnit;
        private float rotationCos = 1.0f;
        private float rotationSin = 0.0f;

        private final int[] skins = {0xffe8c09c, 0xffc58d69, 0xff9b654c,
                0xfff0c9a8, 0xffad7659, 0xff76513f};
        private final int[] hairColors = {0xff795039, 0xff3a3037, 0xffb8894c,
                0xff415d75, 0xff765b4b, 0xffd4c6a2};
        private final int[] clothes = {0xff3c9d79, 0xff4b75c8, 0xffa85a8a,
                0xffb27b36, 0xff596779, 0xff4b6d52};

        AvatarPreview(Context context) {
            super(context);
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
            setClickable(true);
        }

        private float[] project(float x, float y, float z) {
            float rotatedX = x * rotationCos - z * rotationSin;
            float rotatedZ = x * rotationSin + z * rotationCos;
            return new float[]{
                    projectionCenterX + rotatedX * projectionUnit * 0.92f,
                    projectionGround - y * projectionUnit + rotatedZ * projectionUnit * 0.24f
            };
        }

        private void polygon(Canvas canvas, int color, float[][] points) {
            path.reset();
            float[] first = points[0];
            path.moveTo(first[0], first[1]);
            for (int i = 1; i < points.length; i++) path.lineTo(points[i][0], points[i][1]);
            path.close();
            paint.setColor(color);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawPath(path, paint);
            paint.setColor(0x35ffffff);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(1f, dp(0.55f)));
            canvas.drawPath(path, paint);
            paint.setStyle(Paint.Style.FILL);
        }

        private float[][] points(float[][] vertices) {
            float[][] projected = new float[vertices.length][2];
            for (int i = 0; i < vertices.length; i++)
                projected[i] = project(vertices[i][0], vertices[i][1], vertices[i][2]);
            return projected;
        }

        private float[][] posedPoints(float[][] vertices,
                                      float pivotX, float pivotY, float pivotZ,
                                      float pitch, float yaw, float roll) {
            float cp = (float) Math.cos(pitch), sp = (float) Math.sin(pitch);
            float cy = (float) Math.cos(yaw), sy = (float) Math.sin(yaw);
            float cr = (float) Math.cos(roll), sr = (float) Math.sin(roll);
            float[][] projected = new float[vertices.length][2];
            for (int i = 0; i < vertices.length; i++) {
                float x = vertices[i][0] - pivotX;
                float y = vertices[i][1] - pivotY;
                float z = vertices[i][2] - pivotZ;
                float y1 = y * cp - z * sp;
                float z1 = y * sp + z * cp;
                float x2 = x * cy + z1 * sy;
                float z2 = -x * sy + z1 * cy;
                float x3 = x2 * cr - y1 * sr;
                float y3 = x2 * sr + y1 * cr;
                projected[i] = project(pivotX + x3, pivotY + y3, pivotZ + z2);
            }
            return projected;
        }

        private void cubeAnimated(Canvas canvas, float x, float y, float z,
                                  float halfX, float halfY, float halfZ,
                                  int frontColor, int sideColor, int topColor,
                                  float pivotX, float pivotY, float pivotZ,
                                  float pitch, float yaw, float roll) {
            float left = x - halfX, right = x + halfX;
            float bottom = y - halfY, top = y + halfY;
            float back = z - halfZ, front = z + halfZ;
            int visibleZ = rotationCos >= 0.0f ? 1 : -1;
            int visibleX = -rotationSin >= 0.0f ? 1 : -1;
            float sideX = visibleX > 0 ? right : left;
            float[][] side = visibleX > 0
                    ? new float[][]{{sideX,bottom,back},{sideX,bottom,front},
                                    {sideX,top,front},{sideX,top,back}}
                    : new float[][]{{sideX,bottom,front},{sideX,bottom,back},
                                    {sideX,top,back},{sideX,top,front}};
            polygon(canvas, sideColor, posedPoints(side,pivotX,pivotY,pivotZ,pitch,yaw,roll));
            float frontZ = visibleZ > 0 ? front : back;
            float[][] frontFace = visibleZ > 0
                    ? new float[][]{{left,bottom,frontZ},{right,bottom,frontZ},
                                    {right,top,frontZ},{left,top,frontZ}}
                    : new float[][]{{right,bottom,frontZ},{left,bottom,frontZ},
                                    {left,top,frontZ},{right,top,frontZ}};
            polygon(canvas, frontColor, posedPoints(frontFace,pivotX,pivotY,pivotZ,pitch,yaw,roll));
            float[][] topFace = {{left,top,back},{right,top,back},{right,top,front},{left,top,front}};
            polygon(canvas, topColor, posedPoints(topFace,pivotX,pivotY,pivotZ,pitch,yaw,roll));
        }

        private void cube(Canvas canvas, float x, float y, float z,
                          float halfX, float halfY, float halfZ,
                          int frontColor, int sideColor, int topColor) {
            float left = x - halfX, right = x + halfX;
            float bottom = y - halfY, top = y + halfY;
            float back = z - halfZ, front = z + halfZ;
            int visibleZ = rotationCos >= 0.0f ? 1 : -1;
            int visibleX = -rotationSin >= 0.0f ? 1 : -1;

            // Draw the side nearest the virtual camera, then the front plane, then the top.
            float sideX = visibleX > 0 ? right : left;
            float[][] side = visibleX > 0
                    ? new float[][]{{sideX,bottom,back},{sideX,bottom,front},
                                    {sideX,top,front},{sideX,top,back}}
                    : new float[][]{{sideX,bottom,front},{sideX,bottom,back},
                                    {sideX,top,back},{sideX,top,front}};
            polygon(canvas, sideColor, points(side));

            float frontZ = visibleZ > 0 ? front : back;
            float[][] frontFace = visibleZ > 0
                    ? new float[][]{{left,bottom,frontZ},{right,bottom,frontZ},
                                    {right,top,frontZ},{left,top,frontZ}}
                    : new float[][]{{right,bottom,frontZ},{left,bottom,frontZ},
                                    {left,top,frontZ},{right,top,frontZ}};
            polygon(canvas, frontColor, points(frontFace));

            float[][] topFace = {
                    {left,top,back},{right,top,back},{right,top,front},{left,top,front}
            };
            polygon(canvas, topColor, points(topFace));
        }

        @Override public boolean onTouchEvent(android.view.MotionEvent event) {
            switch (event.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    previousX = event.getX();
                    suppressAutoRotationUntilMs = SystemClock.uptimeMillis() + 1800L;
                    return true;
                case android.view.MotionEvent.ACTION_MOVE:
                    float dx = event.getX() - previousX;
                    previousX = event.getX();
                    rotation += dx * 0.85f;
                    if (rotation > 360f) rotation -= 360f;
                    if (rotation < -360f) rotation += 360f;
                    suppressAutoRotationUntilMs = SystemClock.uptimeMillis() + 1800L;
                    invalidate();
                    return true;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    performClick();
                    return true;
                default:
                    return true;
            }
        }

        @Override public boolean performClick() {
            super.performClick();
            return true;
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            long nowMs = SystemClock.uptimeMillis();
            float frameDelta = lastFrameMs == 0L ? 0.0f : Math.min(0.05f, (nowMs - lastFrameMs) / 1000.0f);
            lastFrameMs = nowMs;
            boolean idleMotion = preferences.getBoolean("avatar_idle_animation", true)
                    && preferences.getBoolean("menu_animations", true)
                    && !preferences.getBoolean("reduce_motion", false)
                    && isShown();
            if (idleMotion) {
                idleTime += frameDelta;
                if (nowMs >= suppressAutoRotationUntilMs) rotation += frameDelta * 4.2f;
                if (rotation > 360f) rotation -= 360f;
                postInvalidateOnAnimation();
            }
            float breath = idleMotion ? (float) Math.sin(idleTime * 1.8f) * 0.027f : 0.0f;
            float armSway = idleMotion ? (float) Math.sin(idleTime * 1.15f) * 0.045f : 0.0f;
            float headSway = idleMotion ? (float) Math.sin(idleTime * 0.68f) * 0.035f : 0.0f;
            float legSway = idleMotion ? (float) Math.sin(idleTime * 1.15f) * 0.012f : 0.0f;
            float blinkPhase = idleTime % 5.0f;
            float eyelid = idleMotion && blinkPhase > 4.78f
                    ? (float) Math.sin((blinkPhase - 4.78f) / 0.22f * Math.PI) : 0.0f;
            float eyeOpen = Math.max(0.08f, 1.0f - eyelid * 0.94f);
            float width = getWidth(), height = getHeight();
            projectionCenterX = width * 0.50f;
            projectionGround = height * 0.875f;
            projectionUnit = Math.min(width / 3.35f, height / 4.20f);
            if (projectionUnit < dp(7)) return;
            float angle = (float) Math.toRadians(rotation);
            rotationCos = (float) Math.cos(angle);
            rotationSin = (float) Math.sin(angle);

            paint.setShader(new RadialGradient(projectionCenterX, height * 0.40f,
                    Math.max(width, height) * 0.76f,
                    new int[]{0x554b9c89, 0x00132639}, null, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, width, height, paint);
            paint.setShader(null);
            paint.setColor(0x667f9bad);
            canvas.drawOval(projectionCenterX - projectionUnit * 1.15f,
                    projectionGround - dp(3),
                    projectionCenterX + projectionUnit * 1.15f,
                    projectionGround + dp(11), paint);

            int skin = skins[avatarStyles[0] % skins.length];
            int hair = hairColors[avatarStyles[1] % hairColors.length];
            int outfit = clothes[avatarStyles[3] % clothes.length];
            int eye = new int[]{0xff8b6843, 0xff63a8d4, 0xff65a978,
                    0xff8796a4, 0xff292c3a, 0xffd4a54c}[avatarStyles[2] % 6];
            int armour = new int[]{0x00000000, 0xff805e49, 0xff89969c,
                    0xff4e7f9b, 0xff9b78c7, 0xffd2dfe3}[avatarStyles[4] % 6];
            int accessory = avatarStyles[5] % 6;

            // Boots, trouser legs, hips and belt.
            cubeAnimated(canvas,-0.22f,0.20f+legSway,0.015f,0.17f,0.19f,0.25f,
                    0xff27384a,0xff172538,0xff63798b,-0.22f,0.40f,0.015f,legSway*0.8f,0,0);
            cubeAnimated(canvas,0.22f,0.20f-legSway,0.015f,0.17f,0.19f,0.25f,
                    0xff27384a,0xff172538,0xff63798b,0.22f,0.40f,0.015f,-legSway*0.8f,0,0);
            cubeAnimated(canvas,-0.22f,0.77f+legSway,0.0f,0.145f,0.42f,0.16f,
                    0xff344b65,0xff203044,0xff75899b,-0.22f,1.18f,0.0f,legSway,0,0);
            cubeAnimated(canvas,0.22f,0.77f-legSway,0.0f,0.145f,0.42f,0.16f,
                    0xff405b78,0xff203044,0xff8294a6,0.22f,1.18f,0.0f,-legSway,0,0);
            cube(canvas,0.0f,1.33f,0.0f,0.37f,0.17f,0.22f,
                    0xffaa7d46,0xff5e452e,0xffe2c27d);

            // Cape layer stays behind the torso and rotates with the character.
            if (accessory == 2) {
                cubeAnimated(canvas,0.0f,1.92f+breath,-0.30f,0.49f,0.83f,0.075f,
                        0xff6b527f,0xff3b2d59,0xffb9a5d9,0.0f,2.6f,-0.30f,armSway*0.6f,0,0);
            }

            // Torso and articulated arm pieces.
            cubeAnimated(canvas,0.0f,1.94f+breath,0.0f,0.44f,0.59f,0.27f,
                    outfit,0xff263b54,0xff9bc39a,0.0f,1.94f,0.0f,breath*0.45f,0,0);
            cubeAnimated(canvas,-0.58f,1.96f,0.0f,0.15f,0.47f,0.18f,
                    outfit,0xff263b54,0xffa7c5a0,-0.58f,2.38f,0.0f,armSway,0,0);
            cubeAnimated(canvas,0.58f,1.96f,0.0f,0.15f,0.47f,0.18f,
                    outfit,0xff263b54,0xffa7c5a0,0.58f,2.38f,0.0f,-armSway,0,0);
            cubeAnimated(canvas,0.0f,2.60f+breath,0.0f,0.17f,0.16f,0.16f,
                    skin,0xff93694e,0xfff5d4b0,0.0f,2.60f,0.0f,0,headSway,0);

            // Head, face, hair cap and selectable hairstyle silhouettes.
            cubeAnimated(canvas,0.0f,3.10f+breath*0.2f,0.0f,0.37f,0.42f,0.31f,
                    skin,0xff95694e,0xfff6d5b3,0.0f,3.10f,0.0f,0,headSway,0);
            cubeAnimated(canvas,0.0f,3.48f+breath*0.2f,-0.015f,0.40f,
                    avatarStyles[1]==5?0.22f:0.13f,0.35f,
                    hair,0xff3c302b,0xffd0a16a,0.0f,3.10f,0.0f,0,headSway,0);
            if (avatarStyles[1]==1 || avatarStyles[1]==2 || avatarStyles[1]==4) {
                cube(canvas,-0.34f,3.12f,-0.015f,0.12f,0.37f,0.26f,
                        hair,0xff3c302b,0xffc18c59);
            }
            if (avatarStyles[1]==3 || avatarStyles[1]==4) {
                cube(canvas,0.33f,2.99f,-0.06f,0.12f,0.52f,0.23f,
                        hair,0xff3c302b,0xffc18c59);
            }
            cubeAnimated(canvas,-0.15f,3.12f,0.319f,0.055f,0.065f*eyeOpen,0.025f,
                    eye,0xff18232d,0xffe7f1f4,0.0f,3.10f,0.0f,0,headSway,0);
            cubeAnimated(canvas,0.15f,3.12f,0.319f,0.055f,0.065f*eyeOpen,0.025f,
                    eye,0xff18232d,0xffe7f1f4,0.0f,3.10f,0.0f,0,headSway,0);
            cube(canvas,0.0f,2.96f,0.326f,0.075f,0.025f,0.015f,
                    0xff9d6558,0xff744c43,0xffc88b79);

            // Armour is layered over the outfit and follows the same rotating model.
            if (avatarStyles[4] > 0) {
                cube(canvas,0.0f,1.99f,0.285f,0.47f,0.42f,0.055f,
                        armour,0xff34495b,0xffe5eef1);
                cube(canvas,-0.58f,2.26f,0.0f,0.22f,0.23f,0.24f,
                        armour,0xff34495b,0xffe5eef1);
                cube(canvas,0.58f,2.26f,0.0f,0.22f,0.23f,0.24f,
                        armour,0xff34495b,0xffe5eef1);
            }

            // Small accessories are distinct geometric parts, not text glyphs.
            if (accessory == 1) {
                cube(canvas,0.0f,2.62f,0.0f,0.55f,0.095f,0.30f,
                        0xffc69a55,0xff79522f,0xffffdb8c);
            } else if (accessory == 3) {
                cube(canvas,0.0f,2.72f,0.32f,0.105f,0.13f,0.045f,
                        0xffffd17b,0xff8f6038,0xffffedb2);
            } else if (accessory == 4) {
                cube(canvas,0.0f,3.70f,0.0f,0.48f,0.08f,0.39f,
                        0xffd9b45d,0xff79552f,0xffffe6a0);
                cube(canvas,0.0f,3.84f,0.0f,0.09f,0.18f,0.085f,
                        0xffe9c36e,0xff83572c,0xffffe7a1);
            } else if (accessory == 5) {
                cube(canvas,0.0f,1.95f,-0.34f,0.25f,0.48f,0.18f,
                        0xff536e7a,0xff283f51,0xff9bbac3);
            }
        }
    }

}
