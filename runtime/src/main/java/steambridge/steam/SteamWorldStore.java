package steambridge.steam;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import steambridge.core.LogLimiter;
import steambridge.core.ProblemLog;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public abstract class SteamWorldStore {
    private static final LogLimiter LOG_LIMITER = new LogLimiter(32, 30_000);

    private static final org.apache.logging.log4j.Logger LOG =
            org.apache.logging.log4j.LogManager.getLogger("steambridge");

    public static final class Settings {
        public String gametype = "SURVIVAL";
        public boolean allowCommands = false;
        public String accessPolicy = SteamServerBase.AccessPolicy.EVERYONE.name();
        public String transportMode = SteamServerBase.TransportMode.AUTO.name();
    }

    private static final class Store {
        public Map<String, Settings> worlds = new LinkedHashMap<>();
    }

    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private Store store;
    private File storageFile;
    private boolean preserveFile;

    protected SteamWorldStore() {}

    public synchronized Settings load(String worldKey) {
        ensureLoaded();
        Settings saved = store.worlds.get(normalizeKey(worldKey));
        if (saved == null) return new Settings();
        if (saved.gametype == null) saved.gametype = "SURVIVAL";
        if (saved.accessPolicy == null)
            saved.accessPolicy = SteamServerBase.AccessPolicy.EVERYONE.name();
        if (saved.transportMode == null)
            saved.transportMode = SteamServerBase.TransportMode.AUTO.name();
        return saved;
    }

    public synchronized void save(
            String worldKey,
            SteamServerBase.AccessPolicy accessPolicy,
            SteamServerBase.TransportMode transportMode) {
        ensureLoaded();
        Settings s = store.worlds.get(normalizeKey(worldKey));
        if (s == null) s = new Settings();
        s.accessPolicy =
                accessPolicy != null
                        ? accessPolicy.name()
                        : SteamServerBase.AccessPolicy.EVERYONE.name();
        s.transportMode =
                transportMode != null
                        ? transportMode.name()
                        : SteamServerBase.TransportMode.AUTO.name();
        store.worlds.put(normalizeKey(worldKey), s);
        persist();
    }

    public static SteamServerBase.AccessPolicy parseAccessPolicy(String value) {
        if (value == null) return SteamServerBase.AccessPolicy.EVERYONE;
        try {
            return SteamServerBase.AccessPolicy.valueOf(value);
        } catch (IllegalArgumentException ignored) {
            return SteamServerBase.AccessPolicy.EVERYONE;
        }
    }

    public static SteamServerBase.TransportMode parseTransportMode(String value) {
        if (value == null) return SteamServerBase.TransportMode.AUTO;
        try {
            return SteamServerBase.TransportMode.valueOf(value);
        } catch (IllegalArgumentException ignored) {
            return SteamServerBase.TransportMode.AUTO;
        }
    }

    private void ensureLoaded() {
        if (store != null) return;
        storageFile = resolveFile();
        store = new Store();
        if (storageFile != null && storageFile.isFile()) {
            try (Reader r = Files.newBufferedReader(storageFile.toPath(), StandardCharsets.UTF_8)) {
                Store loaded = gson.fromJson(r, Store.class);
                if (loaded == null || loaded.worlds == null)
                    throw new IOException("Invalid world settings");
                store = loaded;
            } catch (Exception e) {
                preserveFile = true;
                ProblemLog.warn(
                        LOG,
                        LOG_LIMITER,
                        "SteamWorldStore-warn-1",
                        "[Worlds] Failed to load settings: {}",
                        e.getMessage());
            }
        }
    }

    private void persist() {
        if (storageFile == null || preserveFile) return;
        try {
            File parent = storageFile.getParentFile();
            if (parent != null) Files.createDirectories(parent.toPath());
            File tmp =
                    new File(
                            parent != null ? parent : new File("."),
                            storageFile.getName() + ".tmp");
            try (Writer w = Files.newBufferedWriter(tmp.toPath(), StandardCharsets.UTF_8)) {
                gson.toJson(store, w);
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
                    "SteamWorldStore-warn-2",
                    "[Worlds] Failed to save settings: {}",
                    e.getMessage());
        }
    }

    private static String normalizeKey(String key) {
        return key == null || key.trim().isEmpty()
                ? "__default__"
                : key.trim().toLowerCase(java.util.Locale.ROOT);
    }

    protected abstract File resolveFile();
}
