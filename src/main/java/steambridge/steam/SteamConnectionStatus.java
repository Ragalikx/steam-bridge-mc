/*
 * Copyright (c) 2026 Ragalikx
 * MIT License - see the LICENSE file in the repository root.
 * If you use this code, please credit the author.
 */
package steambridge.steam;

public final class SteamConnectionStatus {

    private final long    steamID;
    private final int     connectionHandle;
    private final boolean sessionKnown;
    private final boolean connectionActive;
    private final boolean connecting;
    private final boolean usingRelay;
    private final int     state;
    private final int     endReason;
    private final String  lastError;
    private final int     bytesQueuedForSend;
    private final int     packetsQueuedForSend;
    private final int     pingMs;
    private final int     remotePopId;
    private final int     relayPopId;
    private final float   inBytesPerSecond;
    private final float   outBytesPerSecond;
    private final long    queueTimeMicros;
    private final String  description;

    public SteamConnectionStatus(
        long steamID, int connectionHandle,
        boolean sessionKnown, boolean connectionActive,
        boolean connecting, boolean usingRelay,
        int state, int endReason, String lastError,
        int bytesQueuedForSend, int packetsQueuedForSend,
        int pingMs, int remotePopId, int relayPopId,
        float inBytesPerSecond, float outBytesPerSecond,
        long queueTimeMicros, String description
    ) {
        this.steamID              = steamID;
        this.connectionHandle     = connectionHandle;
        this.sessionKnown         = sessionKnown;
        this.connectionActive     = connectionActive;
        this.connecting           = connecting;
        this.usingRelay           = usingRelay;
        this.state                = state;
        this.endReason            = endReason;
        this.lastError            = lastError   != null ? lastError   : "";
        this.bytesQueuedForSend   = bytesQueuedForSend;
        this.packetsQueuedForSend = packetsQueuedForSend;
        this.pingMs               = pingMs;
        this.remotePopId          = remotePopId;
        this.relayPopId           = relayPopId;
        this.inBytesPerSecond     = inBytesPerSecond;
        this.outBytesPerSecond    = outBytesPerSecond;
        this.queueTimeMicros      = queueTimeMicros;
        this.description          = description != null ? description : "";
    }

    public static SteamConnectionStatus unavailable(long steamID, int connectionHandle) {
        return new SteamConnectionStatus(
            steamID, connectionHandle,
            false, false, false, false,
            SteamSocketsApi.STATE_NONE, 0, "",
            0, 0, -1, 0, 0, 0f, 0f, 0L, ""
        );
    }

    public long    getSteamID()              { return steamID; }
    public int     getConnectionHandle()     { return connectionHandle; }
    public boolean hasSessionState()         { return sessionKnown; }
    public boolean isConnectionActive()      { return connectionActive; }
    public boolean isUsingRelay()            { return usingRelay; }
    public int     getState()                { return state; }
    public String  getLastError()            { return lastError; }
    public int     getPingMs()               { return pingMs; }

    public boolean isTerminal() {
        return SteamSocketsApi.isTerminalState(state);
    }

    public String describeRoute() {
        if (!sessionKnown)               return "No session";
        if (connecting && !connectionActive) return "Finding route";
        if (!connectionActive)           return "Inactive";
        return usingRelay ? "Valve Relay" : "Direct P2P";
    }

    public String describeState() {
        return SteamSocketsApi.stateName(state);
    }
}


