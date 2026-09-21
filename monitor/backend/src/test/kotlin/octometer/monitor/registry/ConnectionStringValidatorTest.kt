package octometer.monitor.registry

import kotlin.test.Test
import kotlin.test.assertIs

// Step 1 of issue #15 (D11 and section 4.3). Each test builds its
// connection string from parts (see MongoTestUris.kt), so no file here
// holds a credential as one literal.
class ConnectionStringValidatorTest {

    @Test
    fun `a valid mongodb+srv URI with a credential passes`() {
        val result = ConnectionStringValidator.check(allowlistedSrvUri())

        assertIs<ConnectionStringCheck.Valid>(result)
    }

    @Test
    fun `a valid mongodb+srv URI with no credential passes`() {
        val result = ConnectionStringValidator.check(allowlistedSrvUriWithoutCredential())

        assertIs<ConnectionStringCheck.Valid>(result)
    }

    @Test
    fun `a loopback mongodb URI passes`() {
        val result = ConnectionStringValidator.check(loopbackUri())

        assertIs<ConnectionStringCheck.Valid>(result)
    }

    @Test
    fun `an http URI gets an invalid result, because the scheme is wrong`() {
        val result = ConnectionStringValidator.check("http://" + ALLOWLISTED_HOST)

        assertIs<ConnectionStringCheck.Invalid>(result)
    }

    @Test
    fun `a mongodb URI with a public host gets an invalid result`() {
        val result = ConnectionStringValidator.check(publicHostUri())

        assertIs<ConnectionStringCheck.Invalid>(result)
    }

    @Test
    fun `a URI with tlsInsecure=true gets an invalid result`() {
        val result = ConnectionStringValidator.check(allowlistedSrvUriWithoutCredential("tlsInsecure=true"))

        assertIs<ConnectionStringCheck.Invalid>(result)
    }

    @Test
    fun `a URI with tlsAllowInvalidCertificates=true gets an invalid result`() {
        val result =
            ConnectionStringValidator.check(allowlistedSrvUriWithoutCredential("tlsAllowInvalidCertificates=true"))

        assertIs<ConnectionStringCheck.Invalid>(result)
    }

    @Test
    fun `a URI with tlsAllowInvalidHostnames=true gets an invalid result`() {
        val result =
            ConnectionStringValidator.check(allowlistedSrvUriWithoutCredential("tlsAllowInvalidHostnames=true"))

        assertIs<ConnectionStringCheck.Invalid>(result)
    }

    @Test
    fun `a URI with tls=false gets an invalid result`() {
        val result = ConnectionStringValidator.check(allowlistedSrvUriWithoutCredential("tls=false"))

        assertIs<ConnectionStringCheck.Invalid>(result)
    }

    @Test
    fun `a URI with ssl=false gets an invalid result`() {
        val result = ConnectionStringValidator.check(allowlistedSrvUriWithoutCredential("ssl=false"))

        assertIs<ConnectionStringCheck.Invalid>(result)
    }

    @Test
    fun `a URI with readPreference gets an invalid result`() {
        val result = ConnectionStringValidator.check(allowlistedSrvUriWithoutCredential("readPreference=secondary"))

        assertIs<ConnectionStringCheck.Invalid>(result)
    }

    @Test
    fun `the invalid result never repeats the checked text`() {
        val uri = allowlistedSrvUri()

        val result = ConnectionStringValidator.check("$uri?readPreference=secondary")

        val invalid = result as ConnectionStringCheck.Invalid
        kotlin.test.assertFalse(invalid.message.contains(uri))
    }
}
