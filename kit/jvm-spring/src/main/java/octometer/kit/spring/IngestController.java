package octometer.kit.spring;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Objects;
import octometer.kit.core.ingest.AnonymousDailyCap;
import octometer.kit.core.ingest.AnonymousMinuteLimiter;
import octometer.kit.core.ingest.IngestRateLimiter;
import octometer.kit.core.ingest.IngestRequestProcessor;
import octometer.kit.core.ingest.IngestResult;
import octometer.kit.core.ingest.IngestSettings;
import octometer.kit.core.store.EventLogStore;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Spring MVC adapter of the ingest route of design section 4.2 (issue
 * #69). {@link IngestRequestProcessor} of `kit/jvm-core` holds the whole
 * order of the checks; this class is a thin adapter over it, the Spring
 * MVC form of {@code octometerIngestRoute} of `kit/jvm-ktor`.
 *
 * <p>This class writes no CORS header, and it gives the request body to
 * {@link IngestRequestProcessor} as a plain {@code String}, with no
 * Jackson class. It reads the body itself, as UTF-8 bytes, because RFC
 * 8259 needs UTF-8 for JSON; a {@code charset} parameter of the
 * `Content-Type` header has no effect (the same rule as
 * `octometerIngestRoute`).
 *
 * <p>The app mounts this class as a Spring bean, for example a
 * {@code @Bean} method of its own {@code @Configuration} class. Spring's
 * request mapping infrastructure finds the {@code @PostMapping} method of
 * this class on any bean of this type, however the app registers it. The
 * app must add no authentication check of its own inside this class; it
 * decides instead whether the security chain of the app lets an anonymous
 * request reach the ingest path.
 *
 * <p>The ingest path comes from the Spring property
 * {@code octometer.ingest-path}, with {@link #DEFAULT_INGEST_PATH} (the
 * path of contract rule C12) as its default. Spring resolves this
 * placeholder when it builds the
 * request mapping of this bean, from the property sources of the app
 * (for example {@code application.properties} or an environment
 * variable). Read `kit/jvm-spring/README.md` for the CSRF exemption that
 * an app with Spring Security must add for this path.
 *
 * <p>A throw of the app's {@link SpringUserIdResolver} and a throw of the
 * app's {@link EventLogStore} each give status 500, never status 400,
 * also when the exception is an
 * {@code octometer.kit.core.ingest.IngestException}. Only an invalid
 * request body gives status 400. This class writes no exception text
 * into the answer and into the log; the log holds only the class name of
 * the exception (design decision D15).
 */
@RestController
public final class IngestController {

    /** The default of the Spring property {@code octometer.ingest-path} (contract rule C12). */
    public static final String DEFAULT_INGEST_PATH = "/api/octometer/v1/clicks";

    /**
     * The maximum size of one request body, in bytes (contract rule
     * C18). {@link #readBody} reads at most one byte above this bound,
     * the same bound as {@code receiveLimitedText} of
     * `kit/jvm-ktor/src/main/kotlin/octometer/kit/ktor/IngestRoute.kt`
     * (the fix of MAJOR 1, the Java and Spring review and the security
     * review of pull request #215).
     */
    private static final long MAX_BODY_BYTES = 16 * 1024;

    private static final Logger LOGGER = System.getLogger("octometer.kit.spring");

    private final IngestRequestProcessor processor;
    private final SpringUserIdResolver userIdResolver;

    /**
     * Builds one controller with the default settings, clock, rate
     * limiter, anonymous limiters, and no client address header (design
     * decision D19, D20, D43).
     *
     * @param store the event log store of the app.
     * @param userIdResolver reads the user id from the current request,
     *   or {@code null} when no user is signed in (contract rule C6).
     */
    public IngestController(EventLogStore store, SpringUserIdResolver userIdResolver) {
        this(store, userIdResolver, IngestSettings.fromEnvironment());
    }

    /**
     * Builds one controller with the given settings, the system clock, the
     * default rate limiter and anonymous limiters, and no client address
     * header.
     */
    public IngestController(EventLogStore store, SpringUserIdResolver userIdResolver, IngestSettings settings) {
        this(store, userIdResolver, settings, Clock.systemUTC());
    }

    /**
     * Builds one controller with the given settings and clock, the
     * default rate limiter and anonymous limiters (each built with
     * {@code clock}), and no client address header.
     */
    public IngestController(EventLogStore store, SpringUserIdResolver userIdResolver, IngestSettings settings,
            Clock clock) {
        this(store, userIdResolver, settings, clock, new IngestRateLimiter(clock),
                new AnonymousMinuteLimiter(clock, settings.anonReqPerMinute(), settings.anonEventsPerMinute(),
                        settings.anonSessionsPerMinute()),
                new AnonymousDailyCap(clock, settings.anonMaxEventsPerDay(), settings.anonEventsPerKeyPerDay()),
                System.getenv("OCTOMETER_CLIENT_IP_HEADER"), trustedProxyCountFromEnvironment());
    }

    /**
     * Builds one controller with every setting given.
     *
     * @param store the event log store of the app.
     * @param userIdResolver reads the user id from the current request,
     *   or {@code null} when no user is signed in (contract rule C6).
     * @param settings the settings of design decision D19 and design
     *   decision D43.
     * @param clock the clock for {@code receivedAt} of design section
     *   4.2.
     * @param rateLimiter the ingest rate limiter of design decision D20.
     * @param minuteLimiter the anonymous per-minute limiter of design
     *   decision D43.
     * @param dailyCap the daily anonymous cap of design decision D43.
     * @param clientIpHeaderName the name of the header that holds the
     *   client address (issue #33), or {@code null} to read only the
     *   remote address of the connection. The default reads
     *   {@code OCTOMETER_CLIENT_IP_HEADER} once, at controller build
     *   time. Set this option only behind a proxy that appends the real
     *   client address this way; see `kit/jvm-spring/README.md`.
     * @param trustedProxyCount the position, counted from the right of
     *   the header list of {@code clientIpHeaderName}, of the address to
     *   trust (design decision D20, issue #116). The default reads
     *   {@code OCTOMETER_TRUSTED_PROXY_COUNT} once, at 1 with no such
     *   variable. A text value, a zero, or a negative value stops the
     *   app start; the error message never repeats the raw value.
     */
    public IngestController(EventLogStore store, SpringUserIdResolver userIdResolver, IngestSettings settings,
            Clock clock, IngestRateLimiter rateLimiter, AnonymousMinuteLimiter minuteLimiter,
            AnonymousDailyCap dailyCap, String clientIpHeaderName, int trustedProxyCount) {
        this.userIdResolver = Objects.requireNonNull(userIdResolver, "userIdResolver must not be null");
        this.processor = new IngestRequestProcessor(store, settings, clock, rateLimiter, minuteLimiter, dailyCap,
                clientIpHeaderName, trustedProxyCount);
    }

    /**
     * Runs the ingest route of design section 4.2 (issue #69). Read the
     * Javadoc of {@link IngestRequestProcessor} for the full order of the
     * checks.
     */
    @PostMapping(path = "${octometer.ingest-path:" + DEFAULT_INGEST_PATH + "}")
    public ResponseEntity<Void> ingest(HttpServletRequest request) {
        try {
            SpringRequestView view = new SpringRequestView(request, userIdResolver);
            IngestRequestProcessor.BeforeBodyOutcome outcome = processor.beforeBody(view);
            if (outcome.mustRespondNow()) {
                return respond(outcome.result());
            }

            String body = readBody(request);
            if (body == null) {
                return respond(new IngestResult(400));
            }
            IngestResult result = processor.afterBody(outcome, body);
            return respond(result);
        } catch (IOException | RuntimeException cause) {
            return respondWithDefect(cause);
        }
    }

    private static ResponseEntity<Void> respond(IngestResult result) {
        return ResponseEntity.status(result.statusCode()).build();
    }

    /**
     * Writes the 500 answer and the one log line of design decision D15
     * for a defect of the app: a log line never holds a user id, a
     * session id, or a connection string. The message of a store
     * exception can hold any of the three, thus the log holds only the
     * class name of the exception.
     */
    private static ResponseEntity<Void> respondWithDefect(Throwable cause) {
        LOGGER.log(Level.ERROR, "The Octometer ingest controller failed. The exception class is {0}.",
                cause.getClass().getName());
        return ResponseEntity.status(500).build();
    }

    /**
     * Reads the request body as text, with a limit of
     * {@link #MAX_BODY_BYTES} raw bytes (contract rule C18). This method
     * never reads more than one byte above the limit into memory, so a
     * large body never reaches the heap in full; a chunked request with
     * no declared {@code Content-Length} still gets this bound (MAJOR 1
     * of the Java and Spring review and of the security review of pull
     * request #215: {@link IngestRequestProcessor#beforeBody} checks
     * only the declared length, and a chunked request declares none).
     * This method returns {@code null} for a body above the limit; the
     * caller then answers 400 with no call of
     * {@link IngestRequestProcessor#afterBody}.
     *
     * <p>It always decodes the body as UTF-8, because RFC 8259 needs
     * UTF-8 for JSON; a {@code charset} parameter of the
     * `Content-Type` header has no effect (the same rule as
     * `octometerIngestRoute` of `kit/jvm-ktor`).
     */
    private static String readBody(HttpServletRequest request) throws IOException {
        try (InputStream inputStream = request.getInputStream()) {
            byte[] bytes = inputStream.readNBytes((int) (MAX_BODY_BYTES + 1));
            if (bytes.length > MAX_BODY_BYTES) {
                return null;
            }
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    /**
     * Reads {@code OCTOMETER_TRUSTED_PROXY_COUNT} from the process
     * environment (design decision D20, issue #116), or 1 with no such
     * variable.
     */
    private static int trustedProxyCountFromEnvironment() {
        return positiveWholeNumberFromEnvironmentValue(System.getenv("OCTOMETER_TRUSTED_PROXY_COUNT"),
                "OCTOMETER_TRUSTED_PROXY_COUNT", 1);
    }

    /**
     * Turns the raw text of one environment variable into a positive
     * whole number, the form of `positiveWholeNumberFromEnvironmentValue`
     * of `kit/jvm-ktor` (design decision D43, issue #116). A {@code null}
     * {@code rawValue} gives {@code defaultValue}, with no error.
     *
     * <p>A value of zero, a negative value, or a value with a character
     * that is not an ASCII digit, stops the app start: this method
     * throws {@link IllegalStateException}, with a message that names
     * {@code variableName} and never repeats {@code rawValue}.
     *
     * <p>This method is package-private, so a test of this module can
     * call it directly (see {@code IngestControllerTest}).
     */
    static int positiveWholeNumberFromEnvironmentValue(String rawValue, String variableName, int defaultValue) {
        if (rawValue == null) {
            return defaultValue;
        }
        String trimmed = rawValue.trim();
        if (trimmed.matches("[0-9]+")) {
            try {
                int parsedValue = Integer.parseInt(trimmed);
                if (parsedValue > 0) {
                    return parsedValue;
                }
            } catch (NumberFormatException cause) {
                // A text of only ASCII digits can still overflow an int.
                // The error below covers this case too.
            }
        }
        throw new IllegalStateException(variableName + " must hold a positive whole number of ASCII digits. "
                + "The app start stops, because a wrong proxy count can let a forged header choose the "
                + "client address.");
    }

    /**
     * The {@link IngestRequestProcessor.RequestView} of one Spring MVC
     * request (issue #69). It gives {@link IngestRequestProcessor} each
     * raw header line, the declared {@code Content-Length}, the remote
     * address, and the app's {@link SpringUserIdResolver}; it reads no
     * request body.
     */
    private static final class SpringRequestView implements IngestRequestProcessor.RequestView {

        private final HttpServletRequest request;
        private final SpringUserIdResolver userIdResolver;

        SpringRequestView(HttpServletRequest request, SpringUserIdResolver userIdResolver) {
            this.request = request;
            this.userIdResolver = userIdResolver;
        }

        @Override
        public List<String> headerLines(String headerName) {
            List<String> lines = new ArrayList<>();
            Enumeration<String> values = request.getHeaders(headerName);
            if (values != null) {
                while (values.hasMoreElements()) {
                    lines.add(values.nextElement());
                }
            }
            return lines;
        }

        @Override
        public Long declaredContentLengthBytes() {
            long length = request.getContentLengthLong();
            return length < 0 ? null : length;
        }

        @Override
        public String remoteAddress() {
            return request.getRemoteAddr();
        }

        @Override
        public String resolveUserId() {
            return userIdResolver.resolve(request);
        }
    }
}
