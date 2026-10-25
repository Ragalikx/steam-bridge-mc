package steambridge.core;

import static org.junit.Assert.*;

import org.junit.*;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;

public class DiagnosticReportTest {
    @Rule public TemporaryFolder files = new TemporaryFolder();

    @Test
    public void reportIsReplacedWithoutLeavingTemporaryFiles() throws Exception {
        Path root = files.getRoot().toPath();
        Path first = DiagnosticReport.write(root, "first\n");
        assertEquals(root.resolve("logs/steambridge-diagnostics.txt"), first);
        assertEquals(first, DiagnosticReport.write(root, "second\n"));
        assertEquals("second\n", new String(Files.readAllBytes(first), StandardCharsets.UTF_8));
        try (java.util.stream.Stream<Path> paths = Files.list(first.getParent())) {
            assertEquals(1, paths.count());
        }
    }

    @Test
    public void oversizedReportDoesNotDestroyPreviousFile() throws Exception {
        Path root = files.getRoot().toPath();
        Path report = DiagnosticReport.write(root, "previous");
        char[] oversized = new char[65537];
        java.util.Arrays.fill(oversized, 'x');
        assertThrows(
                java.io.IOException.class,
                () -> DiagnosticReport.write(root, new String(oversized)));
        assertEquals("previous", new String(Files.readAllBytes(report), StandardCharsets.UTF_8));
    }
}
