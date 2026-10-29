package steambridge.panama;

import static java.lang.foreign.ValueLayout.*;

import steambridge.steam.SteamOffsets;

import java.lang.foreign.*;
import java.util.function.BiConsumer;

public final class SteamAPI {
    private static volatile SteamLibrary library;
    private static boolean initialized;
    private static int pipe;
    private static volatile BiConsumer<Long, Boolean> personaListener = (id, avatar) -> {};
    private static volatile Runnable shutdownListener = () -> {};

    private static volatile BiConsumer<Long, String> inviteListener = (id, connect) -> {};
    private static volatile BiConsumer<Long, Integer> presenceListener = (id, appId) -> {};

    public static void setInviteListeners(
            BiConsumer<Long, String> invite, BiConsumer<Long, Integer> presence) {
        inviteListener = invite;
        presenceListener = presence;
    }

    private SteamAPI() {}

    public static synchronized boolean loadLibraries() {
        if (library != null) return true;
        try {
            library = new SteamLibrary(SteamLibrary.extract());
            return true;
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot load Steam FFM library", failure);
        }
    }

    public static synchronized boolean init() {
        if (initialized) return true;
        if (library == null) loadLibraries();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment error = arena.allocate(SteamOffsets.ERROR_BYTES);
            int result = (int) call("SteamAPI_InitFlat", JAVA_INT, error);
            if (result != 0)
                throw new IllegalStateException(
                        "Steam initialization failed: " + error.getString(0));
            initialized = true;
            call("SteamAPI_ManualDispatch_Init", null);
            pipe = (int) call("SteamAPI_GetHSteamPipe", JAVA_INT);
            return true;
        } catch (RuntimeException | Error failure) {
            shutdown();
            throw failure;
        }
    }

    public static void setListeners(BiConsumer<Long, Boolean> persona, Runnable shutdown) {
        personaListener = persona;
        shutdownListener = shutdown;
    }

    public static FunctionDescriptor connectionStatusCallback() {
        return NativeBindings.STATUS_CALLBACK;
    }

    public static void runCallbacks() {
        if (!initialized) return;
        call("SteamAPI_ManualDispatch_RunFrame", null, pipe);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment message = arena.allocate(SteamOffsets.DISPATCH_BYTES, 8);
            for (int count = 0;
                    count < 256
                            && (byte)
                                            call(
                                                    "SteamAPI_ManualDispatch_GetNextCallback",
                                                    JAVA_BYTE,
                                                    pipe,
                                                    message)
                                    != 0;
                    count++) {
                try {
                    int type = message.get(JAVA_INT, SteamOffsets.DISPATCH_TYPE);
                    int size = message.get(JAVA_INT, SteamOffsets.DISPATCH_SIZE);
                    if (size < 0 || size > 1024 * 1024)
                        throw new IllegalStateException("Invalid Steam callback size");
                    dispatchCallback(
                            type, message.get(ADDRESS, SteamOffsets.DISPATCH_PAYLOAD), size);
                } finally {
                    call("SteamAPI_ManualDispatch_FreeLastCallback", null, pipe);
                }
            }
        }
    }

    static void dispatchCallback(int type, MemorySegment pointer, int size) {
        if (type == NativeConstants.CALLBACK_SHUTDOWN) {
            shutdownListener.run();
            return;
        }
        if (pointer.address() == 0) return;
        MemorySegment payload = pointer.reinterpret(size);
        if (type == NativeConstants.CALLBACK_INVITE && size >= SteamOffsets.INVITE_BYTES) {
            MemorySegment text =
                    payload.asSlice(SteamOffsets.INVITE_TEXT, SteamOffsets.INVITE_TEXT_BYTES);
            for (int i = 0; i < SteamOffsets.INVITE_TEXT_BYTES; i++) {
                if (text.get(JAVA_BYTE, i) == 0) {
                    inviteListener.accept(
                            payload.get(JAVA_LONG, 0), text.asSlice(0, i + 1).getString(0));
                    break;
                }
            }
        } else if (type == NativeConstants.CALLBACK_RICH_PRESENCE
                && size >= SteamOffsets.RICH_PRESENCE_MIN_BYTES) {
            presenceListener.accept(payload.get(JAVA_LONG, 0), payload.get(JAVA_INT, 8));
        } else if (type == NativeConstants.CALLBACK_PERSONA
                && size >= SteamOffsets.PERSONA_MIN_BYTES)
            personaListener.accept(
                    payload.get(JAVA_LONG, SteamOffsets.PERSONA_ID),
                    (payload.get(JAVA_INT, SteamOffsets.PERSONA_FLAGS)
                                    & NativeConstants.PERSONA_AVATAR)
                            != 0);
        else if (type == NativeConstants.CALLBACK_AVATAR && size >= SteamOffsets.AVATAR_MIN_BYTES)
            personaListener.accept(payload.get(JAVA_LONG, SteamOffsets.AVATAR_ID), true);
    }

    public static synchronized void shutdown() {
        SteamLibrary current = library;
        if (current == null) return;
        try {
            if (initialized) call("SteamAPI_Shutdown", null);
        } finally {
            initialized = false;
            pipe = 0;
            personaListener = (id, avatar) -> {};
            shutdownListener = () -> {};
            setInviteListeners((id, connect) -> {}, (id, appId) -> {});
            current.close();
            library = null;
        }
    }

    public static Object call(String name, MemoryLayout result, Object... arguments) {
        FunctionDescriptor descriptor = NativeBindings.require(name, result, arguments.length);
        SteamLibrary current = library;
        if (current == null) throw new IllegalStateException("Steam library is not loaded");
        return current.call(name, descriptor, arguments);
    }

    static MemorySegment requireInterface(String export) {
        MemorySegment pointer = (MemorySegment) call(export, ADDRESS);
        if (pointer.address() == 0)
            throw new IllegalStateException("Steam interface unavailable: " + export);
        return pointer;
    }

    static String string(MemorySegment address) {
        return address.address() == 0 ? "" : address.reinterpret(4096).getString(0);
    }
}
