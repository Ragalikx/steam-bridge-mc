package steambridge.panama;

import static java.lang.foreign.ValueLayout.*;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemoryLayout;
import java.util.HashMap;
import java.util.Map;

final class NativeBindings {
    static final FunctionDescriptor STATUS_CALLBACK = FunctionDescriptor.ofVoid(ADDRESS);
    private static final Map<String, FunctionDescriptor> DESCRIPTORS = create();

    private NativeBindings() {}

    private static Map<String, FunctionDescriptor> create() {
        Map<String, FunctionDescriptor> bindings = new HashMap<>();
        bind(bindings, "SteamAPI_InitFlat", JAVA_INT, ADDRESS);
        bind(bindings, "SteamAPI_Shutdown", null);
        bind(bindings, "SteamAPI_GetHSteamPipe", JAVA_INT);
        bind(bindings, "SteamAPI_ManualDispatch_Init", null);
        bind(bindings, "SteamAPI_ManualDispatch_RunFrame", null, JAVA_INT);
        bind(bindings, "SteamAPI_ManualDispatch_GetNextCallback", JAVA_BYTE, JAVA_INT, ADDRESS);
        bind(bindings, "SteamAPI_ManualDispatch_FreeLastCallback", null, JAVA_INT);
        bind(bindings, "SteamAPI_SteamUser_v023", ADDRESS);
        bind(bindings, "SteamAPI_SteamFriends_v018", ADDRESS);
        bind(bindings, "SteamAPI_SteamUtils_v010", ADDRESS);
        bind(bindings, "SteamAPI_ISteamUser_GetSteamID", JAVA_LONG, ADDRESS);
        bind(bindings, "SteamAPI_ISteamFriends_GetFriendCount", JAVA_INT, ADDRESS, JAVA_INT);
        bind(
                bindings,
                "SteamAPI_ISteamFriends_GetFriendByIndex",
                JAVA_LONG,
                ADDRESS,
                JAVA_INT,
                JAVA_INT);
        bind(bindings, "SteamAPI_ISteamFriends_GetFriendPersonaName", ADDRESS, ADDRESS, JAVA_LONG);
        bind(
                bindings,
                "SteamAPI_ISteamFriends_GetFriendRelationship",
                JAVA_INT,
                ADDRESS,
                JAVA_LONG);
        bind(
                bindings,
                "SteamAPI_ISteamFriends_RequestUserInformation",
                JAVA_BYTE,
                ADDRESS,
                JAVA_LONG,
                JAVA_BYTE);
        bind(
                bindings,
                "SteamAPI_ISteamFriends_SetRichPresence",
                JAVA_BYTE,
                ADDRESS,
                ADDRESS,
                ADDRESS);
        bind(
                bindings,
                "SteamAPI_ISteamFriends_GetFriendRichPresence",
                ADDRESS,
                ADDRESS,
                JAVA_LONG,
                ADDRESS);
        bind(
                bindings,
                "SteamAPI_ISteamFriends_RequestFriendRichPresence",
                null,
                ADDRESS,
                JAVA_LONG);
        bind(
                bindings,
                "SteamAPI_ISteamFriends_InviteUserToGame",
                JAVA_BYTE,
                ADDRESS,
                JAVA_LONG,
                ADDRESS);
        for (String size : new String[] {"Small", "Medium", "Large"})
            bind(
                    bindings,
                    "SteamAPI_ISteamFriends_Get" + size + "FriendAvatar",
                    JAVA_INT,
                    ADDRESS,
                    JAVA_LONG);
        bind(
                bindings,
                "SteamAPI_ISteamUtils_GetImageSize",
                JAVA_BYTE,
                ADDRESS,
                JAVA_INT,
                ADDRESS,
                ADDRESS);
        bind(
                bindings,
                "SteamAPI_ISteamUtils_GetImageRGBA",
                JAVA_BYTE,
                ADDRESS,
                JAVA_INT,
                ADDRESS,
                JAVA_INT);
        bind(bindings, "SteamAPI_SteamNetworkingSockets_SteamAPI_v012", ADDRESS);
        bind(bindings, "SteamAPI_SteamNetworkingUtils_SteamAPI_v004", ADDRESS);
        bind(bindings, "SteamAPI_ISteamNetworkingSockets_RunCallbacks", null, ADDRESS);
        bind(
                bindings,
                "SteamAPI_ISteamNetworkingSockets_CreateListenSocketP2P",
                JAVA_INT,
                ADDRESS,
                JAVA_INT,
                JAVA_INT,
                ADDRESS);
        bind(
                bindings,
                "SteamAPI_ISteamNetworkingSockets_ConnectP2P",
                JAVA_INT,
                ADDRESS,
                ADDRESS,
                JAVA_INT,
                JAVA_INT,
                ADDRESS);
        bind(
                bindings,
                "SteamAPI_ISteamNetworkingSockets_AcceptConnection",
                JAVA_INT,
                ADDRESS,
                JAVA_INT);
        bind(
                bindings,
                "SteamAPI_ISteamNetworkingSockets_CloseListenSocket",
                JAVA_BYTE,
                ADDRESS,
                JAVA_INT);
        bind(
                bindings,
                "SteamAPI_ISteamNetworkingSockets_CloseConnection",
                JAVA_BYTE,
                ADDRESS,
                JAVA_INT,
                JAVA_INT,
                ADDRESS,
                JAVA_BYTE);
        bind(
                bindings,
                "SteamAPI_ISteamNetworkingSockets_SendMessageToConnection",
                JAVA_INT,
                ADDRESS,
                JAVA_INT,
                ADDRESS,
                JAVA_INT,
                JAVA_INT,
                ADDRESS);
        bind(
                bindings,
                "SteamAPI_ISteamNetworkingSockets_ReceiveMessagesOnConnection",
                JAVA_INT,
                ADDRESS,
                JAVA_INT,
                ADDRESS,
                JAVA_INT);
        bind(
                bindings,
                "SteamAPI_ISteamNetworkingSockets_GetConnectionInfo",
                JAVA_BYTE,
                ADDRESS,
                JAVA_INT,
                ADDRESS);
        bind(
                bindings,
                "SteamAPI_ISteamNetworkingSockets_GetConnectionRealTimeStatus",
                JAVA_INT,
                ADDRESS,
                JAVA_INT,
                ADDRESS,
                JAVA_INT,
                ADDRESS);
        bind(
                bindings,
                "SteamAPI_ISteamNetworkingUtils_SetGlobalCallback_SteamNetConnectionStatusChanged",
                JAVA_BYTE,
                ADDRESS,
                ADDRESS);
        bind(
                bindings,
                "SteamAPI_ISteamNetworkingUtils_SetConfigValue",
                JAVA_BYTE,
                ADDRESS,
                JAVA_INT,
                JAVA_INT,
                JAVA_LONG,
                JAVA_INT,
                ADDRESS);
        bind(bindings, "SteamAPI_ISteamNetworkingUtils_InitRelayNetworkAccess", null, ADDRESS);
        bind(bindings, "SteamAPI_SteamNetworkingIdentity_Clear", null, ADDRESS);
        bind(bindings, "SteamAPI_SteamNetworkingIdentity_SetSteamID64", null, ADDRESS, JAVA_LONG);
        bind(bindings, "SteamAPI_SteamNetworkingIdentity_GetSteamID64", JAVA_LONG, ADDRESS);
        bind(bindings, "SteamAPI_SteamNetworkingIdentity_IsInvalid", JAVA_BYTE, ADDRESS);
        bind(bindings, "SteamAPI_SteamNetworkingMessage_t_Release", null, ADDRESS);
        return Map.copyOf(bindings);
    }

    private static void bind(
            Map<String, FunctionDescriptor> bindings,
            String name,
            MemoryLayout result,
            MemoryLayout... arguments) {
        FunctionDescriptor descriptor =
                result == null
                        ? FunctionDescriptor.ofVoid(arguments)
                        : FunctionDescriptor.of(result, arguments);
        if (bindings.put(name, descriptor) != null)
            throw new IllegalStateException("Duplicate Steam binding: " + name);
    }

    static Map<String, FunctionDescriptor> all() {
        return DESCRIPTORS;
    }

    static FunctionDescriptor require(String name, MemoryLayout result, int arguments) {
        FunctionDescriptor descriptor = DESCRIPTORS.get(name);
        if (descriptor == null
                || !java.util.Objects.equals(descriptor.returnLayout().orElse(null), result)
                || descriptor.argumentLayouts().size() != arguments)
            throw new IllegalArgumentException("Invalid Steam binding: " + name);
        return descriptor;
    }
}
