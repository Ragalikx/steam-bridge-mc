package steambridge.steam;

import static org.junit.Assert.*;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.*;
import java.util.function.IntConsumer;

public class SteamLifecycleTest {
    @Rule public TemporaryFolder files = new TemporaryFolder();

    private SteamConnectionStatus status(int handle, int state) {
        return new SteamConnectionStatus(
                1, handle, true, false, false, false, state, 1000, "", 0, -1, 0);
    }

    @Test
    public void hostReconnectAndOldCallbackPreserveNewGameAndVoiceToken() throws Exception {
        FakeRuntime runtime = new FakeRuntime();
        FakeServer server = new FakeServer(runtime, files.newFile());
        server.startHost();
        server.onConnectionStatusChanged(10, 1, status(10, SteamSocketsApi.STATE_CONNECTING), 0);
        Object first = server.getVoiceSessionToken(1);
        assertNotNull(first);
        server.onConnectionStatusChanged(11, 1, status(11, SteamSocketsApi.STATE_CONNECTING), 0);
        Object second = server.getVoiceSessionToken(1);
        assertNotNull(second);
        assertNotSame(first, second);
        server.onConnectionStatusChanged(
                10, 1, status(10, SteamSocketsApi.STATE_CLOSED_BY_PEER), 1);
        assertSame(second, server.getVoiceSessionToken(1));
        assertTrue(server.ownsConnection(11));
        assertFalse(server.ownsConnection(10));
        assertEquals(1, server.getPlayerSnapshots().size());
        server.stop();
        assertNull(server.getVoiceSessionToken(1));
    }

    @Test
    public void eighthPlayerAcceptedAndKickAndBanRevokeVoice() throws Exception {
        FakeRuntime runtime = new FakeRuntime();
        FakeServer server = new FakeServer(runtime, files.newFile());
        server.startHost();
        for (int player = 1; player <= 8; player++) {
            server.onConnectionStatusChanged(
                    player, player, status(player, SteamSocketsApi.STATE_CONNECTING), 0);
            assertNotNull(server.getVoiceSessionToken(player));
        }
        assertEquals(8, server.getPlayerSnapshots().size());
        assertTrue(server.kickPlayer(1));
        assertNull(server.getVoiceSessionToken(1));
        server.onConnectionStatusChanged(100, 1, status(100, SteamSocketsApi.STATE_CONNECTING), 0);
        assertFalse(server.ownsConnection(100));
        assertTrue(server.banPlayer(2));
        assertNull(server.getVoiceSessionToken(2));
        assertEquals(1, server.getBanRecords().size());
        assertTrue(server.unbanPlayer(2));
        server.stop();
    }

    @Test
    public void startingReplacementHostClosesOldListenerAndOldStopKeepsNewHost() throws Exception {
        FakeRuntime runtime = new FakeRuntime();
        FakeServer first = new FakeServer(runtime, files.newFile());
        first.startHost();
        int oldListen = first.getListenSocket();
        FakeServer second = new FakeServer(runtime, files.newFile());
        second.startHost();
        assertFalse(first.isRunning());
        assertTrue(runtime.listenersClosed.contains(oldListen));
        first.stop();
        assertSame(second, runtime.getActiveServer());
        second.stop();
        assertNull(runtime.getActiveServer());
    }

    @Test
    public void lateReadyCannotRegressHandshakeOrWorldState() {
        FakeRuntime runtime = new FakeRuntime();
        FakeClient client = new FakeClient(runtime);
        client.seed(10);
        client.onMinecraftHandshakeStarted("login");
        client.ready();
        assertEquals(SteamClientBase.State.NEGOTIATING, client.getState());
        client.onMinecraftWorldJoined("Player", 0);
        client.ready();
        assertEquals(SteamClientBase.State.IN_WORLD, client.getState());
        client.disconnect();
    }

    @Test
    public void minecraftFailureKeepsReasonAndOldClientCannotClearReplacement() {
        FakeRuntime runtime = new FakeRuntime();
        FakeClient first = new FakeClient(runtime);
        first.seed(10);
        first.closeAfterMinecraftFailure("Missing mods", "Install required mod");
        assertEquals(SteamClientBase.State.FAILED, first.getState());
        assertTrue(first.getStatusMsg().contains("Install required mod"));
        assertFalse(first.isAlive());
        FakeClient second = new FakeClient(runtime);
        second.seed(11);
        first.disconnect();
        assertSame(second, runtime.getActiveClient());
        second.disconnect();
        assertNull(runtime.getActiveClient());
    }

