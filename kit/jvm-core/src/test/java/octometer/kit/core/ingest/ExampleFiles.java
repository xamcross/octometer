package octometer.kit.core.ingest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    /**
     * Reads one string field out of a flat JSON text. This method is a
     * small helper for a test. It does not parse the whole document.
     */
    static String extractStringField(String json, String field) {
        Pattern pattern = Pattern.compile("\"" + Pattern.quote(field) + "\"\\s*:\\s*\"([^\"]*)\"");
        Matcher matcher = pattern.matcher(json);
        if (!matcher.find()) {
            throw new IllegalStateException("The field " + field + " is absent or is not a string.");
        }
        return matcher.group(1);
    }

    /** Checks that one field of a flat JSON text holds the value `null`. */
    static boolean hasNullField(String json, String field) {
        Pattern pattern = Pattern.compile("\"" + Pattern.quote(field) + "\"\\s*:\\s*null");
        return pattern.matcher(json).find();
    }
}
