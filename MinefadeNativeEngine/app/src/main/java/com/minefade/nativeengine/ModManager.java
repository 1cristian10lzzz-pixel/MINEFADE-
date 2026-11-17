package com.minefade.nativeengine;

import android.content.Context;
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

/** Data-only .mfmod packs. No native code or executable scripts are loaded. */
public final class ModManager {
    public static final class ModInfo {
        public final File file;
        public final String id;
        public final String name;
        public final String version;
        public final String description;
        public final boolean enabled;
        public final Map<String, String> settings;

        ModInfo(File file, Map<String,String> values) {
            this.file = file;
            this.id = values.getOrDefault("id", file.getName());
            this.name = values.getOrDefault("name", id);
            this.version = values.getOrDefault("version", "1");
            this.description = values.getOrDefault("description", "Paquete de datos");
            this.enabled = Boolean.parseBoolean(values.getOrDefault("enabled", "false"));
            this.settings = values;
        }
    }

    private final Context context;
    private final File modsDirectory;

    public ModManager(Context context) {
        this.context = context.getApplicationContext();
        modsDirectory = new File(this.context.getFilesDir(), "mods");
        if (!modsDirectory.exists()) modsDirectory.mkdirs();
        // Remove only the two legacy auto-installed/demo packs. The library
        // must contain packages explicitly imported by the player.
        new File(modsDirectory, "minefade_ultimate_core.mfmod").delete();
        new File(modsDirectory, "minefade_ultimate_core.mfmod.part").delete();
        new File(modsDirectory, "example_fauna.mfmod").delete();
        new File(modsDirectory, "example_fauna.mfmod.tmp").delete();
    }

    public File getDirectory() { return modsDirectory; }

    public synchronized List<ModInfo> listMods() {
        ArrayList<ModInfo> result = new ArrayList<>();
        File[] files = modsDirectory.listFiles();
        if (files == null) return result;
        for (File file : files) {
            if (!file.isFile() || !file.getName().toLowerCase(Locale.ROOT).endsWith(".mfmod")) continue;
            ModInfo info = read(file);
            if (info != null) result.add(info);
        }
        result.sort(Comparator.comparing(m -> m.name.toLowerCase(Locale.ROOT)));
        return result;
    }

