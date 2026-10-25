/*
 * Copyright (c) 2026 Ragalikx
 * MIT License - see the LICENSE file in the repository root.
 * If you use this code, please credit the author.
 */
package steambridge.steam;

import com.codedisaster.steamworks.SteamUtils;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.WorldSettings.GameType;

import steambridge.SteamBridgeMod;

import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Namespace for Steam social / profile features and persistence layer.
 *
 * <p>Sub-classes:
 *
 * <ul>
 *   <li>{@link ProfileCache} - caches Steam persona names and avatar textures.
 *   <li>{@link Bans} - per-world Steam ban list.
 *   <li>{@link Worlds} - per-world host settings.
 * </ul>
 */
public final class SteamSocial {
    private SteamSocial() {}

    // =========================================================================
    //  ProfileCache  (was SteamProfileCache.java)
    // =========================================================================

    /**
     * Caches Steam persona names and avatar textures. Singleton - obtain via {@code
     * SteamSocial.ProfileCache.get()}.
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
                Minecraft mc = Minecraft.getMinecraft();
                if (friends == null || utils == null || mc == null) return "";

                requestUserInfoIfNeeded(steamId);

                try {
                    AvatarData avatar = readAvatar(steamId);
                    if (avatar == null) {
                        avatarRetryAt.put(steamId, now + AVATAR_RETRY_MS);
                        return "";
                    }
                    int width = avatar.width, height = avatar.height;
                    byte[] data = avatar.rgba;

                    BufferedImage imageBuffer =
                            new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
                    for (int y = 0; y < height; y++) {
                        for (int x = 0; x < width; x++) {
                            int i = (y * width + x) * 4;
                            int argb =
                                    ((data[i + 3] & 0xFF) << 24)
                                            | ((data[i] & 0xFF) << 16)
                                            | ((data[i + 1] & 0xFF) << 8)
                                            | (data[i + 2] & 0xFF);
                            imageBuffer.setRGB(x, y, argb);
                        }
                    }

                    DynamicTexture texture = new DynamicTexture(imageBuffer);
                    ResourceLocation loc =
                            mc.getTextureManager()
                                    .getDynamicTextureLocation(
                                            "steambridge_avatar_" + steamId, texture);
                    String textureId = loc.toString();
                    avatarTextureBySteamId.put(steamId, textureId);
                    String b64 = encodeIconPngBase64(imageBuffer);
                    if (b64 != null) {
                        avatarIconB64BySteamId.put(steamId, b64);
                    }
                    avatarRetryAt.remove(steamId);

                    return textureId;
                } catch (Exception e) {
                    avatarRetryAt.put(steamId, now + AVATAR_RETRY_MS);
                    SteamBridgeMod.LOG.warn(
                            "[ProfileCache] Avatar load failed for {}: {}",
                            steamId,
                            e.getMessage());
                    return "";
                }
            }
        }

        /**
         * Base64 PNG for multiplayer list icons ({@link
         * net.minecraft.client.multiplayer.ServerData}). Null until Steam has delivered the avatar
         * (caller may retry on tick).
         */
        public String getAvatarIconB64(long steamId) {
            if (steamId == 0L) return null;
            String cached = avatarIconB64BySteamId.get(steamId);
            if (cached != null) return cached;
            getAvatarTexture(steamId);
            return avatarIconB64BySteamId.get(steamId);
        }

        private static String encodeIconPngBase64(BufferedImage src) {
            try {
                int size = 64;
                BufferedImage scaled = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
                java.awt.Graphics2D g = scaled.createGraphics();
                g.setRenderingHint(
                        java.awt.RenderingHints.KEY_INTERPOLATION,
                        java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.drawImage(src, 0, 0, size, size, null);
                g.dispose();
                java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                javax.imageio.ImageIO.write(scaled, "PNG", baos);
                return java.util.Base64.getEncoder().encodeToString(baos.toByteArray());
            } catch (Exception e) {
                return null;
            }
        }

        @Override
        protected void releaseTexture(String texture) {
            if (texture != null) {
                Minecraft.getMinecraft()
                        .addScheduledTask(
                                () ->
                                        Minecraft.getMinecraft()
                                                .getTextureManager()
                                                .deleteTexture(new ResourceLocation(texture)));
            }
        }
    }

    // =========================================================================
    //  Bans  (was SteamStorage.Bans)
    // =========================================================================

    /**
     * Manages the per-world Steam ban list. Stored at {@code <gameDir>/steambridge/ban-cache.json}.
     * Singleton - obtain via {@code SteamSocial.Bans.get()}.
     */
    public static final class Bans extends SteamBanStore {
        private static final Bans INSTANCE = new Bans();

        public static Bans get() {
            return INSTANCE;
        }

        @Override
        protected File resolveStorageFile() {
            try {
                Minecraft mc = Minecraft.getMinecraft();
                File gameDir = mc != null ? mc.mcDataDir : null;
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
     * <gameDir>/steambridge/world-settings.json}. Singleton - obtain via {@code
     * SteamSocial.Worlds.get()}.
     */
    public static final class Worlds extends SteamWorldStore {
        private static final Worlds INSTANCE = new Worlds();

        public static Worlds get() {
            return INSTANCE;
        }

        @Override
        protected File resolveFile() {
            try {
                Minecraft mc = Minecraft.getMinecraft();
                if (mc != null && mc.mcDataDir != null)
                    return new File(mc.mcDataDir, "steambridge/world-settings.json");
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
