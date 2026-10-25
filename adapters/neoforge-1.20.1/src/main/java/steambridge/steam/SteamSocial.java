/*
 * Copyright (c) 2026 Ragalikx
 * MIT License - see the LICENSE file in the repository root.
 * If you use this code, please credit the author.
 */
package steambridge.steam;

import com.codedisaster.steamworks.SteamUtils;
import com.mojang.blaze3d.platform.NativeImage;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.GameType;

import steambridge.SteamBridgeMod;

import java.io.File;

/**
 * Namespace for Steam social / profile features and persistence layer.
 *
 * <p>Sub-classes:
 *
 * <ul>
 *   <li>{@link ProfileCache}: caches Steam persona names and avatar textures.
 *   <li>{@link Bans}: per-world Steam ban list.
 *   <li>{@link Worlds}: per-world host settings.
 * </ul>
 */
public final class SteamSocial {
    private SteamSocial() {}

    // =========================================================================
    //  ProfileCache  (was SteamProfileCache.java)
    // =========================================================================

    /**
     * Caches Steam persona names and avatar textures. Use {@code SteamSocial.ProfileCache.get()} to
     * obtain the instance.
     */
    public static final class ProfileCache extends SteamProfileCache {
        @Override
        protected SteamRuntime<?, ?> runtime() {
            return SteamManager.getInstance();
        }

        private static final ProfileCache INSTANCE = new ProfileCache();

        public static ProfileCache get() {
            return INSTANCE;
        }

        private ProfileCache() {}

        public String getAvatarTexture(long steamId) {
            try (steambridge.core.NativeCallGate.Lease nativeLease =
                    SteamManager.getInstance().acquireNativeCall()) {
                if (nativeLease == null) return "";

                if (steamId == 0L) return "";

                String cached = avatarTextureBySteamId.get(steamId);
                if (cached != null) return cached;

                long now = System.currentTimeMillis();
                Long retryAt = avatarRetryAt.get(steamId);
                if (retryAt != null && retryAt > now) return "";

                com.codedisaster.steamworks.SteamFriends friends =
                        SteamManager.getInstance().getFriends();
                SteamUtils utils = SteamManager.getInstance().getUtils();
                Minecraft mc = Minecraft.getInstance();
                if (friends == null || utils == null || mc == null) return "";

                requestUserInfoIfNeeded(steamId);

                NativeImage pendingImage = null;
                DynamicTexture pendingTexture = null;
                boolean registered = false;
                try {
                    AvatarData avatar = readAvatar(steamId);
                    if (avatar == null) {
                        avatarRetryAt.put(steamId, now + AVATAR_RETRY_MS);
                        return "";
                    }
                    int width = avatar.width, height = avatar.height;
                    byte[] data = avatar.rgba;

                    // NativeImage.setPixelRGBA expects ABGR-packed ints (0xAABBGGRR);
                    // Steam delivers straight RGBA bytes.
                    NativeImage nativeImage = pendingImage = new NativeImage(width, height, false);
                    for (int y = 0; y < height; y++) {
                        for (int x = 0; x < width; x++) {
                            int i = (y * width + x) * 4;
                            int r = data[i] & 0xFF;
                            int g = data[i + 1] & 0xFF;
                            int b = data[i + 2] & 0xFF;
                            int a = data[i + 3] & 0xFF;
                            int abgr = (a << 24) | (b << 16) | (g << 8) | r;
                            nativeImage.setPixelRGBA(x, y, abgr);
                        }
                    }

                    cacheServerIconBytes(steamId, nativeImage);

                    DynamicTexture texture = pendingTexture = new DynamicTexture(nativeImage);
                    ResourceLocation loc =
                            mc.getTextureManager()
                                    .register("steambridge_avatar_" + steamId, texture);
                    registered = true;
                    String textureId = loc.toString();
                    avatarTextureBySteamId.put(steamId, textureId);
                    avatarRetryAt.remove(steamId);

                    return textureId;
                } catch (Exception e) {
                    avatarRetryAt.put(steamId, now + AVATAR_RETRY_MS);
                    SteamBridgeMod.LOG.warn(
                            "[ProfileCache] Avatar load failed for {}: {}",
                            steamId,
                            e.getMessage());
                    return "";
                } finally {
                    if (!registered) {
                        if (pendingTexture != null) pendingTexture.close();
                        else if (pendingImage != null) pendingImage.close();
                    }
                }
            }
        }

