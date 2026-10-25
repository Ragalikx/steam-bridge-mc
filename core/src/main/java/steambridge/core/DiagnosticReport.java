package steambridge.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

public final class DiagnosticReport {
    private DiagnosticReport() {}

    public static Path write(Path directory, String report) throws IOException {
        byte[] content = report.getBytes(StandardCharsets.UTF_8);
        if (content.length > 64 * 1024) throw new IOException("Diagnostic report is too large");
        Path logs = directory.resolve("logs");
        Files.createDirectories(logs);
        Path target = logs.resolve("steambridge-diagnostics.txt");
        Path temporary = Files.createTempFile(logs, "steambridge-diagnostics-", ".tmp");
        try {
            Files.write(temporary, content);
            try {
                Files.move(
                        temporary,
                        target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return target;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
