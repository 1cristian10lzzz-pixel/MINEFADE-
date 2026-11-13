package com.minefade.nativeengine;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.util.Log;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/** Low-latency local game audio with load-state tracking and live master volume. */
public final class SoundEffects {
    private static final String TAG = "MinefadeAudio";
    private final SoundPool pool;
    private final SharedPreferences preferences;
    private final Set<Integer> loadedSamples = Collections.synchronizedSet(new HashSet<>());
    private final AtomicInteger variationSequence = new AtomicInteger();
    private final int blockBreak;
    private final int blockPlace;
    private final int uiClick;
    private final int itemPickup;
    private final int jump;
    private final int footstep;
    private volatile float masterVolume;
    private volatile boolean released;

    private final SharedPreferences.OnSharedPreferenceChangeListener volumeListener =
            (sharedPreferences, key) -> {
                if ("sound_volume".equals(key)) {
                    masterVolume = clamp(sharedPreferences.getFloat("sound_volume", 0.82f));
                }
            };

    public SoundEffects(Context context) {
        preferences = context.getSharedPreferences("minefade_ui", Context.MODE_PRIVATE);
        masterVolume = clamp(preferences.getFloat("sound_volume", 0.82f));
        AudioAttributes attributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        pool = new SoundPool.Builder().setMaxStreams(8).setAudioAttributes(attributes).build();
        pool.setOnLoadCompleteListener((soundPool, sampleId, status) -> {
            if (released) return;
            if (status == 0) loadedSamples.add(sampleId);
            else Log.w(TAG, "A sound sample failed to load: " + sampleId + " status=" + status);
        });
        blockBreak = load(context, "audio/block_break.wav");
        blockPlace = load(context, "audio/block_place.wav");
        uiClick = load(context, "audio/ui_click.wav");
        itemPickup = load(context, "audio/item_pickup.wav");
        jump = load(context, "audio/jump.wav");
        footstep = load(context, "audio/footstep.wav");
        preferences.registerOnSharedPreferenceChangeListener(volumeListener);
    }

    private int load(Context context, String asset) {
        try (android.content.res.AssetFileDescriptor descriptor = context.getAssets().openFd(asset)) {
            return pool.load(descriptor, 1);
        } catch (Exception error) {
            Log.w(TAG, "Optional sound is unavailable: " + asset, error);
            return 0;
        }
    }

    private static float clamp(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }

    private void play(int sampleId, float gain, float rate) {
        if (released || sampleId == 0 || !loadedSamples.contains(sampleId)) return;
        final float volume = clamp(masterVolume * gain);
        if (volume <= 0.001f) return;
        // Tiny deterministic pitch/gain changes make repeated block and footstep
        // samples feel less mechanical without introducing expensive audio work.
        final int variation = Math.floorMod(variationSequence.getAndIncrement() * 37 + sampleId * 11, 9) - 4;
        final float variedRate = Math.max(0.88f, Math.min(1.14f, rate + variation * 0.0125f));
        final float variedVolume = clamp(volume * (0.97f + Math.floorMod(variation + 4, 5) * 0.015f));
        pool.play(sampleId, variedVolume, variedVolume, 1, 0, variedRate);
    }

    /** Menu feedback works before the GL world is launched. */
    public void playMenuClick() {
        if (!preferences.getBoolean("ui_sounds_enabled", true)) return;
        play(uiClick, 0.52f, 1.0f);
    }

    /** Native sound-event bits: break=1, place=2, UI=4, pickup=8, jump=16, footstep=32. */
    public void playEvents(int events) {
        if (!preferences.getBoolean("sound_effects_enabled", true)) return;
        if ((events & 1) != 0) play(blockBreak, 0.82f, 1.0f);
        if ((events & 2) != 0) play(blockPlace, 0.72f, 1.0f);
        if ((events & 4) != 0) play(uiClick, 0.48f, 1.0f);
        if ((events & 8) != 0) play(itemPickup, 0.88f, 1.0f);
        if ((events & 16) != 0) play(jump, 0.74f, 1.0f);
        if ((events & 32) != 0) play(footstep, 0.32f, 1.0f);
    }

    public int loadedSampleCount() {
        return loadedSamples.size();
    }

    public void release() {
        if (released) return;
        released = true;
        preferences.unregisterOnSharedPreferenceChangeListener(volumeListener);
        loadedSamples.clear();
        pool.release();
    }
}
