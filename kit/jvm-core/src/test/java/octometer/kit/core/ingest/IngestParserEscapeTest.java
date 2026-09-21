package octometer.kit.core.ingest;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests of the backslash-u escape of a JSON string. RFC 8259 allows four
 * hex digits only: `0` to `9`, `a` to `f`, and `A` to `F`. Each test
 * below sends one form that this rule excludes.
 *
 * <p>A raw backslash directly followed by the letter u is itself an
 * illegal unicode escape inside Java source, thus each attack string
 * below joins the backslash and the letter u from two literals.
 */
class IngestParserEscapeTest {

    @Test
    void rejectsAuEscapeWithAPlusSign() {
        String body = "{\"sessionId\": \"3fa85f64-5717-4562-b3fc-2c963f66afa6\", \"clicks\": "
                + "[{\"element\": \"\\" + "u+041\", \"ageMs\": 100}]}";

        IngestException error = assertThrows(IngestException.class, () -> IngestParser.parse(body));

        assertEquals(IngestException.Reason.INVALID_JSON, error.reason());
    }

    @Test
    void rejectsAuEscapeWithAMinusSign() {
        String body = "{\"sessionId\": \"3fa85f64-5717-4562-b3fc-2c963f66afa6\", \"clicks\": "
                + "[{\"element\": \"\\" + "u-041\", \"ageMs\": 100}]}";

        IngestException error = assertThrows(IngestException.class, () -> IngestParser.parse(body));

        assertEquals(IngestException.Reason.INVALID_JSON, error.reason());
    }

    @Test
    void rejectsAuEscapeWithANonAsciiDigit() {
        // The four chars below are Arabic-Indic digits (U+0660, U+0660,
        // U+0664, U+0661), not the ASCII digits that rule C13 needs.
        String arabicIndicDigits = "٠٠٤١";
        String body = "{\"sessionId\": \"3fa85f64-5717-4562-b3fc-2c963f66afa6\", \"clicks\": "
                + "[{\"element\": \"\\" + "u" + arabicIndicDigits + "\", \"ageMs\": 100}]}";

        IngestException error = assertThrows(IngestException.class, () -> IngestParser.parse(body));

        assertEquals(IngestException.Reason.INVALID_JSON, error.reason());
    }
}
