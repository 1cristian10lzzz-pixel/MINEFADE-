package com.minefade.nativeengine;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;

/** Small low-latency game feedback sounds; all sound assets are local and optional. */
public final class SoundEffects {
    private final SoundPool pool;
    private final int blockBreak;
    private final int blockPlace;
    private final int uiClick;

    public SoundEffects(Context context) {
        AudioAttributes attributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        pool = new SoundPool.Builder().setMaxStreams(6).setAudioAttributes(attributes).build();
        blockBreak = load(context, "audio/block_break.wav");
        blockPlace = load(context, "audio/block_place.wav");
        uiClick = load(context, "audio/ui_click.wav");
    }

    private int load(Context context, String asset) {
        try (android.content.res.AssetFileDescriptor descriptor = context.getAssets().openFd(asset)) {
            return pool.load(descriptor, 1);
        } catch (Exception ignored) {
            return 0;
        }
    }

    public void playEvents(int events) {
        if ((events & 1) != 0 && blockBreak != 0) pool.play(blockBreak, 0.75f, 0.75f, 1, 0, 1.0f);
        if ((events & 2) != 0 && blockPlace != 0) pool.play(blockPlace, 0.65f, 0.65f, 1, 0, 1.0f);
        if ((events & 4) != 0 && uiClick != 0) pool.play(uiClick, 0.45f, 0.45f, 1, 0, 1.0f);
    }

    public void release() { pool.release(); }
}
