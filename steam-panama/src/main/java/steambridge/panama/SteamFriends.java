package steambridge.panama;

import static java.lang.foreign.ValueLayout.*;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

public final class SteamFriends {
    public enum FriendFlags {
        Immediate
    }

    public enum FriendRelationship {
        None,
        Blocked,
        RequestRecipient,
        Friend,
        RequestInitiator,
        Ignored,
        IgnoredFriend,
        Suggested_DEPRECATED,
        Max
    }

    private final MemorySegment handle = SteamAPI.requireInterface("SteamAPI_SteamFriends_v018");

    public int getFriendCount(FriendFlags flags) {
        return (int)
                SteamAPI.call(
                        "SteamAPI_ISteamFriends_GetFriendCount",
                        JAVA_INT,
                        handle,
                        NativeConstants.FRIEND_IMMEDIATE);
    }

    public SteamID getFriendByIndex(int index, FriendFlags flags) {
        return SteamID.createFromNativeHandle(
                (long)
                        SteamAPI.call(
                                "SteamAPI_ISteamFriends_GetFriendByIndex",
                                JAVA_LONG,
                                handle,
                                index,
                                NativeConstants.FRIEND_IMMEDIATE));
    }

    public String getFriendPersonaName(SteamID id) {
        return SteamAPI.string(
                (MemorySegment)
                        SteamAPI.call(
                                "SteamAPI_ISteamFriends_GetFriendPersonaName",
                                ADDRESS,
                                handle,
                                id.value()));
    }

    public FriendRelationship getFriendRelationship(SteamID id) {
        int value =
                (int)
                        SteamAPI.call(
                                "SteamAPI_ISteamFriends_GetFriendRelationship",
                                JAVA_INT,
                                handle,
                                id.value());
        return value >= 0 && value < FriendRelationship.values().length
                ? FriendRelationship.values()[value]
                : FriendRelationship.None;
    }

    public boolean requestUserInformation(SteamID id, boolean nameOnly) {
        return (byte)
                        SteamAPI.call(
                                "SteamAPI_ISteamFriends_RequestUserInformation",
                                JAVA_BYTE,
                                handle,
                                id.value(),
                                (byte) (nameOnly ? 1 : 0))
                != 0;
    }

    private int avatar(String size, SteamID id) {
        return (int)
                SteamAPI.call(
                        "SteamAPI_ISteamFriends_Get" + size + "FriendAvatar",
                        JAVA_INT,
                        handle,
                        id.value());
    }

    public int getSmallFriendAvatar(SteamID id) {
        return avatar("Small", id);
    }

    public int getMediumFriendAvatar(SteamID id) {
        return avatar("Medium", id);
    }

    public int getLargeFriendAvatar(SteamID id) {
        return avatar("Large", id);
    }

    public boolean setRichPresence(String key, String value) {
        try (Arena arena = Arena.ofConfined()) {
            return (byte)
                            SteamAPI.call(
                                    "SteamAPI_ISteamFriends_SetRichPresence",
                                    JAVA_BYTE,
                                    handle,
                                    arena.allocateFrom(key),
                                    arena.allocateFrom(value))
                    != 0;
        }
    }

    public String getFriendRichPresence(SteamID id, String key) {
        try (Arena arena = Arena.ofConfined()) {
            return SteamAPI.string(
                    (MemorySegment)
                            SteamAPI.call(
                                    "SteamAPI_ISteamFriends_GetFriendRichPresence",
                                    ADDRESS,
                                    handle,
                                    id.value(),
                                    arena.allocateFrom(key)));
        }
    }

    public void requestFriendRichPresence(SteamID id) {
        SteamAPI.call("SteamAPI_ISteamFriends_RequestFriendRichPresence", null, handle, id.value());
    }

    public boolean inviteUserToGame(SteamID id, String connect) {
        try (Arena arena = Arena.ofConfined()) {
            return (byte)
                            SteamAPI.call(
                                    "SteamAPI_ISteamFriends_InviteUserToGame",
                                    JAVA_BYTE,
                                    handle,
                                    id.value(),
                                    arena.allocateFrom(connect))
                    != 0;
        }
    }

    public void dispose() {}
}
