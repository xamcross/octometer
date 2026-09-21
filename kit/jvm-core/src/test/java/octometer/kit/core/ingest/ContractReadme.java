package octometer.kit.core.ingest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A test helper that reads `contract/README.md` (issue #8). It walks up
 * from the current folder, because a Gradle test task can run with a
 * different working folder. {@link ExampleFiles} reads one example file
 * of the contract; this class reads the contract document itself.
 */
final class ContractReadme {

    private ContractReadme() {
    }

    static String read() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve("contract").resolve("README.md");
            if (Files.exists(candidate)) {
                try {
                    return Files.readString(candidate);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("The file contract/README.md is absent.");
    }
}
