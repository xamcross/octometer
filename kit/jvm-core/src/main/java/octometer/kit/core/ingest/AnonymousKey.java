package octometer.kit.core.ingest;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The anonymous key of design decision D43 and issue #117. One IPv4
 * address stays as it is. One IPv6 address becomes its first 64 bits,
 * the first four groups after a canonical expansion of its `::` short
 * form. Issue #116 reuses this class for its per-minute counters. This
 * class never changes the key form of {@link IngestRateLimiter}, which
 * keeps the plain address text of design decision D20.
 *
 * <p>This class makes no network call and resolves no name. A text with
 * no dot and no colon, or a malformed IPv6 text, becomes its own key
 * unchanged; {@link #of} then never throws for a client value it cannot
 * parse as an address.
 */
public final class AnonymousKey {

    private static final Pattern IPV4_ADDRESS = Pattern.compile(
            "^(25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])"
                    + "(\\.(25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])){3}$");

    /** The group count of a full IPv6 address. */
    private static final int GROUP_COUNT = 8;

    /** The group count of the first 64 bits of an IPv6 address. */
    private static final int FIRST_64_BITS_GROUP_COUNT = 4;

    private AnonymousKey() {
    }

    /**
     * Returns the anonymous key of {@code address} (design decision
     * D43). It returns {@code address} as it is when it has the text
     * form of an IPv4 address. It returns the first
     * {@value #FIRST_64_BITS_GROUP_COUNT} groups of a canonical IPv6
     * expansion, joined with a colon, when it has the text form of an
     * IPv6 address. It returns {@code address} as it is for each other
     * text.
     */
    public static String of(String address) {
        Objects.requireNonNull(address, "address must not be null");
        if (IPV4_ADDRESS.matcher(address).matches()) {
            return address;
        }
        String[] first64Bits = first64BitsOfIpv6(address);
        if (first64Bits != null) {
            return String.join(":", first64Bits);
        }
        return address;
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
     * Returns the lowercase hex text of one IPv6 group, with no leading
     * zero. Returns {@code null} for a group with no hex digit, above 4
     * hex digits, or with a character that is not a hex digit; an
     * embedded IPv4 tail (a group with a dot) falls in this last case.
     */
    private static String normalizeGroup(String group) {
        if (group.isEmpty() || group.length() > 4) {
            return null;
        }
        try {
            return Integer.toHexString(Integer.parseInt(group, 16));
        } catch (NumberFormatException cause) {
            return null;
        }
    }
}
