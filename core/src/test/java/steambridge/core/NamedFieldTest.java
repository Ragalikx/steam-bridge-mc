package steambridge.core;

import static org.junit.Assert.*;

import org.junit.Test;

public class NamedFieldTest {
    private static class Screen {
        private boolean otherFlag;
        private boolean field_123;
        private String address = "host";
    }

    private static final class CustomScreen extends Screen {
        private String address = "custom";
    }

    @Test
    public void runtimeAliasChangesOnlyTheRequestedFlag() {
        Screen screen = new Screen();
        NamedField<Boolean> aborted =
                new NamedField<>(Screen.class, Boolean.class, "aborted", "field_123");
        aborted.set(screen, true);
        assertTrue(aborted.get(screen));
        assertFalse(screen.otherFlag);
    }

    @Test
    public void subclassShadowDoesNotReplaceVanillaField() {
        CustomScreen screen = new CustomScreen();
        NamedField<String> address = new NamedField<>(Screen.class, String.class, "address");
        assertEquals("host", address.get(screen));
        address.set(screen, "steam");
        assertEquals("steam", address.get(screen));
        assertEquals("custom", screen.address);
    }

    @Test
    public void wrongTypeAndMissingNameNeverFallBackToAnotherField() {
        Screen screen = new Screen();
        assertThrows(
                IllegalStateException.class,
                () -> new NamedField<>(Screen.class, String.class, "missing").get(screen));
        assertThrows(
                IllegalStateException.class,
                () ->
                        new NamedField<>(Screen.class, Boolean.class, "address", "field_123")
                                .get(screen));
    }
}
