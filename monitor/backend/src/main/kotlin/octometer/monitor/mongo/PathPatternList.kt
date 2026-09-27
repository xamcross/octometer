package octometer.monitor.mongo

import octometer.kit.core.path.PathPatternMatcher

/** The pattern count cap of the route pattern list (issue #226). */
internal const val MAX_PATTERN_COUNT = 200

/** The byte cap of one pattern, the same limit as [PATH_MAX_BYTES] (contract rule C39). */
internal const val MAX_PATTERN_BYTES = PATH_MAX_BYTES

/** The character cap of the whole input text, before the split (issue #226). */
internal const val MAX_PATTERNS_TEXT_LENGTH = 32_000

private val WHITESPACE = Regex("\\s+")

/**
 * The result of [PathPatternList.parse]: the normalized text of
 * [Accepted], or the fixed reason of [Rejected]. A [Rejected] reason
 * names the index of the first bad pattern and the rule only, never
 * the pattern text (issue #226, decision 2 of the brief).
 */
internal sealed class PathPatternListResult {
    data class Accepted(val normalizedText: String) : PathPatternListResult()
    data class Rejected(val message: String) : PathPatternListResult()
}

/**
 * The validation of the route pattern list of one app (issue #226, the
 * D5 rule of 2026-09-27, the correction of the brief review of
 * 2026-09-27). [parse] takes a text of the same form as
 * `OCTOMETER_PATH_PATTERNS`: patterns with whitespace between them.
 *
 * This object owns three input bounds only, the monitor's own caps: the
 * character length of the whole text ([MAX_PATTERNS_TEXT_LENGTH]), the
 * count of patterns ([MAX_PATTERN_COUNT]), and the byte length of one
 * pattern ([MAX_PATTERN_BYTES]). It takes the pattern grammar itself
 * from [PathPatternMatcher.findInvalidIndices], the kit's own check of
 * contract rule C42, so the monitor keeps no copy of that grammar
 * (design decision D17).
 *
 * A caller passes a text that is already known to be non-blank; the
 * caller (`AppRegistryService`) treats a whitespace-only text as the
 * clear of the field, before it ever reaches this method. The rule
 * "rejects an empty list" then covers a non-blank text with zero
 * patterns after the split; a plain non-blank text always splits into
 * one pattern or more, so this case stays as a defence for a direct
 * caller of this method, such as a test.
 */
internal object PathPatternList {

    fun parse(text: String): PathPatternListResult {
        if (text.length > MAX_PATTERNS_TEXT_LENGTH) {
            return PathPatternListResult.Rejected("The route pattern list is too long.")
        }
        val patterns = text.trim().split(WHITESPACE).filter { it.isNotEmpty() }
        if (patterns.isEmpty()) {
            return PathPatternListResult.Rejected("Give at least one route pattern.")
        }
        if (patterns.size > MAX_PATTERN_COUNT) {
            return PathPatternListResult.Rejected("Give $MAX_PATTERN_COUNT route patterns or fewer.")
        }

        val tooLongIndex = patterns.indices.firstOrNull { index ->
            patterns[index].toByteArray(Charsets.UTF_8).size > MAX_PATTERN_BYTES
        }
        val badGrammarIndex = PathPatternMatcher.findInvalidIndices(patterns).firstOrNull()
        val firstBadIndex = listOfNotNull(tooLongIndex, badGrammarIndex).minOrNull()
        if (firstBadIndex != null) {
            val rule = if (firstBadIndex == tooLongIndex) "the byte length cap" else "the route pattern grammar"
            return PathPatternListResult.Rejected("The route pattern at index $firstBadIndex breaks $rule.")
        }

        return PathPatternListResult.Accepted(patterns.joinToString("\n"))
    }
}
