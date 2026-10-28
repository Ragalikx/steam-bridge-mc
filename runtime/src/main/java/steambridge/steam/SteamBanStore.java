package steambridge.steam;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import steambridge.core.LogLimiter;
import steambridge.core.ProblemLog;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public abstract class SteamBanStore {
    private static final LogLimiter LOG_LIMITER = new LogLimiter(32, 30_000);

    private static final org.apache.logging.log4j.Logger LOG =
            org.apache.logging.log4j.LogManager.getLogger("steambridge");

    public static final class Record {
        private long steamId;
        private String steamName;
        private String minecraftName;
        private long bannedAt;

        public Record() {}

        public Record(long steamId, String steamName, String minecraftName, long bannedAt) {
            this.steamId = steamId;
            this.steamName = sanitize(steamName);
            this.minecraftName = sanitize(minecraftName);
            this.bannedAt = bannedAt;
        }

        public long getSteamId() {
            return steamId;
        }

        public String getSteamName() {
            return steamName == null ? "" : steamName;
        }

        public String getMinecraftName() {
            return minecraftName == null ? "" : minecraftName;
        }
    }

    private static final class BanStore {
        private Map<String, List<Record>> worlds = new LinkedHashMap<>();
    }

    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final Map<String, Map<Long, Record>> bansByWorld = new ConcurrentHashMap<>();
    private volatile boolean loaded;
    private volatile File storageFile;
    private boolean preserveFile;

    protected SteamBanStore() {}

    public boolean isBanned(String worldKey, long steamId) {
        ensureLoaded();
        return worldMap(worldKey).containsKey(steamId);
    }

    public List<Record> getBans(String worldKey) {
        ensureLoaded();
        List<Record> records = new ArrayList<>(worldMap(worldKey).values());
        records.sort(
                Comparator.comparing(SteamBanStore::recordSortKey, String.CASE_INSENSITIVE_ORDER));
        return records;
    }

    public synchronized Record ban(
            String worldKey, long steamId, String steamName, String minecraftName) {
        ensureLoaded();
        Record record = new Record(steamId, steamName, minecraftName, System.currentTimeMillis());
        worldMap(worldKey).put(steamId, record);
        saveLocked();
        return record;
    }

    public synchronized boolean unban(String worldKey, long steamId) {
        ensureLoaded();
        boolean removed = worldMap(worldKey).remove(steamId) != null;
        if (removed) saveLocked();
        return removed;
    }

    private synchronized void ensureLoaded() {
        if (loaded) return;
        storageFile = resolveStorageFile();
        loaded = true;
        if (storageFile == null || !storageFile.isFile()) return;
        try (Reader reader =
                Files.newBufferedReader(storageFile.toPath(), StandardCharsets.UTF_8)) {
            BanStore store = gson.fromJson(reader, BanStore.class);
            if (store == null || store.worlds == null) throw new IOException("Invalid ban file");
            for (Map.Entry<String, List<Record>> entry : store.worlds.entrySet()) {
                Map<Long, Record> world = worldMap(entry.getKey());
                if (entry.getValue() == null) continue;
                for (Record record : entry.getValue()) {
                    if (record != null && record.getSteamId() != 0L)
                        world.put(record.getSteamId(), record);
                }
            }
        } catch (Exception e) {
            preserveFile = true;
            ProblemLog.warn(
                    LOG,
                    LOG_LIMITER,
                    "SteamBanStore-warn-1",
                    "[Bans] Failed to load from {}: {}",
                    storageFile,
                    e.getMessage());
        }
    }

    private synchronized void saveLocked() {
        if (storageFile == null) storageFile = resolveStorageFile();
        if (storageFile == null || preserveFile) return;
        BanStore store = new BanStore();
        for (Map.Entry<String, Map<Long, Record>> entry : bansByWorld.entrySet()) {
            List<Record> records = new ArrayList<>(entry.getValue().values());
            records.sort(
                    Comparator.comparing(
                            SteamBanStore::recordSortKey, String.CASE_INSENSITIVE_ORDER));
            if (!records.isEmpty()) store.worlds.put(entry.getKey(), records);
        }
        try {
            File parent = storageFile.getParentFile();
            if (parent != null) Files.createDirectories(parent.toPath());
            File tmp =
                    parent != null
                            ? new File(parent, storageFile.getName() + ".tmp")
                            : new File(storageFile.getName() + ".tmp");
            try (Writer writer = Files.newBufferedWriter(tmp.toPath(), StandardCharsets.UTF_8)) {
                gson.toJson(store, writer);
            }
            try {
                Files.move(
                        tmp.toPath(),
                        storageFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(tmp.toPath(), storageFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            ProblemLog.warn(
                    LOG,
                    LOG_LIMITER,
                    "SteamBanStore-warn-2",
                    "[Bans] Failed to save to {}: {}",
                    storageFile,
                    e.getMessage());
        }
    }

    private Map<Long, Record> worldMap(String worldKey) {
        return bansByWorld.computeIfAbsent(
                normalizeWorldKey(worldKey), k -> new ConcurrentHashMap<>());
    }

    private static String normalizeWorldKey(String worldKey) {
        String t = sanitize(worldKey);
        return t.isEmpty() ? "__default_world__" : t;
    }

    private static String recordSortKey(Record record) {
        if (record == null) return "";
        if (!record.getMinecraftName().isEmpty()) return record.getMinecraftName();
        if (!record.getSteamName().isEmpty()) return record.getSteamName();
        return Long.toUnsignedString(record.getSteamId());
    }

    private static String sanitize(String value) {
        return value == null ? "" : value.trim();
    }

    protected abstract File resolveStorageFile();
}
