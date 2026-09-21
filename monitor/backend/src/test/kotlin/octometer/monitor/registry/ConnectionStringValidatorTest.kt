package octometer.monitor.registry

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

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
        assertFalse(invalid.message.contains(uri))
    }

    // The tests below cover each hostile form of the two reviews of pull
    // request #125. Each one failed against the deny-list, and each one
    // must fail here too, against the allow-list.

    @Test
    fun `an option after a semicolon separator gets an invalid result`() {
        // BLOCKER 1 of the security review: the old split read only "&".
        val result = ConnectionStringValidator.check(
            allowlistedSrvUriWithoutCredential("appName=octometer;tlsInsecure=true"),
        )

        assertIs<ConnectionStringCheck.Invalid>(result)
    }

    @Test
    fun `readPreference after a semicolon separator gets an invalid result`() {
        val result = ConnectionStringValidator.check(
            allowlistedSrvUriWithoutCredential("appName=octometer;readPreference=secondary"),
        )

        assertIs<ConnectionStringCheck.Invalid>(result)
    }

    @Test
    fun `tls=0 gets an invalid result`() {
        // BLOCKER 2 of the security review: the driver reads "0" as false.
        val result = ConnectionStringValidator.check(allowlistedSrvUriWithoutCredential("tls=0"))

        assertIs<ConnectionStringCheck.Invalid>(result)
    }

    @Test
    fun `ssl=no gets an invalid result`() {
        val result = ConnectionStringValidator.check(allowlistedSrvUriWithoutCredential("ssl=no"))

        assertIs<ConnectionStringCheck.Invalid>(result)
    }

    @Test
    fun `tlsInsecure=1 gets an invalid result`() {
        val result = ConnectionStringValidator.check(allowlistedSrvUriWithoutCredential("tlsInsecure=1"))

        assertIs<ConnectionStringCheck.Invalid>(result)
    }

    @Test
    fun `tlsInsecure=yes gets an invalid result`() {
        val result = ConnectionStringValidator.check(allowlistedSrvUriWithoutCredential("tlsInsecure=yes"))

        assertIs<ConnectionStringCheck.Invalid>(result)
    }

    @Test
    fun `tlsAllowInvalidHostnames=yes gets an invalid result`() {
        val result = ConnectionStringValidator.check(allowlistedSrvUriWithoutCredential("tlsAllowInvalidHostnames=yes"))

        assertIs<ConnectionStringCheck.Invalid>(result)
    }

    @Test
    fun `tlsInsecure=true with a trailing percent-encoded space gets an invalid result`() {
        val result = ConnectionStringValidator.check(allowlistedSrvUriWithoutCredential("tlsInsecure=true%20"))

        assertIs<ConnectionStringCheck.Invalid>(result)
    }

    @Test
    fun `proxyHost gets an invalid result`() {
        // MAJOR 1 of the security review: an option outside the allow-list.
        val result = ConnectionStringValidator.check(allowlistedSrvUriWithoutCredential("proxyHost=evil.example"))

        assertIs<ConnectionStringCheck.Invalid>(result)
    }

    @Test
    fun `authMechanism gets an invalid result`() {
        val result = ConnectionStringValidator.check(allowlistedSrvUriWithoutCredential("authMechanism=MONGODB-AWS"))

        assertIs<ConnectionStringCheck.Invalid>(result)
    }

    @Test
    fun `directConnection gets an invalid result`() {
        val result = ConnectionStringValidator.check(allowlistedSrvUriWithoutCredential("directConnection=true"))

        assertIs<ConnectionStringCheck.Invalid>(result)
    }

    @Test
    fun `tls=true passes, because the allow-list accepts the exact value true`() {
        val result = ConnectionStringValidator.check(allowlistedSrvUriWithoutCredential("tls=true"))

        assertIs<ConnectionStringCheck.Valid>(result)
    }

    @Test
    fun `ssl=true passes, because the allow-list accepts the exact value true`() {
        val result = ConnectionStringValidator.check(allowlistedSrvUriWithoutCredential("ssl=true"))

        assertIs<ConnectionStringCheck.Valid>(result)
    }

    @Test
    fun `each allow-listed option with any value passes`() {
        for (option in listOf("retryWrites=false", "retryReads=false", "w=majority", "authSource=admin", "replicaSet=rs0")) {
            val result = ConnectionStringValidator.check(allowlistedSrvUriWithoutCredential(option))

            assertIs<ConnectionStringCheck.Valid>(result, "expected $option to pass")
        }
    }

    @Test
    fun `a percent-encoded option name decodes before the compare, and an allowed name then passes`() {
        // "%74ls" decodes to "tls".
        val result = ConnectionStringValidator.check(allowlistedSrvUriWithoutCredential("%74ls=true"))

        assertIs<ConnectionStringCheck.Valid>(result)
    }

    @Test
    fun `a percent-encoded forbidden option name gets an invalid result too`() {
        // "read%50reference" decodes to "readPreference".
        val result = ConnectionStringValidator.check(allowlistedSrvUriWithoutCredential("read%50reference=secondary"))

        assertIs<ConnectionStringCheck.Invalid>(result)
    }

    @Test
    fun `a repeated option name gets an invalid result`() {
        val result = ConnectionStringValidator.check(allowlistedSrvUriWithoutCredential("appName=a&appName=b"))

        assertIs<ConnectionStringCheck.Invalid>(result)
    }

    @Test
    fun `an option with no value gets an invalid result`() {
        val result = ConnectionStringValidator.check(allowlistedSrvUriWithoutCredential("tls"))

        assertIs<ConnectionStringCheck.Invalid>(result)
    }

    @Test
    fun `the invalid result for tls=false names the option, and never the value`() {
        val result = ConnectionStringValidator.check(allowlistedSrvUriWithoutCredential("tls=false"))

        val invalid = result as ConnectionStringCheck.Invalid
        assertTrue(invalid.message.contains("tls"))
        assertFalse(invalid.message.contains("false"))
    }
}
