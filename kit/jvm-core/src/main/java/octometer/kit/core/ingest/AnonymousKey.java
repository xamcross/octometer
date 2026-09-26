package octometer.kit.core.ingest;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The anonymous key of design decision D43 and issue #117. One IPv4
 * address stays as it is. One IPv6 address becomes its first 64 bits,
 * the first four groups after a canonical expansion of its `::` short
 * form. An embedded IPv4 tail (for example `2001:db8::1.2.3.4`)
 * expands to two hex groups before that cut. An IPv4-mapped address
 * (`::ffff:a.b.c.d`) gives the plain IPv4 text, so one host gets one
 * key under the two address forms together. Issue #116 reuses this
 * class for its per-minute counters. This class never changes the key
 * form of {@link IngestRateLimiter}, which keeps the plain address
 * text of design decision D20.
 *
 * <p>This class makes no network call and resolves no name. A text
 * with no dot and no colon becomes its own key unchanged. A text with
 * a shape this class cannot parse as an address becomes its own key
 * too. {@link #of} then never throws for such a value.
 */
public final class AnonymousKey {

    private static final Pattern IPV4_ADDRESS = Pattern.compile(
            "^(25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])"
                    + "(\\.(25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])){3}$");

    /**
     * The prefix of an IPv4-mapped IPv6 address, in any letter case. The
     * optional `:0` to `:0000` group covers the second review finding
     * of pull request #172 (M10): a proxy can write the mapped prefix
     * with one extra zero group, for example `::ffff:0:a.b.c.d`. This
     * pattern now matches that form too, the same form that
     * `IPV6_ADDRESS_PATTERN` of `kit/jvm-ktor` already accepts.
     */
    private static final Pattern IPV4_MAPPED_PREFIX = Pattern.compile("^::ffff(:0{1,4})?:", Pattern.CASE_INSENSITIVE);

    /** The group count of a full IPv6 address. */
    private static final int GROUP_COUNT = 8;

    /** The group count of the first 64 bits of an IPv6 address. */
    private static final int FIRST_64_BITS_GROUP_COUNT = 4;

    /**
     * One to four ASCII hex digits, with no other character. The first
     * review of pull request #172 found that {@code normalizeGroup}
     * used {@link Character#digit(char, int)} instead, which also
     * accepts a Unicode decimal digit. This pattern accepts only the
     * digits `0` to `9` and the letters `a` to `f`, in each letter case.
     */
    private static final Pattern ASCII_HEX_GROUP = Pattern.compile("^[0-9a-fA-F]{1,4}$");

    private AnonymousKey() {
    }

    /**
     * Returns the anonymous key of {@code address} (design decision
     * D43). It returns {@code address} as it is when it has the text
     * form of an IPv4 address. It returns the plain IPv4 text when
     * {@code address} is an IPv4-mapped IPv6 address
     * (`::ffff:a.b.c.d`). It returns the first
     * {@value #FIRST_64_BITS_GROUP_COUNT} groups of a canonical IPv6
     * expansion, joined with a colon, for each other IPv6 text. It
     * returns {@code address} as it is for each other text.
     */
    public static String of(String address) {
        Objects.requireNonNull(address, "address must not be null");
        if (IPV4_ADDRESS.matcher(address).matches()) {
            return address;
        }
        String ipv4MappedText = ipv4MappedAddressText(address);
        if (ipv4MappedText != null) {
            return ipv4MappedText;
        }
        String[] first64Bits = first64BitsOfIpv6(address);
        if (first64Bits != null) {
            return String.join(":", first64Bits);
        }
        return address;
    }

    /**
     * Returns the plain IPv4 text of an IPv4-mapped IPv6 address
     * (`::ffff:a.b.c.d`, any letter case). Returns {@code null} for
     * each other text. The first 64 bits of a mapped address are zero
     * for every client. This method returns the IPv4 text instead,
     * because one shared, all-zero key would drop each mapped client
     * together.
     */
    private static String ipv4MappedAddressText(String address) {
        Matcher prefixMatch = IPV4_MAPPED_PREFIX.matcher(address);
        if (!prefixMatch.find() || prefixMatch.start() != 0) {
            return null;
        }
        String tail = address.substring(prefixMatch.end());
        return IPV4_ADDRESS.matcher(tail).matches() ? tail : null;
    }

    /**
     * Expands {@code address} to its {@value #GROUP_COUNT} hex groups,
     * with a `::` run filled with zero groups, then returns the first
     * {@value #FIRST_64_BITS_GROUP_COUNT} of them. Returns {@code null}
     * for a text with no colon, or with a shape this method cannot
     * parse; the caller then treats {@code address} as an opaque key.
     */
    private static String[] first64BitsOfIpv6(String address) {
        if (address.indexOf(':') < 0) {
            return null;
        }
        int doubleColon = address.indexOf("::");
        if (doubleColon >= 0 && address.indexOf("::", doubleColon + 1) >= 0) {
            return null;
        }
        String left = doubleColon >= 0 ? address.substring(0, doubleColon) : address;
        String right = doubleColon >= 0 ? address.substring(doubleColon + 2) : "";
        String[] leftGroups = left.isEmpty() ? new String[0] : left.split(":");
        String[] rightGroups = right.isEmpty() ? new String[0] : right.split(":");

        // An embedded IPv4 tail is always the last group of the whole
        // address. It sits in rightGroups when a "::" run is present,
        // and in leftGroups otherwise. This expansion turns it into
        // two plain hex groups before the group count and the /64 cut
        // below. An address such as 2001:db8::1.2.3.4 and
        // 2001:db8::1.2.3.5 then share one 64-bit prefix (Java review
        // MAJOR 2, security review M1).
        if (rightGroups.length > 0) {
            rightGroups = expandEmbeddedIpv4Tail(rightGroups);
        } else {
            leftGroups = expandEmbeddedIpv4Tail(leftGroups);
        }
        if (leftGroups == null || rightGroups == null) {
            return null;
        }
        int filledGroupCount = leftGroups.length + rightGroups.length;

        if (doubleColon < 0 && filledGroupCount != GROUP_COUNT) {
            return null;
        }
        if (doubleColon >= 0 && filledGroupCount >= GROUP_COUNT) {
            return null;
        }

        String[] allGroups = new String[GROUP_COUNT];
        int index = 0;
        for (String group : leftGroups) {
            String normalized = normalizeGroup(group);
            if (normalized == null) {
                return null;
            }
            allGroups[index++] = normalized;
        }
        int zeroGroupCount = GROUP_COUNT - filledGroupCount;
        for (int i = 0; i < zeroGroupCount; i++) {
            allGroups[index++] = "0";
        }
        for (String group : rightGroups) {
            String normalized = normalizeGroup(group);
            if (normalized == null) {
                return null;
            }
            allGroups[index++] = normalized;
        }

        String[] first64Bits = new String[FIRST_64_BITS_GROUP_COUNT];
        System.arraycopy(allGroups, 0, first64Bits, 0, FIRST_64_BITS_GROUP_COUNT);
        return first64Bits;
    }

    /**
     * Returns {@code groups} unchanged when its last element holds no
     * dot. Otherwise, it treats the last element as an embedded IPv4
     * tail. It splits that tail into two hex groups. Each group holds
     * 16 bits: the high two octets, then the low two octets. It
     * returns {@code null} when the last element holds a dot but has
     * no valid IPv4 text form.
     */
    private static String[] expandEmbeddedIpv4Tail(String[] groups) {
        if (groups.length == 0) {
            return groups;
        }
        String last = groups[groups.length - 1];
        if (last.indexOf('.') < 0) {
            return groups;
        }
        if (!IPV4_ADDRESS.matcher(last).matches()) {
            return null;
        }
        String[] octets = last.split("\\.");
        int highGroup = (Integer.parseInt(octets[0]) << 8) | Integer.parseInt(octets[1]);
        int lowGroup = (Integer.parseInt(octets[2]) << 8) | Integer.parseInt(octets[3]);
        String[] expanded = new String[groups.length + 1];
        System.arraycopy(groups, 0, expanded, 0, groups.length - 1);
        expanded[groups.length - 1] = Integer.toHexString(highGroup);
        expanded[groups.length] = Integer.toHexString(lowGroup);
        return expanded;
    }

    /**
     * Returns the lowercase hex text of one IPv6 group, with no leading
     * zero. Returns {@code null} for a group with no hex digit, above 4
     * hex digits, or with a character that is not an ASCII hex digit.
     * This method checks the whole group against {@link #ASCII_HEX_GROUP}
     * and never calls {@link Character#digit(char, int)}, because that
     * method also accepts a Unicode decimal digit, not only an ASCII
     * hex digit (the first review finding of pull request #172). A
     * leading sign (`+1` or `-1`) thus also gives {@code null}, although
     * {@link Integer#parseInt(String, int)} alone would accept it
     * (Java review MINOR 3).
     */
    private static String normalizeGroup(String group) {
        if (!ASCII_HEX_GROUP.matcher(group).matches()) {
            return null;
        }
        return Integer.toHexString(Integer.parseInt(group, 16));
    }
}
