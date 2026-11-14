package com.minefade.nativeengine;

import android.content.Context;
import android.net.Uri;
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
        modsDirectory = new File(context.getFilesDir(), "mods");
        if (!modsDirectory.exists()) modsDirectory.mkdirs();
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
        if (!safe.toLowerCase(Locale.ROOT).endsWith(".mfmod")) safe += ".mfmod";
        File destination = new File(modsDirectory, System.currentTimeMillis() + "-" + safe);
        try (InputStream input = context.getContentResolver().openInputStream(uri);
             FileOutputStream output = new FileOutputStream(destination, false)) {
            if (input == null) return null;
            byte[] buffer = new byte[4096];
            int total = 0, read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > 32768) throw new IllegalArgumentException("El paquete supera 32 KB.");
                output.write(buffer, 0, read);
            }
            output.flush();
            ModInfo info = read(destination);
            if (info == null || info.id.trim().isEmpty() || info.name.trim().isEmpty()) {
                destination.delete();
                return null;
            }
            return info;
        } catch (Exception error) {
            destination.delete();
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
            try (FileOutputStream output = new FileOutputStream(info.file, false)) {
                output.write(data.toString().getBytes(StandardCharsets.UTF_8));
                output.flush();
                output.getFD().sync();
            }
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    public synchronized File createExamplePack() {
        File file = new File(modsDirectory, "example_fauna.mfmod");
        if (file.exists()) return file;
        String data = "# Minefade data mod v1\n"
                + "id=example_fauna\n"
                + "name=Fauna abundante\n"
                + "version=1.0\n"
                + "description=Añade animales al iniciar cada mundo y acelera un poco el día.\n"
                + "enabled=false\n"
                + "spawn.sheep=2\n"
                + "spawn.cow=1\n"
                + "spawn.bird=2\n"
                + "player.speed=1.0\n"
                + "world.day_speed=1.15\n";
        try (FileOutputStream output = new FileOutputStream(file, false)) {
            output.write(data.getBytes(StandardCharsets.UTF_8));
            output.flush();
            output.getFD().sync();
            return file;
        } catch (Exception ignored) {
            file.delete();
            return null;
        }
    }

    private ModInfo read(File file) {
        Map<String,String> values = new LinkedHashMap<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new java.io.FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            int lines = 0;
            while ((line = reader.readLine()) != null && lines++ < 128) {
                String value = line.trim();
                if (value.isEmpty() || value.startsWith("#")) continue;
                int split = value.indexOf('=');
                if (split <= 0) continue;
                String key = value.substring(0, split).trim();
                String content = value.substring(split + 1).trim();
                if (key.matches("[a-zA-Z0-9_.-]{1,64}") && content.length() <= 512)
                    values.put(key, content);
            }
            if (!"1".equals(values.get("version")) && values.get("version") == null) return null;
            return new ModInfo(file, values);
        } catch (Exception ignored) {
            return null;
        }
    }
}
