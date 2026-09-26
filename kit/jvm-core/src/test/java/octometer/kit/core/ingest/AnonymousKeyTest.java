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

    // The tests below cover the fix of Java review MAJOR 2 and security
    // review M1 (an embedded IPv4 tail and an IPv4-mapped address), and
    // Java review MINOR 3 (a sign in one hex group).

    @Test
    void anIpv4MappedAddressGivesThePlainIpv4TextAsTheKey() {
        assertEquals("203.0.113.9", AnonymousKey.of("::ffff:203.0.113.9"));
    }

    @Test
    void anIpv4MappedAddressAndItsPlainIpv4FormShareOneKey() {
        String plainKey = AnonymousKey.of("203.0.113.9");
        String mappedKey = AnonymousKey.of("::ffff:203.0.113.9");

        assertEquals(plainKey, mappedKey);
    }

    @Test
    void anUpperCaseIpv4MappedPrefixGivesTheSameKeyAsTheLowerCaseForm() {
        String upperKey = AnonymousKey.of("::FFFF:203.0.113.9");
        String lowerKey = AnonymousKey.of("::ffff:203.0.113.9");

        assertEquals(upperKey, lowerKey);
    }

    @Test
    void anEmbeddedIpv4TailExpandsToTwoHexGroupsBeforeThe64BitCut() {
        String keyOne = AnonymousKey.of("2001:db8::1.2.3.4");
        String keyTwo = AnonymousKey.of("2001:db8::1.2.3.5");

        assertEquals(keyOne, keyTwo);
    }

    @Test
    void anEmbeddedIpv4TailNeverHoldsTheKeyOfADifferent64Prefix() {
        String keyOne = AnonymousKey.of("2001:db8:aaaa::1.2.3.4");
        String keyTwo = AnonymousKey.of("2001:db8:bbbb::1.2.3.4");

        assertNotEquals(keyOne, keyTwo);
    }

    @Test
    void aTextThatIsNeitherAnIpv4NorAnIpv6FormBecomesItsOwnKey() {
        assertEquals("not-an-address", AnonymousKey.of("not-an-address"));
    }

    @Test
    void anUpperCaseIpv6GroupGivesTheSameKeyAsItsLowerCaseForm() {
        String upperKey = AnonymousKey.of("FE80::1");
        String lowerKey = AnonymousKey.of("fe80::1");

        assertEquals(upperKey, lowerKey);
    }

    @Test
    void aLeadingPlusSignInOneGroupGivesADifferentKeyFromTheSameGroupWithNoSign() {
        String keyWithSign = AnonymousKey.of("+1::1");
        String keyWithNoSign = AnonymousKey.of("1::1");

        assertNotEquals(keyWithSign, keyWithNoSign);
    }

    // The two tests below cover the two open notes of the review of pull
    // request #172 (issue #116).

    @Test
    void aMappedPrefixWithAZeroGroupAlsoGivesThePlainIpv4TextAsTheKey() {
        // The Java review of pull request #172 named this gap as
        // finding M10: IPV4_MAPPED_PREFIX matched only "::ffff:", so
        // "::ffff:0:a.b.c.d" fell through to the all-zero IPv6 key.
        assertEquals("1.2.3.4", AnonymousKey.of("::ffff:0:1.2.3.4"));
        assertEquals("5.6.7.8", AnonymousKey.of("::ffff:0000:5.6.7.8"));
    }

    @Test
    void aMappedPrefixWithAZeroGroupSharesOneKeyWithThePlainMappedForm() {
        String plainMappedKey = AnonymousKey.of("::ffff:1.2.3.4");
        String zeroGroupMappedKey = AnonymousKey.of("::ffff:0:1.2.3.4");

        assertEquals(plainMappedKey, zeroGroupMappedKey);
    }

    @Test
    void anAddressWithNoZeroGroupStillGivesTheAllZeroIpv6KeyWhenItIsNotMapped() {
        // "::1" holds no embedded IPv4 tail, so it never reaches the
        // mapped-prefix branch; it stays the ordinary all-zero /64
        // prefix key.
        assertEquals("0:0:0:0", AnonymousKey.of("::1"));
    }

    @Test
    void aFullWidthDigitInOneGroupIsNotAnAsciiHexDigitAndTheAddressBecomesItsOwnOpaqueForm() {
        // The Java review of pull request #172 found that
        // normalizeGroup called Character.digit(c, 16), which accepts a
        // Unicode decimal digit, not only an ASCII hex digit. A
        // full-width digit ("１", the fullwidth form of "1") must
        // now break the parse, so the whole address falls back to its
        // own opaque text.
        String fullWidthDigitAddress = "１::1";

        assertEquals(fullWidthDigitAddress, AnonymousKey.of(fullWidthDigitAddress));
    }
}
