package dev.ahprofit.api;

import org.junit.jupiter.api.Test;

import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DonutApiClientTest {
    private static final long OBSERVED_AT = 1_800_000_000_000L;
    private static final String LISTING = """
            {"item":{"id":"minecraft:gilded_blackstone","count":64,"display_name":"","enchants":{"enchantments":{"levels":null},"trim":{"material":"","pattern":""}}},"seller":{"name":"seller","uuid":"seller-uuid"},"price":100000,"time_left":60000}
            """;

    @Test
    void duplicateNullAndMalformedListingEntriesAreHandled() throws Exception {
        String body = "{\"status\":200,\"result\":[null," + LISTING + "," + LISTING + ",{}]}";
        DonutApiClient api = client(response(200, body));

        ParsedPage<AuctionListing> page = api.fetchListings(1, OBSERVED_AT);

        assertEquals(1, page.entries().size());
        assertEquals(1, page.nullEntries());
        assertEquals(1, page.duplicateEntries());
        assertEquals(1, page.malformedEntries());
        assertEquals(64, page.entries().getFirst().quantity());
        assertEquals("1562.5", page.entries().getFirst().unitPrice().stripTrailingZeros().toPlainString());
    }

    @Test
    void emptyPagesAreValidAndMissingResultIsRejected() throws Exception {
        DonutApiClient emptyApi = client(response(200, "{\"status\":200,\"result\":[]}"));
        assertTrue(emptyApi.fetchListings(1, OBSERVED_AT).entries().isEmpty());

        DonutApiException error = assertThrows(DonutApiException.class,
                () -> client(response(200, "{\"status\":200}")).fetchListings(1, OBSERVED_AT));
        assertEquals(DonutApiException.Kind.SCHEMA, error.kind());
    }

    @Test
    void unauthorizedKeyIsReportedWithoutEchoingTheSecret() {
        String secret = "do-not-log-this-api-key";
        DonutApiException error = assertThrows(DonutApiException.class,
                () -> new DonutApiClient((path, json, token) -> response(401, "{\"status\":401}"),
                        () -> secret, new RequestThrottle(Duration.ZERO)).fetchListings(1, OBSERVED_AT));
        assertEquals(DonutApiException.Kind.AUTHENTICATION, error.kind());
        assertFalse(error.getMessage().contains(secret));
    }

    @Test
    void rateLimitHonorsRetryAfter() {
        DonutApiException error = assertThrows(DonutApiException.class,
                () -> client(new ApiResponse(429, Map.of("Retry-After", "12"), "{}"))
                        .fetchTransactions(1, OBSERVED_AT));
        assertEquals(DonutApiException.Kind.RATE_LIMIT, error.kind());
        assertEquals(Duration.ofSeconds(12), error.retryAfter());
    }

    @Test
    void upstreamInvalidListingPage500IsARecognizedBoundary() {
        DonutApiException error = assertThrows(DonutApiException.class,
                () -> client(response(500, "{\"message\":\"The page you entered does not exist\",\"status\":500}"))
                        .fetchListings(2, OBSERVED_AT));
        assertEquals(DonutApiException.Kind.NO_MORE_PAGES, error.kind());
    }

    @Test
    void genericServerErrorsAreNotMistakenForTheEndOfPagination() {
        DonutApiException error = assertThrows(DonutApiException.class,
                () -> client(response(500, "{\"message\":\"database unavailable\"}"))
                        .fetchListings(2, OBSERVED_AT));
        assertEquals(DonutApiException.Kind.SERVER, error.kind());
    }

    @Test
    void timeoutAndMalformedJsonAreTypedFailures() throws Exception {
        DonutApiClient timeout = new DonutApiClient((path, body, key) -> {
            throw new HttpTimeoutException("timed out");
        }, () -> "test-key", new RequestThrottle(Duration.ZERO));
        DonutApiException timeoutError = assertThrows(DonutApiException.class,
                () -> timeout.fetchTransactions(1, OBSERVED_AT));
        assertEquals(DonutApiException.Kind.TIMEOUT, timeoutError.kind());

        DonutApiException malformed = assertThrows(DonutApiException.class,
                () -> client(response(200, "{bad-json" )).fetchTransactions(1, OBSERVED_AT));
        assertEquals(DonutApiException.Kind.SCHEMA, malformed.kind());
    }

    @Test
    void excessiveJsonNestingIsRejectedBeforeDeepParsing() {
        String body = "{\"result\":" + "[".repeat(40) + "0" + "]".repeat(40) + "}";
        DonutApiException error = assertThrows(DonutApiException.class,
                () -> client(response(200, body)).fetchListings(1, OBSERVED_AT));
        assertEquals(DonutApiException.Kind.SCHEMA, error.kind());
    }

    @Test
    void transactionPaginationIsRestrictedToDocumentedPages() {
        assertThrows(IllegalArgumentException.class,
                () -> client(response(200, "{\"result\":[]}" )).fetchTransactions(11, OBSERVED_AT));
    }

    @Test
    void apiKeyIsRequiredBeforeAnyRequestIsSent() {
        AtomicReference<Boolean> wasCalled = new AtomicReference<>(false);
        DonutApiClient api = new DonutApiClient((path, body, key) -> {
            wasCalled.set(true);
            return response(200, "{\"result\":[]}");
        }, () -> "", new RequestThrottle(Duration.ZERO));
        DonutApiException error = assertThrows(DonutApiException.class, () -> api.fetchListings(1, OBSERVED_AT));
        assertEquals(DonutApiException.Kind.MISSING_KEY, error.kind());
        assertFalse(wasCalled.get());
    }

    private static DonutApiClient client(ApiResponse response) {
        return new DonutApiClient((path, body, token) -> response, () -> "test-key", new RequestThrottle(Duration.ZERO));
    }

    private static ApiResponse response(int status, String body) {
        return new ApiResponse(status, Map.of(), body);
    }
}
