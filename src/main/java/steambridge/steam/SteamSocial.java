/*
 * Copyright (c) 2026 Ragalikx
 * MIT License - see the LICENSE file in the repository root.
 * If you use this code, please credit the author.
 */
package steambridge.steam;

import com.codedisaster.steamworks.SteamID;
import com.codedisaster.steamworks.SteamUtils;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import steambridge.SteamBridgeConfig;
import steambridge.SteamBridgeMod;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.GameType;

import java.io.File;
import java.io.Reader;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Namespace for Steam social / profile features and persistence layer.
 *
 * <p>Sub-classes:
 * <ul>
 *   <li>{@link ProfileCache}: caches Steam persona names and avatar textures.</li>
 *   <li>{@link Bans}: per-world Steam ban list.</li>
 *   <li>{@link Worlds}: per-world host settings.</li>
 * </ul>
 */
public final class SteamSocial {
    private SteamSocial() {}

    // =========================================================================
    //  ProfileCache  (was SteamProfileCache.java)
    // =========================================================================

    /**
     * Caches Steam persona names and avatar textures.
     * Singleton; obtain via {@code SteamSocial.ProfileCache.get()}. 
     */
    public static final class ProfileCache {

        private static final ProfileCache INSTANCE = new ProfileCache();

        public static ProfileCache get() { return INSTANCE; }

        private static final long INFO_RETRY_MS   = 5_000L;
        private static final long AVATAR_RETRY_MS = 3_000L;

        private final Map<Long, String> personaNameBySteamId   = new ConcurrentHashMap<>();
        private final Map<Long, String> avatarTextureBySteamId = new ConcurrentHashMap<>();
        private final Map<Long, Long>   userInfoRequestedAt    = new ConcurrentHashMap<>();
        private final Map<Long, Long>   avatarRetryAt          = new ConcurrentHashMap<>();

        private ProfileCache() {}

        public String getDisplayName(long steamId) {
            if (steamId == 0L) return "Unknown";

            String cached = personaNameBySteamId.get(steamId);
            if (isUsableName(cached)) return cached;

            com.codedisaster.steamworks.SteamFriends friends = SteamManager.getInstance().getFriends();
            if (friends == null) return fallbackName(steamId);

            requestUserInfoIfNeeded(steamId);
            try {
                String personaName = friends.getFriendPersonaName(SteamID.createFromNativeHandle(steamId));
                if (isUsableName(personaName)) {
                    personaNameBySteamId.put(steamId, personaName);
                    return personaName;
                }
            } catch (Exception e) {
            }
            return fallbackName(steamId);
        }

        public String getAvatarTexture(long steamId) {
            if (steamId == 0L) return "";

            String cached = avatarTextureBySteamId.get(steamId);
            if (cached != null) return cached;

            long now = System.currentTimeMillis();
            Long retryAt = avatarRetryAt.get(steamId);
            if (retryAt != null && retryAt > now) return "";

            com.codedisaster.steamworks.SteamFriends friends = SteamManager.getInstance().getFriends();
            SteamUtils utils = SteamManager.getInstance().getUtils();
            Minecraft mc = Minecraft.getInstance();
            if (friends == null || utils == null || mc == null) return "";

            requestUserInfoIfNeeded(steamId);

            try {
                SteamID id = SteamID.createFromNativeHandle(steamId);
                int image = friends.getMediumFriendAvatar(id);
                if (image <= 0) image = friends.getSmallFriendAvatar(id);
                if (image <= 0) image = friends.getLargeFriendAvatar(id);
                if (image <= 0) { avatarRetryAt.put(steamId, now + AVATAR_RETRY_MS); return ""; }

                int[] dims = new int[2];
                if (!utils.getImageSize(image, dims)) { avatarRetryAt.put(steamId, now + AVATAR_RETRY_MS); return ""; }

                int width = dims[0], height = dims[1];
                // Sanity bound: Steam avatars are at most 184x184. Reject absurd sizes so that
                // width * height * 4 can never overflow int and allocate a mismatched buffer.
                if (width <= 0 || height <= 0 || width > 1024 || height > 1024) {
                    avatarRetryAt.put(steamId, now + AVATAR_RETRY_MS); return "";
                }

                ByteBuffer rgba = ByteBuffer.allocateDirect(width * height * 4);
                if (!utils.getImageRGBA(image, rgba)) { avatarRetryAt.put(steamId, now + AVATAR_RETRY_MS); return ""; }

                byte[] data = new byte[width * height * 4];
                rgba.position(0);
                rgba.get(data);

                // NativeImage.setPixelRGBA is public on 1.21.1 and expects ABGR (0xAABBGGRR).
                // Steam delivers straight RGBA bytes.
                NativeImage nativeImage = new NativeImage(width, height, false);
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        int i = (y * width + x) * 4;
                        int r = data[i]     & 0xFF;
                        int g = data[i + 1] & 0xFF;
                        int b = data[i + 2] & 0xFF;
                        int a = data[i + 3] & 0xFF;
                        int abgr = (a << 24) | (b << 16) | (g << 8) | r;
                        nativeImage.setPixelRGBA(x, y, abgr);
                    }
                }

