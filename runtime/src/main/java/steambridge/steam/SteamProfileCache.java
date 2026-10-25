package steambridge.steam;

import com.codedisaster.steamworks.SteamID;

import steambridge.core.BoundedCache;

import java.util.Map;

public abstract class SteamProfileCache {
    protected static final long INFO_RETRY_MS = 5000;
    protected static final long AVATAR_RETRY_MS = 3000;
    protected final Map<Long, String> personaNameBySteamId = new BoundedCache<>(256);
    protected final Map<Long, String> avatarTextureBySteamId =
            new BoundedCache<>(256, this::releaseTexture);
    protected final Map<Long, byte[]> avatarIconBytesBySteamId = new BoundedCache<>(256);
    protected final Map<Long, String> avatarIconB64BySteamId = new BoundedCache<>(256);
    protected final Map<Long, Long> userInfoRequestedAt = new BoundedCache<>(256);
    protected final Map<Long, Long> avatarRetryAt = new BoundedCache<>(256);

    protected abstract SteamRuntime<?, ?> runtime();

    protected abstract void releaseTexture(String texture);

    public void clear() {
        avatarTextureBySteamId.clear();
        avatarIconBytesBySteamId.clear();
        avatarIconB64BySteamId.clear();
        personaNameBySteamId.clear();
        userInfoRequestedAt.clear();
        avatarRetryAt.clear();
    }

    public String getDisplayName(long steamId) {
        try (steambridge.core.NativeCallGate.Lease nativeLease = runtime().acquireNativeCall()) {
            if (nativeLease == null) return fallbackName(steamId);

            if (steamId == 0L) return "Unknown";

            String cached = personaNameBySteamId.get(steamId);
            if (isUsableName(cached)) return cached;

            com.codedisaster.steamworks.SteamFriends friends = runtime().getFriends();
            if (friends == null) return fallbackName(steamId);

            requestUserInfoIfNeeded(steamId);
            try {
                String personaName =
                        friends.getFriendPersonaName(SteamID.createFromNativeHandle(steamId));
                if (isUsableName(personaName)) {
                    personaNameBySteamId.put(steamId, personaName);
                    return personaName;
                }
            } catch (Exception e) {
            }
            return fallbackName(steamId);
        }
    }

    public void invalidate(long steamId) {
        if (steamId == 0L) return;
        personaNameBySteamId.remove(steamId);
        releaseAvatar(steamId);
        avatarIconBytesBySteamId.remove(steamId);
        avatarIconB64BySteamId.remove(steamId);
        avatarRetryAt.remove(steamId);
    }

    public void invalidateAvatar(long steamId) {
        if (steamId == 0L) return;
        releaseAvatar(steamId);
        avatarIconBytesBySteamId.remove(steamId);
        avatarIconB64BySteamId.remove(steamId);
        avatarRetryAt.remove(steamId);
    }

    public void invalidateName(long steamId) {
        personaNameBySteamId.remove(steamId);
    }

    private void releaseAvatar(long steamId) {
        releaseTexture(avatarTextureBySteamId.remove(steamId));
    }

    protected synchronized void requestUserInfoIfNeeded(long steamId) {
        long now = System.currentTimeMillis();
        Long last = userInfoRequestedAt.get(steamId);
        if (last != null && now - last < INFO_RETRY_MS) return;
        userInfoRequestedAt.put(steamId, now);
        runtime().requestUserInformation(steamId);
    }

    protected static boolean isUsableName(String value) {
        if (value == null) return false;
        String t = value.trim();
        return !t.isEmpty() && !"[unknown]".equalsIgnoreCase(t) && !"unknown".equalsIgnoreCase(t);
    }

    protected static String fallbackName(long steamId) {
        String raw = Long.toUnsignedString(steamId);
        return raw.length() > 6 ? "Steam " + raw.substring(raw.length() - 6) : "Steam " + raw;
    }

    protected static final class AvatarData {
        public final int width;
        public final int height;
        public final byte[] rgba;

        AvatarData(int width, int height, byte[] rgba) {
            this.width = width;
            this.height = height;
            this.rgba = rgba;
        }
    }

    protected final AvatarData readAvatar(long steamId) throws Exception {
        com.codedisaster.steamworks.SteamFriends friends = runtime().getFriends();
        com.codedisaster.steamworks.SteamUtils utils = runtime().getUtils();
        if (friends == null || utils == null) return null;
        SteamID id = SteamID.createFromNativeHandle(steamId);
        int image = friends.getMediumFriendAvatar(id);
        if (image <= 0) image = friends.getSmallFriendAvatar(id);
        if (image <= 0) image = friends.getLargeFriendAvatar(id);
        if (image <= 0) return null;
        int[] dims = new int[2];
        if (!utils.getImageSize(image, dims)) return null;
        int width = dims[0], height = dims[1];
        if (width <= 0 || height <= 0 || width > 1024 || height > 1024) return null;
        int bytes = width * height * 4;
        try (NativeAllocation memory = new NativeAllocation(bytes)) {
            java.nio.ByteBuffer rgba = memory.buffer().getByteBuffer(0, bytes);
            if (!utils.getImageRGBA(image, rgba)) return null;
            byte[] data = new byte[bytes];
            rgba.position(0);
            rgba.get(data);
            return new AvatarData(width, height, data);
        }
    }
}
