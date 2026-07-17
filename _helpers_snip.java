    private void disposeSteamInterfaces() {
        if (steamUser != null) {
            steamUser.dispose();
            steamUser = null;
        }
        if (steamFriends != null) {
            steamFriends.dispose();
            steamFriends = null;
        }
        if (steamUtils != null) {
            steamUtils.dispose();
            steamUtils = null;
        }
    }

    private void diagnoseInitFailure(InitResult result) {
        boolean steamProc = SteamAppIdHelper.isSteamClientProcessRunning();
        if (result == InitResult.NoSteamClient) {
            lastInitFailure = InitFailure.NO_STEAM_CLIENT;
        } else if (result == InitResult.VersionMismatch) {
            lastInitFailure = InitFailure.VERSION_MISMATCH;
        } else if (steamProc) {
            // Client is up, but Spacewar / Steamworks for app 480 did not attach.
            // Common causes: Family View, family-library restrictions, missing free license edge cases,
            // or another process already owning the Steam API pipe for a different app.
            lastInitFailure = InitFailure.SPACEWAR_FAILED;
        } else if (result == InitResult.FailedGeneric) {
            lastInitFailure = InitFailure.NO_STEAM_CLIENT;
        } else {
            lastInitFailure = InitFailure.UNKNOWN;
        }
        SteamBridgeMod.LOG.error(
                "[SteamManager] SteamAPI.initEx()={} steamProcess={} failure={} (appid={})",
                result, steamProc, lastInitFailure, SteamAppIdHelper.APP_ID
        );
    }

    /**
     * After a successful SteamAPI init, confirm we are running as Spacewar (480) and
     * surface Family Library / license quirks.
     *
     * @return {@code false} only for hard problems (wrong AppID); family-share is logged and allowed
     */
    private boolean verifySpacewarContext() {
        int appId = steamUtils != null ? steamUtils.getAppID() : 0;
        if (appId != 0 && appId != SteamAppIdHelper.APP_ID_INT) {
            lastInitFailure = InitFailure.WRONG_APP_ID;
            SteamBridgeMod.LOG.error(
                    "[SteamManager] Wrong Steam AppID after init: got {} expected {} (Spacewar). "
                            + "Check steam_appid.txt is not overridden.",
                    appId, SteamAppIdHelper.APP_ID_INT
            );
            return false;
        }

        SteamApps apps = null;
        try {
            apps = new SteamApps();
            boolean subscribed = apps.isSubscribed();
            SteamID owner = apps.getAppOwner();
            long me = mySteamID != null ? SteamNativeHandle.getNativeHandle(mySteamID) : 0L;
            long ownerHandle = owner != null ? SteamNativeHandle.getNativeHandle(owner) : 0L;
            boolean familyShared = ownerHandle != 0L && me != 0L && ownerHandle != me;

            SteamBridgeMod.LOG.info(
                    "[SteamManager] Spacewar context: appId={} subscribed={} familyShared={} owner={}",
                    appId, subscribed, familyShared, ownerHandle
            );

            if (!subscribed) {
                // Free Spacewar is almost always subscribed; false usually means Family View /
                // parental or a restricted shared library session.
                lastInitFailure = InitFailure.FAMILY_OR_LICENSE;
                SteamBridgeMod.LOG.error(
                        "[SteamManager] isSubscribed()=false for Spacewar. Family View / shared library "
                                + "restrictions may block multiplayer."
                );
                return false;
            }
            if (familyShared) {
                SteamBridgeMod.LOG.info(
                        "[SteamManager] App owned by another account (Family Library). Owner SteamID={}",
                        ownerHandle
                );
            }
        } catch (Throwable t) {
            SteamBridgeMod.LOG.warn("[SteamManager] Spacewar license probe failed: {}", t.getMessage());
        } finally {
            if (apps != null) {
                try {
                    apps.dispose();
                } catch (Throwable ignored) {}
            }
        }
        return true;
    }

    public InitFailure getLastInitFailure() {
        return lastInitFailure;
    }

    /** i18n key for the last init failure (or a generic key if none). */
    public String getLastInitFailureKey() {
        switch (lastInitFailure) {
            case NATIVE_LOAD:       return "steambridge.error.native_load";
            case NO_STEAM_CLIENT:   return "steambridge.error.no_steam_client";
            case VERSION_MISMATCH:  return "steambridge.error.steam_version";
            case SPACEWAR_FAILED:   return "steambridge.error.spacewar_failed";
            case WRONG_APP_ID:      return "steambridge.error.wrong_appid";
            case FAMILY_OR_LICENSE: return "steambridge.error.family_or_license";
            case UNKNOWN:           return "steambridge.error.steam_init_failed";
            case NONE:
            default:                return "steambridge.error.steam_init_failed";
        }
    }

    /** Short second-line hint key for the resync screen (may be empty). */
    public String getLastInitFailureHintKey() {
        switch (lastInitFailure) {
            case SPACEWAR_FAILED:   return "steambridge.error.spacewar_failed_hint";
            case FAMILY_OR_LICENSE: return "steambridge.error.family_or_license_hint";
            case NO_STEAM_CLIENT:   return "steambridge.error.no_steam_client_hint";
            default:                return "";
        }
    }

    public void shutdown() {
        if (!initialized) {
            return;
        }

        SteamBridgeMod.LOG.info("[SteamManager] Shutting down...");
        running.set(false);
        signalReceiveWake(); // unblock the receive thread if it is parked waiting for connections

        // Wait for both background threads to actually exit their loop before freeing any native
        // Steam resources below. Without this, a thread can still be inside a native JNA call
        // (e.g. SteamAPI.runCallbacks()) when SteamAPI.shutdown() frees the SDK underneath it.
        joinBackgroundThread(callbackThread);
        joinBackgroundThread(receiveThread);
        callbackThread = null;
        receiveThread = null;

        SteamServer server = activeServer;
        if (server != null) {
            server.stop();
        }

        SteamClient client = activeClient;
        if (client != null) {
            client.disconnect();
        }

        statusByConnection.clear();
        connectionBySteamId.clear();
        loopbackByConnection.clear();

        if (socketsApi != null) {
            socketsApi.dispose();
            socketsApi = null;
        }

        if (steamUser != null) {
            steamUser.dispose();
            steamUser = null;
        }
        if (steamFriends != null) {
            steamFriends.dispose();
            steamFriends = null;
        }
        if (steamUtils != null) {
            steamUtils.dispose();
            steamUtils = null;
        }

        SteamAPI.shutdown();
        initialized = false;
        SteamBridgeMod.LOG.info("[SteamManager] Shutdown complete.");
    }

    /**
     * Shuts down and re-initializes Steam. Used by the "resync Steam" button in GUI.
     *
     * @return {@code true} if re-initialization succeeded
     */
