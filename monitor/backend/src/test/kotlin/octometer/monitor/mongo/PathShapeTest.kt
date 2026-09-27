package octometer.monitor.mongo

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Issue #205, decision 2: the shape check of contract rule C39. Each
// case here is a pure function call, with no database and no reader.
class PathShapeTest {

    @Test
    fun `isPatternResult accepts a pattern with a name segment`() {
        assertTrue(PathShape.isPatternResult("/users/:name"))
    }

    @Test
    fun `isPatternResult accepts the literal other`() {
        assertTrue(PathShape.isPatternResult(OTHER_PATH))
    }

    // The shape check alone cannot tell a raw client path apart from a
    // pattern result; PathAdmission applies the further, per-app cap.
    @Test
    fun `isPatternResult accepts a raw path shaped like a pattern result`() {
        assertTrue(PathShape.isPatternResult("/users/12345"))
    }

    @Test
    fun `isPatternResult rejects a double leading slash`() {
        assertFalse(PathShape.isPatternResult("//x"))
    }

    @Test
    fun `isPatternResult rejects a query string`() {
        assertFalse(PathShape.isPatternResult("/a?b=1"))
    }

    @Test
    fun `isPatternResult rejects a fragment`() {
        assertFalse(PathShape.isPatternResult("/a#x"))
    }

    @Test
    fun `isPatternResult rejects a space`() {
        assertFalse(PathShape.isPatternResult("/a b"))
    }

    @Test
    fun `isPatternResult rejects a segment above 80 characters`() {
        assertFalse(PathShape.isPatternResult("/" + "a".repeat(81)))
    }

    @Test
    fun `isPatternResult rejects a malformed percent escape`() {
        assertFalse(PathShape.isPatternResult("/%zz"))
    }

    @Test
    fun `isPatternResult rejects a value with no leading slash`() {
        assertFalse(PathShape.isPatternResult("users"))
    }

    @Test
    fun `isPatternResult rejects an empty value`() {
        assertFalse(PathShape.isPatternResult(""))
    }

    @Test
    fun `isPatternResult accepts the root path`() {
        assertTrue(PathShape.isPatternResult("/"))
    }

    @Test
    fun `isPatternResult rejects a value above 150 bytes`() {
        assertFalse(PathShape.isPatternResult("/" + "a".repeat(150)))
    }

    @Test
    fun `isPatternResult accepts a well-formed percent escape`() {
        assertTrue(PathShape.isPatternResult("/a%20b"))
    }
}
