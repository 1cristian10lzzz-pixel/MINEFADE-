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
import android.widget.TextView;

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
    }

    private final Listener listener;
    private final SharedPreferences preferences;
    private final int white = Color.rgb(241, 247, 250);
    private final int muted = Color.rgb(157, 177, 190);
    private final int panel = Color.rgb(17, 31, 45);
    private int avatarTone = 0;
    private int avatarOutfit = 0;
    private AvatarPreview avatarPreview;

    public MinefadeHomeScreen(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        preferences = context.getSharedPreferences("minefade_ui", Context.MODE_PRIVATE);
        avatarTone = preferences.getInt("avatar_tone", 0);
        avatarOutfit = preferences.getInt("avatar_outfit", 0);
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
        TextView glyph = text(icon, 20, white, true);
        glyph.setGravity(Gravity.CENTER);
        iconBox.addView(glyph, new FrameLayout.LayoutParams(-1, -1));
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
        TextView status = pill("NATIVE ENGINE  ·  PREVIEW", 0xff9bdd70);
        header.addView(status, new LinearLayout.LayoutParams(-2, dp(28)));
        shell.addView(header, new LinearLayout.LayoutParams(-1, dp(38)));

        LinearLayout body = row();
        LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(-1, 0, 1f);
        bodyParams.topMargin = dp(7);
        shell.addView(body, bodyParams);

        LinearLayout left = column();
        left.setPadding(dp(2), dp(2), dp(10), dp(1));
        body.addView(left, new LinearLayout.LayoutParams(0, -1, 0.94f));

        TextView headline = text("Tu mundo. Tus reglas.", 21, white, true);
        left.addView(headline);
        TextView description = text("Explora, construye y descubre.", 10, muted, false);
        LinearLayout.LayoutParams descriptionParams = new LinearLayout.LayoutParams(-1, -2);
        descriptionParams.topMargin = dp(4);
        descriptionParams.bottomMargin = dp(8);
        left.addView(description, descriptionParams);

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
        TextView footer = text("Mundos guardados · Generación procedural", 9, 0xffa5bac8, false);
        LinearLayout.LayoutParams footParams = new LinearLayout.LayoutParams(-1, -2);
        footParams.topMargin = dp(9);
        overlay.addView(footer, footParams);

        TextView bottom = text("MINEFADE 1.0  ·  DESARROLLO POR FASES", 8, 0xff78909f, true);
        bottom.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams bottomParams = new LinearLayout.LayoutParams(-1, dp(16));
        bottomParams.topMargin = dp(3);
        shell.addView(bottom, bottomParams);
    }

    private void showWorlds() {
        showSimplePage("MUNDOS", "Tu partida local está preparada para continuar.",
                "▣", new int[]{0xff173c35, 0xff13293a},
                "PARTIDA LOCAL", "El mundo se guarda en el almacenamiento privado de Minefade.",
                "ENTRAR AL MUNDO", () -> listener.onPlay());
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
        LinearLayout root = pageRoot("AVATAR", "Editor visual compacto · cuatro módulos por fila.");
        LinearLayout body = row();
        LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(-1, 0, 1f);
        bodyParams.topMargin = dp(12);
        root.addView(body, bodyParams);

        FrameLayout previewCard = new FrameLayout(getContext());
        previewCard.setBackground(shape(new int[]{0xff172d42, 0xff101b2c}, 18, 0x665a7b92));
        avatarPreview = new AvatarPreview(getContext());
        previewCard.addView(avatarPreview, new FrameLayout.LayoutParams(-1, -1));
        body.addView(previewCard, new LinearLayout.LayoutParams(0, -1, 1.02f));

        LinearLayout options = column();
        options.setPadding(dp(10), 0, 0, 0);
        body.addView(options, new LinearLayout.LayoutParams(0, -1, 0.98f));

        String[] glyphs = {"◐", "▰", "◉", "◇", "♜", "✧", "◈", "⌁"};
        String[] descriptions = {
                "Tono de piel", "Cabello", "Ojos", "Rasgos",
                "Vestimenta", "Armadura", "Accesorios", "Estilo"
        };
        for (int rowIndex = 0; rowIndex < 2; rowIndex++) {
            LinearLayout line = row();
            LinearLayout.LayoutParams lineParams = new LinearLayout.LayoutParams(-1, 0, 1f);
            lineParams.bottomMargin = dp(7);
            options.addView(line, lineParams);
            for (int col = 0; col < 4; col++) {
                final int index = rowIndex * 4 + col;
                FrameLayout tile = new FrameLayout(getContext());
                int accent = (index % 2 == 0) ? 0xffa9ee70 : 0xff9ed9f4;
                tile.setBackground(shape(new int[]{0xff2a4357, 0xff192e42}, 12, 0x66516e83));
                TextView glyph = text(glyphs[index], 22, accent, true);
                glyph.setGravity(Gravity.CENTER);
                tile.addView(glyph, new FrameLayout.LayoutParams(-1, -1));
                tile.setContentDescription(descriptions[index]);
                tile.setOnClickListener(v -> {
                    haptic(v);
                    if (index <= 3) {
                        avatarTone = (avatarTone + 1) % 4;
                        preferences.edit().putInt("avatar_tone", avatarTone).apply();
                    } else {
                        avatarOutfit = (avatarOutfit + 1) % 5;
                        preferences.edit().putInt("avatar_outfit", avatarOutfit).apply();
                    }
                    avatarPreview.invalidate();
                });
                LinearLayout.LayoutParams cell = new LinearLayout.LayoutParams(0, -1, 1f);
                if (col > 0) cell.leftMargin = dp(5);
                line.addView(tile, cell);
            }
        }

        TextView note = text("Toca los iconos para cambiar la vista previa. Los cambios de tono y vestimenta se guardan en este dispositivo; la vinculación con todas las piezas del modelo 3D del mundo seguirá ampliándose dentro de esta fase.", 9, muted, false);
        note.setGravity(Gravity.TOP);
        LinearLayout.LayoutParams noteParams = new LinearLayout.LayoutParams(-1, dp(56));
        options.addView(note, noteParams);
    }

    private void showSettingsPage() {
        LinearLayout root = pageRoot("AJUSTES", "Preferencias visuales y comodidad de uso.");
        ScrollView scroll = new ScrollView(getContext());
        scroll.setFillViewport(true);
        LinearLayout settings = column();
        settings.setPadding(0, dp(10), 0, dp(8));
        scroll.addView(settings);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        settings.addView(settingSwitch("Animaciones de interfaz", "Transiciones suaves en los menús.",
                "menu_animations", true));
        settings.addView(settingSwitch("Respuesta táctil", "Vibración ligera al pulsar botones.",
                "menu_haptics", true));

        LinearLayout info = column();
        info.setPadding(dp(13), dp(11), dp(13), dp(11));
        info.setBackground(shape(new int[]{0x332e4b60, 0x22162435}, 13, 0x334f7087));
        TextView title = text("PERFILES GRÁFICOS", 10, 0xffb7d4e3, true);
        info.addView(title);
        TextView body = text("Los perfiles de calidad, distancia de renderizado y sombras se conectarán al panel gráfico del motor en la siguiente parte de la Fase 2. No se muestran controles que todavía no actúan sobre el renderizador.", 9, muted, false);
        body.setGravity(Gravity.TOP);
        LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(-1, -2);
        bodyParams.topMargin = dp(6);
        info.addView(body, bodyParams);
        LinearLayout.LayoutParams infoParams = new LinearLayout.LayoutParams(-1, -2);
        infoParams.topMargin = dp(4);
        settings.addView(info, infoParams);
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
        LinearLayout root = pageRoot("MULTIJUGADOR", "Conexiones locales y juego en red.");
        LinearLayout grid = row();
        LinearLayout.LayoutParams gridParams = new LinearLayout.LayoutParams(-1, 0, 1f);
        gridParams.topMargin = dp(12);
        root.addView(grid, gridParams);
        grid.addView(moduleCard("⌁", "LOCAL", "Descubrir partidas cercanas automáticamente.", 0xff24506a),
                new LinearLayout.LayoutParams(0, -1, 1f));
        View spacer = new View(getContext());
        grid.addView(spacer, new LinearLayout.LayoutParams(dp(9), 1));
        grid.addView(moduleCard("◎", "SERVIDORES", "Conexión a mundos remotos y lista de amigos.", 0xff51432c),
                new LinearLayout.LayoutParams(0, -1, 1f));
    }

    private View moduleCard(String icon, String title, String description, int accent) {
        LinearLayout card = column();
        card.setPadding(dp(14), dp(14), dp(14), dp(14));
        card.setBackground(shape(new int[]{0xff20384b, 0xff13263a}, 16, 0x664f7087));
        card.addView(text(icon, 25, accent | 0xff000000, true));
        TextView name = text(title, 14, white, true);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(-1, -2);
        np.topMargin = dp(8);
        card.addView(name, np);
        TextView detail = text(description, 10, muted, false);
        detail.setGravity(Gravity.TOP);
        LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(-1, 0, 1f);
        dp.topMargin = dp(6);
        card.addView(detail, dp);
        TextView status = pill("EN PREPARACIÓN", 0xffffce72);
        card.addView(status, new LinearLayout.LayoutParams(-2, dp(25)));
        return card;
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

    private final class AvatarPreview extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final int[] skins = {0xffd5a37c, 0xff9a6549, 0xfff0c6a1, 0xffb97b57};
        private final int[] outfits = {0xff3c9d79, 0xff4b75c8, 0xffa85a8a, 0xffb27b36, 0xff596779};
        AvatarPreview(Context context) { super(context); }
        private void box(Canvas c, float x, float y, float w, float h, int color) {
            paint.setColor(color);
            c.drawRoundRect(new RectF(x, y, x + w, y + h), dp(5), dp(5), paint);
            paint.setColor(0x27ffffff);
            c.drawLine(x + dp(3), y + dp(2), x + w - dp(3), y + dp(2), paint);
        }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float cx = getWidth() * 0.5f, ground = getHeight() * 0.83f;
            paint.setShader(new RadialGradient(cx, getHeight() * 0.4f, getHeight() * 0.7f,
                    new int[]{0x3357d4b3, 0x00132639}, null, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
            paint.setShader(null);
            paint.setColor(0x557f9bad);
            canvas.drawOval(cx - dp(65), ground - dp(4), cx + dp(65), ground + dp(10), paint);
            int skin = skins[avatarTone % skins.length];
            int outfit = outfits[avatarOutfit % outfits.length];
            float unit = Math.min(getWidth(), getHeight()) / 8.2f;
            float x = cx - unit * 0.48f;
            box(canvas, x - unit * 0.40f, ground - unit * 2.25f, unit * 0.80f, unit * 0.83f, skin);
            box(canvas, x - unit * 0.45f, ground - unit * 1.40f, unit * 0.90f, unit * 1.0f, outfit);
            box(canvas, x - unit * 0.39f, ground - unit * 0.42f, unit * 0.34f, unit * 0.42f, 0xff34496e);
            box(canvas, x + unit * 0.05f, ground - unit * 0.42f, unit * 0.34f, unit * 0.42f, 0xff34496e);
            box(canvas, x - unit * 0.67f, ground - unit * 1.37f, unit * 0.30f, unit * 0.90f, outfit);
            box(canvas, x + unit * 0.38f, ground - unit * 1.37f, unit * 0.30f, unit * 0.90f, outfit);
            box(canvas, x - unit * 0.33f, ground - unit * 3.05f, unit * 0.66f, unit * 0.75f, skin);
            box(canvas, x - unit * 0.36f, ground - unit * 3.10f, unit * 0.72f, unit * 0.22f, 0xff53362a);
            paint.setColor(white);
            canvas.drawCircle(x - unit * 0.14f, ground - unit * 2.73f, unit * 0.055f, paint);
            canvas.drawCircle(x + unit * 0.14f, ground - unit * 2.73f, unit * 0.055f, paint);
            paint.setColor(0xff25303b);
            canvas.drawCircle(x - unit * 0.14f, ground - unit * 2.73f, unit * 0.025f, paint);
            canvas.drawCircle(x + unit * 0.14f, ground - unit * 2.73f, unit * 0.025f, paint);
        }
    }
}
