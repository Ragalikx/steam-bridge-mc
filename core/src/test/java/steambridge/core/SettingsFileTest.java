package steambridge.core;

import static org.junit.Assert.*;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class SettingsFileTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void forgeCfgMigratesWithoutChangingOriginal() throws Exception {
        Path directory = folder.getRoot().toPath();
        String original =
                "general {\n"
                    + " I:\"Virtual Port\"=7\n"
                    + " B:\"Allow Without Auth\"=false\n"
                    + " S:\"Stun Servers\"=stun:example.test:3478\n"
                    + " I:\"Timeout Initial Seconds\"=45\n"
                    + "}";
        Path legacy = directory.resolve("steambridge.cfg");
        Files.write(legacy, original.getBytes(StandardCharsets.UTF_8));
        JsonSettings settings = SettingsFile.load(directory, JsonSettings.defaults());
        assertEquals(7, settings.virtualPort);
        assertFalse(settings.allowWithoutAuth);
        assertEquals(45, settings.timeoutInitialSec);
        assertEquals(original, new String(Files.readAllBytes(legacy), StandardCharsets.UTF_8));
        assertTrue(Files.exists(directory.resolve("steambridge.json")));
    }

    @Test
    public void jsonWinsOverTomlAndInvalidReloadDoesNotOverwriteIt() throws Exception {
        Path directory = folder.getRoot().toPath();
        Files.write(
                directory.resolve("steambridge-client.toml"),
                "virtualPort = 9 # custom\nsameNameAsHost = \"kick\""
                        .getBytes(StandardCharsets.UTF_8));
        JsonSettings migrated = SettingsFile.load(directory, JsonSettings.defaults());
        assertEquals(9, migrated.virtualPort);
        assertEquals("kick", migrated.sameNameAsHost);
        Path json = directory.resolve("steambridge.json");
        Files.write(json, "{\"virtualPort\":4}".getBytes(StandardCharsets.UTF_8));
        assertEquals(4, SettingsFile.load(directory, migrated).virtualPort);
        String invalid = "{\"virtualPort\":65535}";
        Files.write(json, invalid.getBytes(StandardCharsets.UTF_8));
        try {
            SettingsFile.load(directory, migrated);
            fail("Invalid settings accepted");
        } catch (IllegalArgumentException expected) {
        }
        assertEquals(invalid, new String(Files.readAllBytes(json), StandardCharsets.UTF_8));
        assertEquals(9, migrated.virtualPort);
    }
}
