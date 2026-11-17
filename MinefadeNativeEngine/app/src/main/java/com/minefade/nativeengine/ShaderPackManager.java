package com.minefade.nativeengine;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.system.Os;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Versioned data-only shader profiles. Profiles configure the built-in GLES
 * renderer with bounded options; v1 never executes scripts or arbitrary GLSL.
 */
public final class ShaderPackManager {
    private static final int MAX_BYTES = 64 * 1024;
    private static final int MAX_LINES = 128;

    public static final class PackInfo {
        public final File file;
        public final String id;
        public final String name;
        public final String version;
        public final Map<String, String> settings;

        PackInfo(File file, Map<String, String> values) {
            this.file = file;
            this.id = values.getOrDefault("id", file.getName());
            this.name = values.getOrDefault("name", id);
            this.version = values.getOrDefault("version", "1");
            this.settings = values;
        }
    }

    private final Context context;
    private final File shaderDirectory;

    public ShaderPackManager(Context context) {
        this.context = context.getApplicationContext();
        shaderDirectory = new File(this.context.getFilesDir(), "shaders");
        if (!shaderDirectory.exists()) shaderDirectory.mkdirs();
    }

    public synchronized List<PackInfo> listPacks() {
        ArrayList<PackInfo> result = new ArrayList<>();
        File[] files = shaderDirectory.listFiles();
        if (files == null) return result;
        for (File file : files) {
            if (!file.isFile() || !file.getName().toLowerCase(Locale.ROOT).endsWith(".mfshader")) continue;
            PackInfo pack = read(file);
            if (pack != null) result.add(pack);
        }
        result.sort(Comparator.comparing(pack -> pack.name.toLowerCase(Locale.ROOT)));
        return result;
    }

    public synchronized PackInfo importShader(Uri uri) {
        if (uri == null) return null;
        String segment = uri.getLastPathSegment();
        String safe = new File(segment == null ? "shader.mfshader" : segment)
                .getName().replaceAll("[^a-zA-Z0-9._-]", "_");
        if (!safe.toLowerCase(Locale.ROOT).endsWith(".mfshader")) safe += ".mfshader";
        File destination = new File(shaderDirectory, System.currentTimeMillis() + "-" + safe);
        File temporary = new File(shaderDirectory, destination.getName() + ".part");
        try (InputStream input = context.getContentResolver().openInputStream(uri);
             FileOutputStream output = new FileOutputStream(temporary, false)) {
            if (input == null) { temporary.delete(); return null; }
            byte[] buffer = new byte[4096];
            int total = 0;
            int count;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > MAX_BYTES) throw new IllegalArgumentException("El perfil supera 64 KiB.");
                output.write(buffer, 0, count);
            }
            output.flush();
            output.getFD().sync();
        } catch (Exception error) {
            temporary.delete();
            return null;
        }
        PackInfo validated = read(temporary);
        if (validated == null) {
            temporary.delete();
            return null;
        }
        for (PackInfo existing : listPacks()) {
            if (existing.id.equalsIgnoreCase(validated.id)) {
                temporary.delete();
                return existing;
            }
        }
        try {
            Os.rename(temporary.getAbsolutePath(), destination.getAbsolutePath());
            return read(destination);
        } catch (Exception error) {
            temporary.delete();
            return null;
        }
    }

    public synchronized boolean activate(PackInfo pack, SharedPreferences preferences) {
        if (pack == null || pack.file == null || !pack.file.isFile()) return false;
        Map<String, String> values = pack.settings;
        float strength;
        try {
            strength = Float.parseFloat(values.getOrDefault("leaves.strength", "0.04"));
        } catch (NumberFormatException invalid) {
            return false;
        }
        preferences.edit()
                .putString("shader_pack_active", pack.id)
                .putBoolean("water_waves", Boolean.parseBoolean(values.getOrDefault("water.waves", "true")))
                .putBoolean("lava_animations", Boolean.parseBoolean(values.getOrDefault("lava.animation", "true")))
                .putBoolean("leaf_wind", Boolean.parseBoolean(values.getOrDefault("leaves.wind", "true")))
                .putInt("leaf_wind_strength", Math.round(strength * 100.0f))
                .apply();
        return true;
    }

    public static void selectBuiltIn(SharedPreferences preferences) {
        preferences.edit().putString("shader_pack_active", "builtin-vibrant")
                .putBoolean("water_waves", true).putBoolean("lava_animations", true)
                .putBoolean("leaf_wind", true).putInt("leaf_wind_strength", 4).apply();
    }

    private PackInfo read(File file) {
        if (file == null || !file.isFile() || file.length() <= 0L || file.length() > MAX_BYTES) return null;
        Map<String, String> values = new LinkedHashMap<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new java.io.FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            int lineCount = 0;
            while ((line = reader.readLine()) != null) {
                if (++lineCount > MAX_LINES) return null;
                String entry = line.trim();
                if (entry.isEmpty() || entry.startsWith("#")) continue;
                int split = entry.indexOf('=');
                if (split <= 0) return null;
                String key = entry.substring(0, split).trim();
                String value = entry.substring(split + 1).trim();
                if (!key.matches("[a-zA-Z0-9_.-]{1,64}") || value.length() > 512
                        || values.containsKey(key) || !isSupported(key, value)) return null;
                values.put(key, value);
            }
            if (!values.containsKey("id") || !values.containsKey("name")
                    || !values.containsKey("version") || !"1".equals(values.get("format"))) return null;
            if (!values.get("id").matches("[a-zA-Z0-9_.-]{1,48}")) return null;
            return new PackInfo(file, values);
        } catch (Exception error) {
            return null;
        }
    }

    private boolean isSupported(String key, String value) {
        switch (key) {
            case "format": return "1".equals(value);
            case "id": return value.matches("[a-zA-Z0-9_.-]{1,48}");
            case "name": case "version": return !value.isEmpty();
            case "water.waves": case "lava.animation": case "leaves.wind":
                return "true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value);
            case "leaves.strength":
                try {
                    float strength = Float.parseFloat(value);
                    return Float.isFinite(strength) && strength >= 0.0f && strength <= 0.12f;
                } catch (NumberFormatException ignored) { return false; }
            default: return false;
        }
    }
}