    public synchronized ModInfo importMod(Uri uri) {
        if (uri == null) return null;
        String raw = new File(uri.getLastPathSegment() == null ? "paquete.mfmod"
                : uri.getLastPathSegment()).getName();
        String safe = raw.replaceAll("[^a-zA-Z0-9._-]", "_");
        String lowerName = safe.toLowerCase(Locale.ROOT);
        if (!lowerName.endsWith(".mfmod")) safe += ".mfmod";
        else if (!safe.endsWith(".mfmod"))
            safe = safe.substring(0, safe.length() - ".mfmod".length()) + ".mfmod";

        String prefix = System.currentTimeMillis() + "-";
        File destination = new File(modsDirectory, prefix + safe);
        int suffix = 1;
        while (destination.exists()) destination = new File(modsDirectory,
                prefix + suffix++ + "-" + safe);
        File temporary = new File(modsDirectory, destination.getName() + ".part");

        try (InputStream input = context.getContentResolver().openInputStream(uri);
             FileOutputStream output = new FileOutputStream(temporary, false)) {
            if (input == null) { temporary.delete(); return null; }
            byte[] buffer = new byte[4096];
            int total = 0, count;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > 32768) throw new IllegalArgumentException("El paquete supera 32 KB.");
                output.write(buffer, 0, count);
            }
            output.flush();
            output.getFD().sync();
        } catch (Exception error) {
            temporary.delete();
            return null;
        }

        ModInfo validated = read(temporary);
        if (validated == null) {
            temporary.delete();
            return null;
        }
        for (ModInfo existing : listMods()) {
            if (existing.id.equalsIgnoreCase(validated.id)) {
                temporary.delete();
                return existing;
            }
        }
        try {
            Os.rename(temporary.getAbsolutePath(), destination.getAbsolutePath());
            return new ModInfo(destination, validated.settings);
        } catch (Exception error) {
            temporary.delete();
            return null;
        }
    }

    public synchronized boolean setEnabled(ModInfo info, boolean enabled) {
        if (info == null || info.file == null || !info.file.isFile()) return false;
        try {
            Map<String,String> values = new LinkedHashMap<>(info.settings);
            values.put("enabled", Boolean.toString(enabled));
            StringBuilder data = new StringBuilder("# Minefade data mod v1\n");
            for (Map.Entry<String,String> entry : values.entrySet()) {
                String key = entry.getKey();
                String value = entry.getValue();
                if (key.matches("[a-zA-Z0-9_.-]{1,64}") && value.indexOf('\n') < 0 && value.indexOf('\r') < 0)
                    data.append(key).append('=').append(value).append('\n');
            }
            return writeAtomically(info.file, data.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean writeAtomically(File target, byte[] bytes) {
        if (target == null || target.getParentFile() == null || bytes == null) return false;
        File temporary = new File(target.getParentFile(), target.getName() + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temporary, false)) {
            output.write(bytes);
            output.flush();
            output.getFD().sync();
        } catch (Exception error) {
            temporary.delete();
            return false;
        }
        try {
            Os.rename(temporary.getAbsolutePath(), target.getAbsolutePath());
            return target.isFile() && target.length() == bytes.length;
        } catch (Exception error) {
            temporary.delete();
            return false;
        }
    }

    private ModInfo read(File file) {
        if (file == null || !file.isFile() || file.length() <= 0L || file.length() > 32768L) return null;
        Map<String,String> values = new LinkedHashMap<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new java.io.FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            int lines = 0;
            while ((line = reader.readLine()) != null) {
                if (++lines > 128) return null;
                String value = line.trim();
                if (value.isEmpty() || value.startsWith("#")) continue;
                int split = value.indexOf('=');
                if (split <= 0) return null;
                String key = value.substring(0, split).trim();
                String content = value.substring(split + 1).trim();
                if (!key.matches("[a-zA-Z0-9_.-]{1,64}") || content.length() > 512) return null;
                if (!isSupportedKey(key) || values.containsKey(key)) return null;
                values.put(key, content);
            }
            if (!isValidPack(values)) return null;
            return new ModInfo(file, values);
        } catch (Exception ignored) {
            return null;
        }
    }

    /** Keep the Java importer and the native Engine::LoadEnabledMods contract in sync. */
    private static boolean isSupportedKey(String key) {
        switch (key) {
            case "id":
            case "name":
            case "version":
            case "description":
            case "enabled":
            case "spawn.sheep":
            case "spawn.cow":
            case "spawn.bird":
            case "spawn.pig":
            case "spawn.rabbit":
            case "spawn.zombie":
            case "spawn.skeleton":
            case "player.speed":
            case "player.jump":
            case "entity.speed":
            case "world.day_speed":
                return true;
            default:
                return false;
        }
    }

    private static boolean isValidPack(Map<String,String> values) {
        String id = values.get("id");
        String name = values.get("name");
        String format = values.get("version");
        String enabled = values.get("enabled");
        String description = values.getOrDefault("description", "");
        if (id == null || !id.matches("[a-z0-9][a-z0-9_.-]{1,63}")) return false;
        if (name == null || name.trim().isEmpty() || name.length() > 96) return false;
        if (!"1".equals(format) && !"1.0".equals(format)) return false;
        if (!"true".equals(enabled) && !"false".equals(enabled)) return false;
        if (description.length() > 512) return false;

        for (String key : new String[]{"spawn.sheep", "spawn.cow", "spawn.bird", "spawn.pig",
                "spawn.rabbit", "spawn.zombie", "spawn.skeleton"}) {
            String value = values.get(key);
            if (value == null) continue;
            try {
                int count = Integer.parseInt(value);
                if (count < 0 || count > 24) return false;
            } catch (NumberFormatException invalid) {
                return false;
            }
        }
        if (!isFiniteInRange(values.get("player.speed"), 0.5f, 2.0f)) return false;
        if (!isFiniteInRange(values.get("player.jump"), 0.75f, 1.5f)) return false;
        if (!isFiniteInRange(values.get("entity.speed"), 0.5f, 2.0f)) return false;
        if (!isFiniteInRange(values.get("world.day_speed"), 0.25f, 4.0f)) return false;
        return true;
    }

    private static boolean isFiniteInRange(String value, float minimum, float maximum) {
        if (value == null) return true;
        try {
            float number = Float.parseFloat(value);
            return !Float.isNaN(number) && !Float.isInfinite(number)
                    && number >= minimum && number <= maximum;
        } catch (NumberFormatException invalid) {
            return false;
        }
    }
}
