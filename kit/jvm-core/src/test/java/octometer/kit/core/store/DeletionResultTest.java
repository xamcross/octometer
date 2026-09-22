package octometer.kit.core.store;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests of {@link DeletionResult} (issue #35).
 */
class DeletionResultTest {

    @Test
    void totalCountIsTheSumOfTheTwoCounts() {
        DeletionResult result = new DeletionResult(3, 2, true);

        assertEquals(5, result.totalCount());
    }

    @Test
    void aZeroCountOnBothFieldsIsValid() {
        DeletionResult result = new DeletionResult(0, 0, true);

        assertEquals(0, result.totalCount());
    }

    @Test
    void aNegativeUserEventCountIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new DeletionResult(-1, 0, true));
    }

    @Test
    void aNegativeAnonymousEventCountIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new DeletionResult(0, -1, true));
    }

    @Test
    void theCompleteFlagKeepsItsGivenValue() {
        assertEquals(true, new DeletionResult(0, 0, true).complete());
        assertEquals(false, new DeletionResult(0, 0, false).complete());
    }
}
