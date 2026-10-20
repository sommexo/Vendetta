package dev.prc.vendetta;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SafeYamlTest {
    @TempDir Path tempDir;

    @Test void emptyYamlDocumentLoadsAsEmptyState() throws Exception {
        Path file = Files.createFile(tempDir.resolve("pending-marks.yml"));

        assertEquals(0, SafeYaml.load(file.toFile()).getKeys(false).size());
    }

    @Test void malformedNonemptyYamlIsNotSilentlyDiscarded() throws Exception {
        Path file = tempDir.resolve("balances.yml");
        Files.writeString(file, "broken: [unclosed\n");

        assertThrows(IllegalStateException.class, () -> SafeYaml.load(file.toFile()));
    }
}