    @Test
    public void missingNativeLibraryDoesNotAbortClientAndAllowsRetry() {
        StartupRuntime runtime = new StartupRuntime();
        runtime.loadFailure = new UnsatisfiedLinkError("Missing Steam DLL");
        assertFalse(runtime.init());
        assertFalse(runtime.isInitialized());
        assertEquals(1, runtime.cleanups);
        assertEquals(0, runtime.initCalls);
        runtime.loadFailure = null;
        assertFalse(runtime.reinit());
        assertEquals(2, runtime.cleanups);
        assertEquals(1, runtime.initCalls);
    }

    @Test
    public void failedNativeLoadIsCleanedWithoutInitializingSteam() {
        StartupRuntime runtime = new StartupRuntime();
        runtime.loaded = false;
        assertFalse(runtime.init());
        assertEquals(1, runtime.cleanups);
        assertEquals(0, runtime.initCalls);
    }

    @Test
    public void initializationAndCleanupExceptionsLeaveRuntimeStopped() {
        StartupRuntime runtime = new StartupRuntime();
        runtime.initFailure = new IllegalStateException("Steam is not running");
        runtime.cleanupFailure = true;
        assertFalse(runtime.init());
        assertFalse(runtime.isInitialized());
        assertFalse(runtime.running.get());
        assertEquals(1, runtime.cleanups);
        assertEquals(1, runtime.initCalls);
    }

    private static final class StartupRuntime extends FakeRuntime {
        boolean loaded = true, cleanupFailure;
        LinkageError loadFailure;
        RuntimeException initFailure;
        int cleanups, initCalls;

        protected boolean voiceSupported() {
            return false;
        }

        protected boolean loadNativeLibraries() {
            if (loadFailure != null) throw loadFailure;
            return loaded;
        }

        protected boolean initNativeApi() {
            initCalls++;
            if (initFailure != null) throw initFailure;
            return false;
        }

        protected void shutdownNativeApi() {
            cleanups++;
            if (cleanupFailure) throw new IllegalStateException("Cleanup failed");
        }
    }

    private static class FakeRuntime extends SteamRuntime<SteamGameClient, SteamGameServer> {
        int listen;
        final List<Integer> listenersClosed = new ArrayList<>();

        protected void clearProfiles() {}

        protected void invalidateProfile(long id, boolean avatar) {}

        public void handleSteamShutdown() {}

        public int createListenSocketP2P(int port) {
            return ++listen;
        }

        public boolean closeListenSocket(int handle) {
            listenersClosed.add(handle);
            return true;
        }

        public boolean acceptConnection(int handle) {
            return true;
        }

        public void requestUserInformation(long id) {}
    }

    private static final class FakeServer extends SteamServerBase {
        final FakeRuntime runtime;
        final SteamBanStore bans;

        FakeServer(FakeRuntime runtime, File file) {
            super(AccessPolicy.EVERYONE, "world");
            this.runtime = runtime;
            bans =
                    new SteamBanStore() {
                        protected File resolveStorageFile() {
                            return file;
                        }
                    };
        }

        protected SteamRuntime<?, ?> runtime() {
            return runtime;
        }

        protected SteamBanStore bans() {
            return bans;
        }

        protected String displayName(long id) {
            return "Player" + id;
        }

        protected boolean voiceSupported() {
            return false;
        }

        protected void executeOnClient(Runnable task) {
            task.run();
        }

        protected boolean integratedServerAvailable() {
            return true;
        }

        protected boolean createLoopbackBridge(int handle, long id, int port, IntConsumer bound) {
            return true;
        }
    }

    private static final class FakeClient extends SteamClientBase<Object> {
        final FakeRuntime runtime;

        FakeClient(FakeRuntime runtime) {
            this.runtime = runtime;
        }

        void seed(int handle) {
            connectionHandle = handle;
            alive.set(true);
            state = State.CONNECTING;
            runtime.setActiveClient(this);
        }

        void ready() {
            markSteamReady();
        }

        protected SteamRuntime<?, ?> runtime() {
            return runtime;
        }

        protected void activateLoopback(int handle, long id, Object screen) {}

        protected void showFailure(String msg) {}

        protected String i18n(String key, String fallback) {
            return fallback;
        }
    }
}
