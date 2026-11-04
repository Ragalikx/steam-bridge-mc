package steambridge.core;

import static org.junit.Assert.*;

import com.google.gson.JsonParser;

import org.junit.Test;

public class JsonSettingsTest {
    private final JsonSettings current = new JsonSettings(true, 0, true, true, "allow", 30, 60, "");

    @Test
    public void iceStaysEnabledUnlessTurnedOff() {
        assertTrue(current.iceEnabled);
        assertTrue(JsonSettings.defaults().toJson().get("iceEnabled").getAsBoolean());
        JsonSettings relayOnly =
                JsonSettings.parse(
                        new JsonParser().parse("{\"iceEnabled\":false}").getAsJsonObject(), current);
        assertFalse(relayOnly.iceEnabled);
        assertFalse(relayOnly.toJson().get("iceEnabled").getAsBoolean());
        assertEquals(current.virtualPort, relayOnly.virtualPort);
    }

    @Test
    public void readsOldMillisecondSettings() {
        JsonSettings settings =
                JsonSettings.parse(
                        new JsonParser()
                                .parse("{\"timeoutInitialMs\":45000,\"virtualPort\":12}")
                                .getAsJsonObject(),
                        current);
        assertEquals(45, settings.timeoutInitialSec);
        assertEquals(12, settings.virtualPort);
        assertEquals(60, settings.timeoutConnectedSec);
    }

    @Test
    public void malformedSettingsLeavePreviousSnapshotIntact() {
        for (String value :
                new String[] {
                    "{\"virtualPort\":2147483648}",
                    "{\"virtualPort\":65535}",
                    "{\"virtualPort\":1.5}",
                    "{\"interceptUdp\":\"true\"}",
                    "{\"timeoutConnectedSec\":null}"
                }) {
            try {
                JsonSettings.parse(new JsonParser().parse(value).getAsJsonObject(), current);
                fail(value);
            } catch (RuntimeException expected) {
            }
            assertEquals(0, current.virtualPort);
            assertEquals(60, current.timeoutConnectedSec);
        }
    }
}
