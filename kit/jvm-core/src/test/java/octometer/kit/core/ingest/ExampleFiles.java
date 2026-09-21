package octometer.kit.core.ingest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A test helper that finds a file of the contract examples (issue #8:
 * `contract/examples/`). It walks up from the current folder, because a
 * Gradle test task can run with a different working folder.
 */
final class ExampleFiles {

    private ExampleFiles() {
    }

    static String read(String name) {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve("contract").resolve("examples").resolve(name);
            if (Files.exists(candidate)) {
                try {
                    return Files.readString(candidate);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("The contract examples folder is absent for the file " + name + ".");
    }
}
