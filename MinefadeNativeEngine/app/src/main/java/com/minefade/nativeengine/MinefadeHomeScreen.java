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
    private final Runnable clickSound;
    private final SharedPreferences preferences;
    private final int white = Color.rgb(241, 247, 250);
    private final int muted = Color.rgb(157, 177, 190);
    private final int panel = Color.rgb(17, 31, 45);
    private int avatarTone = 0;
    private int avatarOutfit = 0;
    private AvatarPreview avatarPreview;

    public MinefadeHomeScreen(Context context, Listener listener, Runnable clickSound) {
        super(context);
        this.listener = listener;
        this.clickSound = clickSound;
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
        LinearLayout root = pageRoot("MUNDOS", "Partidas locales guardadas en este dispositivo.");
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
        identity.addView(text("MUNDO PRINCIPAL", 15, white, true));
        TextView subtitle = text("Partida local · modo individual", 9, 0xffb7d1c5, false);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2);
        sp.topMargin = dp(4);
        identity.addView(subtitle, sp);
        top.addView(identity, new LinearLayout.LayoutParams(0, -2, 1f));
        top.addView(pill("AUTOGUARDADO", 0xffb9f27c), new LinearLayout.LayoutParams(-2, dp(25)));
        card.addView(top);
        TextView description = text("Continúa tu aventura. En la primera entrada se generará el mundo; después se cargará desde el guardado local.", 10, 0xffc0d3d9, false);
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
        TextView enter = actionButton("ENTRAR AL MUNDO", () -> listener.onPlay());
        LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(-1, this.dp(46));
        ep.topMargin = this.dp(12);
        card.addView(enter, ep);
        TextView note = text("No desinstales la aplicación para conservar tus mundos locales.", 8, 0xff9eb9ad, false);
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
                tile.addView(new AvatarIconView(getContext(), index, accent),
                        new FrameLayout.LayoutParams(-1, -1));
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
        settings.addView(soundVolumeControl());

        LinearLayout info = column();
        info.setPadding(dp(13), dp(11), dp(13), dp(11));
        info.setBackground(shape(new int[]{0x332e4b60, 0x22162435}, 13, 0x334f7087));
        TextView title = text("PERFILES GRÁFICOS", 10, 0xffb7d4e3, true);
        info.addView(title);
        TextView body = text("El volumen se aplica al instante. Los perfiles de calidad, distancia de renderizado y sombras se habilitarán cuando estén conectados al renderizador.", 9, muted, false);
        body.setGravity(Gravity.TOP);
        LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(-1, -2);
        bodyParams.topMargin = dp(6);
        info.addView(body, bodyParams);
        LinearLayout.LayoutParams infoParams = new LinearLayout.LayoutParams(-1, -2);
        infoParams.topMargin = dp(4);
        settings.addView(info, infoParams);
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
