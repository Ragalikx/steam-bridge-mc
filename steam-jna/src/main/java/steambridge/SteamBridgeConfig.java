package steambridge;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import steambridge.core.JsonSettings;
import steambridge.core.LogText;
import steambridge.core.SettingsFile;

import java.nio.file.Path;

public final class SteamBridgeConfig {
    private static final Logger LOG = LogManager.getLogger("steambridge");
    private static volatile JsonSettings settings = JsonSettings.defaults();
    private static volatile Path configDirectory;

    private SteamBridgeConfig() {}

    public static JsonSettings settings() {
        return settings;
    }

    public static boolean kickOnSameNameAsHost() {
        return "kick".equals(settings.sameNameAsHost);
    }

    public static synchronized void load(Path directory) {
        configDirectory = directory.toAbsolutePath().normalize();
        try {
            settings = SettingsFile.load(directory, settings);
        } catch (Exception failure) {
            LOG.warn(
                    "Steam Bridge configuration unchanged: {}", LogText.safe(failure.getMessage()));
        }
    }

    public static Path gameDirectory() {
        Path config = configDirectory;
        return config == null || config.getParent() == null
                ? java.nio.file.Paths.get(".")
                : config.getParent();
    }
}
