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
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
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
        drawable.setStroke(dp(1), strokeColor);
        return drawable;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(getContext());
        view.setText(value);
        view.setTextSize(size);
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
        if (!preferences.getBoolean("menu_animations", true)) {
            view.setAlpha(1f);
            return;
        }
        view.setAlpha(0f);
        view.setTranslationY(dp(12));
        view.animate().alpha(1f).translationY(0f)
                .setStartDelay(index * 42L).setDuration(280L)
                .setInterpolator(new DecelerateInterpolator()).start();
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
        LinearLayout root = pageRoot("MUNDOS", "Crea tu mundo o vuelve a tu partida local.");
        LinearLayout card = column();
        card.setPadding(dp(17), dp(15), dp(17), dp(15));
        card.setBackground(shape(new int[]{0xff1b453a, 0xff122c35}, 18, 0x6676b88d));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, 0, 1f);
        cp.topMargin = dp(13);
        root.addView(card, cp);
        LinearLayout top = row();
        FrameLayout icon = new FrameLayout(getContext());
        icon.setBackground(shape(new int[]{0xff315d48, 0xff1b473c}, 13, 0x557acb91));
        TextView glyph = text("▦", 25, 0xffb9f27c, true);
        glyph.setGravity(Gravity.CENTER);
        icon.addView(glyph, new FrameLayout.LayoutParams(-1, -1));
        top.addView(icon, new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout identity = column();
        identity.setPadding(dp(11), 0, 0, 0);
        identity.addView(text(hasSavedWorld ? "MUNDO PRINCIPAL" : "NUEVA AVENTURA", 15, white, true));
        TextView subtitle = text(hasSavedWorld ? "Partida local · guardado encontrado" : "Partida local · mundo por generar", 9, 0xffb7d1c5, false);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2);
        sp.topMargin = dp(4);
        identity.addView(subtitle, sp);
        top.addView(identity, new LinearLayout.LayoutParams(0, -2, 1f));
        top.addView(pill(hasSavedWorld ? "GUARDADO" : "NUEVO", 0xffb9f27c), new LinearLayout.LayoutParams(-2, dp(25)));
        card.addView(top);
        TextView description = text(hasSavedWorld ? "Tu mundo está guardado en este dispositivo. Entra para continuar exactamente donde lo dejaste." : "Todavía no hay ningún mundo guardado. Minefade generará el terreno y abrirá tu primera aventura al continuar.", 10, 0xffc0d3d9, false);
        description.setGravity(Gravity.TOP);
        LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(-1, -2);
        dp.topMargin = this.dp(15);
        card.addView(description, dp);
        LinearLayout facts = row();
        facts.setPadding(this.dp(10), this.dp(9), this.dp(10), this.dp(9));
        facts.setBackground(shape(new int[]{0x332a5a4b, 0x22152d36}, 11, 0x334b8d73));
        facts.addView(text("ALMACENAMIENTO LOCAL", 8, 0xffd1e6d8, true),
                new LinearLayout.LayoutParams(0, this.dp(23), 1f));
        facts.addView(text("SIN CUENTA", 8, 0xffa9c4b6, true));
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(-1, -2);
        fp.topMargin = this.dp(12);
        card.addView(facts, fp);
        View spacer = new View(getContext());
        card.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1f));
        TextView enter = actionButton(hasSavedWorld ? "CONTINUAR PARTIDA" : "CREAR MUNDO Y JUGAR", () -> listener.onPlay());
        LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(-1, this.dp(46));
        ep.topMargin = this.dp(12);
        card.addView(enter, ep);
        TextView note = text("El progreso se guarda automáticamente en el dispositivo.", 8, 0xff9eb9ad, false);
        note.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(-1, this.dp(25));
        np.topMargin = this.dp(5);
        card.addView(note, np);
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
        root.setPadding(dp(18), dp(12), dp(18), dp(12));
        addView(root, new LayoutParams(-1, -1));

        LinearLayout header = row();
        TextView back = text("‹", 31, white, true);
        back.setGravity(Gravity.CENTER);
        back.setBackground(shape(new int[]{0x442d4659, 0x22202d3b}, 12, 0x66516c82));
        back.setOnClickListener(v -> showHome());
        header.addView(back, new LinearLayout.LayoutParams(dp(42), dp(38)));
        LinearLayout titleBox = column();
        titleBox.setPadding(dp(10), 0, 0, 0);
        titleBox.addView(text(title, 18, white, true));
        TextView desc = text(subtitle, 9, muted, false);
        LinearLayout.LayoutParams descParams = new LinearLayout.LayoutParams(-1, -2);
        descParams.topMargin = dp(3);
        titleBox.addView(desc, descParams);
        header.addView(titleBox, new LinearLayout.LayoutParams(0, -2, 1f));
        root.addView(header, new LinearLayout.LayoutParams(-1, dp(42)));
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
        LinearLayout root = pageRoot("AVATAR", "Personaliza cada pieza del personaje.");
        LinearLayout body = row();
        LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(-1, 0, 1f);
        bodyParams.topMargin = dp(9);
        root.addView(body, bodyParams);

        // The editor occupies the left side; the character remains large and
        // visible on the right while the user changes individual appearance slots.
        LinearLayout editor = column();
        editor.setPadding(0, 0, dp(9), 0);
        body.addView(editor, new LinearLayout.LayoutParams(0, -1, 1.16f));

        String[] moduleLabels = {"PIEL", "CABELLO", "OJOS", "ROPA", "ARMADURA", "ACCESORIOS"};
        for (int r = 0; r < 2; r++) {
            LinearLayout tabs = row();
            LinearLayout.LayoutParams tabsParams = new LinearLayout.LayoutParams(-1, dp(34));
            if (r > 0) tabsParams.topMargin = dp(5);
            editor.addView(tabs, tabsParams);
            for (int col = 0; col < 3; col++) {
                final int module = r * 3 + col;
                boolean selected = avatarModuleIndex == module;
                TextView tab = text(moduleLabels[module], 8,
                        selected ? 0xffd8ffb5 : 0xffa9bfcd, true);
                tab.setGravity(Gravity.CENTER);
                tab.setBackground(shape(selected
                        ? new int[]{0xff345b48, 0xff203e39}
                        : new int[]{0xff203448, 0xff15283a},
                        9, selected ? 0xff83c76f : 0x554b667b));
                tab.setOnClickListener(v -> {
                    haptic(v);
                    avatarModuleIndex = module;
                    preferences.edit().putInt("avatar_module", module).apply();
                    showAvatarPage();
                });
                LinearLayout.LayoutParams tabParams = new LinearLayout.LayoutParams(0, -1, 1f);
                if (col > 0) tabParams.leftMargin = dp(5);
                tabs.addView(tab, tabParams);
            }
        }

        LinearLayout optionsPanel = column();
        optionsPanel.setPadding(dp(8), dp(7), dp(8), dp(5));
        optionsPanel.setBackground(shape(new int[]{0x4420394e, 0x22101e2d}, 13, 0x443d5d72));
        LinearLayout.LayoutParams panelParams = new LinearLayout.LayoutParams(-1, 0, 1f);
        panelParams.topMargin = dp(7);
        editor.addView(optionsPanel, panelParams);

        String[][] optionNames = {
                {"Marfil", "Arena", "Miel", "Cobre", "Ébano", "Oliva"},
                {"Corto", "Despeinado", "Ondulado", "Largo", "Trenzado", "Cresta"},
                {"Avellana", "Azules", "Verdes", "Grises", "Oscuros", "Ámbar"},
                {"Aventurero", "Explorador", "Artesano", "Nómada", "Guardián", "Real"},
                {"Sin placas", "Cuero", "Hierro", "Cobalto", "Cristal", "Élite"},
                {"Ninguno", "Hombrera", "Capa", "Amuleto", "Diadema", "Mochila"}
        };
        TextView section = text(moduleLabels[avatarModuleIndex] + "  ·  SELECCIÓN",
                9, 0xffc7dce7, true);
        LinearLayout.LayoutParams sectionParams = new LinearLayout.LayoutParams(-1, dp(19));
        sectionParams.bottomMargin = dp(5);
        optionsPanel.addView(section, sectionParams);

        for (int r = 0; r < 2; r++) {
            LinearLayout line = row();
            LinearLayout.LayoutParams lineParams = new LinearLayout.LayoutParams(-1, 0, 1f);
            if (r > 0) lineParams.topMargin = dp(5);
            optionsPanel.addView(line, lineParams);
            for (int col = 0; col < 3; col++) {
                final int choice = r * 3 + col;
                boolean selected = avatarStyles[avatarModuleIndex] == choice;
                LinearLayout tile = column();
                tile.setGravity(Gravity.CENTER);
                tile.setPadding(dp(3), dp(3), dp(3), dp(3));
                tile.setBackground(shape(selected
                        ? new int[]{0xff365747, 0xff203e3a}
                        : new int[]{0xff263c50, 0xff172b3f},
                        10, selected ? 0xff91d47a : 0x444b6b80));
                FrameLayout icon = new FrameLayout(getContext());
                icon.addView(new AvatarOptionView(getContext(), avatarModuleIndex, choice),
                        new FrameLayout.LayoutParams(-1, -1));
                tile.addView(icon, new LinearLayout.LayoutParams(-1, 0, 1f));
                TextView label = text(optionNames[avatarModuleIndex][choice], 7,
                        selected ? 0xffe2f7d4 : 0xffc1d0d8, selected);
                label.setGravity(Gravity.CENTER);
                label.setMaxLines(1);
                LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(-1, dp(15));
                labelParams.topMargin = dp(2);
                tile.addView(label, labelParams);
                tile.setOnClickListener(v -> {
                    haptic(v);
                    avatarStyles[avatarModuleIndex] = choice;
                    preferences.edit().putInt("avatar_" + avatarModules[avatarModuleIndex], choice).apply();
                    showAvatarPage();
                });
                LinearLayout.LayoutParams cell = new LinearLayout.LayoutParams(0, -1, 1f);
                if (col > 0) cell.leftMargin = dp(5);
                line.addView(tile, cell);
            }
        }

        FrameLayout previewCard = new FrameLayout(getContext());
        previewCard.setBackground(shape(new int[]{0xff1a3449, 0xff101b2b}, 17, 0x665a7b92));
        previewCard.setClipToOutline(true);
        body.addView(previewCard, new LinearLayout.LayoutParams(0, -1, 0.84f));
        LinearLayout previewOverlay = column();
        previewOverlay.setPadding(dp(8), dp(8), dp(8), dp(8));
        previewOverlay.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        previewCard.addView(previewOverlay, new FrameLayout.LayoutParams(-1, -1));
        TextView previewTitle = text("VISTA DEL PERSONAJE", 8, 0xffc8dce7, true);
        previewTitle.setGravity(Gravity.CENTER);
        previewOverlay.addView(previewTitle, new LinearLayout.LayoutParams(-1, dp(18)));
        avatarPreview = new AvatarPreview(getContext());
        FrameLayout previewFrame = new FrameLayout(getContext());
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(-1, 0, 1f);
        previewParams.topMargin = dp(4);
        previewOverlay.addView(previewFrame, previewParams);
        previewFrame.addView(avatarPreview, new FrameLayout.LayoutParams(-1, -1));
        TextView rotate = text("DESLIZA PARA GIRAR", 7, 0xff91aaba, true);
        rotate.setGravity(Gravity.CENTER);
        previewOverlay.addView(rotate, new LinearLayout.LayoutParams(-1, dp(17)));
    }

    private void showSettingsPage() {
        LinearLayout root = pageRoot("AJUSTES", "Opciones agrupadas por categoría.");
        LinearLayout body = row();
        LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(-1, 0, 1f);
        bodyParams.topMargin = dp(9);
        root.addView(body, bodyParams);

        LinearLayout navigation = column();
        navigation.setPadding(0, 0, dp(7), 0);
        body.addView(navigation, new LinearLayout.LayoutParams(0, -1, 0.66f));
        String[][] modules = {
                {"video", "VÍDEO", "Imagen y rendimiento"},
                {"audio", "AUDIO", "Efectos y volumen"},
                {"controls", "CONTROLES", "Tacto y cámara"},
                {"interface", "INTERFAZ", "HUD y animaciones"},
                {"accessibility", "ACCESIBILIDAD", "Comodidad visual"}
        };
        for (String[] module : modules) {
            boolean active = settingsModule.equals(module[0]);
            LinearLayout item = row();
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setPadding(dp(8), dp(5), dp(5), dp(5));
            item.setBackground(shape(active
                    ? new int[]{0xff315443, 0xff1d3a3a}
                    : new int[]{0xff203448, 0xff152638},
                    10, active ? 0xff83c76f : 0x444d687c));
            LinearLayout labels = column();
            labels.addView(text(module[1], 8, active ? 0xffe1ffd0 : white, true));
            TextView description = text(module[2], 7, muted, false);
            LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(-1, -2);
            dp.topMargin = dp(2);
            labels.addView(description, dp);
            item.addView(labels, new LinearLayout.LayoutParams(0, -2, 1f));
            item.setOnClickListener(v -> {
                haptic(v);
                settingsModule = module[0];
                preferences.edit().putString("settings_module", settingsModule).apply();
                showSettingsPage();
            });
            LinearLayout.LayoutParams itemParams = new LinearLayout.LayoutParams(-1, 0, 1f);
            itemParams.bottomMargin = dp(5);
            navigation.addView(item, itemParams);
        }

        ScrollView scroll = new ScrollView(getContext());
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        LinearLayout content = column();
        content.setPadding(dp(9), dp(8), dp(8), dp(9));
        content.setBackground(shape(new int[]{0x33233d51, 0x22101d2b}, 13, 0x33445f73));
        scroll.addView(content);
        body.addView(scroll, new LinearLayout.LayoutParams(0, -1, 1.64f));

        switch (settingsModule) {
            case "audio":
                settingsHeading(content, "SONIDO DEL JUEGO", "Ajusta la mezcla de efectos que se reproduce en el dispositivo.");
                content.addView(soundVolumeControl());
                content.addView(settingSwitch("Efectos de juego", "Rotura, colocación, pasos, salto y recogida.",
                        "sound_effects_enabled", true));
                content.addView(settingSwitch("Sonidos de interfaz", "Confirmaciones y pulsaciones del menú.",
                        "ui_sounds_enabled", true));
                break;
            case "controls":
                settingsHeading(content, "CONTROLES TÁCTILES", "Sensibilidad y orientación de la cámara.");
                content.addView(settingSlider("Sensibilidad de cámara", "Velocidad de giro al deslizar.",
                        "touch_sensitivity", 35, 150, 100, "%"));
                content.addView(settingSwitch("Invertir eje vertical", "Invierte el movimiento arriba y abajo.",
                        "invert_look_y", false));
                content.addView(settingSwitch("Animación de respuesta táctil", "Pequeña vibración al pulsar controles.",
                        "menu_haptics", true));
                break;
            case "interface":
                settingsHeading(content, "INTERFAZ Y HUD", "Preferencias visuales de los menús y la pantalla de juego.");
                content.addView(settingSwitch("Animaciones de interfaz", "Transiciones suaves entre pantallas.",
                        "menu_animations", true));
                content.addView(settingSwitch("Vibración de interfaz", "Respuesta táctil en botones del menú.",
                        "menu_haptics", true));
                content.addView(settingSlider("Escala del HUD", "Tamaño preferido de los elementos de pantalla.",
                        "hud_scale", 80, 120, 100, "%"));
                break;
            case "accessibility":
                settingsHeading(content, "ACCESIBILIDAD", "Reduce efectos visuales y adapta la lectura.");
                content.addView(settingSwitch("Reducir movimiento", "Evita desplazamientos y entradas animadas.",
                        "reduce_motion", false));
                content.addView(settingSwitch("Contraste reforzado", "Aumenta la separación visual de los paneles.",
                        "high_contrast", false));
                content.addView(settingSlider("Tamaño del texto", "Preferencia de tamaño para los menús.",
                        "menu_text_scale", 85, 125, 100, "%"));
                break;
            default:
                settingsHeading(content, "VÍDEO Y RENDIMIENTO", "Opciones gráficas que se aplican al iniciar el mundo.");
                content.addView(settingChoice("Calidad gráfica", "Iluminación y coste de renderizado.",
                        "graphics_quality", new String[]{"Ahorro", "Equilibrado", "Alta"}, 1));
                content.addView(settingChoice("Distancia de visión", "Más chunks visibles requieren más memoria y GPU.",
                        "render_distance", new String[]{"Cercana · 3×3", "Normal · 5×5", "Amplia · 7×7"}, 1));
                content.addView(settingSwitch("Ondulación del agua", "Animación suave de la superficie del agua.",
                        "water_waves", true));
                TextView note = text("Los cambios gráficos se cargan la próxima vez que entres a un mundo.", 8, muted, false);
                note.setGravity(Gravity.TOP);
                LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(-1, -2);
                np.topMargin = dp(7);
                content.addView(note, np);
                break;
        }
    }

    private void settingsHeading(LinearLayout parent, String title, String subtitle) {
        TextView heading = text(title, 10, 0xffcbe2ed, true);
        parent.addView(heading);
        TextView detail = text(subtitle, 8, muted, false);
        detail.setGravity(Gravity.TOP);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(4);
        params.bottomMargin = dp(9);
        parent.addView(detail, params);
    }

    private View settingChoice(String title, String subtitle, String key,
                               String[] entries, int defaultValue) {
        LinearLayout card = column();
        card.setPadding(dp(10), dp(8), dp(10), dp(7));
        card.setBackground(shape(new int[]{0xff1c3043, 0xff142334}, 11, 0x554b6478));
        card.addView(text(title, 10, white, true));
        TextView detail = text(subtitle, 8, muted, false);
        LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(-1, -2);
        detailParams.topMargin = dp(3);
        card.addView(detail, detailParams);
        Spinner spinner = new Spinner(getContext());
        ArrayAdapter<String> adapter = new ArrayAdapter<>(getContext(),
                android.R.layout.simple_spinner_item, entries);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setSelection(Math.max(0, Math.min(entries.length - 1,
                preferences.getInt(key, defaultValue))));
        spinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view,
                                                 int position, long id) {
                preferences.edit().putInt(key, position).apply();
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });
        LinearLayout.LayoutParams spinnerParams = new LinearLayout.LayoutParams(-1, dp(37));
        spinnerParams.topMargin = dp(3);
        card.addView(spinner, spinnerParams);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(7);
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
        card.setBackground(shape(new int[]{0xff1c3043, 0xff142334}, 13, 0x554b6478));
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
        card.setPadding(dp(12), dp(7), dp(10), dp(7));
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
    private static final class AvatarIconView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final int kind;
        private final int tint;
        AvatarIconView(Context context, int kind, int tint) {
            super(context);
            this.kind = kind;
            this.tint = tint;
        }
        private void poly(Canvas canvas, float... points) {
            path.reset();
            path.moveTo(points[0], points[1]);
            for (int i = 2; i < points.length; i += 2) path.lineTo(points[i], points[i + 1]);
            path.close();
            canvas.drawPath(path, paint);
        }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float cx = getWidth() * 0.5f, cy = getHeight() * 0.5f;
            float s = Math.min(getWidth(), getHeight()) * 0.30f;
            paint.setColor(tint);
            paint.setStyle(Paint.Style.FILL);
            switch (kind) {
                case 0: // skin tones
                    paint.setColor(0xffeac19d); canvas.drawCircle(cx - s * 0.45f, cy, s * 0.43f, paint);
                    paint.setColor(0xffc58c67); canvas.drawCircle(cx + s * 0.45f, cy, s * 0.43f, paint);
                    break;
                case 1: // hair
                    canvas.drawRoundRect(new RectF(cx - s, cy - s * 0.65f, cx + s, cy + s * 0.8f),
                            s * 0.4f, s * 0.4f, paint);
                    paint.setColor(0xffd3a47e);
                    canvas.drawRoundRect(new RectF(cx - s * 0.66f, cy - s * 0.18f, cx + s * 0.66f, cy + s),
                            s * 0.25f, s * 0.25f, paint);
                    paint.setColor(tint);
                    canvas.drawRoundRect(new RectF(cx - s, cy - s * 0.8f, cx + s, cy - s * 0.12f),
                            s * 0.3f, s * 0.3f, paint);
                    break;
                case 2: // eyes
                    paint.setColor(0xffdbeaf0);
                    canvas.drawOval(new RectF(cx - s, cy - s * 0.48f, cx - s * 0.05f, cy + s * 0.48f), paint);
                    canvas.drawOval(new RectF(cx + s * 0.05f, cy - s * 0.48f, cx + s, cy + s * 0.48f), paint);
                    paint.setColor(0xff60b7d2);
                    canvas.drawCircle(cx - s * 0.5f, cy, s * 0.22f, paint);
                    canvas.drawCircle(cx + s * 0.5f, cy, s * 0.22f, paint);
                    break;
                case 3: // face
                    paint.setColor(0xffd2a17d);
                    canvas.drawRoundRect(new RectF(cx - s * 0.75f, cy - s, cx + s * 0.75f, cy + s),
                            s * 0.38f, s * 0.38f, paint);
                    paint.setColor(0xff49352c);
                    canvas.drawCircle(cx - s * 0.30f, cy - s * 0.1f, s * 0.10f, paint);
                    canvas.drawCircle(cx + s * 0.30f, cy - s * 0.1f, s * 0.10f, paint);
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(s * 0.08f);
                    canvas.drawArc(new RectF(cx - s * 0.3f, cy + s * 0.05f, cx + s * 0.3f, cy + s * 0.48f),
                            10, 160, false, paint);
                    paint.setStyle(Paint.Style.FILL);
                    break;
                case 4: // clothes
                    poly(canvas, cx - s * 0.65f, cy - s * 0.8f, cx - s * 0.20f, cy - s,
                            cx, cy - s * 0.65f, cx + s * 0.2f, cy - s,
                            cx + s * 0.65f, cy - s * 0.8f, cx + s, cy - s * 0.3f,
                            cx + s * 0.65f, cy + s * 0.1f, cx + s * 0.5f, cy,
                            cx + s * 0.5f, cy + s, cx - s * 0.5f, cy + s,
                            cx - s * 0.5f, cy, cx - s * 0.65f, cy + s * 0.1f,
                            cx - s, cy - s * 0.3f);
                    break;
                case 5: // armour
                    poly(canvas, cx - s * 0.85f, cy - s * 0.65f, cx - s * 0.25f, cy - s,
                            cx, cy - s * 0.62f, cx + s * 0.25f, cy - s,
                            cx + s * 0.85f, cy - s * 0.65f, cx + s * 0.6f, cy + s,
                            cx - s * 0.6f, cy + s);
                    paint.setColor(0xffd5e4ea);
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(s * 0.10f);
                    canvas.drawLine(cx, cy - s * 0.58f, cx, cy + s * 0.88f, paint);
                    canvas.drawLine(cx - s * 0.55f, cy, cx + s * 0.55f, cy, paint);
                    paint.setStyle(Paint.Style.FILL);
                    break;
                case 6: // accessories
                    paint.setColor(0xffffd177);
                    canvas.drawCircle(cx, cy, s * 0.80f, paint);
                    paint.setColor(0xff6d4b34);
                    canvas.drawCircle(cx, cy, s * 0.50f, paint);
                    paint.setColor(0xffffd177);
                    canvas.drawCircle(cx, cy, s * 0.20f, paint);
                    break;
                default: // palette/style
                    paint.setColor(0xffd5a0ef);
                    canvas.drawCircle(cx - s * 0.32f, cy - s * 0.2f, s * 0.53f, paint);
                    paint.setColor(0xff70c9e1);
                    canvas.drawCircle(cx + s * 0.30f, cy - s * 0.23f, s * 0.48f, paint);
                    paint.setColor(0xff9fdf75);
                    canvas.drawCircle(cx + s * 0.18f, cy + s * 0.42f, s * 0.48f, paint);
                    break;
            }
        }
    }

    private static final class Backdrop extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        Backdrop(Context context) { super(context); }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth(), h = getHeight();
            paint.setShader(new LinearGradient(0, 0, w, h,
                    new int[]{0xff0b1726, 0xff10283a, 0xff0b1422},
                    null, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, paint);
            paint.setShader(null);
            paint.setColor(0x1c8bd5ef);
            for (int x = 0; x < w; x += 38) canvas.drawLine(x, 0, x, h, paint);
            for (int y = 0; y < h; y += 38) canvas.drawLine(0, y, w, y, paint);
            paint.setShader(new RadialGradient(w * 0.76f, h * 0.22f,
                    Math.max(w, h) * 0.65f,
                    new int[]{0x3342bb8a, 0x00152c3d},
                    null, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, paint);
            paint.setShader(null);
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

    /** Perspective character preview; drag horizontally to rotate the voxel model. */
    private final class AvatarPreview extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private float rotation = -17.0f;
        private float previousX;
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
            float dx = depth * 0.52f, dy = depth * 0.34f;
            polygon(canvas, side, right, upper, right + dx, upper - dy,
                    right + dx, lower - dy, right, lower);
            polygon(canvas, top, left, upper, left + dx, upper - dy,
                    right + dx, upper - dy, right, upper);
            polygon(canvas, front, left, upper, right, upper, right, lower, left, lower);
            paint.setColor(0x36ffffff);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(1f, dp(0.7f)));
            canvas.drawLine(left + dp(2), upper + dp(2), right - dp(2), upper + dp(2), paint);
            paint.setStyle(Paint.Style.FILL);
        }
        @Override public boolean onTouchEvent(android.view.MotionEvent event) {
            switch (event.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    previousX = event.getX();
                    return true;
                case android.view.MotionEvent.ACTION_MOVE:
                    float dx = event.getX() - previousX;
                    previousX = event.getX();
                    rotation += dx * 0.72f;
                    if (rotation > 360f) rotation -= 360f;
                    if (rotation < -360f) rotation += 360f;
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
            float w = getWidth(), h = getHeight();
            float cx = w * 0.50f, ground = h * 0.88f;
            float unit = Math.min(w * 0.27f, h * 0.135f);
            if (unit < dp(8)) return;
            paint.setShader(new RadialGradient(cx, h * 0.38f, Math.max(w, h) * 0.75f,
                    new int[]{0x444c9b88, 0x00132639}, null, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, paint);
            paint.setShader(null);
            paint.setColor(0x557f9bad);
            canvas.drawOval(cx - unit * 1.55f, ground - dp(4),
                    cx + unit * 1.55f, ground + dp(11), paint);

            float facing = (float) Math.cos(Math.toRadians(rotation));
            float widthScale = 0.58f + Math.abs(facing) * 0.42f;
            int skin = skins[avatarStyles[0] % skins.length];
            int hair = hairColors[avatarStyles[1] % hairColors.length];
            int outfit = clothes[avatarStyles[3] % clothes.length];
            int eye = new int[]{0xff8b6843, 0xff63a8d4, 0xff65a978,
                    0xff8796a4, 0xff292c3a, 0xffd4a54c}[avatarStyles[2] % 6];
            int armour = new int[]{0x00000000, 0xff805e49, 0xff89969c,
                    0xff4e7f9b, 0xff9b78c7, 0xffd2dfe3}[avatarStyles[4] % 6];
            int accessory = avatarStyles[5] % 6;

            canvas.save();
            canvas.translate(cx, 0);
            canvas.scale(widthScale, 1f, 0f, 0f);
            float x = 0f;
            // Legs and boots.
            cube(canvas, x - unit * 0.25f, ground - unit * 0.54f, unit * 0.34f,
                    unit * 0.91f, unit * 0.22f, 0xff344b65, 0xff203044, 0xff75899b);
            cube(canvas, x + unit * 0.25f, ground - unit * 0.54f, unit * 0.34f,
                    unit * 0.91f, unit * 0.22f, 0xff405b78, 0xff203044, 0xff8294a6);
            cube(canvas, x - unit * 0.25f, ground - unit * 0.12f, unit * 0.39f,
                    unit * 0.25f, unit * 0.24f, 0xff27384a, 0xff172538, 0xff63798b);
            cube(canvas, x + unit * 0.25f, ground - unit * 0.12f, unit * 0.39f,
                    unit * 0.25f, unit * 0.24f, 0xff27384a, 0xff172538, 0xff63798b);

            // Torso, shoulders and articulated arms.
            cube(canvas, x, ground - unit * 1.60f, unit * 0.92f, unit * 1.23f,
                    unit * 0.34f, outfit, 0xff263b54, 0xff9bc39a);
            cube(canvas, x - unit * 0.59f, ground - unit * 1.56f,
                    unit * 0.29f, unit * 0.98f, unit * 0.22f,
                    outfit, 0xff263b54, 0xffa7c5a0);
            cube(canvas, x + unit * 0.59f, ground - unit * 1.56f,
                    unit * 0.29f, unit * 0.98f, unit * 0.22f,
                    outfit, 0xff263b54, 0xffa7c5a0);
            cube(canvas, x, ground - unit * 2.19f, unit * 0.48f, unit * 0.22f,
                    unit * 0.30f, 0xffd0b07a, 0xff71583e, 0xfff0d4a1);

            // Neck and head.
            cube(canvas, x, ground - unit * 2.36f, unit * 0.30f, unit * 0.30f,
                    unit * 0.24f, skin, 0xff93694e, 0xfff5d4b0);
            cube(canvas, x, ground - unit * 2.86f, unit * 0.74f, unit * 0.86f,
                    unit * 0.30f, skin, 0xff95694e, 0xfff6d5b3);
            // Hair cap and module-specific silhouette.
            cube(canvas, x, ground - unit * 3.24f, unit * 0.82f,
                    avatarStyles[1] == 5 ? unit * 0.40f : unit * 0.25f,
                    unit * 0.36f, hair, 0xff3c302b, 0xffd0a16a);
            if (avatarStyles[1] == 1 || avatarStyles[1] == 2 || avatarStyles[1] == 4) {
                cube(canvas, x - unit * 0.35f, ground - unit * 2.91f,
                        unit * 0.22f, unit * 0.75f, unit * 0.24f,
                        hair, 0xff3c302b, 0xffc18c59);
            }
            if (avatarStyles[1] == 3) {
                cube(canvas, x + unit * 0.43f, ground - unit * 2.66f,
                        unit * 0.20f, unit * 0.85f, unit * 0.20f,
                        hair, 0xff3c302b, 0xffc18c59);
            }

            // Facial details on the visible front plane.
            paint.setColor(eye);
            canvas.drawOval(new RectF(x - unit * 0.22f, ground - unit * 2.93f,
                    x - unit * 0.09f, ground - unit * 2.77f), paint);
            canvas.drawOval(new RectF(x + unit * 0.09f, ground - unit * 2.93f,
                    x + unit * 0.22f, ground - unit * 2.77f), paint);
            paint.setColor(0xff18232d);
            canvas.drawCircle(x - unit * 0.155f, ground - unit * 2.85f, unit * 0.045f, paint);
            canvas.drawCircle(x + unit * 0.155f, ground - unit * 2.85f, unit * 0.045f, paint);
            paint.setColor(0xff9d6558);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(1f, unit * 0.025f));
            canvas.drawArc(new RectF(x - unit * 0.13f, ground - unit * 2.70f,
                    x + unit * 0.13f, ground - unit * 2.53f), 15, 150, false, paint);
            paint.setStyle(Paint.Style.FILL);

            // Armour overlays the clothes instead of replacing the base outfit.
            if (avatarStyles[4] > 0) {
                cube(canvas, x, ground - unit * 1.67f, unit * 0.98f, unit * 0.83f,
                        unit * 0.36f, armour, 0xff34495b, 0xffe5eef1);
                cube(canvas, x - unit * 0.60f, ground - unit * 1.88f,
                        unit * 0.38f, unit * 0.44f, unit * 0.25f,
                        armour, 0xff34495b, 0xffe5eef1);
                cube(canvas, x + unit * 0.60f, ground - unit * 1.88f,
                        unit * 0.38f, unit * 0.44f, unit * 0.25f,
                        armour, 0xff34495b, 0xffe5eef1);
            }
            // Accessories are separate geometry layers.
            if (accessory == 1) {
                cube(canvas, x, ground - unit * 2.18f, unit * 0.98f, unit * 0.18f,
                        unit * 0.28f, 0xffc69a55, 0xff79522f, 0xffffdb8c);
            } else if (accessory == 2) {
                cube(canvas, x + unit * 0.02f, ground - unit * 1.52f,
                        unit * 1.13f, unit * 1.43f, unit * 0.08f,
                        0xff6b527f, 0xff3b2d59, 0xffb9a5d9);
            } else if (accessory == 3) {
                paint.setColor(0xffffd17b);
                canvas.drawCircle(x, ground - unit * 2.15f, unit * 0.11f, paint);
            } else if (accessory == 4) {
                cube(canvas, x, ground - unit * 3.34f, unit * 0.90f, unit * 0.15f,
                        unit * 0.35f, 0xffd9b45d, 0xff79552f, 0xffffe6a0);
            } else if (accessory == 5) {
                cube(canvas, x, ground - unit * 1.56f, unit * 0.44f, unit * 0.89f,
                        unit * 0.34f, 0xff536e7a, 0xff283f51, 0xff9bbac3);
            }
            canvas.restore();
        }
    }

}
