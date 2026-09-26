package octometer.kit.spring;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import octometer.kit.core.ingest.AnonymousDailyCap;
import octometer.kit.core.ingest.AnonymousMinuteLimiter;
import octometer.kit.core.ingest.IngestRateLimiter;
import octometer.kit.core.ingest.IngestSettings;
import octometer.kit.core.store.DeletionResult;
import octometer.kit.core.store.EventLogStore;
import octometer.kit.core.store.InMemoryEventLogStore;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests of {@link IngestController} with {@link MockMvc}, in standalone
 * mode (issue #69): no {@code ApplicationContext}, no property source, so
 * each request goes to {@link IngestController#DEFAULT_INGEST_PATH}. The
 * whole order of the checks stays a test of
 * {@code IngestRequestProcessorTest} of `kit/jvm-core`; this class proves
 * that the adapter itself wires each check correctly, with a real
 * {@link jakarta.servlet.http.HttpServletRequest} and a real MockMvc
 * dispatch.
 */
class IngestControllerTest {

    private static final Instant START = Instant.parse("2026-09-26T10:00:00Z");

    private static final String VALID_BODY = "{\"sessionId\":\"0b0e4e0e-6a55-4c1e-9a53-0c1f6f7a2d11\","
            + "\"clicks\":[{\"element\":\"checkout.save\",\"ageMs\":1200}]}";

    private static MockMvc mockMvcOf(IngestController controller) {
        return MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void aValidBatchGives204AndStoresTheEvent() throws Exception {
        InMemoryEventLogStore store = new InMemoryEventLogStore();
        IngestController controller = new IngestController(store, request -> "user-1",
                new IngestSettings(true), Clock.fixed(START, ZoneOffset.UTC));
        MockMvc mockMvc = mockMvcOf(controller);

        mockMvc.perform(post(IngestController.DEFAULT_INGEST_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isNoContent());

        assertEquals(1, store.events().size());
        assertEquals("checkout.save", store.events().get(0).element());
        assertEquals("user-1", store.events().get(0).userId());
    }

    @Test
    void textPlainGives415() throws Exception {
        IngestController controller = new IngestController(new InMemoryEventLogStore(), request -> "user-1",
                new IngestSettings(true), Clock.fixed(START, ZoneOffset.UTC));
        MockMvc mockMvc = mockMvcOf(controller);

        mockMvc.perform(post(IngestController.DEFAULT_INGEST_PATH)
                        .contentType(MediaType.TEXT_PLAIN)
                        .content(VALID_BODY))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void aRateLimitResultGives429() throws Exception {
        Clock clock = Clock.fixed(START, ZoneOffset.UTC);
        IngestRateLimiter rateLimiter = new IngestRateLimiter(clock);
        for (int i = 1; i <= 30; i++) {
            rateLimiter.check("user-1", "");
        }
        IngestController controller = new IngestController(new InMemoryEventLogStore(), request -> "user-1",
                new IngestSettings(true), clock, rateLimiter, new AnonymousMinuteLimiter(clock, 300, 900, 120),
                new AnonymousDailyCap(clock, 20_000, 2_000), null, 1);
        MockMvc mockMvc = mockMvcOf(controller);

        mockMvc.perform(post(IngestController.DEFAULT_INGEST_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void anInvalidBodyGives400() throws Exception {
        IngestController controller = new IngestController(new InMemoryEventLogStore(), request -> "user-1",
                new IngestSettings(true), Clock.fixed(START, ZoneOffset.UTC));
        MockMvc mockMvc = mockMvcOf(controller);

        mockMvc.perform(post(IngestController.DEFAULT_INGEST_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":\"not-a-uuid\",\"clicks\":[]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void theD19DropGives204AndStoresNothingWithNoUserIdAndAnonymousClicksOff() throws Exception {
        InMemoryEventLogStore store = new InMemoryEventLogStore();
        IngestController controller = new IngestController(store, request -> null,
                new IngestSettings(false), Clock.fixed(START, ZoneOffset.UTC));
        MockMvc mockMvc = mockMvcOf(controller);

        mockMvc.perform(post(IngestController.DEFAULT_INGEST_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isNoContent());

        assertTrue(store.events().isEmpty());
    }

    @Test
    void aBatchAboveTheDailyCapGives204() throws Exception {
        InMemoryEventLogStore store = new InMemoryEventLogStore();
        Clock clock = Clock.fixed(START, ZoneOffset.UTC);
        IngestController controller = new IngestController(store, request -> null, new IngestSettings(true), clock,
                new IngestRateLimiter(clock), new AnonymousMinuteLimiter(clock, 300, 900, 120),
                new AnonymousDailyCap(clock, 1, 100), null, 1);
        MockMvc mockMvc = mockMvcOf(controller);

        mockMvc.perform(post(IngestController.DEFAULT_INGEST_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isNoContent());
        assertEquals(1, store.events().size());

        mockMvc.perform(post(IngestController.DEFAULT_INGEST_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isNoContent());
        assertEquals(1, store.events().size(), "The batch above the daily cap must add no new event.");
    }

    @Test
    void aDeclaredContentLengthAboveTheLimitGives400() throws Exception {
        IngestController controller = new IngestController(new InMemoryEventLogStore(), request -> "user-1",
                new IngestSettings(true), Clock.fixed(START, ZoneOffset.UTC));
        MockMvc mockMvc = mockMvcOf(controller);
        String oversizedBody = "{\"sessionId\":\"" + "a".repeat(20_000) + "\"}";

        mockMvc.perform(post(IngestController.DEFAULT_INGEST_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(oversizedBody))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aBodyAboveTheLimitWithNoDeclaredLengthGives400AndTheControllerReadsNoUnlimitedBody() {
        // MAJOR 1 of the Java and Spring review and of the security
        // review of pull request #215: a chunked request declares no
        // Content-Length, so the check of step 5 alone lets an
        // unbounded body reach readBody. The status alone cannot prove
        // the fix: afterBody checks the decoded byte count again, so it
        // still answers 400 for a body that readBody already read in
        // full. This test instead counts each byte that readBody pulls
        // from a 1 MB source stream with no declared length, through
        // CountingServletInputStream, and asserts that the count stays
        // at or under 16385 bytes (the 16 KB limit of contract rule C18,
        // plus one). This test calls IngestController.ingest directly,
        // because MockMvc ties getContentLengthLong() to the byte count
        // of its own content array, so it cannot build such a request.
        byte[] oneMegabyte = new byte[1024 * 1024];
        java.util.Arrays.fill(oneMegabyte, (byte) 'a');
        CountingServletInputStream countingInputStream = new CountingServletInputStream(oneMegabyte);
        HttpServletRequestForTest request = new HttpServletRequestForTest(countingInputStream);
        IngestController controller = new IngestController(new InMemoryEventLogStore(), req -> "user-1",
                new IngestSettings(true), Clock.fixed(START, ZoneOffset.UTC));

        org.springframework.http.ResponseEntity<Void> response = controller.ingest(request);

        assertEquals(400, response.getStatusCode().value());
        assertTrue(countingInputStream.bytesRead() <= 16 * 1024 + 1,
                "Expected readBody to read at most 16385 bytes, but it read " + countingInputStream.bytesRead() + ".");
    }

    /**
     * A minimal {@link jakarta.servlet.http.HttpServletRequest} for
     * {@link #aBodyAboveTheLimitWithNoDeclaredLengthGives400AndTheControllerReadsNoUnlimitedBody}.
     * It gives {@code application/json}, no declared length, and the
     * given input stream; every other method throws, because
     * {@link IngestController} never calls one.
     */
    private static final class HttpServletRequestForTest extends jakarta.servlet.http.HttpServletRequestWrapper {
        private final jakarta.servlet.ServletInputStream inputStream;

        HttpServletRequestForTest(jakarta.servlet.ServletInputStream inputStream) {
            super(new org.springframework.mock.web.MockHttpServletRequest("POST", IngestController.DEFAULT_INGEST_PATH));
            this.inputStream = inputStream;
            ((org.springframework.mock.web.MockHttpServletRequest) getRequest())
                    .setContentType(MediaType.APPLICATION_JSON_VALUE);
        }

        @Override
        public long getContentLengthLong() {
            return -1;
        }

        @Override
        public int getContentLength() {
            return -1;
        }

        @Override
        public jakarta.servlet.ServletInputStream getInputStream() {
            return inputStream;
        }
    }

    /**
     * A {@link jakarta.servlet.ServletInputStream} over a byte array,
     * that counts each byte a caller reads from it.
     */
    private static final class CountingServletInputStream extends jakarta.servlet.ServletInputStream {
        private final java.io.ByteArrayInputStream delegate;
        private long bytesRead;

        CountingServletInputStream(byte[] data) {
            this.delegate = new java.io.ByteArrayInputStream(data);
        }

        long bytesRead() {
            return bytesRead;
        }

        @Override
        public int read() {
            int value = delegate.read();
            if (value >= 0) {
                bytesRead += 1;
            }
            return value;
        }

        @Override
        public int read(byte[] b, int off, int len) {
            int count = delegate.read(b, off, len);
            if (count > 0) {
                bytesRead += count;
            }
            return count;
        }

        @Override
        public boolean isFinished() {
            return delegate.available() == 0;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(jakarta.servlet.ReadListener readListener) {
            throw new UnsupportedOperationException("This test stream needs no async read listener.");
        }
    }

    @Test
    void theControllerCallsTheStoreOfTheApp() throws Exception {
        List<String> appendedUserIds = new java.util.ArrayList<>();
        EventLogStore store = new EventLogStore() {
            @Override
            public void append(List<octometer.kit.core.ingest.IngestEvent> events, String userId) {
                appendedUserIds.add(userId);
            }

            @Override
            public DeletionResult deleteByUserId(String userId) {
                throw new UnsupportedOperationException();
            }
        };
        IngestController controller = new IngestController(store, request -> "user-42",
                new IngestSettings(true), Clock.fixed(START, ZoneOffset.UTC));
        MockMvc mockMvc = mockMvcOf(controller);

        mockMvc.perform(post(IngestController.DEFAULT_INGEST_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isNoContent());

        assertEquals(List.of("user-42"), appendedUserIds);
    }

    @Test
    void withoutAUserIdTheControllerGivesTheClientIpHeaderValueToTheLimiter() throws Exception {
        Clock clock = Clock.fixed(START, ZoneOffset.UTC);
        AnonymousMinuteLimiter minuteLimiter = new AnonymousMinuteLimiter(clock, 1, 900, 120);
        IngestController controller = new IngestController(new InMemoryEventLogStore(), request -> null,
                new IngestSettings(true), clock, new IngestRateLimiter(clock), minuteLimiter,
                new AnonymousDailyCap(clock, 20_000, 2_000), "X-Client-Ip", 1);
        MockMvc mockMvc = mockMvcOf(controller);

        mockMvc.perform(post(IngestController.DEFAULT_INGEST_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Client-Ip", "203.0.113.9")
                        .content(VALID_BODY))
                .andExpect(status().isNoContent());

        // A second request of the same header value shares the first
        // request's key of the 1-request minute limiter, so it gives 429;
        // this proves the controller reads the header that
        // OCTOMETER_CLIENT_IP_HEADER names, and gives it to the limiter
        // (design decision D20, issue #33).
        mockMvc.perform(post(IngestController.DEFAULT_INGEST_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Client-Ip", "203.0.113.9")
                        .content(VALID_BODY))
                .andExpect(status().isTooManyRequests());

        // A different header value is a different key, with its own
        // fresh counter.
        mockMvc.perform(post(IngestController.DEFAULT_INGEST_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Client-Ip", "198.51.100.2")
                        .content(VALID_BODY))
                .andExpect(status().isNoContent());
    }

    @Test
    void noResponseHoldsACorsHeader() throws Exception {
        IngestController controller = new IngestController(new InMemoryEventLogStore(), request -> "user-1",
                new IngestSettings(true), Clock.fixed(START, ZoneOffset.UTC));
        MockMvc mockMvc = mockMvcOf(controller);

        mockMvc.perform(post(IngestController.DEFAULT_INGEST_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    void aStoreDefectGives500AndNever400() throws Exception {
        EventLogStore store = new EventLogStore() {
            @Override
            public void append(List<octometer.kit.core.ingest.IngestEvent> events, String userId) {
                throw new IllegalStateException("a store defect");
            }

            @Override
            public DeletionResult deleteByUserId(String userId) {
                throw new UnsupportedOperationException();
            }
        };
        IngestController controller = new IngestController(store, request -> "user-1",
                new IngestSettings(true), Clock.fixed(START, ZoneOffset.UTC));
        MockMvc mockMvc = mockMvcOf(controller);

        mockMvc.perform(post(IngestController.DEFAULT_INGEST_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isInternalServerError());
    }

    @Test
    void aResolveUserIdThatThrowsGives500AndNever400() throws Exception {
        IngestController controller = new IngestController(new InMemoryEventLogStore(), request -> {
            throw new IllegalArgumentException("the app resolver fails");
        }, new IngestSettings(true), Clock.fixed(START, ZoneOffset.UTC));
        MockMvc mockMvc = mockMvcOf(controller);

        mockMvc.perform(post(IngestController.DEFAULT_INGEST_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isInternalServerError());
    }

    // The tests below check
    // IngestController.positiveWholeNumberFromEnvironmentValue, the form
    // of positiveWholeNumberFromEnvironmentValue of kit/jvm-ktor (issue
    // #69).

    @Test
    void positiveWholeNumberFromEnvironmentValueGivesTheDefaultForANullValue() {
        assertEquals(1, IngestController.positiveWholeNumberFromEnvironmentValue(null,
                "OCTOMETER_TRUSTED_PROXY_COUNT", 1));
    }

    @Test
    void positiveWholeNumberFromEnvironmentValueStopsTheAppStartForAZeroValueWithNoRepeatOfTheValue() {
        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> IngestController.positiveWholeNumberFromEnvironmentValue("0", "OCTOMETER_TRUSTED_PROXY_COUNT", 1));
        assertTrue(exception.getMessage().contains("OCTOMETER_TRUSTED_PROXY_COUNT"));
        assertFalse(exception.getMessage().contains("=0"));
    }

    @Test
    void positiveWholeNumberFromEnvironmentValueStopsTheAppStartForANegativeValue() {
        assertThrows(IllegalStateException.class,
                () -> IngestController.positiveWholeNumberFromEnvironmentValue("-1", "OCTOMETER_TRUSTED_PROXY_COUNT", 1));
    }

    @Test
    void positiveWholeNumberFromEnvironmentValueStopsTheAppStartForATextValueWithNoRepeatOfTheValue() {
        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> IngestController.positiveWholeNumberFromEnvironmentValue("many", "OCTOMETER_TRUSTED_PROXY_COUNT", 1));
        assertFalse(exception.getMessage().contains("many"));
    }
}
