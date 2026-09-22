package octometer.kit.core.path;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A test helper that finds a file of the contract examples
 * (`contract/examples/`). It walks up from the current folder, because a
 * Gradle test task can run with a different working folder. This class
 * copies the small lookup of {@code octometer.kit.core.ingest.ExampleFiles}
 * for the `path` test package, so the two packages stay independent.
 */
final class ContractExampleFile {

    private ContractExampleFile() {
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