                DynamicTexture texture  = new DynamicTexture(nativeImage);
                ResourceLocation loc    = mc.getTextureManager().register(
                        "steambridge_avatar_" + steamId, texture);
                String textureId = loc.toString();
                avatarTextureBySteamId.put(steamId, textureId);
                avatarRetryAt.remove(steamId);

                return textureId;
            } catch (Exception e) {
                avatarRetryAt.put(steamId, now + AVATAR_RETRY_MS);
                SteamBridgeMod.LOG.warn("[ProfileCache] Avatar load failed for {}: {}", steamId, e.getMessage());
                return "";
            }
        }

        public void invalidate(long steamId) {
            if (steamId == 0L) return;
            personaNameBySteamId.remove(steamId);
            avatarTextureBySteamId.remove(steamId);
            avatarRetryAt.remove(steamId);
        }

        public void invalidateAvatar(long steamId) {
            if (steamId == 0L) return;
            avatarTextureBySteamId.remove(steamId);
            avatarRetryAt.remove(steamId);
        }

        private void requestUserInfoIfNeeded(long steamId) {
            long now = System.currentTimeMillis();
            Long last = userInfoRequestedAt.get(steamId);
            if (last != null && now - last < INFO_RETRY_MS) return;
            userInfoRequestedAt.put(steamId, now);
            SteamManager.getInstance().requestUserInformation(steamId);
        }

        private static boolean isUsableName(String value) {
            if (value == null) return false;
            String t = value.trim();
            return !t.isEmpty() && !"[unknown]".equalsIgnoreCase(t) && !"unknown".equalsIgnoreCase(t);
        }

        private static String fallbackName(long steamId) {
            String raw = Long.toUnsignedString(steamId);
            return raw.length() > 6 ? "Steam " + raw.substring(raw.length() - 6) : "Steam " + raw;
        }
    }


    // =========================================================================
    //  Bans  (was SteamStorage.Bans)
    // =========================================================================

    /**
     * Manages the per-world Steam ban list.
     * Stored at {@code <gameDir>/steambridge/ban-cache.json}.
     * Singleton; obtain via {@code SteamSocial.Bans.get()}. 
     */
    public static final class Bans {

        /** Immutable ban record stored in {@code ban-cache.json}. */
        public static final class Record {
            private long   steamId;
            private String steamName;
            private String minecraftName;
            private long   bannedAt;

            public Record() {}

            public Record(long steamId, String steamName, String minecraftName, long bannedAt) {
                this.steamId       = steamId;
                this.steamName     = sanitize(steamName);
                this.minecraftName = sanitize(minecraftName);
                this.bannedAt      = bannedAt;
            }

            public long   getSteamId()       { return steamId; }
            public String getSteamName()     { return steamName; }
            public String getMinecraftName() { return minecraftName; }
            public long   getBannedAt()      { return bannedAt; }
        }

        private static final class BanStore {
            private Map<String, List<Record>> worlds = new LinkedHashMap<>();
        }

        private static final Bans INSTANCE = new Bans();
        public static Bans get() { return INSTANCE; }

        private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
        private final Map<String, Map<Long, Record>> bansByWorld = new ConcurrentHashMap<>();
        private volatile boolean loaded;
        private volatile File    storageFile;

        private Bans() {}

        public boolean isBanned(String worldKey, long steamId) {
            ensureLoaded();
            return worldMap(worldKey).containsKey(steamId);
        }

        public List<Record> getBans(String worldKey) {
            ensureLoaded();
            List<Record> records = new ArrayList<>(worldMap(worldKey).values());
            records.sort(Comparator.comparing(Bans::recordSortKey, String.CASE_INSENSITIVE_ORDER));
            return records;
        }

        public synchronized Record ban(String worldKey, long steamId, String steamName, String minecraftName) {
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
            try (Reader reader = Files.newBufferedReader(storageFile.toPath(), StandardCharsets.UTF_8)) {
                BanStore store = gson.fromJson(reader, BanStore.class);
                if (store == null || store.worlds == null) return;
                for (Map.Entry<String, List<Record>> entry : store.worlds.entrySet()) {
                    Map<Long, Record> world = worldMap(entry.getKey());
                    if (entry.getValue() == null) continue;
                    for (Record record : entry.getValue()) {
                        if (record != null && record.getSteamId() != 0L)
                            world.put(record.getSteamId(), record);
                    }
                }
            } catch (Exception e) {
                SteamBridgeMod.LOG.warn("[Bans] Failed to load from {}: {}", storageFile, e.getMessage());
            }
        }

        private synchronized void saveLocked() {
            if (storageFile == null) storageFile = resolveStorageFile();
            if (storageFile == null) return;
            BanStore store = new BanStore();
            for (Map.Entry<String, Map<Long, Record>> entry : bansByWorld.entrySet()) {
                List<Record> records = new ArrayList<>(entry.getValue().values());
                records.sort(Comparator.comparing(Bans::recordSortKey, String.CASE_INSENSITIVE_ORDER));
                if (!records.isEmpty()) store.worlds.put(entry.getKey(), records);
            }
            try {
                File parent = storageFile.getParentFile();
                if (parent != null) Files.createDirectories(parent.toPath());
                File tmp = parent != null
                        ? new File(parent, storageFile.getName() + ".tmp")
                        : new File(storageFile.getName() + ".tmp");
                try (Writer writer = Files.newBufferedWriter(tmp.toPath(), StandardCharsets.UTF_8)) {
                    gson.toJson(store, writer);
                }
                Files.move(tmp.toPath(), storageFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception e) {
                SteamBridgeMod.LOG.warn("[Bans] Failed to save to {}: {}", storageFile, e.getMessage());
            }
        }

        private Map<Long, Record> worldMap(String worldKey) {
            return bansByWorld.computeIfAbsent(normalizeWorldKey(worldKey), k -> new ConcurrentHashMap<>());
        }

        private static String normalizeWorldKey(String worldKey) {
            String t = sanitize(worldKey);
            return t.isEmpty() ? "__default_world__" : t;
        }

        private static String recordSortKey(Record record) {
            if (record == null) return "";
            if (!record.getMinecraftName().isEmpty()) return record.getMinecraftName();
            if (!record.getSteamName().isEmpty())     return record.getSteamName();
            return Long.toUnsignedString(record.getSteamId());
        }

        private static String sanitize(String value) {
            return value == null ? "" : value.trim();
        }

        private static File resolveStorageFile() {
            try {
                Minecraft mc = Minecraft.getInstance();
                File gameDir = mc != null ? mc.gameDirectory : null;
                if (gameDir != null) return new File(gameDir, "steambridge/ban-cache.json");
            } catch (Exception ignored) {}
            return new File("steambridge-ban-cache.json");
        }
    }

    // =========================================================================
    //  Worlds  (was SteamStorage.Worlds)
    // =========================================================================

    /**
     * Persists per-world host settings across sessions.
     * Stored at {@code <gameDir>/steambridge/world-settings.json}.
     * Singleton; obtain via {@code SteamSocial.Worlds.get()}. 
     */
    public static final class Worlds {

        /** Per-world host settings data object. */
        public static final class Settings {
            public String  gametype         = GameType.SURVIVAL.name();
            public boolean allowCommands    = false;
            public String  accessPolicy     = SteamServer.AccessPolicy.EVERYONE.name();
            public String  transportMode    = SteamServer.TransportMode.AUTO.name();
        }

        private static final class Store {
            public Map<String, Settings> worlds = new LinkedHashMap<>();
        }

        private static final Worlds INSTANCE = new Worlds();
        public static Worlds get() { return INSTANCE; }

        private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
        private Store store;
        private File  storageFile;

        private Worlds() {}

        /** Returns saved settings for the given world, or defaults if none exist. */
        public synchronized Settings load(String worldKey) {
            ensureLoaded();
            Settings saved = store.worlds.get(normalizeKey(worldKey));
            if (saved == null) return new Settings();
            if (saved.gametype      == null) saved.gametype      = GameType.SURVIVAL.name();
            if (saved.accessPolicy  == null) saved.accessPolicy  = SteamServer.AccessPolicy.EVERYONE.name();
            if (saved.transportMode == null) saved.transportMode = SteamServer.TransportMode.AUTO.name();
            return saved;
        }

        /** Saves the current settings for the given world key. */
        public synchronized void save(String worldKey, GameType gameType,
                                      boolean allowCommands,
                                      SteamServer.AccessPolicy accessPolicy,
                                      SteamServer.TransportMode transportMode) {
            ensureLoaded();
            Settings s = new Settings();
            s.gametype         = gameType      != null ? gameType.name()      : GameType.SURVIVAL.name();
            s.allowCommands    = allowCommands;
            s.accessPolicy     = accessPolicy  != null ? accessPolicy.name()  : SteamServer.AccessPolicy.EVERYONE.name();
            s.transportMode    = transportMode != null ? transportMode.name() : SteamServer.TransportMode.AUTO.name();
            store.worlds.put(normalizeKey(worldKey), s);
            persist();
        }

        public static GameType parseGameType(String value) {
            if (value == null) return GameType.SURVIVAL;
            try { return GameType.valueOf(value); } catch (IllegalArgumentException ignored) { return GameType.SURVIVAL; }
        }

        public static SteamServer.AccessPolicy parseAccessPolicy(String value) {
            if (value == null) return SteamServer.AccessPolicy.EVERYONE;
            try { return SteamServer.AccessPolicy.valueOf(value); } catch (IllegalArgumentException ignored) { return SteamServer.AccessPolicy.EVERYONE; }
        }

        public static SteamServer.TransportMode parseTransportMode(String value) {
            if (value == null) return SteamServer.TransportMode.AUTO;
            try { return SteamServer.TransportMode.valueOf(value); } catch (IllegalArgumentException ignored) { return SteamServer.TransportMode.AUTO; }
        }

        private void ensureLoaded() {
            if (store != null) return;
            storageFile = resolveFile();
            store = new Store();
            if (storageFile != null && storageFile.isFile()) {
                try (Reader r = Files.newBufferedReader(storageFile.toPath(), StandardCharsets.UTF_8)) {
                    Store loaded = gson.fromJson(r, Store.class);
                    if (loaded != null && loaded.worlds != null) store = loaded;
                } catch (Exception e) {
                    SteamBridgeMod.LOG.warn("[Worlds] Failed to load settings: {}", e.getMessage());
                }
            }
        }

        private void persist() {
            if (storageFile == null) return;
            try {
                File parent = storageFile.getParentFile();
                if (parent != null) Files.createDirectories(parent.toPath());
                File tmp = new File(parent != null ? parent : new File("."), storageFile.getName() + ".tmp");
                try (Writer w = Files.newBufferedWriter(tmp.toPath(), StandardCharsets.UTF_8)) {
                    gson.toJson(store, w);
                }
                Files.move(tmp.toPath(), storageFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception e) {
                SteamBridgeMod.LOG.warn("[Worlds] Failed to save settings: {}", e.getMessage());
            }
        }

        private static String normalizeKey(String key) {
            return key == null || key.trim().isEmpty() ? "__default__" : key.trim().toLowerCase();
        }

        private static File resolveFile() {
            try {
                Minecraft mc = Minecraft.getInstance();
                if (mc != null && mc.gameDirectory != null)
                    return new File(mc.gameDirectory, "steambridge/world-settings.json");
            } catch (Exception ignored) {}
            return new File("steambridge-world-settings.json");
        }
    }
}

