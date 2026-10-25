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
import java.util.Base64;

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
            if (steamId == 0L) return "";

            String cached = avatarTextureBySteamId.get(steamId);
            if (cached != null) return cached;

            NativeImage nativeImage = loadAvatarNativeImage(steamId);
            if (nativeImage == null) return "";

            Minecraft mc = Minecraft.getInstance();
            if (mc == null) {
                nativeImage.close();
                return "";
            }

            try {
                cacheServerIconB64(steamId, nativeImage);
                DynamicTexture texture = new DynamicTexture(nativeImage);
                ResourceLocation loc =
                        mc.getTextureManager().register("steambridge_avatar_" + steamId, texture);
                String textureId = loc.toString();
                avatarTextureBySteamId.put(steamId, textureId);
                return textureId;
            } catch (Exception e) {
                nativeImage.close();
                avatarRetryAt.put(steamId, System.currentTimeMillis() + AVATAR_RETRY_MS);
                SteamBridgeMod.LOG.warn(
                        "[ProfileCache] Avatar texture register failed for {}: {}",
                        steamId,
                        e.getMessage());
                return "";
            }
        }

        /**
         * Raw 64x64 PNG as Base64 for the multiplayer server-list favicon slot. Returns null until
         * Steam has the avatar ready (caller may retry next tick).
         */
        public String getAvatarIconB64(long steamId) {
            if (steamId == 0L) return null;
            String cached = avatarIconB64BySteamId.get(steamId);
            if (cached != null) return cached;
            // Load once; also warms the DynamicTexture cache used by friends GUI.
            getAvatarTexture(steamId);
            return avatarIconB64BySteamId.get(steamId);
        }

        /**
         * Fetches the Steam avatar into a {@link NativeImage}, or null if not ready / failed.
         * Caller owns the image if non-null (except when passed into {@link DynamicTexture}).
         */
        private NativeImage loadAvatarNativeImage(long steamId) {
            try (steambridge.core.NativeCallGate.Lease nativeLease =
                    SteamManager.getInstance().acquireNativeCall()) {
                if (nativeLease == null) return null;

                long now = System.currentTimeMillis();
                Long retryAt = avatarRetryAt.get(steamId);
                if (retryAt != null && retryAt > now) return null;

                com.codedisaster.steamworks.SteamFriends friends =
                        SteamManager.getInstance().getFriends();
                SteamUtils utils = SteamManager.getInstance().getUtils();
                if (friends == null || utils == null) return null;

                requestUserInfoIfNeeded(steamId);

                try {
                    AvatarData avatar = readAvatar(steamId);
                    if (avatar == null) {
                        avatarRetryAt.put(steamId, now + AVATAR_RETRY_MS);
                        return null;
                    }
                    int width = avatar.width, height = avatar.height;
                    byte[] data = avatar.rgba;

                    // NativeImage.setPixelRGBA expects ABGR-packed ints (0xAABBGGRR);
                    // Steam delivers straight RGBA bytes.
                    NativeImage nativeImage = new NativeImage(width, height, false);
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

                    avatarRetryAt.remove(steamId);
                    return nativeImage;
                } catch (Exception e) {
                    avatarRetryAt.put(steamId, now + AVATAR_RETRY_MS);
                    SteamBridgeMod.LOG.warn(
                            "[ProfileCache] Avatar load failed for {}: {}",
                            steamId,
                            e.getMessage());
                    return null;
                }
            }
        }

        /** Encode a copy scaled to 64x64 PNG base64 (vanilla multiplayer icon size). */
        private void cacheServerIconB64(long steamId, NativeImage src) {
            if (avatarIconB64BySteamId.containsKey(steamId) || src == null) return;
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
                    avatarIconB64BySteamId.put(steamId, Base64.getEncoder().encodeToString(png));
                }
            } catch (Exception e) {
                SteamBridgeMod.LOG.warn(
                        "[ProfileCache] Avatar iconB64 encode failed for {}: {}",
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
