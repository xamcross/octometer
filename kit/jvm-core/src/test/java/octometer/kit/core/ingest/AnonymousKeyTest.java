package octometer.kit.core.ingest;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Tests of {@link AnonymousKey} against design decision D43 and issue
 * #117. An IPv4 address stays as it is. An IPv6 address becomes its
 * first 64 bits, the first four groups after a canonical expansion.
 */
class AnonymousKeyTest {

    @Test
    void anIpv4AddressStaysAsItIs() {
        assertEquals("203.0.113.9", AnonymousKey.of("203.0.113.9"));
    }

    @Test
    void twoIpv6AddressesOfTheSame64PrefixGiveTheSameKey() {
        String keyOne = AnonymousKey.of("2001:db8:85a3::8a2e:370:7334");
        String keyTwo = AnonymousKey.of("2001:db8:85a3::1");

        assertEquals(keyOne, keyTwo);
    }

    @Test
    void twoIpv6AddressesOfADifferent64PrefixGiveADifferentKey() {
        String keyOne = AnonymousKey.of("2001:db8:85a3::8a2e:370:7334");
        String keyTwo = AnonymousKey.of("2001:db8:85a4::8a2e:370:7334");

        assertNotEquals(keyOne, keyTwo);
    }

    @Test
    void aFullyWrittenIpv6AddressGivesTheSameKeyAsItsShortForm() {
        String fullForm = AnonymousKey.of("2001:0db8:0000:0000:0000:0000:0000:0001");
        String shortForm = AnonymousKey.of("2001:db8::1");

        assertEquals(fullForm, shortForm);
    }

    @Test
    void theIpv6KeyNeverHoldsTheFullAddressText() {
        String key = AnonymousKey.of("2001:db8:85a3::8a2e:370:7334");

        assertNotEquals("2001:db8:85a3::8a2e:370:7334", key);
    }

    @Test
    void aDoubleColonAtTheStartExpandsToZeroGroups() {
        String keyOne = AnonymousKey.of("::1");
        String keyTwo = AnonymousKey.of("0:0:0:0:0:0:0:1");

        assertEquals(keyOne, keyTwo);
    }
}
