package octometer.kit.core.ingest;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import octometer.kit.core.store.DeletionResult;
import octometer.kit.core.store.EventLogStore;
import octometer.kit.core.store.InMemoryEventLogStore;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests of {@link IngestRequestProcessor} against design section 4.2 and
 * contract rules C12 to C19, C32, C33, C36, and C37 (issue #69). This
 * class holds each test that {@code IngestRouteTest} of `kit/jvm-ktor`
 * held before this issue for the ORDER of the checks; a numeric
 * acceptance criterion of one check on its own (for example the exact 30,
 * 120, 300, 900, or 120 limits) stays in the dedicated test class of that
 * check ({@code IngestRateLimiterTest}, {@code AnonymousMinuteLimiterTest},
 * {@code AnonymousDailyCapTest}, {@code BotUserAgentFilterTest}), so this
 * class gives each limiter a small, custom limit instead, the same
 * pattern that {@code IngestRouteTest} already used for its own order
 * tests.
 */
class IngestRequestProcessorTest {

    private static final Instant START = Instant.parse("2026-09-21T10:00:00Z");

    private static final String VALID_BODY = "{\"sessionId\":\"0b0e4e0e-6a55-4c1e-9a53-0c1f6f7a2d11\","
            + "\"clicks\":[{\"element\":\"checkout.save\",\"ageMs\":1200}]}";

    private static IngestRequestProcessor processor(EventLogStore store, IngestSettings settings, Clock clock,
            IngestRateLimiter rateLimiter, AnonymousMinuteLimiter minuteLimiter, AnonymousDailyCap dailyCap,
            String clientIpHeaderName, int trustedProxyCount) {
        return new IngestRequestProcessor(store, settings, clock, rateLimiter, minuteLimiter, dailyCap,
                clientIpHeaderName, trustedProxyCount);
    }

    private static IngestRequestProcessor defaultProcessor(EventLogStore store, IngestSettings settings,
            Clock clock) {
        return processor(store, settings, clock, new IngestRateLimiter(clock),
                new AnonymousMinuteLimiter(clock, 300, 900, 120), new AnonymousDailyCap(clock, 20_000, 2_000), null,
                1);
    }

    // The tests below check contract rule C12 (step 1: the content type).

    @Test
    void textPlainGives415() {
        IngestRequestProcessor processor = defaultProcessor(new InMemoryEventLogStore(), new IngestSettings(true),
                Clock.fixed(START, ZoneOffset.UTC));
        IngestRequestProcessor.BeforeBodyOutcome outcome = processor.beforeBody(new FakeRequestView()
                .contentType("text/plain").userId("user-1"));

        assertTrue(outcome.mustRespondNow());
        assertEquals(415, outcome.result().statusCode());
    }

    @Test
    void anAbsentContentTypeGives415() {
        IngestRequestProcessor processor = defaultProcessor(new InMemoryEventLogStore(), new IngestSettings(true),
                Clock.fixed(START, ZoneOffset.UTC));
        IngestRequestProcessor.BeforeBodyOutcome outcome = processor.beforeBody(new FakeRequestView()
                .contentType(null).userId("user-1"));

        assertTrue(outcome.mustRespondNow());
        assertEquals(415, outcome.result().statusCode());
    }

    @Test
    void aMalformedContentTypeValueGives415() {
        IngestRequestProcessor processor = defaultProcessor(new InMemoryEventLogStore(), new IngestSettings(true),
                Clock.fixed(START, ZoneOffset.UTC));

        assertEquals(415, processor.beforeBody(new FakeRequestView().contentType("@@@").userId("user-1"))
                .result().statusCode());
        assertEquals(415, processor.beforeBody(new FakeRequestView().contentType("json").userId("user-1"))
                .result().statusCode());
    }

    @Test
    void applicationJsonWithACharsetParameterContinues() {
        IngestRequestProcessor processor = defaultProcessor(new InMemoryEventLogStore(), new IngestSettings(true),
                Clock.fixed(START, ZoneOffset.UTC));
        IngestRequestProcessor.BeforeBodyOutcome outcome = processor.beforeBody(new FakeRequestView()
                .contentType("application/json; charset=utf-8").userId("user-1"));

        assertFalse(outcome.mustRespondNow());
    }

    // The tests below check that resolveUserId (contract rule C6) runs
    // once, right after the content type check, and never earlier and
    // never twice.

    @Test
    void resolveUserIdNeverRunsForARequestThatThe415CheckAlreadyRejects() {
        IngestRequestProcessor processor = defaultProcessor(new InMemoryEventLogStore(), new IngestSettings(true),
                Clock.fixed(START, ZoneOffset.UTC));
        AtomicBoolean supplierCalled = new AtomicBoolean(false);

        IngestRequestProcessor.BeforeBodyOutcome outcome = processor.beforeBody(new FakeRequestView()
                .contentType("text/plain")
                .userIdSupplier(() -> {
                    supplierCalled.set(true);
                    return null;
                }));

        assertEquals(415, outcome.result().statusCode());
        assertFalse(supplierCalled.get(), "resolveUserId must not run once the content type check already rejects.");
    }

    @Test
    void aResolveUserIdThatThrowsPropagatesUncaughtForTheAdapterToMapTo500() {
        IngestRequestProcessor processor = defaultProcessor(new InMemoryEventLogStore(), new IngestSettings(true),
                Clock.fixed(START, ZoneOffset.UTC));
        Supplier<String> throwingSupplier = () -> {
            throw new IllegalArgumentException("the app resolver fails");
        };

        assertThrows(IllegalArgumentException.class,
                () -> processor.beforeBody(new FakeRequestView().userIdSupplier(throwingSupplier)));
    }

    // The tests below check the order of design decision D20 (the rate
    // limiter, step 2) against the bot filter (step 4) and the declared
    // size check (step 5): a client already at its limit must never reach
    // either later check.

    @Test
    void aClientAlreadyLimitedByTheRateLimiterNeverReachesTheBotFilterOrTheDeclaredSizeCheck() {
        Clock clock = Clock.fixed(START, ZoneOffset.UTC);
        IngestRateLimiter rateLimiter = new IngestRateLimiter(clock);
        for (int i = 1; i <= 30; i++) {
            assertEquals(RateLimitResult.ALLOWED, rateLimiter.check("user-1", ""));
        }
        IngestRequestProcessor processor = processor(new InMemoryEventLogStore(), new IngestSettings(true), clock,
                rateLimiter, new AnonymousMinuteLimiter(clock, 300, 900, 120),
                new AnonymousDailyCap(clock, 20_000, 2_000), null, 1);

        IngestRequestProcessor.BeforeBodyOutcome outcome = processor.beforeBody(new FakeRequestView()
                .userId("user-1")
                .header("User-Agent", "Mozilla/5.0 (compatible; Googlebot/2.1)")
                .declaredContentLengthBytes(1_000_000L));

        assertEquals(429, outcome.result().statusCode(),
                "The rate limiter must reject the request before the bot filter or the size check runs.");
    }

    @Test
    void theAnonymousMinuteRequestCounterAnswers429BeforeTheBotFilterOrTheDeclaredSizeCheck() {
        Clock clock = Clock.fixed(START, ZoneOffset.UTC);
        AnonymousMinuteLimiter minuteLimiter = new AnonymousMinuteLimiter(clock, 2, 900, 120);
        IngestRequestProcessor processor = processor(new InMemoryEventLogStore(), new IngestSettings(true), clock,
                new IngestRateLimiter(clock), minuteLimiter, new AnonymousDailyCap(clock, 20_000, 2_000), null, 1);

        for (int i = 1; i <= 2; i++) {
            IngestRequestProcessor.BeforeBodyOutcome outcome = processor.beforeBody(new FakeRequestView());
            assertFalse(outcome.mustRespondNow(), "Request " + i + " of 2 must continue.");
        }

        IngestRequestProcessor.BeforeBodyOutcome outcome = processor.beforeBody(new FakeRequestView()
                .header("User-Agent", "Mozilla/5.0 (compatible; Googlebot/2.1)")
                .declaredContentLengthBytes(1_000_000L));
        assertEquals(429, outcome.result().statusCode());
    }

    @Test
    void aSignedInUserNeverPaysTheAnonymousMinuteLimiter() {
        Clock clock = Clock.fixed(START, ZoneOffset.UTC);
        AnonymousMinuteLimiter minuteLimiter = new AnonymousMinuteLimiter(clock, 1, 1, 1);
        IngestRequestProcessor processor = processor(new InMemoryEventLogStore(), new IngestSettings(true), clock,
                new IngestRateLimiter(clock), minuteLimiter, new AnonymousDailyCap(clock, 20_000, 2_000), null, 1);

        for (int i = 1; i <= 5; i++) {
            IngestRequestProcessor.BeforeBodyOutcome outcome = processor.beforeBody(new FakeRequestView()
                    .userId("user-1"));
            assertFalse(outcome.mustRespondNow(), "Request " + i + " of 5 must continue.");
        }
    }

    @Test
    void theAnonymousMinuteLimiterStaysOffWhenTheAppRecordsNoAnonymousClick() {
        Clock clock = Clock.fixed(START, ZoneOffset.UTC);
        AnonymousMinuteLimiter minuteLimiter = new AnonymousMinuteLimiter(clock, 1, 1, 1);
        IngestRequestProcessor processor = processor(new InMemoryEventLogStore(), new IngestSettings(false), clock,
                new IngestRateLimiter(clock), minuteLimiter, new AnonymousDailyCap(clock, 20_000, 2_000), null, 1);

        for (int i = 1; i <= 5; i++) {
            IngestRequestProcessor.BeforeBodyOutcome outcome = processor.beforeBody(new FakeRequestView());
            assertFalse(outcome.mustRespondNow(),
                    "Request " + i + " of 5 must continue with a 1-request limiter, because the limiter never runs.");
        }
    }

    // The tests below check the bot filter (step 4, design decision D43)
    // against the declared size check (step 5): a robot batch must never
    // reach the size check.

    @Test
    void theBotFilterAnswers204AndNeverReachesTheDeclaredSizeCheck() {
        IngestRequestProcessor processor = defaultProcessor(new InMemoryEventLogStore(), new IngestSettings(true),
                Clock.fixed(START, ZoneOffset.UTC));

        IngestRequestProcessor.BeforeBodyOutcome outcome = processor.beforeBody(new FakeRequestView()
                .header("User-Agent", "Mozilla/5.0 (compatible; Googlebot/2.1)")
                .declaredContentLengthBytes(1_000_000L));

        assertEquals(204, outcome.result().statusCode());
    }

    @Test
    void anAbsentUserAgentHeaderPassesTheBotFilter() {
        IngestRequestProcessor processor = defaultProcessor(new InMemoryEventLogStore(), new IngestSettings(true),
                Clock.fixed(START, ZoneOffset.UTC));

        IngestRequestProcessor.BeforeBodyOutcome outcome = processor.beforeBody(new FakeRequestView()
                .userId("user-1"));

        assertFalse(outcome.mustRespondNow());
    }

    @Test
    void aBotTokenPastTheFirst512CharactersOfAnEightKilobyteUserAgentValueNeverMatches() {
        IngestRequestProcessor processor = defaultProcessor(new InMemoryEventLogStore(), new IngestSettings(true),
                Clock.fixed(START, ZoneOffset.UTC));
        String longUserAgent = "x".repeat(600) + "bot" + "x".repeat(7 * 1024);

        IngestRequestProcessor.BeforeBodyOutcome outcome = processor.beforeBody(new FakeRequestView()
                .header("User-Agent", longUserAgent)
                .userId("user-1"));

        assertFalse(outcome.mustRespondNow(), "The bot token sits past the 512-character cut, so it must never match.");
    }

    @Test
    void anEightKilobyteUserAgentValueWithABotTokenNearItsStartStillGives204() {
        IngestRequestProcessor processor = defaultProcessor(new InMemoryEventLogStore(), new IngestSettings(true),
                Clock.fixed(START, ZoneOffset.UTC));
        String longUserAgent = "bot" + "x".repeat(8 * 1024);

        IngestRequestProcessor.BeforeBodyOutcome outcome = processor.beforeBody(new FakeRequestView()
                .header("User-Agent", longUserAgent));

        assertEquals(204, outcome.result().statusCode());
    }

    @Test
    void aRobotDropWritesOneDebugLineWithoutTheHeaderValue() {
        CapturingLoggerFinder.clear();
        Clock clock = Clock.fixed(START, ZoneOffset.UTC);
        IngestRequestProcessor processor = defaultProcessor(new InMemoryEventLogStore(), new IngestSettings(true),
                clock);
        String userAgent = "Mozilla/5.0 (compatible; Googlebot/2.1)";

        processor.beforeBody(new FakeRequestView().header("User-Agent", userAgent));

        assertEquals(1, CapturingLoggerFinder.messages().size());
        assertFalse(CapturingLoggerFinder.messages().peek().contains(userAgent));
    }

    @Test
    void aSecondRobotDropInsideOneHourWritesNoLineAndTheNextHourReportsBoth() {
        CapturingLoggerFinder.clear();
        MutableClock clock = new MutableClock(START);
        IngestRequestProcessor processor = defaultProcessor(new InMemoryEventLogStore(), new IngestSettings(true),
                clock);
        String userAgent = "Mozilla/5.0 (compatible; Googlebot/2.1)";

        processor.beforeBody(new FakeRequestView().header("User-Agent", userAgent));
        assertEquals(1, CapturingLoggerFinder.messages().size());

        processor.beforeBody(new FakeRequestView().header("User-Agent", userAgent));
        assertEquals(1, CapturingLoggerFinder.messages().size(), "The second drop of the same hour must write no line.");

        clock.advance(Duration.ofMinutes(61));
        processor.beforeBody(new FakeRequestView().header("User-Agent", userAgent));
        assertEquals(2, CapturingLoggerFinder.messages().size());
    }

    // The tests below check the declared size check (step 5, contract
    // rule C18): the declared length only, with no body read.

    @Test
    void aDeclaredContentLengthAboveTheLimitGives400WithNoBodyRead() {
        IngestRequestProcessor processor = defaultProcessor(new InMemoryEventLogStore(), new IngestSettings(true),
                Clock.fixed(START, ZoneOffset.UTC));

        IngestRequestProcessor.BeforeBodyOutcome outcome = processor.beforeBody(new FakeRequestView()
                .userId("user-1")
                .declaredContentLengthBytes(16_385L));

        assertEquals(400, outcome.result().statusCode());
    }

    @Test
    void aDeclaredContentLengthAtTheLimitContinues() {
        IngestRequestProcessor processor = defaultProcessor(new InMemoryEventLogStore(), new IngestSettings(true),
                Clock.fixed(START, ZoneOffset.UTC));

        IngestRequestProcessor.BeforeBodyOutcome outcome = processor.beforeBody(new FakeRequestView()
                .userId("user-1")
                .declaredContentLengthBytes(16_384L));

        assertFalse(outcome.mustRespondNow());
    }

    @Test
    void anAbsentDeclaredContentLengthContinues() {
        IngestRequestProcessor processor = defaultProcessor(new InMemoryEventLogStore(), new IngestSettings(true),
                Clock.fixed(START, ZoneOffset.UTC));

        IngestRequestProcessor.BeforeBodyOutcome outcome = processor.beforeBody(new FakeRequestView()
                .userId("user-1")
                .declaredContentLengthBytes(null));

        assertFalse(outcome.mustRespondNow());
    }

    // The tests below check afterBody: the real body size and the parse
    // (step 6), the anonymous per-minute entry counters (step 7), the
    // design decision D19 drop (step 8), the daily anonymous caps (step
    // 9), and the store call (step 10).

    @Test
    void aValidBatchGives204AndStoresTheEvents() {
        InMemoryEventLogStore store = new InMemoryEventLogStore();
        IngestRequestProcessor processor = defaultProcessor(store, new IngestSettings(true),
                Clock.fixed(START, ZoneOffset.UTC));

        IngestRequestProcessor.BeforeBodyOutcome outcome = processor.beforeBody(new FakeRequestView()
                .userId("user-1"));
        assertFalse(outcome.mustRespondNow());
        IngestResult result = processor.afterBody(outcome, VALID_BODY);

        assertEquals(204, result.statusCode());
        assertEquals(1, store.events().size());
        assertEquals("checkout.save", store.events().get(0).element());
        assertEquals("user-1", store.events().get(0).userId());
    }

    @Test
    void anInvalidBodyGives400() {
        InMemoryEventLogStore store = new InMemoryEventLogStore();
        IngestRequestProcessor processor = defaultProcessor(store, new IngestSettings(true),
                Clock.fixed(START, ZoneOffset.UTC));
        IngestRequestProcessor.BeforeBodyOutcome outcome = processor.beforeBody(new FakeRequestView()
                .userId("user-1"));

        IngestResult result = processor.afterBody(outcome, "{\"sessionId\":\"not-a-uuid\",\"clicks\":[]}");

        assertEquals(400, result.statusCode());
        assertTrue(store.events().isEmpty());
    }

    @Test
    void callingAfterBodyOnceBeforeBodyAlreadyGaveAResultThrowsIllegalStateException() {
        IngestRequestProcessor processor = defaultProcessor(new InMemoryEventLogStore(), new IngestSettings(true),
                Clock.fixed(START, ZoneOffset.UTC));
        IngestRequestProcessor.BeforeBodyOutcome outcome = processor.beforeBody(new FakeRequestView()
                .contentType("text/plain"));
        assertTrue(outcome.mustRespondNow());

        assertThrows(IllegalStateException.class, () -> processor.afterBody(outcome, VALID_BODY));
    }

    @Test
    void aBodyOfExactly16384BytesGives204() {
        InMemoryEventLogStore store = new InMemoryEventLogStore();
        IngestRequestProcessor processor = defaultProcessor(store, new IngestSettings(true),
                Clock.fixed(START, ZoneOffset.UTC));
        IngestRequestProcessor.BeforeBodyOutcome outcome = processor.beforeBody(new FakeRequestView()
                .userId("user-1"));
        String body = exactlySizedBody(16_384);

        IngestResult result = processor.afterBody(outcome, body);

        assertEquals(16_384, body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        assertEquals(204, result.statusCode());
    }

    @Test
    void aBodyOfExactly16385BytesGives400() {
        IngestRequestProcessor processor = defaultProcessor(new InMemoryEventLogStore(), new IngestSettings(true),
                Clock.fixed(START, ZoneOffset.UTC));
        IngestRequestProcessor.BeforeBodyOutcome outcome = processor.beforeBody(new FakeRequestView()
                .userId("user-1"));
        String body = exactlySizedBody(16_385);

        IngestResult result = processor.afterBody(outcome, body);

        assertEquals(16_385, body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        assertEquals(400, result.statusCode());
    }

    @Test
    void theClickEntryCounterAnswers429OnlyAfterTheParseAndCountsEachElementThatIsNotOctoSessionStart() {
        InMemoryEventLogStore store = new InMemoryEventLogStore();
        Clock clock = Clock.fixed(START, ZoneOffset.UTC);
        AnonymousMinuteLimiter minuteLimiter = new AnonymousMinuteLimiter(clock, 900, 2, 120);
        IngestRequestProcessor processor = processor(store, new IngestSettings(true), clock,
                new IngestRateLimiter(clock), minuteLimiter, new AnonymousDailyCap(clock, 20_000, 2_000), null, 1);
        String twoClickBody = "{\"sessionId\":\"0b0e4e0e-6a55-4c1e-9a53-0c1f6f7a2d11\","
                + "\"clicks\":[{\"element\":\"checkout.save\",\"ageMs\":1200},"
                + "{\"element\":\"checkout.next\",\"ageMs\":1200}]}";

        IngestRequestProcessor.BeforeBodyOutcome firstOutcome = processor.beforeBody(new FakeRequestView());
        IngestResult firstResult = processor.afterBody(firstOutcome, twoClickBody);
        assertEquals(204, firstResult.statusCode());
        assertEquals(2, store.events().size());

        IngestRequestProcessor.BeforeBodyOutcome secondOutcome = processor.beforeBody(new FakeRequestView());
        IngestResult secondResult = processor.afterBody(secondOutcome, VALID_BODY);
        assertEquals(429, secondResult.statusCode());
        assertEquals(2, store.events().size(), "The rejected batch must add no new event.");
    }

    @Test
    void theSessionStartCounterAnswers429IndependentlyOfTheClickEntryCounter() {
        InMemoryEventLogStore store = new InMemoryEventLogStore();
        Clock clock = Clock.fixed(START, ZoneOffset.UTC);
        AnonymousMinuteLimiter minuteLimiter = new AnonymousMinuteLimiter(clock, 900, 900, 1);
        IngestRequestProcessor processor = processor(store, new IngestSettings(true), clock,
                new IngestRateLimiter(clock), minuteLimiter, new AnonymousDailyCap(clock, 20_000, 2_000), null, 1);
        String sessionStartBody = "{\"sessionId\":\"0b0e4e0e-6a55-4c1e-9a53-0c1f6f7a2d11\","
                + "\"clicks\":[{\"element\":\"octo:session-start\",\"ageMs\":0}]}";

        IngestResult first = processor.afterBody(processor.beforeBody(new FakeRequestView()), sessionStartBody);
        assertEquals(204, first.statusCode());

        IngestResult second = processor.afterBody(processor.beforeBody(new FakeRequestView()), sessionStartBody);
        assertEquals(429, second.statusCode());

        IngestResult third = processor.afterBody(processor.beforeBody(new FakeRequestView()), VALID_BODY);
        assertEquals(204, third.statusCode(), "The click entry counter of the same key still has its own budget.");
    }

    @Test
    void theD19DropGives204AndStoresNothingWithNoUserIdAndAnonymousClicksOff() {
        InMemoryEventLogStore store = new InMemoryEventLogStore();
        IngestRequestProcessor processor = defaultProcessor(store, new IngestSettings(false),
                Clock.fixed(START, ZoneOffset.UTC));

        IngestResult result = processor.afterBody(processor.beforeBody(new FakeRequestView()), VALID_BODY);

        assertEquals(204, result.statusCode());
        assertTrue(store.events().isEmpty());
    }

    @Test
    void aBatchAboveTheDailyCapGives204AndTheStoreHoldsNoNewEvent() {
        InMemoryEventLogStore store = new InMemoryEventLogStore();
        Clock clock = Clock.fixed(START, ZoneOffset.UTC);
        AnonymousDailyCap dailyCap = new AnonymousDailyCap(clock, 1, 100);
        IngestRequestProcessor processor = processor(store, new IngestSettings(true), clock,
                new IngestRateLimiter(clock), new AnonymousMinuteLimiter(clock, 300, 900, 120), dailyCap, null, 1);

        IngestResult first = processor.afterBody(processor.beforeBody(new FakeRequestView()), VALID_BODY);
        assertEquals(204, first.statusCode());
        assertEquals(1, store.events().size());

        IngestResult second = processor.afterBody(processor.beforeBody(new FakeRequestView()), VALID_BODY);
        assertEquals(204, second.statusCode());
        assertEquals(1, store.events().size(), "The batch above the daily cap must add no new event.");
    }

    @Test
    void aSignedInUserNeverPaysTheDailyAnonymousCap() {
        InMemoryEventLogStore store = new InMemoryEventLogStore();
        Clock clock = Clock.fixed(START, ZoneOffset.UTC);
        AnonymousDailyCap dailyCap = new AnonymousDailyCap(clock, 1, 1);
        IngestRequestProcessor processor = processor(store, new IngestSettings(true), clock,
                new IngestRateLimiter(clock), new AnonymousMinuteLimiter(clock, 300, 900, 120), dailyCap, null, 1);

        for (int i = 1; i <= 3; i++) {
            IngestResult result = processor.afterBody(
                    processor.beforeBody(new FakeRequestView().userId("user-1")), VALID_BODY);
            assertEquals(204, result.statusCode(), "Request " + i + " of 3 must pass.");
        }
        assertEquals(3, store.events().size());
    }

    @Test
    void anEmptyBatchGives204AndCallsNoCheckBelowTheParse() {
        InMemoryEventLogStore store = new InMemoryEventLogStore();
        Clock clock = Clock.fixed(START, ZoneOffset.UTC);
        AnonymousDailyCap dailyCap = new AnonymousDailyCap(clock, 1, 1);
        IngestRequestProcessor processor = processor(store, new IngestSettings(true), clock,
                new IngestRateLimiter(clock), new AnonymousMinuteLimiter(clock, 300, 900, 120), dailyCap, null, 1);
        String emptyBatchBody = "{\"sessionId\":\"0b0e4e0e-6a55-4c1e-9a53-0c1f6f7a2d11\",\"clicks\":[]}";

        IngestResult result = processor.afterBody(processor.beforeBody(new FakeRequestView()), emptyBatchBody);

        assertEquals(204, result.statusCode());
        assertTrue(store.events().isEmpty());
    }

    @Test
    void aStoreDefectGives500ByThrowingUncaught() {
        EventLogStore store = new EventLogStore() {
            @Override
            public void append(List<IngestEvent> events, String userId) {
                throw new IllegalStateException("a store defect");
            }

            @Override
            public DeletionResult deleteByUserId(String userId) {
                throw new UnsupportedOperationException();
            }
        };
        IngestRequestProcessor processor = defaultProcessor(store, new IngestSettings(true),
                Clock.fixed(START, ZoneOffset.UTC));
        IngestRequestProcessor.BeforeBodyOutcome outcome = processor.beforeBody(new FakeRequestView()
                .userId("user-1"));

        assertThrows(IllegalStateException.class, () -> processor.afterBody(outcome, VALID_BODY));
    }

    @Test
    void aStoreThatThrowsAnIngestExceptionIsWrappedInIllegalStateException() {
        IngestException cause = new IngestException(IngestException.Reason.INVALID_JSON, "the store throws this by mistake");
        EventLogStore store = new EventLogStore() {
            @Override
            public void append(List<IngestEvent> events, String userId) {
                throw cause;
            }

            @Override
            public DeletionResult deleteByUserId(String userId) {
                throw new UnsupportedOperationException();
            }
        };
        IngestRequestProcessor processor = defaultProcessor(store, new IngestSettings(true),
                Clock.fixed(START, ZoneOffset.UTC));
        IngestRequestProcessor.BeforeBodyOutcome outcome = processor.beforeBody(new FakeRequestView()
                .userId("user-1"));

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> processor.afterBody(outcome, VALID_BODY));
        assertSame(cause, thrown.getCause());
    }

    @Test
    void aBatchThatTheStoreDropsAboveTheEventCapStillGives204() {
        EventLogStore store = new EventLogStore() {
            @Override
            public void append(List<IngestEvent> events, String userId) {
                // The event cap of design decision D21: the store drops
                // the whole batch and writes no event.
            }

            @Override
            public DeletionResult deleteByUserId(String userId) {
                return new DeletionResult(0, 0, true);
            }
        };
        IngestRequestProcessor processor = defaultProcessor(store, new IngestSettings(true),
                Clock.fixed(START, ZoneOffset.UTC));
        IngestRequestProcessor.BeforeBodyOutcome outcome = processor.beforeBody(new FakeRequestView()
                .userId("user-1"));

        IngestResult result = processor.afterBody(outcome, VALID_BODY);

        assertEquals(204, result.statusCode());
    }

    @Test
    void aUserIdThatBreaksContractRuleC6GivesIllegalStateExceptionAndNeverReachesTheStore() {
        AtomicBoolean storeCalled = new AtomicBoolean(false);
        EventLogStore store = new EventLogStore() {
            @Override
            public void append(List<IngestEvent> events, String userId) {
                storeCalled.set(true);
            }

            @Override
            public DeletionResult deleteByUserId(String userId) {
                throw new UnsupportedOperationException();
            }
        };
        IngestRequestProcessor processor = defaultProcessor(store, new IngestSettings(true),
                Clock.fixed(START, ZoneOffset.UTC));
        IngestRequestProcessor.BeforeBodyOutcome outcome = processor.beforeBody(new FakeRequestView().userId(""));

        assertThrows(IllegalStateException.class, () -> processor.afterBody(outcome, VALID_BODY));
        assertFalse(storeCalled.get());
    }

    // The tests below check the client address of design decision D20
    // (issue #33) and design decision D43 (issue #116): the header that
    // the constructor's clientIpHeaderName names, at the position that
    // trustedProxyCount counts from the right, or the fallback remote
    // address. Each test gives the minute limiter a request limit of 1,
    // so a second request of the same resolved key gives 429, and a
    // second request of a different resolved key still passes; that
    // difference proves the resolved key of each request with no need
    // for a slow, repeated loop up to a real acceptance number.

    private static IngestRequestProcessor addressProcessor(String clientIpHeaderName, int trustedProxyCount) {
        Clock clock = Clock.fixed(START, ZoneOffset.UTC);
        return processor(new InMemoryEventLogStore(), new IngestSettings(true), clock, new IngestRateLimiter(clock),
                new AnonymousMinuteLimiter(clock, 1, 900, 120), new AnonymousDailyCap(clock, 20_000, 2_000),
                clientIpHeaderName, trustedProxyCount);
    }

    @Test
    void twoDifferentClientIpHeaderValuesGetTwoKeys() {
        IngestRequestProcessor processor = addressProcessor("X-Client-Ip", 1);

        assertFalse(processor.beforeBody(new FakeRequestView().header("X-Client-Ip", "203.0.113.9"))
                .mustRespondNow());
        assertEquals(429, processor.beforeBody(new FakeRequestView().header("X-Client-Ip", "203.0.113.9"))
                .result().statusCode(), "The same header value must share one key.");
        assertFalse(processor.beforeBody(new FakeRequestView().header("X-Client-Ip", "198.51.100.2"))
                .mustRespondNow(), "A different header value is a different key, with its own fresh counter.");
    }

    @Test
    void withNoClientIpHeaderNameASpoofedHeaderNeverChangesTheKey() {
        IngestRequestProcessor processor = addressProcessor(null, 1);

        assertFalse(processor.beforeBody(new FakeRequestView()
                .remoteAddress("192.0.2.9")
                .header("X-Forwarded-For", "203.0.113.1"))
                .mustRespondNow());
        assertEquals(429, processor.beforeBody(new FakeRequestView()
                .remoteAddress("192.0.2.9")
                .header("X-Forwarded-For", "203.0.113.250"))
                .result().statusCode(), "With no configured header, the key is always the remote address.");
    }

    @Test
    void aForgedLeftElementNeverChangesTheKeyWithTheDefaultTrustedProxyCount() {
        IngestRequestProcessor processor = addressProcessor("X-Forwarded-For", 1);

        assertFalse(processor.beforeBody(new FakeRequestView()
                .header("X-Forwarded-For", "203.0.113.1, 198.51.100.9"))
                .mustRespondNow());
        assertEquals(429, processor.beforeBody(new FakeRequestView()
                .header("X-Forwarded-For", "203.0.113.999, 198.51.100.9"))
                .result().statusCode(), "The nearest proxy's own element (the last one) must stay the key.");
    }

    @Test
    void trustedProxyCount2ReadsTheSecondValueFromTheRight() {
        IngestRequestProcessor processor = addressProcessor("X-Forwarded-For", 2);

        assertFalse(processor.beforeBody(new FakeRequestView()
                .header("X-Forwarded-For", "203.0.113.1, 198.51.100.9, 192.0.2.1"))
                .mustRespondNow());
        assertEquals(429, processor.beforeBody(new FakeRequestView()
                .header("X-Forwarded-For", "203.0.113.250, 198.51.100.9, 192.0.2.250"))
                .result().statusCode());
    }

    @Test
    void aTrustedProxyCountAboveTheHeaderListLengthFallsBackToTheRemoteAddress() {
        IngestRequestProcessor processor = addressProcessor("X-Forwarded-For", 3);

        assertFalse(processor.beforeBody(new FakeRequestView()
                .remoteAddress("192.0.2.9")
                .header("X-Forwarded-For", "203.0.113.1, 198.51.100.1"))
                .mustRespondNow());
        assertEquals(429, processor.beforeBody(new FakeRequestView()
                .remoteAddress("192.0.2.9")
                .header("X-Forwarded-For", "203.0.113.250, 198.51.100.250"))
                .result().statusCode(), "The count sits above the list length, so both requests fall back to the remote address.");
    }

    @Test
    void aClientIpHeaderValueAbove64CharactersFallsBackToTheRemoteAddress() {
        IngestRequestProcessor processor = addressProcessor("X-Client-Ip", 1);
        String longValue = "9".repeat(65);

        assertFalse(processor.beforeBody(new FakeRequestView()
                .remoteAddress("192.0.2.9")
                .header("X-Client-Ip", longValue + "-1"))
                .mustRespondNow());
        assertEquals(429, processor.beforeBody(new FakeRequestView()
                .remoteAddress("192.0.2.9")
                .header("X-Client-Ip", longValue + "-2"))
                .result().statusCode(), "Both values sit above 64 characters, so both fall back to the remote address.");
    }

    @Test
    void aClientIpHeaderValueWithNoIpAddressFormFallsBackToTheRemoteAddress() {
        IngestRequestProcessor processor = addressProcessor("X-Client-Ip", 1);

        assertFalse(processor.beforeBody(new FakeRequestView()
                .remoteAddress("192.0.2.9")
                .header("X-Client-Ip", "not-an-ip-address"))
                .mustRespondNow());
        assertEquals(429, processor.beforeBody(new FakeRequestView()
                .remoteAddress("192.0.2.9")
                .header("X-Client-Ip", "still-not-an-ip-address"))
                .result().statusCode());
    }

    @Test
    void aRequestWithNoClientIpHeaderFallsBackToTheRemoteAddress() {
        IngestRequestProcessor processor = addressProcessor("X-Client-Ip", 1);

        assertFalse(processor.beforeBody(new FakeRequestView().remoteAddress("192.0.2.9")).mustRespondNow());
        assertEquals(429, processor.beforeBody(new FakeRequestView().remoteAddress("192.0.2.9"))
                .result().statusCode());
    }

    @Test
    void anIpv6ShapedClientIpHeaderValueIsAcceptedAsTheKey() {
        IngestRequestProcessor processor = addressProcessor("X-Client-Ip", 1);

        assertFalse(processor.beforeBody(new FakeRequestView()
                .remoteAddress("192.0.2.9")
                .header("X-Client-Ip", "2001:db8::1"))
                .mustRespondNow());
        assertEquals(429, processor.beforeBody(new FakeRequestView()
                .remoteAddress("192.0.2.9")
                .header("X-Client-Ip", "2001:db8::1"))
                .result().statusCode(), "The same IPv6-shaped value must share one key, not fall back to the remote address.");
    }

    @Test
    void aMarkerAddressStaysOutOfTheLogLineOfAMinuteLimiter429() {
        CapturingLoggerFinder.clear();
        String markerAddress = "203.0.113.88";
        Clock clock = Clock.fixed(START, ZoneOffset.UTC);
        AnonymousMinuteLimiter minuteLimiter = new AnonymousMinuteLimiter(clock, 1, 900, 120);
        IngestRequestProcessor processor = processor(new InMemoryEventLogStore(), new IngestSettings(true), clock,
                new IngestRateLimiter(clock), minuteLimiter, new AnonymousDailyCap(clock, 20_000, 2_000),
                "X-Client-Ip", 1);

        processor.beforeBody(new FakeRequestView().header("X-Client-Ip", markerAddress));
        processor.beforeBody(new FakeRequestView().header("X-Client-Ip", markerAddress));

        assertTrue(CapturingLoggerFinder.messages().stream().noneMatch(message -> message.contains(markerAddress)),
                "No log line of this class must hold the client address.");
    }

    private static String exactlySizedBody(int totalBytes) {
        String prefix = "{\"sessionId\":\"0b0e4e0e-6a55-4c1e-9a53-0c1f6f7a2d11\",\"clicks\":[{\"element\":"
                + "\"checkout.save\",\"ageMs\":1200,\"pad\":\"";
        String suffix = "\"}]}";
        int padLength = totalBytes - prefix.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                - suffix.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        assertTrue(padLength >= 0, "The prefix and the suffix alone are already " + totalBytes + " bytes or more.");
        return prefix + "a".repeat(padLength) + suffix;
    }

    /** A {@link Clock} that a test can move forward, for the bot-drop throttle test. */
    private static final class MutableClock extends Clock {
        private volatile Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException("This test clock always uses UTC.");
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