        public byte[] getAvatarIconBytes(long steamId) {
            if (steamId == 0L) return null;
            byte[] cached = avatarIconBytesBySteamId.get(steamId);
            if (cached != null) return cached;
            getAvatarTexture(steamId);
            return avatarIconBytesBySteamId.get(steamId);
        }

        private void cacheServerIconBytes(long steamId, NativeImage src) {
            if (avatarIconBytesBySteamId.containsKey(steamId) || src == null) return;
            NativeImage scaled = null;
            try {
                if (src.getWidth() == 64 && src.getHeight() == 64) {
                    scaled = new NativeImage(64, 64, false);
                    scaled.copyFrom(src);
                } else {
                    scaled = new NativeImage(64, 64, false);
                    src.resizeSubRectTo(0, 0, src.getWidth(), src.getHeight(), scaled);
                }
                byte[] png = scaled.asByteArray();
                if (png != null && png.length > 0) {
                    avatarIconBytesBySteamId.put(steamId, png);
                }
            } catch (Exception e) {
                SteamBridgeMod.LOG.warn(
                        "[ProfileCache] Avatar icon encode failed for {}: {}",
                        steamId,
                        e.getMessage());
            } finally {
                if (scaled != null) {
                    scaled.close();
                }
            }
        }

        @Override
        protected void releaseTexture(String texture) {
            if (texture != null) {
                Minecraft.getInstance()
                        .execute(
                                () ->
                                        Minecraft.getInstance()
                                                .getTextureManager()
                                                .release(new ResourceLocation(texture)));
            }
        }
    }

    // =========================================================================
    //  Bans  (was SteamStorage.Bans)
    // =========================================================================

    /**
     * Manages the per-world Steam ban list. Stored at {@code <gameDir>/steambridge/ban-cache.json}.
     * Use {@code SteamSocial.Bans.get()} to obtain the instance.
     */
    public static final class Bans extends SteamBanStore {
        private static final Bans INSTANCE = new Bans();

        public static Bans get() {
            return INSTANCE;
        }

        @Override
        protected File resolveStorageFile() {
            try {
                Minecraft mc = Minecraft.getInstance();
                File gameDir = mc != null ? mc.gameDirectory : null;
                if (gameDir != null) return new File(gameDir, "steambridge/ban-cache.json");
            } catch (Exception ignored) {
            }
            return new File("steambridge-ban-cache.json");
        }
    }

    // =========================================================================
    //  Worlds  (was SteamStorage.Worlds)
    // =========================================================================

    /**
     * Persists per-world host settings across sessions. Stored at {@code
     * <gameDir>/steambridge/world-settings.json}. Use {@code SteamSocial.Worlds.get()} to obtain
     * the instance.
     */
    public static final class Worlds extends SteamWorldStore {
        private static final Worlds INSTANCE = new Worlds();

        public static Worlds get() {
            return INSTANCE;
        }

        @Override
        protected File resolveFile() {
            try {
                Minecraft mc = Minecraft.getInstance();
                if (mc != null && mc.gameDirectory != null)
                    return new File(mc.gameDirectory, "steambridge/world-settings.json");
            } catch (Exception ignored) {
            }
            return new File("steambridge-world-settings.json");
        }

        public static GameType parseGameType(String value) {
            if (value == null) return GameType.SURVIVAL;
            try {
                return GameType.valueOf(value);
            } catch (IllegalArgumentException ignored) {
                return GameType.SURVIVAL;
            }
        }
    }
}
