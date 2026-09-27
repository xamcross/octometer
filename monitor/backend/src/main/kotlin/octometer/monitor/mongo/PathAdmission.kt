package octometer.monitor.mongo

/**
 * The stored value of a `path` that fails the shape check of
 * [PathShape], or that is a new distinct value above `pathValueCap`
 * (issue #205, the D5 rule of 2026-09-27). It is the meaning of
 * contract rule C42: a path outside the known list of the app.
 */
internal const val OTHER_PATH = "/other"

/** The byte limit of `path` (contract rule C39), the same constant as [PATH_MAX_BYTES]. */
private const val MAX_SEGMENT_LENGTH = 80

// The character set of contract rule C39: a literal of the set, or a
// well-formed escape %[0-9A-Fa-f]{2}. The class holds no "%" on its own
// and no "?" or "#", so each of the two also fails this regex on its own.
private val PATH_CHARACTER_PATTERN = Regex("^(?:[A-Za-z0-9._~!$&'()*+,;=:@/-]|%[0-9A-Fa-f]{2})*$")

/**
 * The shape check of contract rule C39 (issue #205, decision 2). A
 * value that passes this check has the shape of a server match result
 * (a pattern, or the literal `/other`); a value that fails it is
 * either malformed, or a raw client path that the C39 shape alone
 * cannot always tell apart from a pattern result, so [PathAdmission]
 * applies the further, per-app cap.
 *
 * The check: the value starts with `/`; its second character, when
 * one exists, is not `/`; it holds only the character set of C39, with
 * no `?` and no `#`; it has 1 to 150 bytes in UTF-8
 * ([PATH_MAX_BYTES]); each segment has a maximum of 80 characters (the
 * `*` bound of C42).
 *
 * This is a pure function with no I/O and no shared state, so a test
 * calls it directly, with no `SqliteDatabase` and no `MongoAppReader`.
 */
internal object PathShape {

    fun isPatternResult(value: String): Boolean {
        if (value.isEmpty() || value[0] != '/') return false
        if (value.length > 1 && value[1] == '/') return false
        if (value.contains('?') || value.contains('#')) return false
        val byteLength = value.toByteArray(Charsets.UTF_8).size
        if (byteLength < 1 || byteLength > PATH_MAX_BYTES) return false
        if (!PATH_CHARACTER_PATTERN.matches(value)) return false
        return value.split('/').all { segment -> segment.length <= MAX_SEGMENT_LENGTH }
    }
}

/**
 * The per-app, per-cycle admission of a `path` value that already
 * passed [PathShape.isPatternResult] (issue #205, decision 4 of the
 * correction round). [MongoAppReader.runCycle] builds one instance at
 * the start of each cycle, from [EventStore.pathValuesOf] (the loaded
 * values), and it keeps this one instance for the whole cycle, across
 * each page. The cap then counts the loaded values plus each value
 * that an earlier page of the same cycle already admitted.
 *
 * [admit] never inserts a row; [MongoAppReader.runCycle] reads
 * [drainNewValues] once for each page, and it passes that set to
 * [EventStore.commitPage], inside the one transaction of that page.
 */
internal class PathAdmission(initialValues: Set<String>, private val cap: Int) {

    private val known: MutableSet<String> = HashSet(initialValues)
    private val newValuesSinceDrain = mutableSetOf<String>()

    /**
     * Admits [path], a value that already passed
     * [PathShape.isPatternResult]. A known value passes as it is, also
     * when the set is already at the cap. A new value joins the set,
     * and it passes as it is, only while the set holds fewer than
     * [cap] values; each other new value gives [OTHER_PATH].
     */
    fun admit(path: String): String {
        if (path in known) return path
        if (known.size < cap) {
            known += path
            newValuesSinceDrain += path
            return path
        }
        return OTHER_PATH
    }

    /** Gives each value that [admit] added since the last call to this method, and clears that set. */
    fun drainNewValues(): Set<String> {
        val drained = newValuesSinceDrain.toSet()
        newValuesSinceDrain.clear()
        return drained
    }
}
