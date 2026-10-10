package dev.ahprofit.scanner;

import dev.ahprofit.api.ApiResponse;
import dev.ahprofit.api.ApiTransport;
import dev.ahprofit.api.DonutApiClient;
import dev.ahprofit.api.RequestThrottle;
import dev.ahprofit.config.ConfigManager;
import dev.ahprofit.database.MarketDatabase;
import dev.ahprofit.market.OpportunityEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScannerServiceTest {
    private static final String EMPTY_PAGE = "{\"status\":200,\"result\":[]}";
    private static final String ONE_LISTING = """
            {"status":200,"result":[{"item":{"id":"minecraft:gilded_blackstone","count":64},"seller":{"name":"seller","uuid":"seller-uuid"},"price":1000,"time_left":43200000}]}
            """;

    @TempDir
    Path temporaryDirectory;

    @Test
    void startStopAndRepeatedToggleKeepNetworkWorkOffAndCancelable() throws Exception {
        Path configDirectory = configurationWithTestKey("scanner-toggle");
        AtomicInteger calls = new AtomicInteger();
        CopyOnWriteArrayList<String> paths = new CopyOnWriteArrayList<>();
        ApiTransport transport = (path, body, key) -> {
            calls.incrementAndGet();
            paths.add(path);
            return response(200, EMPTY_PAGE);
        };

        try (ConfigManager config = new ConfigManager(configDirectory, ignored -> { }, ignored -> { });
             ScannerService scanner = scanner(config, "scanner-toggle", transport)) {
            assertTrue(scanner.start());
            assertFalse(scanner.start());
            await(() -> scanner.snapshot().apiStatus() == ApiStatus.CONNECTED);
            assertEquals(3, calls.get());
            assertTrue(scanner.snapshot().lastSuccessfulFetchMillis() > 0);

            assertTrue(scanner.stop());
            assertFalse(scanner.stop());
            assertFalse(scanner.snapshot().running());
            assertEquals(ApiStatus.CONNECTED, scanner.snapshot().apiStatus(), "stopping should retain the last API state");
            int callsWhenStopped = calls.get();
            Thread.sleep(150);
            assertEquals(callsWhenStopped, calls.get(), "a stopped scanner must not issue recurring requests");

            assertTrue(scanner.toggle());
            await(() -> calls.get() >= callsWhenStopped + 3 && scanner.snapshot().apiStatus() == ApiStatus.CONNECTED);
            assertFalse(scanner.toggle());
            assertFalse(scanner.snapshot().running());
            int callsAfterToggleOff = calls.get();
            Thread.sleep(150);
            assertEquals(callsAfterToggleOff, calls.get());
        }
    }

    @Test
    void upstreamFailureKeepsTheLastValidListingAndFetchTimestamp() throws Exception {
        Path configDirectory = configurationWithTestKey("scanner-outage");
        AtomicBoolean fail = new AtomicBoolean(false);
        AtomicInteger calls = new AtomicInteger();
        ApiTransport transport = (path, body, key) -> {
            calls.incrementAndGet();
            if (fail.get()) {
                return response(503, "{\"message\":\"upstream unavailable\"}");
            }
            if (path.equals("/v1/auction/list/1")) {
                return response(200, ONE_LISTING);
            }
            if (path.equals("/v1/auction/list/2")) {
                return response(500, "{\"message\":\"The page you entered does not exist\",\"status\":500}");
            }
            return response(200, EMPTY_PAGE);
        };

        try (ConfigManager config = new ConfigManager(configDirectory, ignored -> { }, ignored -> { });
             ScannerService scanner = scanner(config, "scanner-outage", transport)) {
            assertTrue(scanner.start());
            await(() -> scanner.snapshot().apiStatus() == ApiStatus.CONNECTED
                    && scanner.snapshot().listingsAnalyzed() == 1);
            long lastGoodFetch = scanner.snapshot().lastSuccessfulFetchMillis();
            assertTrue(lastGoodFetch > 0);
            assertEquals(4, calls.get());

            fail.set(true);
            assertEquals(ScannerService.RefreshResult.QUEUED, scanner.requestManualRefresh());
            await(() -> scanner.snapshot().apiStatus() == ApiStatus.OFFLINE);

            assertEquals(1, scanner.snapshot().listingsAnalyzed(), "the last successful listing page must remain visible");
            assertEquals(lastGoodFetch, scanner.snapshot().lastSuccessfulFetchMillis());
            assertTrue(scanner.snapshot().apiMessage().contains("server error"));
        }
    }

    @Test
    void malformedEmptyRefreshCannotReplaceTheLastValidPage() throws Exception {
        Path configDirectory = configurationWithTestKey("scanner-malformed");
        AtomicBoolean malformed = new AtomicBoolean(false);
        ApiTransport transport = (path, body, key) -> {
            if (malformed.get() && path.equals("/v1/auction/list/1")) {
                return response(200, "{\"status\":200,\"result\":[{}]}");
            }
            if (path.equals("/v1/auction/list/1")) {
                return response(200, ONE_LISTING);
            }
            if (path.equals("/v1/auction/list/2")) {
                return response(500, "{\"message\":\"The page you entered does not exist\",\"status\":500}");
            }
            return response(200, EMPTY_PAGE);
        };

        try (ConfigManager config = new ConfigManager(configDirectory, ignored -> { }, ignored -> { });
             ScannerService scanner = scanner(config, "scanner-malformed", transport)) {
            assertTrue(scanner.start());
            await(() -> scanner.snapshot().apiStatus() == ApiStatus.CONNECTED
                    && scanner.snapshot().listingsAnalyzed() == 1);
            long lastGoodFetch = scanner.snapshot().lastSuccessfulFetchMillis();

            malformed.set(true);
            assertEquals(ScannerService.RefreshResult.QUEUED, scanner.requestManualRefresh());
            await(() -> scanner.snapshot().apiStatus() == ApiStatus.SCHEMA_ERROR);

            assertEquals(1, scanner.snapshot().listingsAnalyzed());
            assertEquals(lastGoodFetch, scanner.snapshot().lastSuccessfulFetchMillis());
        }
    }

    @Test
    void manualRefreshDoesNotBypassUpstreamRateLimitBackoff() throws Exception {
        Path configDirectory = configurationWithTestKey("scanner-rate-limit");
        AtomicInteger calls = new AtomicInteger();
        ApiTransport transport = (path, body, key) -> {
            calls.incrementAndGet();
            return new ApiResponse(429, Map.of("Retry-After", "30"), "{\"message\":\"slow down\"}");
        };

        try (ConfigManager config = new ConfigManager(configDirectory, ignored -> { }, ignored -> { });
             ScannerService scanner = scanner(config, "scanner-rate-limit", transport)) {
            assertTrue(scanner.start());
            await(() -> scanner.snapshot().apiStatus() == ApiStatus.RATE_LIMITED);
            assertEquals(1, calls.get());

            assertEquals(ScannerService.RefreshResult.QUEUED, scanner.requestManualRefresh());
            await(() -> scanner.snapshot().apiStatus() == ApiStatus.BACKING_OFF);
            assertEquals(1, calls.get(), "the server Retry-After pause must not be bypassed by the local refresh command");
        }
    }

    private Path configurationWithTestKey(String name) throws Exception {
        Path configDirectory = temporaryDirectory.resolve(name);
        Files.createDirectories(configDirectory);
        Files.writeString(configDirectory.resolve("credentials.json"), "{\"apiKey\":\"test-key\"}");
        return configDirectory;
    }

    private ScannerService scanner(ConfigManager config, String name, ApiTransport transport) {
        return new ScannerService(config,
                new MarketDatabase(temporaryDirectory.resolve(name).resolve("market.sqlite")),
                new DonutApiClient(transport, config::apiKeyForRequest, new RequestThrottle(Duration.ZERO)),
                new OpportunityEngine(), ignored -> { });
    }

    private static ApiResponse response(int status, String body) {
        return new ApiResponse(status, Map.of(), body);
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(condition.getAsBoolean(), "condition did not become true before timeout");
    }
}
