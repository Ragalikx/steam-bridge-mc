package steambridge.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SettingsFile {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Pattern LEGACY =
            Pattern.compile("^(?:[BIS]:)?\\s*\"?([A-Za-z][A-Za-z0-9 ]*)\"?\\s*=\\s*(.*?)\\s*$");

    private SettingsFile() {}

    public static JsonSettings load(Path directory, JsonSettings current) throws IOException {
        Path json = directory.resolve("steambridge.json");
        if (Files.exists(json)) return JsonSettings.parse(parse(read(json)), current);
        JsonSettings migrated = current;
        for (String name :
                new String[] {
                    "steambridge-client.toml", "steambridge-common.toml", "steambridge.cfg"
                }) {
            Path legacy = directory.resolve(name);
            if (Files.isRegularFile(legacy)) {
                migrated = JsonSettings.parse(legacy(read(legacy)), current);
                break;
            }
        }
        save(json, migrated);
        return migrated;
    }

    private static String read(Path file) throws IOException {
        if (Files.size(file) > 1024 * 1024) throw new IOException("Configuration exceeds 1 MiB");
        byte[] bytes = Files.readAllBytes(file);
        if (bytes.length > 1024 * 1024) throw new IOException("Configuration exceeds 1 MiB");
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static JsonObject parse(String text) {
        JsonElement value = new JsonParser().parse(text);
        if (value == null || !value.isJsonObject())
            throw new IllegalArgumentException("Configuration must be an object");
        return value.getAsJsonObject();
    }

    static JsonObject legacy(String text) {
        JsonObject result = new JsonObject();
        for (String line : text.split("\\r?\\n")) {
            Matcher matcher = LEGACY.matcher(line.trim());
            if (!matcher.matches()) continue;
            String normalized = matcher.group(1).replace(" ", "").toLowerCase(Locale.ROOT);
            String key = null;
            for (String candidate :
                    new String[] {
                        "allowWithoutAuth",
                        "virtualPort",
                        "interceptUdp",
                        "disableOnlineModeOnPublish",
                        "sameNameAsHost",
                        "timeoutInitialSec",
                        "timeoutConnectedSec",
                        "stunServers"
                    }) {
                if (normalized.equals(candidate.toLowerCase(Locale.ROOT))) key = candidate;
            }
            if (normalized.equals("timeoutinitialseconds")) key = "timeoutInitialSec";
            if (normalized.equals("timeoutconnectedseconds")) key = "timeoutConnectedSec";
            if (key == null) continue;
            String raw = matcher.group(2);
            if (key.equals("sameNameAsHost") || key.equals("stunServers")) {
                if (raw.startsWith("\"")) result.add(key, new JsonParser().parse(raw));
                else result.addProperty(key, raw);
            } else {
                int comment = raw.indexOf('#');
                if (comment >= 0) raw = raw.substring(0, comment).trim();
                result.add(key, new JsonParser().parse(raw));
            }
        }
        return result;
    }

    public static void save(Path path, JsonSettings settings) throws IOException {
        Files.createDirectories(path.toAbsolutePath().getParent());
        Path temporary =
                Files.createTempFile(
                        path.toAbsolutePath().getParent(), "steambridge-", ".json.tmp");
        try {
            Files.write(temporary, GSON.toJson(settings.toJson()).getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(
                        temporary,
                        path,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
