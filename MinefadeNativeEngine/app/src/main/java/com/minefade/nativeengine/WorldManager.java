package com.minefade.nativeengine;

import android.content.Context;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Persistent catalog for independent Minefade worlds. */
public final class WorldManager {
    public static final int SURVIVAL = 0;
    public static final int CREATIVE = 1;
    public static final int ADVENTURE = 2;

    public static final class WorldInfo {
        public final String id;
        public final String name;
        public final long seed;
        public final int gameMode;
        public final boolean allowCheats;
        public final boolean starterChest;
        public final long createdAt;
        public final long lastPlayed;
        public final File directory;
        public final File worldFile;

        WorldInfo(String id, String name, long seed, int gameMode, boolean allowCheats,
                  boolean starterChest, long createdAt, long lastPlayed, File directory) {
            this.id = id;
            this.name = name;
            this.seed = seed;
            this.gameMode = gameMode;
            this.allowCheats = allowCheats;
            this.starterChest = starterChest;
            this.createdAt = createdAt;
            this.lastPlayed = lastPlayed;
            this.directory = directory;
            this.worldFile = new File(directory, "world.mfworld");
        }

        public String modeLabel() {
            switch (gameMode) {
                case CREATIVE: return "CREATIVO";
                case ADVENTURE: return "AVENTURA";
                default: return "SUPERVIVENCIA";
            }
        }

        public String getPath() { return worldFile.getAbsolutePath(); }
    }

    private final File worldsDirectory;

    public WorldManager(Context context) {
        worldsDirectory = new File(context.getFilesDir(), "worlds");
        if (!worldsDirectory.exists()) worldsDirectory.mkdirs();
        migrateLegacyDefaultWorld();
    }

    private void migrateLegacyDefaultWorld() {
        File legacyFile = new File(worldsDirectory, "default.mfworld");
        if (!legacyFile.isFile()) return;
        File folder = new File(worldsDirectory, "legacy");
        if (!folder.exists() && !folder.mkdirs()) return;
        File destination = new File(folder, "world.mfworld");
        try {
            if (!destination.exists()) {
                try (FileInputStream input = new FileInputStream(legacyFile);
                     FileOutputStream output = new FileOutputStream(destination, false)) {
                    byte[] buffer = new byte[16 * 1024];
                    int read;
                    while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
                    output.flush();
                    output.getFD().sync();
                }
            }
            long stamp = legacyFile.lastModified();
            if (!write(new WorldInfo("legacy", "Mundo anterior", 0x4D464445L,
                    SURVIVAL, false, false, stamp, stamp, folder))) return;
            // Keep the original until the metadata and destination both exist.
            if (destination.isFile()) legacyFile.delete();
        } catch (Exception ignored) { }
    }

    public synchronized List<WorldInfo> listWorlds() {
        ArrayList<WorldInfo> result = new ArrayList<>();
        File[] folders = worldsDirectory.listFiles();
        if (folders == null) return result;
        for (File folder : folders) {
            if (!folder.isDirectory() || folder.getName().startsWith(".")) continue;
            WorldInfo info = read(folder);
            if (info != null) result.add(info);
        }
        result.sort((a,b) -> Long.compare(b.lastPlayed, a.lastPlayed));
        return result;
    }

    public synchronized WorldInfo createWorld(String rawName, long seed, int gameMode,
                                               boolean allowCheats, boolean starterChest) {
        String name = rawName == null ? "" : rawName.trim();
        if (name.isEmpty()) name = "Nuevo mundo";
        if (name.length() > 28) name = name.substring(0, 28);
        int mode = Math.max(SURVIVAL, Math.min(ADVENTURE, gameMode));
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        File folder = new File(worldsDirectory, id);
        if (!folder.mkdirs()) return null;
        long now = System.currentTimeMillis();
        WorldInfo info = new WorldInfo(id, name, seed, mode, allowCheats,
                starterChest, now, now, folder);
        if (!write(info)) {
            deleteTree(folder);
            return null;
        }
        return info;
    }

