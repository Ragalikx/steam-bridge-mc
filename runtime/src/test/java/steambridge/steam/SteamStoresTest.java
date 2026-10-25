package steambridge.steam;

import static org.junit.Assert.*;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;

public class SteamStoresTest {
    @Rule public TemporaryFolder files = new TemporaryFolder();

    private SteamWorldStore worlds(File file) {
        return new SteamWorldStore() {
            protected File resolveFile() {
                return file;
            }
        };
    }

    private SteamBanStore bans(File file) {
        return new SteamBanStore() {
            protected File resolveStorageFile() {
                return file;
            }
        };
    }

    @Test
    public void invalidWorldFileIsPreservedAfterEditingSettings() throws Exception {
        for (String text : new String[] {"{", "null", "{\"worlds\":null}"}) {
            File file = files.newFile();
            byte[] original = text.getBytes(StandardCharsets.UTF_8);
            Files.write(file.toPath(), original);
            SteamWorldStore store = worlds(file);
            assertEquals("SURVIVAL", store.load("world").gametype);
            store.save(
                    "world",
                    SteamServerBase.AccessPolicy.EVERYONE,
                    SteamServerBase.TransportMode.AUTO);
            assertArrayEquals(original, Files.readAllBytes(file.toPath()));
        }
    }

    @Test
    public void invalidBanFileIsPreservedAfterBanAndUnban() throws Exception {
        for (String text : new String[] {"{", "null", "{\"worlds\":null}"}) {
            File file = files.newFile();
            byte[] original = text.getBytes(StandardCharsets.UTF_8);
            Files.write(file.toPath(), original);
            SteamBanStore store = bans(file);
            store.ban("world", 123, "Steam name", "Minecraft name");
            assertTrue(store.isBanned("world", 123));
            assertTrue(store.unban("world", 123));
            assertArrayEquals(original, Files.readAllBytes(file.toPath()));
        }
    }

    @Test
    public void storesRoundTripAndWorldKeysDoNotDependOnLocale() throws Exception {
        File banFile = new File(files.getRoot(), "bans.json");
        bans(banFile).ban("world", 123, "Name", "Player");
        assertEquals("Player", bans(banFile).getBans("world").get(0).getMinecraftName());
        assertTrue(bans(banFile).unban("world", 123));
        assertFalse(bans(banFile).isBanned("world", 123));
        File worldFile = new File(files.getRoot(), "worlds.json");
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(new Locale("tr", "TR"));
            SteamWorldStore store = worlds(worldFile);
            store.save(
                    "ISLAND",
                    SteamServerBase.AccessPolicy.EVERYONE,
                    SteamServerBase.TransportMode.P2P_ONLY);
            assertEquals("P2P_ONLY", worlds(worldFile).load("island").transportMode);
            String saved =
                    new String(Files.readAllBytes(worldFile.toPath()), StandardCharsets.UTF_8);
            assertTrue(saved.contains("\"island\""));
            assertFalse(saved.contains("\u0131sland"));
        } finally {
            Locale.setDefault(previous);
        }
    }
}
