package com.minefade.nativeengine;

import android.content.Context;
import android.app.AlertDialog;
import android.text.InputType;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.RectF;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.LinearGradient;
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
import android.widget.EditText;
import android.widget.CheckBox;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
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
        void onPlayWorld(WorldManager.WorldInfo world);
        void onCreateWorld(WorldManager.WorldInfo world);
        void onImportMod();
        void onImportShader();
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
    private final WorldManager worldManager;
    private final ModManager modManager;
    private final ShaderPackManager shaderPackManager;
    private final String[] avatarModules = {"piel", "cabello", "ojos", "ropa", "armadura", "accesorios"};
    private int avatarModuleIndex = 1;
    private String settingsModule = "video";
    private AvatarPreview avatarPreview;
    private LinearLayout pageHeader;
    private TextView multiplayerStatus;
    private LinearLayout multiplayerGames;

    public MinefadeHomeScreen(Context context, Listener listener, Runnable clickSound) {
        super(context);
        this.listener = listener;
        this.clickSound = clickSound;
        preferences = context.getSharedPreferences("minefade_ui", Context.MODE_PRIVATE);
        worldManager = new WorldManager(context);
        modManager = new ModManager(context);
        shaderPackManager = new ShaderPackManager(context);
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

    private void installPressAnimation(View view) {
        view.setOnTouchListener((target, event) -> {
            if (!preferences.getBoolean("menu_animations", true)
                    || preferences.getBoolean("reduce_motion", false)) return false;
            int action = event.getActionMasked();
            if (action == android.view.MotionEvent.ACTION_DOWN) {
                target.animate().cancel();
                target.animate().scaleX(0.975f).scaleY(0.975f).setDuration(75L)
                        .setInterpolator(new DecelerateInterpolator()).start();
            } else if (action == android.view.MotionEvent.ACTION_UP
                    || action == android.view.MotionEvent.ACTION_CANCEL) {
                target.animate().scaleX(1f).scaleY(1f).setDuration(145L)
                        .setInterpolator(new DecelerateInterpolator()).start();
            }
            return false;
        });
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
        content.setPadding(dp(8), dp(4), dp(9), dp(4));
        outer.addView(content, new FrameLayout.LayoutParams(-1, -1));

        FrameLayout iconBox = new FrameLayout(getContext());
        iconBox.setBackground(shape(new int[]{accent | 0x55000000, accent | 0x22000000},
                11, accent | 0x66000000));
        iconBox.setContentDescription(title);
        iconBox.addView(new MenuGlyph(getContext(), index, accent),
                new FrameLayout.LayoutParams(-1, -1));
        content.addView(iconBox, new LinearLayout.LayoutParams(dp(30), dp(30)));

        LinearLayout labels = column();
        labels.setGravity(Gravity.CENTER_VERTICAL);
        labels.setPadding(dp(10), 0, 0, 0);
        TextView name = text(title, 11, white, true);
        labels.addView(name);
        if (subtitle != null && !subtitle.trim().isEmpty()) {
            TextView detail = text(subtitle, 8, 0xffb4c5d0, false);
            LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(-1, -2);
            detailParams.topMargin = dp(2);
            labels.addView(detail, detailParams);
        }
        content.addView(labels, new LinearLayout.LayoutParams(0, -1, 1f));

        TextView arrow = text("›", 24, accent, true);
        arrow.setGravity(Gravity.CENTER);
        content.addView(arrow, new LinearLayout.LayoutParams(dp(22), -1));

        installPressAnimation(outer);
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
        shell.setPadding(dp(14), dp(8), dp(14), dp(8));
        addView(shell, new LayoutParams(-1, -1));

        LinearLayout header = row();
        TextView mark = text("◆", 23, 0xffa9ee70, true);
        header.addView(mark, new LinearLayout.LayoutParams(dp(28), dp(34)));
        LinearLayout brand = column();
        TextView name = text("MINEFADE", 18, white, true);
        brand.addView(name);
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
        buttons.add(menuButton("▶", "JUGAR", "",
                new int[]{0xff275d3b, 0xff183e35}, 0xffa9ee70, 40,
                () -> showWorlds(), 0));
        buttons.add(menuButton("◈", "AVATAR", "",
                new int[]{0xff43345d, 0xff282943}, 0xffc3a4ff, 42,
                () -> showSection("avatar"), 1));
        buttons.add(menuButton("⌘", "MULTIJUGADOR", "",
                new int[]{0xff24506a, 0xff1d344e}, 0xff74d5ee, 42,
                () -> showSection("multiplayer"), 2));
        buttons.add(menuButton("☷", "AJUSTES", "",
                new int[]{0xff51432c, 0xff302d2c}, 0xffffce72, 42,
                () -> showSection("settings"), 3));
        buttons.add(menuButton("▧", "MODS", "",
                new int[]{0xff3a4c6b, 0xff242e48}, 0xff9db9ff, 42,
                () -> showSection("mods"), 4));

        for (int i = 0; i < buttons.size(); i++) {
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, dp(i == 0 ? 40 : 36));
            if (i > 0) bp.topMargin = dp(4);
            left.addView(buttons.get(i), bp);
        }

        FrameLayout showcase = new FrameLayout(getContext());
        showcase.setBackground(shape(new int[]{0xff132b43, 0xff0c1727}, 20, 0x665a7b92));
        showcase.setClipToOutline(true);
        showcase.setElevation(dp(4));
        body.addView(showcase, new LinearLayout.LayoutParams(0, -1, 1.06f));

        CoverArtView cover = new CoverArtView(getContext());
        showcase.addView(cover, new FrameLayout.LayoutParams(-1, -1));
        // No engine/version/debug banner on the start screen; build metadata stays
        // in the APK and release notes rather than consuming game-menu space.
    }

    private void addWorldTabs(LinearLayout root, boolean achievementsSelected) {
        LinearLayout tabs = row();
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(38));
        params.topMargin = dp(7);
        root.addView(tabs, params);
        TextView worlds = text("▦  MUNDOS", 10, achievementsSelected ? 0xffb8cbd5 : 0xffe8ffd8, true);
        worlds.setGravity(Gravity.CENTER);
        worlds.setBackground(shape(achievementsSelected
                ? new int[]{0xff20374a,0xff142638} : new int[]{0xff365b48,0xff1e4039},
                10, achievementsSelected ? 0x55496379 : 0xff91d77a));
        tabs.addView(worlds, new LinearLayout.LayoutParams(0,-1,1f));
        TextView achievements = text("✦  LOGROS", 10, achievementsSelected ? 0xffe8ffd8 : 0xffb8cbd5, true);
        achievements.setGravity(Gravity.CENTER);
        achievements.setBackground(shape(achievementsSelected
                ? new int[]{0xff365b48,0xff1e4039} : new int[]{0xff20374a,0xff142638},
                10, achievementsSelected ? 0xff91d77a : 0x55496379));
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(0,-1,1f);
        ap.leftMargin = dp(7);
        tabs.addView(achievements, ap);
        worlds.setOnClickListener(v -> { haptic(v); showWorlds(); });
        achievements.setOnClickListener(v -> { haptic(v); showAchievementsPage(); });
    }

    private void showWorlds() {
        LinearLayout root = pageRoot("MUNDOS", "Crea y administra varias partidas independientes.");
        addWorldTabs(root, false);

        LinearLayout toolbar = row();
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams toolbarParams = new LinearLayout.LayoutParams(-1, dp(40));
        toolbarParams.topMargin = dp(8);
        root.addView(toolbar, toolbarParams);
        toolbar.addView(text("TUS PARTIDAS", 9, 0xffc6d8e1, true),
                new LinearLayout.LayoutParams(0,-1,1f));
        TextView create = actionButton("+ CREAR", this::showCreateWorld);
        create.setTextSize(9);
        create.setPadding(dp(12),0,dp(12),0);
        toolbar.addView(create, new LinearLayout.LayoutParams(dp(104),dp(34)));

        ScrollView scroll = new ScrollView(getContext());
        scroll.setFillViewport(false);
        scroll.setClipChildren(true);
        scroll.setClipToPadding(true);
        scroll.setVerticalScrollBarEnabled(true);
        scroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(-1,0,1f);
        scrollParams.topMargin = dp(6);
        root.addView(scroll, scrollParams);
        LinearLayout list = column();
        list.setPadding(0,0,0,dp(5));
        list.setClipChildren(true);
        list.setClipToPadding(true);
        scroll.addView(list, new ScrollView.LayoutParams(-1,-2));

        List<WorldManager.WorldInfo> worlds = worldManager.listWorlds();
        if (worlds.isEmpty()) {
            LinearLayout empty = column();
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(18),dp(18),dp(18),dp(18));
            empty.setBackground(shape(new int[]{0xff1b3346,0xff122536},16,0x66536f83));
            empty.addView(new WorldMarkView(getContext()),new LinearLayout.LayoutParams(dp(70),dp(70)));
            TextView title = text("TODAVÍA NO HAY MUNDOS",12,white,true);
            title.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(-1,-2);
            titleParams.topMargin = dp(9);
            empty.addView(title,titleParams);
            TextView detail = text("Crea una partida, elige el modo y configura tus opciones antes de entrar.",9,muted,false);
            detail.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(-1,-2);
            detailParams.topMargin = dp(5);
            empty.addView(detail,detailParams);
            TextView first = actionButton("CREAR MI PRIMER MUNDO",this::showCreateWorld);
            LinearLayout.LayoutParams firstParams = new LinearLayout.LayoutParams(-2,dp(38));
            firstParams.topMargin = dp(12);
            empty.addView(first,firstParams);
            list.addView(empty,new LinearLayout.LayoutParams(-1,dp(224)));
        } else {
            for (WorldManager.WorldInfo world : worlds) {
                LinearLayout card = row();
                card.setPadding(dp(10),dp(8),dp(8),dp(8));
                card.setBackground(shape(new int[]{0xff203a4c,0xff14283a},13,0x66536f83));
                LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(-1,dp(70));
                cardParams.bottomMargin = dp(7);
                list.addView(card,cardParams);

                FrameLayout icon = new FrameLayout(getContext());
                icon.setBackground(shape(new int[]{0xff315d48,0xff1b473c},11,0x557acb91));
                icon.addView(new WorldMarkView(getContext()),new FrameLayout.LayoutParams(-1,-1));
                card.addView(icon,new LinearLayout.LayoutParams(dp(48),dp(48)));

                LinearLayout labels = column();
                labels.setGravity(Gravity.CENTER_VERTICAL);
                labels.setPadding(dp(9),0,dp(5),0);
                TextView name = text(world.name,11,white,true);
                name.setMaxLines(1);
                name.setEllipsize(android.text.TextUtils.TruncateAt.END);
                labels.addView(name);
                TextView meta = text(world.modeLabel() + "  ·  SEMILLA " + world.seed,7,muted,false);
                meta.setMaxLines(1);
                LinearLayout.LayoutParams metaParams = new LinearLayout.LayoutParams(-1,-2);
                metaParams.topMargin = dp(4);
                labels.addView(meta,metaParams);
                card.addView(labels,new LinearLayout.LayoutParams(0,-1,1f));

                TextView enter = actionButton("JUGAR",() -> {
                    if (listener != null) listener.onPlayWorld(world);
                });
                enter.setTextSize(9);
                enter.setPadding(dp(8),0,dp(8),0);
                card.addView(enter,new LinearLayout.LayoutParams(dp(72),dp(34)));

                TextView more = text("⚙",17,0xffc5d9e4,true);
                more.setContentDescription("Opciones del mundo");
                more.setGravity(Gravity.CENTER);
                card.addView(more,new LinearLayout.LayoutParams(dp(30),dp(42)));
                more.setOnClickListener(v -> showWorldActions(world,more));
            }
        }
        animateIn(list,0);
    }

    private void showWorldActions(WorldManager.WorldInfo world, View anchor) {
        PopupMenu menu = new PopupMenu(getContext(),anchor);
        menu.getMenu().add("Renombrar");
        menu.getMenu().add("Eliminar mundo");
        menu.setOnMenuItemClickListener(item -> {
            if ("Renombrar".contentEquals(item.getTitle())) {
                EditText name = new EditText(getContext());
                name.setSingleLine(true);
                name.setText(world.name);
                name.setSelection(name.length());
                new AlertDialog.Builder(getContext()).setTitle("Renombrar mundo")
                        .setView(name).setNegativeButton("Cancelar",null)
                        .setPositiveButton("Guardar",(dialog,which) -> {
                            if (worldManager.renameWorld(world,name.getText().toString())) showWorlds();
                        }).show();
            } else {
                new AlertDialog.Builder(getContext()).setTitle("Eliminar mundo")
                        .setMessage("Se eliminará «"+world.name+"» y su partida guardada. No se puede deshacer.")
                        .setNegativeButton("Cancelar",null)
                        .setPositiveButton("Eliminar",(dialog,which) -> {
                            worldManager.deleteWorld(world);
                            showWorlds();
                        }).show();
            }
            return true;
        });
        menu.show();
    }

    private void showCreateWorld() {
        LinearLayout root = pageRoot("CREAR MUNDO","Configura tu partida antes de generar el terreno.");
        ScrollView scroll = new ScrollView(getContext());
        scroll.setFillViewport(false);
        scroll.setClipChildren(true);
        scroll.setClipToPadding(true);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(-1,0,1f);
        scrollParams.topMargin = dp(8);
        root.addView(scroll,scrollParams);
        LinearLayout form = column();
        form.setPadding(dp(11),dp(10),dp(11),dp(12));
        form.setBackground(shape(new int[]{0x44243f52,0x22101d2b},15,0x55466378));
        scroll.addView(form,new ScrollView.LayoutParams(-1,-2));

        form.addView(text("NOMBRE DEL MUNDO",9,0xffd8eaf0,true));
        EditText name = new EditText(getContext());
        name.setSingleLine(true);
        name.setText("Mi mundo");
        name.setTextSize(12);
        name.setTextColor(white);
        name.setHintTextColor(muted);
        name.setHint("Nombre de la partida");
        name.setPadding(dp(10),0,dp(10),0);
        name.setBackground(shape(new int[]{0xff1b3042,0xff142638},10,0x665c7a8c));
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(-1,dp(42));
        nameParams.topMargin = dp(5);
        form.addView(name,nameParams);

        TextView seedTitle = text("SEMILLA DEL MUNDO",9,0xffd8eaf0,true);
        LinearLayout.LayoutParams seedTitleParams = new LinearLayout.LayoutParams(-1,-2);
        seedTitleParams.topMargin = dp(11);
        form.addView(seedTitle,seedTitleParams);
        EditText seed = new EditText(getContext());
        seed.setSingleLine(true);
        seed.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);
        seed.setText(Long.toString(System.currentTimeMillis() / 1000L));
        seed.setTextSize(11);
        seed.setTextColor(white);
        seed.setPadding(dp(10),0,dp(10),0);
        seed.setBackground(shape(new int[]{0xff1b3042,0xff142638},10,0x665c7a8c));
        LinearLayout.LayoutParams seedParams = new LinearLayout.LayoutParams(-1,dp(40));
        seedParams.topMargin = dp(5);
        form.addView(seed,seedParams);

        TextView modeTitle = text("MODO DE JUEGO",9,0xffd8eaf0,true);
        LinearLayout.LayoutParams modeTitleParams = new LinearLayout.LayoutParams(-1,-2);
        modeTitleParams.topMargin = dp(11);
        form.addView(modeTitle,modeTitleParams);
        Spinner mode = new Spinner(getContext());
        ArrayAdapter<String> modes = new ArrayAdapter<>(getContext(),
                android.R.layout.simple_spinner_dropdown_item,
                new String[]{"Supervivencia","Creativo","Aventura"});
        mode.setAdapter(modes);
        mode.setSelection(WorldManager.SURVIVAL);
        LinearLayout.LayoutParams modeParams = new LinearLayout.LayoutParams(-1,dp(42));
        modeParams.topMargin = dp(3);
        form.addView(mode,modeParams);

        CheckBox chest = new CheckBox(getContext());
        chest.setText("Cofre inicial con recursos");
        chest.setTextSize(10);
        chest.setTextColor(white);
        chest.setButtonTintList(ColorStateList.valueOf(0xffa9ee70));
        chest.setChecked(true);
        LinearLayout.LayoutParams chestParams = new LinearLayout.LayoutParams(-1,dp(42));
        chestParams.topMargin = dp(5);
        form.addView(chest,chestParams);

        CheckBox cheats = new CheckBox(getContext());
        cheats.setText("Permitir trucos y comandos");
        cheats.setTextSize(10);
        cheats.setTextColor(white);
        cheats.setButtonTintList(ColorStateList.valueOf(0xffa9ee70));
        cheats.setChecked(false);
        LinearLayout.LayoutParams cheatsParams = new LinearLayout.LayoutParams(-1,dp(42));
        form.addView(cheats,cheatsParams);

        TextView note = text("El modo aventura limita la rotura y colocación de bloques. Los trucos se pueden cambiar solo si los activas aquí.",8,muted,false);
        note.setMaxLines(3);
        LinearLayout.LayoutParams noteParams = new LinearLayout.LayoutParams(-1,-2);
        noteParams.topMargin = dp(7);
        form.addView(note,noteParams);

        TextView create = actionButton("CREAR MUNDO",() -> {
            long parsedSeed;
            try { parsedSeed = Long.parseLong(seed.getText().toString().trim()); }
            catch (NumberFormatException error) { parsedSeed = System.currentTimeMillis(); }
            WorldManager.WorldInfo world = worldManager.createWorld(
                    name.getText().toString(),parsedSeed,mode.getSelectedItemPosition(),
                    cheats.isChecked(),chest.isChecked());
            if (world == null) {
                new AlertDialog.Builder(getContext()).setTitle("No se pudo crear")
                        .setMessage("No se pudo guardar la configuración del mundo. Comprueba el espacio disponible.")
                        .setPositiveButton("Aceptar",null).show();
                return;
            }
            if (listener != null) listener.onCreateWorld(world);
        });
        LinearLayout.LayoutParams createParams = new LinearLayout.LayoutParams(-1,dp(44));
        createParams.topMargin = dp(10);
        form.addView(create,createParams);
    }

    private void showModsPage() {
        // The back arrow, search field and import action share one compact line.
        // No explanatory banner or oversized example card consumes the mod list.
        LinearLayout root = pageRoot("", "");
        if (pageHeader.getChildCount() > 1) pageHeader.removeViewAt(1);

        EditText search = new EditText(getContext());
        search.setSingleLine(true);
        search.setTextSize(10);
        search.setTextColor(white);
        search.setHintTextColor(muted);
        search.setHint("Buscar mods…");
        search.setPadding(dp(9), 0, dp(7), 0);
        search.setBackground(shape(new int[]{0xaa132b3e, 0xaa0d1c2c}, 9, 0x665d7a90));
        pageHeader.addView(search, new LinearLayout.LayoutParams(0, dp(32), 1f));

        TextView importButton = actionButton("IMPORTAR", () -> {
            if (listener != null) listener.onImportMod();
        });
        importButton.setTextSize(8);
        importButton.setPadding(dp(7), 0, dp(7), 0);
        LinearLayout.LayoutParams importParams = new LinearLayout.LayoutParams(dp(78), dp(32));
        importParams.leftMargin = dp(6);
        pageHeader.addView(importButton, importParams);

        ScrollView scroll = new ScrollView(getContext());
        scroll.setFillViewport(false);
        scroll.setClipChildren(true);
        scroll.setClipToPadding(true);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(-1, 0, 1f);
        scrollParams.topMargin = dp(6);
        root.addView(scroll, scrollParams);
        LinearLayout list = column();
        list.setClipChildren(false);
        list.setClipToPadding(false);
        scroll.addView(list, new ScrollView.LayoutParams(-1, -2));
        Runnable update = () -> renderModCards(list, search.getText().toString());
        search.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { update.run(); }
            @Override public void afterTextChanged(android.text.Editable editable) { }
        });
        update.run();
    }

    private void renderModCards(LinearLayout list, String query) {
        list.removeAllViews();
        List<ModManager.ModInfo> mods = modManager.listMods();
        String needle = query == null ? "" : query.trim().toLowerCase(java.util.Locale.ROOT);
        List<ModManager.ModInfo> filtered = new ArrayList<>();
        for (ModManager.ModInfo mod : mods) {
            if (needle.isEmpty() || mod.name.toLowerCase(java.util.Locale.ROOT).contains(needle)
                    || mod.id.toLowerCase(java.util.Locale.ROOT).contains(needle)
                    || mod.description.toLowerCase(java.util.Locale.ROOT).contains(needle)) filtered.add(mod);
        }
        if (filtered.isEmpty()) {
            TextView empty = text(mods.isEmpty() ? "No hay mods instalados. Importa un paquete .mfmod." : "No hay resultados para esa búsqueda.",
                    10, muted, false);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(16), dp(22), dp(16), dp(22));
            list.addView(empty, new LinearLayout.LayoutParams(-1, -2));
            return;
        }
        for (int index = 0; index < filtered.size(); index += 2) {
            LinearLayout pair = row();
            pair.setGravity(Gravity.TOP);
            LinearLayout.LayoutParams pairParams = new LinearLayout.LayoutParams(-1, -2);
            pairParams.bottomMargin = dp(6);
            list.addView(pair, pairParams);
            addModCard(pair, filtered.get(index), true);
            if (index + 1 < filtered.size()) addModCard(pair, filtered.get(index + 1), false);
            else pair.addView(new View(getContext()), new LinearLayout.LayoutParams(0, dp(1), 1f));
        }
    }

    private void addModCard(LinearLayout parentRow, ModManager.ModInfo mod, boolean first) {
        LinearLayout card = column();
        card.setPadding(dp(9), dp(8), dp(9), dp(8));
        card.setBackground(shape(new int[]{0xee243e53, 0xee14283a}, 13, 0x665e7e94));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(0, dp(112), 1f);
        if (first) cardParams.rightMargin = dp(4); else cardParams.leftMargin = dp(4);
        parentRow.addView(card, cardParams);
        LinearLayout top = row();
        card.addView(top, new LinearLayout.LayoutParams(-1, 0, 1f));
        FrameLayout thumb = new FrameLayout(getContext());
        thumb.setBackground(shape(new int[]{0xff3b6b58, 0xff1b3548}, 9, 0x6696c99b));
        TextView initials = text(mod.name.length() > 1 ? mod.name.substring(0, 2).toUpperCase(java.util.Locale.ROOT) : "MF",
                15, 0xffe5f4cf, true);
        initials.setGravity(Gravity.CENTER);
        thumb.addView(initials, new FrameLayout.LayoutParams(-1, -1));
        top.addView(thumb, new LinearLayout.LayoutParams(dp(40), dp(40)));
        LinearLayout labels = column();
        labels.setPadding(dp(7), 0, 0, 0);
        labels.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = text(mod.name, 9, white, true);
        name.setMaxLines(2);
        labels.addView(name);
        labels.addView(text("v" + mod.version, 8, muted, false));
        top.addView(labels, new LinearLayout.LayoutParams(0, -1, 1f));
        LinearLayout bottom = row();
        card.addView(bottom, new LinearLayout.LayoutParams(-1, dp(25)));
        TextView state = text(mod.enabled ? "ACTIVO" : "INACTIVO", 7, mod.enabled ? 0xffa9ee70 : muted, true);
        bottom.addView(state, new LinearLayout.LayoutParams(0, -2, 1f));
        Switch enabled = new Switch(getContext());
        enabled.setChecked(mod.enabled);
        enabled.setButtonTintList(ColorStateList.valueOf(0xffa9ee70));
        enabled.setScaleX(0.78f);
        enabled.setScaleY(0.78f);
        enabled.setOnCheckedChangeListener((button, checked) -> {
            modManager.setEnabled(mod, checked);
            showModsPage();
        });
        bottom.addView(enabled);
    }

    public void refreshMods() { showModsPage(); }
    public void refreshShaders() { showSettingsPage(); }

    private void showSection(String section) {
        if ("avatar".equals(section)) {
            showAvatarPage();
        } else if ("settings".equals(section)) {
            showSettingsPage();
        } else if ("multiplayer".equals(section)) {
            showMultiplayerPage();
        } else if ("mods".equals(section)) {
            showModsPage();
        } else {
            showWorlds();
        }
    }

    private LinearLayout pageRoot(String title, String subtitle) {
        removeAllViews();
        addView(new Backdrop(getContext()), new LayoutParams(-1, -1));
        LinearLayout root = column();
        root.setPadding(dp(12), dp(7), dp(12), dp(8));
        addView(root, new LayoutParams(-1, -1));

        LinearLayout header = row();
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = text("‹", 27, white, true);
        back.setGravity(Gravity.CENTER);
        back.setContentDescription("Salir");
        back.setBackgroundColor(Color.TRANSPARENT);
        back.setOnClickListener(v -> { haptic(v); showHome(); });
        header.addView(back, new LinearLayout.LayoutParams(dp(34), dp(34)));
        if (title != null && !title.trim().isEmpty()) {
            TextView heading = text(title, 13, white, true);
            LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, dp(34), 1f);
            titleParams.leftMargin = dp(7);
            header.addView(heading, titleParams);
        } else {
            header.addView(new View(getContext()), new LinearLayout.LayoutParams(0, dp(34), 1f));
        }
        pageHeader = header;
        root.addView(header, new LinearLayout.LayoutParams(-1, dp(36)));
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
        installPressAnimation(button);
        button.setOnClickListener(v -> {
            haptic(v);
            if (action != null) action.run();
        });
        return button;
    }

    private void showAvatarPage() {
        LinearLayout root = pageRoot("", "");

        // Landscape editor: the character preview stays on the RIGHT while every
        // customization module and choice remains on the LEFT.
        LinearLayout body = row();
        body.setClipChildren(true);
        body.setClipToPadding(true);
        LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(-1, 0, 1f);
        bodyParams.topMargin = dp(8);
        root.addView(body, bodyParams);

        LinearLayout editor = column();
        editor.setPadding(dp(7), dp(6), dp(7), dp(6));
        editor.setBackground(shape(new int[]{0x44243f52, 0x22101d2b}, 15, 0x55466378));
        LinearLayout.LayoutParams editorParams = new LinearLayout.LayoutParams(0, -1, 1.38f);
        editorParams.rightMargin = dp(9);
        body.addView(editor, editorParams);

        FrameLayout previewCard = new FrameLayout(getContext());
        previewCard.setBackground(shape(new int[]{0xff1c3b50, 0xff0d1a2a}, 18, 0x77668ba0));
        previewCard.setClipToOutline(true);
        previewCard.setClipChildren(true);
        body.addView(previewCard, new LinearLayout.LayoutParams(0, -1, 0.96f));

        LinearLayout previewOverlay = column();
        previewOverlay.setPadding(dp(7), dp(7), dp(7), dp(7));
        previewOverlay.setGravity(Gravity.CENTER_HORIZONTAL);
        previewCard.addView(previewOverlay, new FrameLayout.LayoutParams(-1, -1));
        FrameLayout previewFrame = new FrameLayout(getContext());
        LinearLayout.LayoutParams avatarParams = new LinearLayout.LayoutParams(-1, 0, 1f);
        avatarParams.topMargin = dp(2);
        avatarParams.bottomMargin = dp(2);
        previewOverlay.addView(previewFrame, avatarParams);
        avatarPreview = new AvatarPreview(getContext());
        previewFrame.addView(avatarPreview, new FrameLayout.LayoutParams(-1, -1));

        String[] moduleLabels = {"PIEL", "CABELLO", "OJOS", "ROPA", "ARMADURA", "ACCESORIOS"};
        String[][] optionNames = {
                {"Marfil", "Arena", "Miel", "Cobre", "Ébano", "Oliva"},
                {"Corto", "Despeinado", "Ondulado", "Largo", "Trenzado", "Cresta"},
                {"Avellana", "Azules", "Verdes", "Grises", "Oscuros", "Ámbar"},
                {"Aventurero", "Explorador", "Artesano", "Nómada", "Guardián", "Real"},
                {"Sin placas", "Cuero", "Hierro", "Cobalto", "Cristal", "Élite"},
                {"Ninguno", "Hombrera", "Capa", "Amuleto", "Diadema", "Mochila"}
        };

        HorizontalScrollView moduleScroller = new HorizontalScrollView(getContext());
        moduleScroller.setHorizontalScrollBarEnabled(false);
        moduleScroller.setFillViewport(false);
        moduleScroller.setClipChildren(true);
        moduleScroller.setClipToPadding(true);
        LinearLayout.LayoutParams tabsParams = new LinearLayout.LayoutParams(-1, dp(34));
        tabsParams.bottomMargin = dp(5);
        editor.addView(moduleScroller, tabsParams);
        LinearLayout tabs = row();
        tabs.setPadding(dp(1), 0, dp(1), 0);
        moduleScroller.addView(tabs, new HorizontalScrollView.LayoutParams(-2, -1));
        for (int i = 0; i < moduleLabels.length; i++) {
            final int module = i;
            boolean selected = avatarModuleIndex == module;
            LinearLayout tab = row();
            tab.setGravity(Gravity.CENTER);
            tab.setPadding(dp(7), 0, dp(8), 0);
            tab.setBackground(shape(selected
                    ? new int[]{0xff3a624e, 0xff1c403b}
                    : new int[]{0xff20374a, 0xff142638},
                    11, selected ? 0xff9be17e : 0x66516c7f));
            tab.addView(new AvatarModuleGlyph(getContext(), module, selected
                    ? 0xffc9f4a1 : 0xffa8c3d0),
                    new LinearLayout.LayoutParams(dp(18), dp(18)));
            TextView label = text(moduleLabels[i], 7, selected ? 0xffe2ffd1 : 0xffb8cbd5, true);
            LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(-2, -1);
            labelParams.leftMargin = dp(5);
            tab.addView(label, labelParams);
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

        LinearLayout optionShell = column();
        optionShell.setPadding(dp(6), dp(6), dp(6), dp(5));
        optionShell.setBackground(shape(new int[]{0x33243f52, 0x22101d2b}, 13, 0x55466378));
        editor.addView(optionShell, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout optionHeading = row();
        optionHeading.setGravity(Gravity.CENTER_VERTICAL);
        optionHeading.addView(new View(getContext()), new LinearLayout.LayoutParams(0, dp(20), 1f));
        TextView selectedName = text(optionNames[avatarModuleIndex][avatarStyles[avatarModuleIndex]],
                8, 0xffb9f27c, true);
        selectedName.setGravity(Gravity.CENTER);
        optionHeading.addView(selectedName, new LinearLayout.LayoutParams(-2, dp(22)));
        optionShell.addView(optionHeading);

        ScrollView optionScroll = new ScrollView(getContext());
        optionScroll.setFillViewport(false);
        optionScroll.setVerticalScrollBarEnabled(false);
        optionScroll.setClipChildren(true);
        optionScroll.setClipToPadding(true);
        optionScroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        LinearLayout.LayoutParams optionScrollParams = new LinearLayout.LayoutParams(-1, 0, 1f);
        optionScrollParams.topMargin = dp(3);
        optionShell.addView(optionScroll, optionScrollParams);
        LinearLayout grid = column();
        grid.setClipChildren(true);
        grid.setClipToPadding(true);
        optionScroll.addView(grid, new ScrollView.LayoutParams(-1, -2));

        for (int r = 0; r < 2; r++) {
            LinearLayout line = row();
            LinearLayout.LayoutParams lineParams = new LinearLayout.LayoutParams(-1, dp(76));
            if (r > 0) lineParams.topMargin = dp(6);
            grid.addView(line, lineParams);
            for (int col = 0; col < 3; col++) {
                final int choice = r * 3 + col;
                boolean selected = avatarStyles[avatarModuleIndex] == choice;
                LinearLayout tile = column();
                tile.setGravity(Gravity.CENTER);
                tile.setPadding(dp(3), dp(3), dp(3), dp(3));
                tile.setBackground(shape(selected
                        ? new int[]{0xff365a48, 0xff1d3e39}
                        : new int[]{0xff263d51, 0xff172b3f},
                        11, selected ? 0xff9ae17c : 0x554a687c));
                FrameLayout icon = new FrameLayout(getContext());
                icon.setClipChildren(true);
                icon.addView(new AvatarOptionView(getContext(), avatarModuleIndex, choice),
                        new FrameLayout.LayoutParams(-1, -1));
                tile.addView(icon, new LinearLayout.LayoutParams(-1, 0, 1f));
                TextView label = text(optionNames[avatarModuleIndex][choice], 8,
                        selected ? 0xffe4f8d7 : 0xffc2d2dc, selected);
                label.setGravity(Gravity.CENTER);
                label.setMaxLines(1);
                LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(-1, dp(16));
                labelParams.topMargin = dp(1);
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
        animateAvatarIn(previewCard, 0);
        animateAvatarIn(editor, 1);
    }

    private void showSettingsPage() {
        // Compact settings workspace: modules remain visible in a left rail and
        // the selected module has its own scrollable control pane on the right.
        LinearLayout root = pageRoot("", "");
        List<View> settingsCards = new ArrayList<>();

        String[][] modules = {
                {"video", "VÍDEO", "◈"},
                {"shaders", "SHADERS", "✧"},
                {"audio", "AUDIO", "♫"},
                {"controls", "CONTROLES", "⌖"},
                {"interface", "HUD", "▤"},
                {"animation", "ANIMACIÓN", "✧"},
                {"accessibility", "ACCESO", "◉"}
        };

        LinearLayout workspace = row();
        LinearLayout.LayoutParams workspaceParams = new LinearLayout.LayoutParams(-1, 0, 1f);
        workspaceParams.topMargin = dp(4);
        root.addView(workspace, workspaceParams);

        ScrollView moduleScroll = new ScrollView(getContext());
        moduleScroll.setFillViewport(false);
        moduleScroll.setVerticalScrollBarEnabled(false);
        moduleScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        workspace.addView(moduleScroll, new LinearLayout.LayoutParams(dp(82), -1));
        LinearLayout moduleRail = column();
        moduleRail.setPadding(0, dp(2), dp(2), dp(4));
        moduleScroll.addView(moduleRail, new ScrollView.LayoutParams(-1, -2));
        for (String[] module : modules) {
            boolean active = settingsModule.equals(module[0]);
            LinearLayout tab = column();
            tab.setGravity(Gravity.CENTER);
            tab.setPadding(dp(3), dp(4), dp(3), dp(4));
            tab.setBackground(shape(active
                    ? new int[]{0xff355b48, 0xff1d403a}
                    : new int[]{0x8830475a, 0x66172536},
                    10, active ? 0xff91d77a : 0x55496379));
            TextView icon = text(module[2], 15, active ? 0xffe4ffd6 : 0xffb8cbd5, true);
            icon.setGravity(Gravity.CENTER);
            tab.addView(icon, new LinearLayout.LayoutParams(-1, dp(19)));
            TextView label = text(module[1], 7, active ? 0xffe4ffd6 : 0xffb8cbd5, true);
            label.setGravity(Gravity.CENTER);
            label.setMaxLines(2);
            tab.addView(label, new LinearLayout.LayoutParams(-1, dp(18)));
            tab.setOnClickListener(v -> {
                haptic(v);
                settingsModule = module[0];
                preferences.edit().putString("settings_module", settingsModule).apply();
                showSettingsPage();
            });
            LinearLayout.LayoutParams tabParams = new LinearLayout.LayoutParams(-1, dp(45));
            tabParams.bottomMargin = dp(5);
            moduleRail.addView(tab, tabParams);
        }

        ScrollView scroll = new ScrollView(getContext());
        scroll.setFillViewport(false);
        scroll.setClipChildren(true);
        scroll.setClipToPadding(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setSmoothScrollingEnabled(true);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(0, -1, 1f);
        scrollParams.leftMargin = dp(5);
        workspace.addView(scroll, scrollParams);
        LinearLayout content = column();
        content.setPadding(dp(3), dp(2), dp(3), dp(10));
        content.setClipChildren(true);
        content.setClipToPadding(true);
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));

        switch (settingsModule) {
            case "shaders":
                settingsHeading(content, "SHADERS", "Efectos del renderizador GLES y perfiles importados.");
                settingsCards.add(settingSwitch("Ondulación del agua", "Animación de la superficie del agua.", "water_waves", true));
                settingsCards.add(settingSwitch("Animación de lava", "Movimiento de calor y superficie de la lava.", "lava_animations", true));
                settingsCards.add(settingSwitch("Viento en las hojas", "Balanceo sutil de las hojas de los árboles.", "leaf_wind", true));
                settingsCards.add(settingSlider("Intensidad del viento", "Controla cuánto se mueven las hojas.", "leaf_wind_strength", 0, 12, 4, "%"));
                settingsCards.add(menuButton("✦", "IMPORTAR SHADER", "Importa un perfil .mfshader compatible.", new int[]{0xff365d71, 0xff203e52}, 0xffaee6f1, 38, () -> {
                    if (listener != null) listener.onImportShader();
                }, 4));
                settingsCards.add(menuButton("↺", "SHADER ORIGINAL", "Restaura el perfil visual integrado de Minefade.", new int[]{0xff3a4b5d, 0xff263648}, 0xffa9c6d8, 38, () -> {
                    ShaderPackManager.selectBuiltIn(preferences);
                    showSettingsPage();
                }, 5));
                break;
            case "audio":
                settingsHeading(content, "SONIDO DEL JUEGO", "Ajusta la mezcla que reproduce el dispositivo.");
                settingsCards.add(soundVolumeControl());
                settingsCards.add(settingSwitch("Efectos de juego", "Rotura, colocación, pasos, salto y recogida.",
                        "sound_effects_enabled", true));
                settingsCards.add(settingSwitch("Sonidos de interfaz", "Confirmaciones y pulsaciones del menú.",
                        "ui_sounds_enabled", true));
                break;
            case "controls":
                settingsHeading(content, "CONTROLES TÁCTILES", "Ajusta cada eje y la disposición de movimiento.");
                int legacyTouch = preferences.getInt("touch_sensitivity", 100);
                settingsCards.add(settingSlider("Sensibilidad horizontal", "Velocidad de giro de izquierda a derecha.",
                        "touch_sensitivity_x", 35, 150, legacyTouch, "%"));
                settingsCards.add(settingSlider("Sensibilidad vertical", "Velocidad de giro de arriba abajo.",
                        "touch_sensitivity_y", 35, 150, legacyTouch, "%"));
                settingsCards.add(settingSlider("Zona muerta del joystick", "Filtra movimientos accidentales cerca del centro.",
                        "joystick_deadzone", 5, 30, 12, "%"));
                settingsCards.add(settingChoice("Tipo de controles", "Elige el esquema táctil inspirado en Bedrock.",
                        "control_mode", new String[]{"Joystick clásico", "D-pad direccional", "Joystick + mira"}, 0));
                settingsCards.add(settingSwitch("Salto automático", "Salta al avanzar contra obstáculos bajos si hay espacio libre.",
                        "auto_jump", true));
                settingsCards.add(settingSwitch("Invertir eje vertical", "Invierte el movimiento arriba y abajo.",
                        "invert_look_y", false));
                settingsCards.add(settingSwitch("Controles para mano izquierda", "Mueve el joystick al lado derecho y refleja el salto.",
                        "left_handed_controls", false));
                settingsCards.add(settingSwitch("Respuesta háptica", "Vibración breve al tocar elementos del menú.",
                        "menu_haptics", true));
                settingsCards.add(menuButton("↺", "RESTABLECER CONTROLES", "Recupera los valores predeterminados.",
                        new int[]{0xff3a4b5d, 0xff263648}, 0xffa9c6d8, 38, () -> {
                            preferences.edit().putInt("touch_sensitivity", 100)
                                    .putInt("touch_sensitivity_x", 100).putInt("touch_sensitivity_y", 100)
                                    .putInt("joystick_deadzone", 12).putInt("control_mode", 0)
                                    .putBoolean("invert_look_y", false).putBoolean("left_handed_controls", false)
                                    .putBoolean("menu_haptics", true).apply();
                            showSettingsPage();
                        }, 4));
                break;
            case "interface":
                settingsHeading(content, "INTERFAZ Y HUD", "Preferencias visuales de menús y pantalla de juego.");
                settingsCards.add(settingSwitch("Animaciones de interfaz", "Entradas y transiciones suaves.",
                        "menu_animations", true));
                settingsCards.add(settingSwitch("Mostrar información superior", "FPS, chunks y datos de estado en el HUD.",
                        "show_hud_stats", true));
                settingsCards.add(settingSlider("Escala del HUD", "Tamaño de elementos durante la partida.",
                        "hud_scale", 80, 120, 100, "%"));
                settingsCards.add(settingSwitch("Animar el fondo de menús", "Partículas ambientales y movimiento sutil.",
                        "menu_background_animations", true));
                break;
            case "animation":
                settingsHeading(content, "ANIMACIÓN DEL JUEGO", "Controla las poses que calcula el núcleo Rust.");
                settingsCards.add(settingSwitch("Animaciones del mundo", "Movimiento de jugador, criaturas, alas, cabeza y reposo.",
                        "world_animations", true));
                settingsCards.add(settingChoice("Calidad de animación", "Más detalle de pose; menor carga en ahorro.",
                        "animation_quality", new String[]{"Ahorro", "Equilibrada", "Completa"}, 2));
                settingsCards.add(settingSwitch("Movimiento de reposo del avatar", "Respiración, parpadeo y giro automático de la vista previa.",
                        "avatar_idle_animation", true));
                settingsCards.add(settingSwitch("Suavizar transiciones de apariencia", "Intercambios visuales al seleccionar estilos.",
                        "avatar_transition_animations", true));
                break;
            case "accessibility":
                settingsHeading(content, "ACCESIBILIDAD", "Adapta movimiento, contraste y lectura.");
                settingsCards.add(settingSwitch("Reducir movimiento", "Desactiva movimiento decorativo y transiciones.",
                        "reduce_motion", false));
                settingsCards.add(settingSwitch("Animaciones de avatar", "Permite movimiento de reposo en el editor.",
                        "avatar_idle_animation", true));
                break;
            default:
                settingsHeading(content, "VÍDEO Y RENDIMIENTO", "Ajustes gráficos que se aplican al entrar al mundo.");
                settingsCards.add(settingChoice("Calidad gráfica", "Iluminación y coste de renderizado.",
                        "graphics_quality", new String[]{"Ahorro", "Equilibrado", "Alta"}, 1));
                settingsCards.add(settingChoice("Distancia de visión", "Más chunks consumen memoria y GPU.",
                        "render_distance", new String[]{"Cercana · 3×3", "Normal · 5×5", "Amplia · 7×7"}, 1));
                settingsCards.add(settingSlider("Campo de visión", "Ángulo de cámara en primera y tercera persona.",
                        "field_of_view", 60, 100, 68, "°"));
                settingsCards.add(settingSwitch("Sombras de contacto", "Oscurecimiento suave bajo jugador y criaturas.",
                        "contact_shadows", true));
                break;
        }
        addSettingsGrid(content, settingsCards);
        if ("shaders".equals(settingsModule)) addImportedShaderProfiles(content);
        animateIn(content, 0);
    }

    private void addImportedShaderProfiles(LinearLayout parent) {
        TextView heading = text("PERFILES IMPORTADOS", 9, 0xffc5d9e4, true);
        LinearLayout.LayoutParams headingParams = new LinearLayout.LayoutParams(-1, dp(24));
        headingParams.topMargin = dp(8);
        parent.addView(heading, headingParams);
        List<ShaderPackManager.PackInfo> packs = shaderPackManager.listPacks();
        if (packs.isEmpty()) {
            parent.addView(text("Aún no has importado perfiles shader. El perfil integrado sigue activo.",
                    8, muted, false), new LinearLayout.LayoutParams(-1, -2));
            return;
        }
        String activeId = preferences.getString("shader_pack_active", "builtin-vibrant");
        for (ShaderPackManager.PackInfo pack : packs) {
            LinearLayout row = row();
            row.setPadding(dp(9), dp(6), dp(8), dp(6));
            row.setBackground(shape(new int[]{0xdd243d50, 0xdd152a3c}, 10, 0x555f8195));
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, dp(46));
            rowParams.bottomMargin = dp(5);
            parent.addView(row, rowParams);
            LinearLayout labels = column();
            labels.setGravity(Gravity.CENTER_VERTICAL);
            labels.addView(text(pack.name + " · v" + pack.version, 9, white, true));
            labels.addView(text(pack.id.equals(activeId) ? "ACTIVO" : "PERFIL DISPONIBLE", 7,
                    pack.id.equals(activeId) ? 0xffa9ee70 : muted, false));
            row.addView(labels, new LinearLayout.LayoutParams(0, -1, 1f));
            TextView activate = actionButton(pack.id.equals(activeId) ? "ACTIVO" : "USAR", () -> {
                shaderPackManager.activate(pack, preferences);
                showSettingsPage();
            });
            activate.setTextSize(8);
            row.addView(activate, new LinearLayout.LayoutParams(dp(86), dp(32)));
        }
    }

    private void addSettingsGrid(LinearLayout parent, List<View> cards) {
        for (int index = 0; index < cards.size(); index += 2) {
            LinearLayout pair = row();
            pair.setGravity(Gravity.TOP);
            pair.setBaselineAligned(false);
            LinearLayout.LayoutParams pairParams = new LinearLayout.LayoutParams(-1, -2);
            pairParams.bottomMargin = dp(2);
            View first = cards.get(index);
            int firstHeight = first.getLayoutParams() == null ? -2 : first.getLayoutParams().height;
            if (firstHeight <= 0) firstHeight = -2;
            LinearLayout.LayoutParams firstParams = new LinearLayout.LayoutParams(0, firstHeight, 1f);
            firstParams.rightMargin = dp(4);
            pair.addView(first, firstParams);
            if (index + 1 < cards.size()) {
                View second = cards.get(index + 1);
                int secondHeight = second.getLayoutParams() == null ? -2 : second.getLayoutParams().height;
                if (secondHeight <= 0) secondHeight = -2;
                LinearLayout.LayoutParams secondParams = new LinearLayout.LayoutParams(0, secondHeight, 1f);
                secondParams.leftMargin = dp(4);
                pair.addView(second, secondParams);
            } else {
                View spacer = new View(getContext());
                pair.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));
            }
            parent.addView(pair, pairParams);
            animateIn(pair, index / 2 + 1);
        }
    }

    private void settingsHeading(LinearLayout parent, String title, String subtitle) {
        // The left module rail already identifies this category. Keep the right
        // pane focused on its controls instead of repeating a title and paragraph.
    }

    private TextView settingInfoButton(String title, String information) {
        TextView info = text("i", 10, 0xffc6d9e4, true);
        info.setGravity(Gravity.CENTER);
        info.setBackground(shape(new int[]{0x553c596d, 0x33243d50}, 12, 0x665d7a90));
        info.setContentDescription("Información de " + title);
        info.setOnClickListener(v -> {
            haptic(v);
            new AlertDialog.Builder(getContext()).setTitle(title)
                    .setMessage(information == null ? "" : information)
                    .setPositiveButton("ENTENDIDO", null).show();
        });
        return info;
    }

    private View settingChoice(String title, String subtitle, String key,
                               String[] entries, int defaultValue) {
        LinearLayout card = column();
        card.setPadding(dp(8), dp(6), dp(8), dp(6));
        card.setBackground(shape(new int[]{0xff20394d, 0xff142638}, 11, 0x665b788d));
        LinearLayout heading = row();
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.addView(text(title, 9, white, true),
                new LinearLayout.LayoutParams(0, dp(20), 1f));
        heading.addView(settingInfoButton(title, subtitle),
                new LinearLayout.LayoutParams(dp(21), dp(21)));
        card.addView(heading);

        int initial = Math.max(0, Math.min(entries.length - 1, preferences.getInt(key, defaultValue)));
        LinearLayout selector = row();
        selector.setGravity(Gravity.CENTER_VERTICAL);
        selector.setPadding(dp(8), 0, dp(7), 0);
        selector.setBackground(shape(new int[]{0xff304a5e, 0xff1e3447}, 8, 0x77617e91));
        TextView current = text(entries[initial], 8, 0xffe6f4f7, true);
        selector.addView(current, new LinearLayout.LayoutParams(0, dp(29), 1f));
        TextView arrow = text("⌄", 15, 0xffb9f27c, true);
        arrow.setGravity(Gravity.CENTER);
        selector.addView(arrow, new LinearLayout.LayoutParams(dp(22), dp(29)));
        installPressAnimation(selector);
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
        LinearLayout.LayoutParams selectorParams = new LinearLayout.LayoutParams(-1, dp(30));
        selectorParams.topMargin = dp(3);
        card.addView(selector, selectorParams);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(5);
        card.setLayoutParams(params);
        return card;
    }

    private View settingSlider(String title, String subtitle, String key,
                               int min, int max, int defaultValue, String suffix) {
        LinearLayout card = column();
        card.setPadding(dp(8), dp(5), dp(8), dp(4));
        card.setBackground(shape(new int[]{0xff1c3043, 0xff142334}, 11, 0x554b6478));
        LinearLayout heading = row();
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.addView(text(title, 9, white, true),
                new LinearLayout.LayoutParams(0, dp(19), 1f));
        heading.addView(settingInfoButton(title, subtitle),
                new LinearLayout.LayoutParams(dp(20), dp(20)));
        TextView amount = text("", 8, 0xffb9f27c, true);
        amount.setGravity(Gravity.CENTER);
        heading.addView(amount, new LinearLayout.LayoutParams(dp(39), dp(19)));
        card.addView(heading);

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
        LinearLayout.LayoutParams seekParams = new LinearLayout.LayoutParams(-1, dp(26));
        card.addView(seek, seekParams);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(5);
        card.setLayoutParams(params);
        return card;
    }

    private View soundVolumeControl() {
        LinearLayout card = column();
        card.setPadding(dp(12), dp(9), dp(12), dp(8));
        card.setBackground(shape(new int[]{0xff20394d, 0xff142638}, 14, 0x665b788d));
        LinearLayout heading = row();
        heading.addView(text("Volumen de efectos", 10, white, true),
                new LinearLayout.LayoutParams(0, dp(22), 1f));
        heading.addView(settingInfoButton("Volumen de efectos",
                "Ajusta el volumen de los sonidos de juego, pasos, rotura y recogida."),
                new LinearLayout.LayoutParams(dp(21), dp(21)));
        TextView amount = text("", 9, 0xffb9f27c, true);
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
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(8), dp(5), dp(7), dp(5));
        card.setBackground(shape(new int[]{0xff1c3043, 0xff142334}, 11, 0x554b6478));
        card.addView(text(title, 9, white, true),
                new LinearLayout.LayoutParams(0, -2, 1f));
        card.addView(settingInfoButton(title, subtitle),
                new LinearLayout.LayoutParams(dp(21), dp(21)));
        Switch toggle = new Switch(getContext());
        toggle.setButtonTintList(ColorStateList.valueOf(0xffa9ee70));
        toggle.setScaleX(0.82f);
        toggle.setScaleY(0.82f);
        toggle.setChecked(preferences.getBoolean(key, defaultValue));
        toggle.setOnCheckedChangeListener((button, checked) ->
                preferences.edit().putBoolean(key, checked).apply());
        LinearLayout.LayoutParams toggleParams = new LinearLayout.LayoutParams(dp(40), dp(32));
        toggleParams.leftMargin = dp(2);
        card.addView(toggle, toggleParams);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(46));
        p.bottomMargin = dp(5);
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
        LinearLayout root = pageRoot("", "");
        multiplayerStatus = text("Buscando partidas en la red local…", 8, 0xffb9cbd6, false);
        multiplayerStatus.setGravity(Gravity.CENTER_VERTICAL);
        multiplayerStatus.setPadding(dp(8), 0, dp(8), 0);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(-1, dp(25));
        statusParams.topMargin = dp(3);
        root.addView(multiplayerStatus, statusParams);

        multiplayerGames = column();
        multiplayerGames.setClipChildren(true);
        multiplayerGames.setClipToPadding(true);
        ScrollView scroll = new ScrollView(getContext());
        scroll.setFillViewport(false);
        scroll.setClipChildren(true);
        scroll.setClipToPadding(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.addView(multiplayerGames, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        setNearbyGames(new ArrayList<>());

        LinearLayout actionRow = row();
        actionRow.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(-1, dp(40));
        actionParams.topMargin = dp(5);
        root.addView(actionRow, actionParams);
        TextView host = actionButton("CREAR PARTIDA", () -> {
            if (listener != null) listener.onHostMultiplayer();
        });
        host.setTextSize(8);
        actionRow.addView(host, new LinearLayout.LayoutParams(dp(112), dp(34)));
        TextView search = actionButton("BUSCAR", () -> {
            setMultiplayerStatus("Buscando partidas en la red local…");
            if (listener != null) listener.onSearchMultiplayer();
        });
        search.setTextSize(8);
        LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(dp(88), dp(34));
        searchParams.leftMargin = dp(6);
        actionRow.addView(search, searchParams);

        // Discover Wi-Fi LAN sessions as soon as the LOCAL module is opened.
        if (listener != null) listener.onSearchMultiplayer();
    }

    public void setMultiplayerStatus(String status) {
        if (multiplayerStatus != null) multiplayerStatus.setText(status == null ? "" : status);
    }

    public void setNearbyGames(List<LanMultiplayerService.GameInfo> games) {
        if (multiplayerGames == null) return;
        multiplayerGames.removeAllViews();
        if (games == null || games.isEmpty()) {
            TextView empty = text("No se encontraron partidas en esta red todavía.", 9, muted, false);
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
        LinearLayout root = pageRoot("", "");
        addWorldTabs(root, true);
        int mined = preferences.getInt("achievement_blocks_mined", 0);
        int placed = preferences.getInt("achievement_blocks_placed", 0);
        int collected = preferences.getInt("achievement_items_collected", 0);
        int jumps = preferences.getInt("achievement_jumps", 0);
        boolean entered = preferences.getBoolean("achievement_world_entered", false);

        String[] titles = {"Primera aventura", "Primer minero", "Minero experto", "Constructor", "Arquitecto",
                "Recolector", "Primer salto", "Explorador del cielo", "Coleccionista", "Maestro constructor"};
        String[] details = {"Entra en tu primer mundo.", "Rompe tu primer bloque.",
                "Rompe 100 bloques.", "Coloca tu primer bloque.", "Coloca 100 bloques.",
                "Recoge un objeto del suelo.", "Salta por primera vez.", "Realiza 25 saltos.",
                "Recoge 25 objetos.", "Coloca 500 bloques."};
        int[] progress = {entered ? 1 : 0, mined, mined, placed, placed, collected, jumps, jumps, collected, placed};
        int[] targets = {1, 1, 100, 1, 100, 1, 1, 25, 25, 500};

        ScrollView scroll = new ScrollView(getContext());
        scroll.setFillViewport(true);
        scroll.setClipChildren(true);
        scroll.setClipToPadding(true);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(-1, 0, 1f);
        scrollParams.topMargin = dp(10);
        root.addView(scroll, scrollParams);

        LinearLayout grid = column();
        grid.setPadding(0, 0, 0, dp(4));
        grid.setClipChildren(true);
        grid.setClipToPadding(true);
        scroll.addView(grid, new ScrollView.LayoutParams(-1, -2));
        for (int rowIndex = 0; rowIndex < 2; rowIndex++) {
            LinearLayout line = row();
            LinearLayout.LayoutParams lineParams = new LinearLayout.LayoutParams(-1, dp(112));
            if (rowIndex > 0) lineParams.topMargin = dp(7);
            grid.addView(line, lineParams);
            for (int col = 0; col < 5; col++) {
                final int index = rowIndex * 5 + col;
                final int current = Math.max(0, progress[index]);
                final int target = Math.max(1, targets[index]);
                final boolean unlocked = current >= target;
                LinearLayout tile = column();
                tile.setGravity(Gravity.CENTER_VERTICAL);
                tile.setPadding(dp(5), dp(5), dp(5), dp(5));
                tile.setBackground(shape(unlocked
                        ? new int[]{0xff274d3e, 0xff17382f}
                        : new int[]{0xff203449, 0xff142438},
                        12, unlocked ? 0xff8fd17b : 0x554b6b80));

                LinearLayout top = row();
                top.setGravity(Gravity.CENTER_VERTICAL);
                top.addView(new AchievementGlyph(getContext(), index, unlocked ? 0xffb9f27c : 0xff8caabd),
                        new LinearLayout.LayoutParams(dp(21), dp(21)));
                LinearLayout labels = column();
                labels.setPadding(dp(6), 0, 0, 0);
                TextView title = text(titles[index], 7, white, true);
                title.setMaxLines(1);
                labels.addView(title);
                TextView description = text(details[index], 6, muted, false);
                description.setMaxLines(2);
                LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(-1, -2);
                detailParams.topMargin = dp(2);
                labels.addView(description, detailParams);
                top.addView(labels, new LinearLayout.LayoutParams(0, -2, 1f));
                tile.addView(top);

                LinearLayout footer = row();
                footer.setGravity(Gravity.CENTER_VERTICAL);
                TextView status = text(unlocked ? "COMPLETADO" : current + " / " + target,
                        7, unlocked ? 0xffb9f27c : 0xffc2d2dc, true);
                footer.addView(status, new LinearLayout.LayoutParams(0, dp(16), 1f));
                if (unlocked) footer.addView(text("✓", 12, 0xffb9f27c, true));
                LinearLayout.LayoutParams footerParams = new LinearLayout.LayoutParams(-1, dp(16));
                footerParams.topMargin = dp(5);
                tile.addView(footer, footerParams);
                tile.addView(new AchievementProgressView(getContext(),
                        Math.min(1.0f, current / (float) target), unlocked),
                        new LinearLayout.LayoutParams(-1, dp(5)));

                LinearLayout.LayoutParams cell = new LinearLayout.LayoutParams(0, -1, 1f);
                if (col > 0) cell.leftMargin = dp(4);
                line.addView(tile, cell);
            }
        }
    }


    private static final class AchievementGlyph extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final int kind;
        private final int tint;
        AchievementGlyph(Context context, int kind, int tint) {
            super(context);
            this.kind = kind;
            this.tint = tint;
        }
        private void polygon(Canvas canvas, float... points) {
            path.reset();
            path.moveTo(points[0], points[1]);
            for (int i = 2; i < points.length; i += 2) path.lineTo(points[i], points[i + 1]);
            path.close();
            canvas.drawPath(path, paint);
        }
        private void line(Canvas canvas, float x1, float y1, float x2, float y2, float width) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(width);
            paint.setStrokeCap(Paint.Cap.ROUND);
            canvas.drawLine(x1, y1, x2, y2, paint);
            paint.setStyle(Paint.Style.FILL);
        }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float cx = getWidth()*0.5f, cy = getHeight()*0.5f;
            float u = Math.min(getWidth(),getHeight())*0.36f;
            paint.setColor(tint);
            paint.setStyle(Paint.Style.FILL);
            switch (kind) {
                case 0:
                    polygon(canvas,cx,cy-u,cx+u*0.82f,cy,cx,cy+u,cx-u*0.82f,cy);
                    break;
                case 1:
                    line(canvas,cx-u*0.65f,cy+u*0.62f,cx+u*0.62f,cy-u*0.60f,u*0.16f);
                    line(canvas,cx-u*0.58f,cy-u*0.50f,cx+u*0.58f,cy+u*0.50f,u*0.16f);
                    canvas.drawCircle(cx-u*0.62f,cy-u*0.58f,u*0.18f,paint);
                    break;
                case 2:
                    polygon(canvas,cx-u,cy-u*0.55f,cx,cy-u,cx+u,cy-u*0.55f,
                            cx+u*0.75f,cy+u*0.78f,cx-u*0.75f,cy+u*0.78f);
                    break;
                case 3: case 4:
                    polygon(canvas,cx-u,cy-u*0.2f,cx-u*0.45f,cy-u,cx+u*0.45f,cy-u,
                            cx+u,cy-u*0.2f,cx+u*0.65f,cy+u,cx-u*0.65f,cy+u);
                    if (kind == 4) {
                        paint.setColor(0xffffe3a0);
                        canvas.drawRect(cx-u*0.13f,cy-u*0.55f,cx+u*0.13f,cy+u*0.85f,paint);
                    }
                    break;
                case 5:
                    canvas.drawCircle(cx,cy,u*0.78f,paint);
                    paint.setColor(0xff193246);
                    canvas.drawCircle(cx,cy,u*0.40f,paint);
                    break;
                case 6: case 7:
                    line(canvas,cx,cy+u,cx,cy-u,u*0.17f);
                    polygon(canvas,cx,cy-u*1.08f,cx-u*0.58f,cy-u*0.30f,
                            cx+u*0.58f,cy-u*0.30f);
                    break;
                default:
                    canvas.drawCircle(cx,cy,u*0.7f,paint);
            }
        }
    }

    private static final class AchievementProgressView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float progress;
        private final boolean unlocked;
        AchievementProgressView(Context context, float progress, boolean unlocked) {
            super(context);
            this.progress = Math.max(0f,Math.min(1f,progress));
            this.unlocked = unlocked;
        }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float radius = getHeight()*0.5f;
            paint.setColor(0x553a5569);
            canvas.drawRoundRect(new RectF(0,0,getWidth(),getHeight()),radius,radius,paint);
            paint.setColor(unlocked ? 0xffa9ee70 : 0xff72b4ce);
            canvas.drawRoundRect(new RectF(0,0,getWidth()*progress,getHeight()),radius,radius,paint);
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

        private void polygon(Canvas canvas, int color, float... points) {
            Path path = new Path();
            path.moveTo(points[0], points[1]);
            for (int i = 2; i < points.length; i += 2) path.lineTo(points[i], points[i + 1]);
            path.close();
            paint.setShader(null);
            paint.setColor(color);
            canvas.drawPath(path, paint);
        }

        private void cloud(Canvas canvas, float x, float y, float scale, int alpha) {
            paint.setColor(Color.argb(alpha, 241, 249, 242));
            canvas.drawRoundRect(new RectF(x, y + scale * 0.22f, x + scale * 2.7f,
                    y + scale * 0.82f), scale * 0.28f, scale * 0.28f, paint);
            canvas.drawCircle(x + scale * 0.66f, y + scale * 0.42f, scale * 0.45f, paint);
            canvas.drawCircle(x + scale * 1.18f, y + scale * 0.22f, scale * 0.62f, paint);
            canvas.drawCircle(x + scale * 1.82f, y + scale * 0.39f, scale * 0.48f, paint);
        }

        private void mountain(Canvas canvas, float width, float height, float shift,
                              int color, float baseY, float peakScale) {
            Path path = new Path();
            path.moveTo(-width * 0.1f + shift, baseY);
            path.lineTo(width * 0.12f + shift, baseY - height * peakScale * 0.58f);
            path.lineTo(width * 0.24f + shift, baseY - height * peakScale * 0.34f);
            path.lineTo(width * 0.39f + shift, baseY - height * peakScale);
            path.lineTo(width * 0.56f + shift, baseY - height * peakScale * 0.45f);
            path.lineTo(width * 0.72f + shift, baseY - height * peakScale * 0.74f);
            path.lineTo(width * 0.91f + shift, baseY - height * peakScale * 0.32f);
            path.lineTo(width * 1.1f + shift, baseY);
            path.close();
            paint.setColor(color);
            canvas.drawPath(path, paint);
        }

        private void tree(Canvas canvas, float x, float base, float size, int leafColor) {
            paint.setColor(0xff473d34);
            canvas.drawRect(x - size * 0.07f, base - size * 0.55f,
                    x + size * 0.07f, base, paint);
            polygon(canvas, leafColor,
                    x, base - size * 1.28f,
                    x - size * 0.45f, base - size * 0.58f,
                    x + size * 0.43f, base - size * 0.58f);
            polygon(canvas, leafColor,
                    x, base - size * 0.98f,
                    x - size * 0.52f, base - size * 0.32f,
                    x + size * 0.49f, base - size * 0.32f);
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            final float w = getWidth(), h = getHeight();
            SharedPreferences prefs = getContext().getSharedPreferences("minefade_ui", Context.MODE_PRIVATE);
            boolean animate = prefs.getBoolean("menu_background_animations", true)
                    && !prefs.getBoolean("reduce_motion", false);
            float time = (SystemClock.uptimeMillis() - createdAt) / 1000.0f;
            float density = getResources().getDisplayMetrics().density;
            float parallax = animate ? (float) Math.sin(time * 0.075f) * w * 0.025f : 0.0f;

            paint.setShader(new LinearGradient(0, 0, 0, h,
                    new int[]{0xff4f9ab8, 0xff93c9c1, 0xffe2d9a9}, null, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, paint);
            paint.setShader(null);

            float sunX = w * 0.77f + parallax * 0.32f;
            float sunY = h * 0.235f;
            paint.setShader(new RadialGradient(sunX, sunY, h * 0.34f,
                    new int[]{0x88fff5c2, 0x32fff0c2, 0x00fff0c2}, null, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, paint);
            paint.setShader(null);
            paint.setColor(0xffffefbb);
            canvas.drawCircle(sunX, sunY, h * 0.074f, paint);

            float cloudShift = animate ? (time * w * 0.012f) % (w * 1.25f) : 0.0f;
            cloud(canvas, w * 0.07f + cloudShift - w * 0.16f, h * 0.16f, h * 0.07f, 118);
            cloud(canvas, w * 0.54f + cloudShift - w * 0.32f, h * 0.11f, h * 0.055f, 90);
            cloud(canvas, w * 0.83f - cloudShift * 0.65f, h * 0.31f, h * 0.045f, 68);

            mountain(canvas, w, h, -parallax * 0.35f, 0xff8aa9a5, h * 0.72f, 0.43f);
            mountain(canvas, w, h, parallax * 0.45f, 0xff597e78, h * 0.80f, 0.34f);
            mountain(canvas, w, h, -parallax * 0.8f, 0xff355f55, h * 0.87f, 0.25f);

            Path valley = new Path();
            valley.moveTo(0, h * 0.71f);
            valley.cubicTo(w * 0.23f, h * 0.66f, w * 0.34f, h * 0.78f, w * 0.49f, h * 0.73f);
            valley.cubicTo(w * 0.68f, h * 0.66f, w * 0.83f, h * 0.79f, w, h * 0.70f);
            valley.lineTo(w, h);
            valley.lineTo(0, h);
            valley.close();
            paint.setShader(new LinearGradient(0, h * 0.67f, 0, h,
                    new int[]{0xff426c4d, 0xff25483d, 0xff102c32}, null, Shader.TileMode.CLAMP));
            canvas.drawPath(valley, paint);
            paint.setShader(null);

            Path river = new Path();
            river.moveTo(w * 0.46f + parallax * 0.3f, h);
            river.cubicTo(w * 0.55f, h * 0.87f, w * 0.47f, h * 0.83f, w * 0.57f, h * 0.75f);
            river.cubicTo(w * 0.64f, h * 0.78f, w * 0.62f, h * 0.87f, w * 0.72f, h);
            river.close();
            paint.setShader(new LinearGradient(0, h * 0.74f, 0, h,
                    new int[]{0xff7cc8c0, 0xff367f80, 0xff1c5361}, null, Shader.TileMode.CLAMP));
            canvas.drawPath(river, paint);
            paint.setShader(null);

            float treeBase = h * 0.91f;
            for (int i = 0; i < 13; i++) {
                float x = (i / 12.0f) * w + (i % 2 == 0 ? -parallax : parallax);
                float size = h * (0.11f + (i % 4) * 0.012f);
                tree(canvas, x, treeBase + (i % 3) * h * 0.025f, size,
                        i % 2 == 0 ? 0xff204d42 : 0xff2f634d);
            }

            paint.setShader(new LinearGradient(0, h * 0.72f, 0, h,
                    new int[]{0x00203939, 0x66101e25, 0xcc07121c}, null, Shader.TileMode.CLAMP));
            canvas.drawRect(0, h * 0.68f, w, h, paint);
            paint.setShader(null);

            paint.setColor(0x58e4f4df);
            paint.setStrokeWidth(Math.max(1.0f, density));
            for (int i = 0; i < 12; i++) {
                float y = h * (0.78f + (i % 4) * 0.045f);
                float x = w * (0.35f + (i * 0.047f) % 0.38f) + (animate ? (float)Math.sin(time * 0.3f + i) * density * 4 : 0);
                canvas.drawLine(x, y, x + w * (0.025f + (i % 3) * 0.01f), y, paint);
            }

            // Keep the foreground legible while preserving the scenic panorama.
            paint.setShader(new LinearGradient(0, 0, 0, h,
                    new int[]{0x00071421, 0x22071421, 0x99071421}, null, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, paint);
            paint.setShader(null);
            if (animate && isAttachedToWindow()) postInvalidateDelayed(50L);
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

    /** Purpose-drawn silhouettes keep the six avatar module icons consistent on every font. */
    private static final class AvatarModuleGlyph extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final int module;
        private final int tint;

        AvatarModuleGlyph(Context context, int module, int tint) {
            super(context);
            this.module = module;
            this.tint = tint;
        }

        private void polygon(Canvas canvas, float... points) {
            path.reset();
            path.moveTo(points[0], points[1]);
            for (int i = 2; i < points.length; i += 2) path.lineTo(points[i], points[i + 1]);
            path.close();
            canvas.drawPath(path, paint);
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float cx = getWidth() * 0.5f, cy = getHeight() * 0.5f;
            float unit = Math.min(getWidth(), getHeight()) * 0.42f;
            paint.setColor(tint);
            paint.setStyle(Paint.Style.FILL);
            switch (module) {
                case 0: // skin
                    canvas.drawCircle(cx, cy, unit * 0.78f, paint);
                    paint.setColor(0x553b2a25);
                    canvas.drawArc(new RectF(cx-unit*0.8f,cy-unit*0.8f,cx+unit*0.8f,cy+unit*0.8f),
                            205,130,false,paint);
                    break;
                case 1: // hair
                    canvas.drawRoundRect(new RectF(cx-unit*0.78f,cy-unit*0.35f,
                            cx+unit*0.78f,cy+unit*0.78f),unit*0.2f,unit*0.2f,paint);
                    canvas.drawArc(new RectF(cx-unit*0.82f,cy-unit*0.95f,
                            cx+unit*0.82f,cy+unit*0.68f),180,180,true,paint);
                    break;
                case 2: // eyes
                    canvas.drawOval(new RectF(cx-unit*0.95f,cy-unit*0.3f,
                            cx-unit*0.08f,cy+unit*0.38f),paint);
                    canvas.drawOval(new RectF(cx+unit*0.08f,cy-unit*0.3f,
                            cx+unit*0.95f,cy+unit*0.38f),paint);
                    paint.setColor(0xff172638);
                    canvas.drawCircle(cx-unit*0.50f,cy,unit*0.17f,paint);
                    canvas.drawCircle(cx+unit*0.50f,cy,unit*0.17f,paint);
                    break;
                case 3: // clothing
                    polygon(canvas,cx-unit*0.9f,cy-unit*0.45f,cx-unit*0.35f,cy-unit*0.95f,
                            cx+unit*0.35f,cy-unit*0.95f,cx+unit*0.9f,cy-unit*0.45f,
                            cx+unit*0.62f,cy+unit*0.95f,cx-unit*0.62f,cy+unit*0.95f);
                    break;
                case 4: // armour
                    polygon(canvas,cx,cy-unit,cx+unit*0.82f,cy-unit*0.42f,
                            cx+unit*0.64f,cy+unit*0.72f,cx,cy+unit,
                            cx-unit*0.64f,cy+unit*0.72f,cx-unit*0.82f,cy-unit*0.42f);
                    paint.setColor(0x66ffffff);
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(Math.max(1f,unit*0.10f));
                    canvas.drawLine(cx,cy-unit*0.64f,cx,cy+unit*0.72f,paint);
                    canvas.drawLine(cx-unit*0.54f,cy-0.04f*unit,cx+unit*0.54f,cy-0.04f*unit,paint);
                    break;
                default: // accessories
                    polygon(canvas,cx,cy-unit,cx+unit*0.25f,cy-unit*0.28f,
                            cx+unit,cy,cx+unit*0.25f,cy+unit*0.28f,
                            cx,cy+unit,cx-unit*0.25f,cy+unit*0.28f,
                            cx-unit,cy,cx-unit*0.25f,cy-unit*0.28f);
                    paint.setColor(0xffffe7a1);
                    canvas.drawCircle(cx,cy,unit*0.20f,paint);
                    break;
            }
            paint.setStyle(Paint.Style.FILL);
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
            float unit = Math.min(getWidth(), getHeight()) * 0.40f;
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
                case 1: // head and hairstyle silhouette
                    paint.setColor(skin);
                    canvas.drawRoundRect(new RectF(cx-unit*0.57f,cy-unit*0.78f,
                            cx+unit*0.57f,cy+unit*0.87f),unit*0.24f,unit*0.24f,paint);
                    paint.setColor(0xffe7f2f5);
                    canvas.drawCircle(cx-unit*0.22f,cy+unit*0.08f,unit*0.06f,paint);
                    canvas.drawCircle(cx+unit*0.22f,cy+unit*0.08f,unit*0.06f,paint);
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
                default: // individual accessory silhouettes
                    if (choice == 0) {
                        paint.setColor(0x77d4e4ef);
                        paint.setStyle(Paint.Style.STROKE);
                        paint.setStrokeWidth(Math.max(1f,dp(1.8f)));
                        canvas.drawCircle(cx,cy,unit*0.72f,paint);
                        canvas.drawLine(cx-unit*0.48f,cy+unit*0.48f,
                                cx+unit*0.48f,cy-unit*0.48f,paint);
                    } else if (choice == 1) {
                        cube(canvas,cx-unit*0.46f,cy,unit*0.75f,unit*0.70f,
                                unit*0.25f,0xffc49a57,0xff78532f,0xffffe7a1);
                        cube(canvas,cx+unit*0.34f,cy+unit*0.12f,unit*0.70f,unit*0.45f,
                                unit*0.22f,0xff879dac,0xff40596a,0xffdce8ed);
                    } else if (choice == 2) {
                        polygon(canvas,0xff6b527f,cx-unit*0.55f,cy-unit*0.65f,
                                cx+unit*0.55f,cy-unit*0.65f,cx+unit*0.75f,cy+unit*0.85f,
                                cx,cy+unit*0.52f,cx-unit*0.75f,cy+unit*0.85f);
                    } else if (choice == 3) {
                        paint.setColor(0xffffe0a0);
                        paint.setStyle(Paint.Style.STROKE);
                        paint.setStrokeWidth(Math.max(1f,dp(2f)));
                        canvas.drawArc(new RectF(cx-unit*0.56f,cy-unit*0.85f,
                                cx+unit*0.56f,cy+unit*0.50f),200,140,false,paint);
                        polygon(canvas,0xff62b9c4,cx,cy-unit*0.10f,cx+unit*0.30f,cy+unit*0.26f,
                                cx,cy+unit*0.72f,cx-unit*0.30f,cy+unit*0.26f);
                    } else if (choice == 4) {
                        polygon(canvas,0xffffdf83,cx-unit*0.86f,cy+unit*0.38f,
                                cx-unit*0.65f,cy-unit*0.62f,cx-unit*0.18f,cy-unit*0.12f,
                                cx,cy-unit*0.80f,cx+unit*0.18f,cy-unit*0.12f,
                                cx+unit*0.65f,cy-unit*0.62f,cx+unit*0.86f,cy+unit*0.38f);
                    } else {
                        cube(canvas,cx,cy+unit*0.12f,unit*0.72f,unit*1.18f,
                                unit*0.45f,0xff536e7a,0xff283f51,0xff9bbac3);
                        paint.setColor(0xffffe2a0);
                        canvas.drawCircle(cx,cy+unit*0.12f,unit*0.15f,paint);
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
            projectionUnit = Math.min(width / 3.10f, height / 4.05f);
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
            // Reinforced boots and articulated toe caps add readable silhouette detail.
            cubeAnimated(canvas,-0.22f,0.12f+legSway,0.19f,0.16f,0.10f,0.13f,
                    0xff1a2b3c,0xff101e2e,0xff8197a7,-0.22f,0.25f,0.05f,legSway*0.6f,0,0);
            cubeAnimated(canvas,0.22f,0.12f-legSway,0.19f,0.16f,0.10f,0.13f,
                    0xff1a2b3c,0xff101e2e,0xff8197a7,0.22f,0.25f,0.05f,-legSway*0.6f,0,0);
            cubeAnimated(canvas,-0.22f,0.77f+legSway,0.0f,0.145f,0.42f,0.16f,
                    0xff344b65,0xff203044,0xff75899b,-0.22f,1.18f,0.0f,legSway,0,0);
            cubeAnimated(canvas,0.22f,0.77f-legSway,0.0f,0.145f,0.42f,0.16f,
                    0xff405b78,0xff203044,0xff8294a6,0.22f,1.18f,0.0f,-legSway,0,0);
            cube(canvas,0.0f,1.33f,0.0f,0.37f,0.17f,0.22f,
                    0xffaa7d46,0xff5e452e,0xffe2c27d);
            cube(canvas,0.0f,1.34f,0.225f,0.095f,0.085f,0.035f,
                    0xffe4c77f,0xff805c35,0xffffe8a7);

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
            // Hands remain separate from the sleeves and follow the gentle arm swing.
            cubeAnimated(canvas,-0.58f,1.42f,0.015f,0.12f,0.13f,0.13f,
                    skin,0xff91684f,0xffefc9a7,-0.58f,1.58f,0.0f,armSway*0.35f,0,0);
            cubeAnimated(canvas,0.58f,1.42f,0.015f,0.12f,0.13f,0.13f,
                    skin,0xff91684f,0xffefc9a7,0.58f,1.58f,0.0f,-armSway*0.35f,0,0);
            cubeAnimated(canvas,0.0f,2.60f+breath,0.0f,0.17f,0.16f,0.16f,
                    skin,0xff93694e,0xfff5d4b0,0.0f,2.60f,0.0f,0,headSway,0);

            // Head, face, hair cap and selectable hairstyle silhouettes.
            cubeAnimated(canvas,0.0f,3.10f+breath*0.2f,0.0f,0.37f,0.42f,0.31f,
                    skin,0xff95694e,0xfff6d5b3,0.0f,3.10f,0.0f,0,headSway,0);
            // Ears, nose and brows make facial expression readable at the smaller preview scale.
            cubeAnimated(canvas,-0.385f,3.10f+breath*0.2f,0.015f,0.075f,0.14f,0.11f,
                    skin,0xff95694e,0xfff6d5b3,0.0f,3.10f,0.0f,0,headSway,0);
            cubeAnimated(canvas,0.385f,3.10f+breath*0.2f,0.015f,0.075f,0.14f,0.11f,
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
            cubeAnimated(canvas,-0.15f,3.30f,0.321f,0.075f,0.025f,0.026f,
                    hair,0xff3c302b,0xffd0a16a,0.0f,3.10f,0.0f,0,headSway,0);
            cubeAnimated(canvas,0.15f,3.30f,0.321f,0.075f,0.025f,0.026f,
                    hair,0xff3c302b,0xffd0a16a,0.0f,3.10f,0.0f,0,headSway,0);
            cubeAnimated(canvas,0.0f,3.02f,0.338f,0.065f,0.075f,0.065f,
                    skin,0xff95694e,0xfff6d5b3,0.0f,3.10f,0.0f,0,headSway,0);
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
                cube(canvas,0.0f,1.72f,-0.355f,0.16f,0.07f,0.045f,
                        0xffd0a96b,0xff78532f,0xffffe0a0);
            }
        }
    }

}