    public synchronized boolean renameWorld(WorldInfo info, String rawName) {
        if (info == null) return false;
        String name = rawName == null ? "" : rawName.trim();
        if (name.isEmpty()) return false;
        if (name.length() > 28) name = name.substring(0, 28);
        return write(new WorldInfo(info.id, name, info.seed, info.gameMode,
                info.allowCheats, info.starterChest, info.createdAt,
                info.lastPlayed, info.directory));
    }

    public synchronized boolean markPlayed(WorldInfo info) {
        if (info == null) return false;
        return write(new WorldInfo(info.id, info.name, info.seed, info.gameMode,
                info.allowCheats, info.starterChest, info.createdAt,
                System.currentTimeMillis(), info.directory));
    }

    public synchronized boolean deleteWorld(WorldInfo info) {
        if (info == null || info.directory == null) return false;
        try {
            String root = worldsDirectory.getCanonicalPath() + File.separator;
            String child = info.directory.getCanonicalPath();
            return child.startsWith(root) && deleteTree(info.directory);
        } catch (Exception ignored) {
            return false;
        }
    }

    private WorldInfo read(File folder) {
        File metadata = new File(folder, "world.json");
        if (!metadata.isFile()) {
            // Migrate the old single-save layout without deleting or moving it.
            File legacy = new File(folder, "default.mfworld");
            if (legacy.isFile()) {
                WorldInfo migrated = new WorldInfo("legacy", "Mundo anterior", 0x4D464445L,
                        SURVIVAL, false, false, legacy.lastModified(), legacy.lastModified(), folder);
                migrated.worldFile.delete();
                try {
                    java.nio.file.Files.copy(legacy.toPath(), migrated.worldFile.toPath());
                    legacy.delete();
                    write(migrated);
                    return migrated;
                } catch (Exception ignored) { return null; }
            }
            return null;
        }
        try (FileInputStream input = new FileInputStream(metadata)) {
            byte[] bytes = new byte[(int)Math.min(metadata.length(), 16384L)];
            int length = input.read(bytes);
            if (length <= 0) return null;
            JSONObject json = new JSONObject(new String(bytes, 0, length, StandardCharsets.UTF_8));
            String id = json.optString("id", folder.getName());
            String name = json.optString("name", "Mundo");
            long seed = json.optLong("seed", 0x4D464445L);
            int mode = Math.max(SURVIVAL, Math.min(ADVENTURE, json.optInt("gameMode", SURVIVAL)));
            boolean cheats = json.optBoolean("allowCheats", false);
            boolean chest = json.optBoolean("starterChest", false);
            long created = json.optLong("createdAt", metadata.lastModified());
            long played = json.optLong("lastPlayed", metadata.lastModified());
            return new WorldInfo(id, name, seed, mode, cheats, chest, created, played, folder);
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean write(WorldInfo info) {
        try {
            JSONObject json = new JSONObject();
            json.put("id", info.id);
            json.put("name", info.name);
            json.put("seed", info.seed);
            json.put("gameMode", info.gameMode);
            json.put("allowCheats", info.allowCheats);
            json.put("starterChest", info.starterChest);
            json.put("createdAt", info.createdAt);
            json.put("lastPlayed", info.lastPlayed);
            File target = new File(info.directory, "world.json");
            File temp = new File(info.directory, "world.json.tmp");
            try (FileOutputStream output = new FileOutputStream(temp, false)) {
                output.write(json.toString(2).getBytes(StandardCharsets.UTF_8));
                output.flush();
                output.getFD().sync();
            }
            if (target.exists() && !target.delete()) return false;
            return temp.renameTo(target);
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean deleteTree(File file) {
        if (file == null || !file.exists()) return true;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) if (!deleteTree(child)) return false;
        return file.delete();
    }
}
