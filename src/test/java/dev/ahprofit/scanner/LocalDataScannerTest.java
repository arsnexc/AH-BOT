package dev.ahprofit.scanner;

import dev.ahprofit.api.ApiResponse;
import dev.ahprofit.api.ApiTransport;
import dev.ahprofit.api.DonutApiClient;
import dev.ahprofit.api.RequestThrottle;
import dev.ahprofit.config.ConfigManager;
import dev.ahprofit.config.MarketDataMode;
import dev.ahprofit.database.MarketDatabase;
import dev.ahprofit.market.OpportunityEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalDataScannerTest {
    private static final String EMPTY_PAGE = "{\"status\":200,\"result\":[]}";
    private static final String API_LISTING = """
            {"status":200,"result":[{"item":{"id":"minecraft:gilded_blackstone","count":64},"seller":{"name":"api-seller"},"price":1000,"time_left":3600000}]}
            """;

    @TempDir
    Path temporaryDirectory;

    @Test
    void missingKeyUsesLocalModeAndBankrollCommandsWithoutAnyNetworkRequests() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (ConfigManager config = config(false);
             ScannerService scanner = scanner(config, countingTransport(calls))) {
            config.update(current -> current.withManualBalance(new BigDecimal("100000"), System.currentTimeMillis()));
            assertTrue(scanner.start());
            await(() -> scanner.snapshot().apiStatus() == ApiStatus.LOCAL_WAITING);
            assertEquals(0, calls.get());
            assertTrue(scanner.isUsingLocalData());
            assertEquals(0, scanner.snapshot().lastSuccessfulFetchMillis());
            assertNotNull(scanner.snapshot().analysis());
            assertTrue(scanner.snapshot().analysis().bankroll().balanceConfigured());
            assertTrue(scanner.snapshot().analysis().opportunities().isEmpty());
        }
    }

    @Test
    void localFilePowersAnalysisButRepeatedReadsNeverRenewFreshnessOrDuplicateSales() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        long observedAt = System.currentTimeMillis() - 1000;
        try (ConfigManager config = config(false);
             ScannerService scanner = scanner(config, countingTransport(calls))) {
            config.update(current -> current.withManualBalance(new BigDecimal("100000"), System.currentTimeMillis()));
            Files.writeString(config.marketInputPath(), marketInput(observedAt, true));
            assertTrue(scanner.start());
            await(() -> scanner.snapshot().apiStatus() == ApiStatus.LOCAL);
            assertEquals(2, scanner.snapshot().listingsAnalyzed());
            assertEquals(6, scanner.snapshot().comparableSalesCached());
            assertEquals(1, scanner.snapshot().qualifiedOpportunities());
            assertEquals(observedAt, scanner.snapshot().lastSuccessfulFetchMillis());
            assertTrue(scanner.snapshot().analysis().warnings().stream().anyMatch(warning -> warning.contains("not live API-verified")));

            assertTrue(scanner.stop());
            assertTrue(scanner.start());
            await(() -> scanner.snapshot().apiStatus() == ApiStatus.LOCAL);
            assertEquals(observedAt, scanner.snapshot().lastSuccessfulFetchMillis());
            assertEquals(6, scanner.snapshot().comparableSalesCached());
            assertEquals(0, calls.get());
        }
    }

    @Test
    void staleFileAndAskOnlyFileCannotCreateFreshEvidenceBackedRecommendations() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        long oldObservation = System.currentTimeMillis() - Duration.ofMinutes(10).toMillis();
        try (ConfigManager config = config(false);
             ScannerService scanner = scanner(config, countingTransport(calls))) {
            config.update(current -> current.withManualBalance(new BigDecimal("100000"), System.currentTimeMillis()));
            Files.writeString(config.marketInputPath(), marketInput(oldObservation, true));
            assertTrue(scanner.start());
            await(() -> scanner.snapshot().apiStatus() == ApiStatus.LOCAL);
            assertEquals(oldObservation, scanner.snapshot().lastSuccessfulFetchMillis());
            assertEquals(0, scanner.snapshot().listingsAnalyzed());
            assertEquals(0, scanner.snapshot().qualifiedOpportunities());
            assertTrue(scanner.snapshot().analysis().warnings().stream().anyMatch(warning -> warning.contains("stale")));
        }
        // A separate local history with only asks must not manufacture completed-sale evidence.
        Path other = temporaryDirectory.resolve("ask-only");
        try (ConfigManager config = new ConfigManager(other, ignored -> { }, ignored -> { });
             ScannerService scanner = scanner(config, countingTransport(calls))) {
            config.update(current -> current.withManualBalance(new BigDecimal("100000"), System.currentTimeMillis()));
            Files.writeString(config.marketInputPath(), marketInput(System.currentTimeMillis() - 1000, false));
            assertTrue(scanner.start());
            await(() -> scanner.snapshot().apiStatus() == ApiStatus.LOCAL);
            assertEquals(2, scanner.snapshot().listingsAnalyzed());
            assertEquals(0, scanner.snapshot().comparableSalesCached());
            assertEquals(0, scanner.snapshot().qualifiedOpportunities());
            assertEquals(0, calls.get());
        }
    }

    @Test
    void malformedInputAndOlderSnapshotKeepTheLastValidCache() throws Exception {
        for (boolean malformed : new boolean[]{true, false}) {
            Path directory = temporaryDirectory.resolve("preserve-" + malformed);
            long observedAt = System.currentTimeMillis() - 1000;
            AtomicInteger calls = new AtomicInteger();
            try (ConfigManager config = new ConfigManager(directory, ignored -> { }, ignored -> { });
                 ScannerService scanner = scanner(config, countingTransport(calls))) {
                Files.writeString(config.marketInputPath(), marketInput(observedAt, true));
                assertTrue(scanner.start());
                await(() -> scanner.snapshot().apiStatus() == ApiStatus.LOCAL);
                Files.writeString(config.marketInputPath(), malformed ? "{invalid-json"
                        : marketInput(observedAt - 1000, true));
                assertEquals(ScannerService.RefreshResult.QUEUED, scanner.requestManualRefresh());
                await(() -> scanner.snapshot().apiStatus() == ApiStatus.LOCAL_ERROR);
                assertEquals(observedAt, scanner.snapshot().lastSuccessfulFetchMillis());
                assertEquals(2, scanner.snapshot().listingsAnalyzed());
                assertEquals(6, scanner.snapshot().comparableSalesCached());
                assertEquals(0, calls.get());
            }
        }
    }

    @Test
    void forcedLocalModeIgnoresConfiguredKeyAndKeepsApiHistorySeparate() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (ConfigManager config = config(true);
             ScannerService scanner = scanner(config, countingTransport(calls))) {
            config.update(current -> current.withManualBalance(new BigDecimal("100000"), System.currentTimeMillis()));
            Files.writeString(config.marketInputPath(), marketInput(System.currentTimeMillis() - 1000, true));
            assertTrue(scanner.start());
            await(() -> scanner.snapshot().apiStatus() == ApiStatus.CONNECTED);
            assertEquals(1, scanner.snapshot().listingsAnalyzed());
            assertEquals(0, scanner.snapshot().comparableSalesCached());
            int apiCalls = calls.get();

            assertTrue(scanner.setMarketDataMode(MarketDataMode.LOCAL));
            await(() -> scanner.snapshot().apiStatus() == ApiStatus.LOCAL);
            assertEquals(2, scanner.snapshot().listingsAnalyzed());
            assertEquals(6, scanner.snapshot().comparableSalesCached());
            assertEquals(1, scanner.snapshot().qualifiedOpportunities());
            assertEquals(apiCalls, calls.get());

            assertTrue(scanner.setMarketDataMode(MarketDataMode.API));
            await(() -> scanner.snapshot().apiStatus() == ApiStatus.CONNECTED);
            assertEquals(1, scanner.snapshot().listingsAnalyzed());
            assertEquals(0, scanner.snapshot().comparableSalesCached());
            assertEquals(0, scanner.snapshot().qualifiedOpportunities());
        }
    }

    @Test
    void explicitApiModeStillRequiresAKey() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (ConfigManager config = config(false);
             ScannerService scanner = scanner(config, countingTransport(calls))) {
            config.update(current -> current.withMarketDataMode(MarketDataMode.API));
            assertFalse(scanner.isUsingLocalData());
            assertTrue(scanner.start());
            await(() -> scanner.snapshot().apiStatus() == ApiStatus.MISSING_KEY);
            assertEquals(0, calls.get());
        }
    }

    @Test
    void switchingSourcesDoesNotBypassApiAuthenticationOrRateLimitPauses() throws Exception {
        for (int status : new int[]{401, 429}) {
            Path directory = temporaryDirectory.resolve("blocked-" + status);
            Files.createDirectories(directory);
            Files.writeString(directory.resolve("credentials.json"), "{\"apiKey\":\"test-key\"}");
            AtomicInteger calls = new AtomicInteger();
            ApiTransport transport = (path, body, key) -> {
                calls.incrementAndGet();
                return new ApiResponse(status, Map.of("Retry-After", "60"), "{}");
            };
            try (ConfigManager config = new ConfigManager(directory, ignored -> { }, ignored -> { });
                 ScannerService scanner = scanner(config, transport)) {
                assertTrue(scanner.start());
                await(() -> scanner.snapshot().apiStatus() == (status == 401 ? ApiStatus.AUTH_REQUIRED : ApiStatus.RATE_LIMITED));
                assertEquals(1, calls.get());
                assertTrue(scanner.setMarketDataMode(MarketDataMode.LOCAL));
                await(() -> scanner.snapshot().apiStatus() == ApiStatus.LOCAL_WAITING);
                assertTrue(scanner.setMarketDataMode(MarketDataMode.API));
                await(() -> scanner.snapshot().apiStatus() == (status == 401 ? ApiStatus.AUTH_REQUIRED : ApiStatus.BACKING_OFF));
                assertEquals(1, calls.get(), "source switching must not clear upstream API restrictions");
            }
        }
    }

    @Test
    void inFlightApiResponseCannotPublishAfterSwitchingToLocal() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ApiTransport transport = (path, body, key) -> {
            started.countDown();
            boolean released = false;
            while (!released) {
                try {
                    release.await();
                    released = true;
                } catch (InterruptedException ignored) {
                    // Simulate a transport that cannot immediately cancel an already-started request.
                }
            }
            return new ApiResponse(200, Map.of(), API_LISTING);
        };
        try (ConfigManager config = config(true);
             ScannerService scanner = scanner(config, transport)) {
            try {
                Files.writeString(config.marketInputPath(), marketInput(System.currentTimeMillis() - 1000, true));
                assertTrue(scanner.start());
                assertTrue(started.await(8, TimeUnit.SECONDS));
                assertTrue(scanner.setMarketDataMode(MarketDataMode.LOCAL));
                release.countDown();
                await(() -> scanner.snapshot().apiStatus() == ApiStatus.LOCAL);
                assertEquals(2, scanner.snapshot().listingsAnalyzed());
                assertEquals(6, scanner.snapshot().comparableSalesCached());
            } finally {
                release.countDown();
            }
        }
    }

    private ConfigManager config(boolean key) throws Exception {
        Path directory = temporaryDirectory.resolve("ahprofit");
        Files.createDirectories(directory);
        if (key) {
            Files.writeString(directory.resolve("credentials.json"), "{\"apiKey\":\"test-key\"}");
        }
        return new ConfigManager(directory, ignored -> { }, ignored -> { });
    }

    private ScannerService scanner(ConfigManager config, ApiTransport transport) {
        return new ScannerService(config,
                new MarketDatabase(config.localMarketDatabasePath().resolveSibling("market.sqlite")),
                new DonutApiClient(transport, config::apiKeyForRequest, new RequestThrottle(Duration.ZERO)),
                new OpportunityEngine(), ignored -> { });
    }

    private static ApiTransport countingTransport(AtomicInteger calls) {
        return (path, body, key) -> {
            calls.incrementAndGet();
            return new ApiResponse(200, Map.of(), path.equals("/v1/auction/list/1") ? API_LISTING : EMPTY_PAGE);
        };
    }

    private static String marketInput(long observedAt, boolean includeSales) {
        String item = "{\"id\":\"minecraft:diamond\",\"count\":64}";
        String listings = "{\"item\":" + item + ",\"seller\":{\"name\":\"cheap\"},\"price\":8000,\"time_left\":3600000},"
                + "{\"item\":" + item + ",\"seller\":{\"name\":\"competition\"},\"price\":19200,\"time_left\":3600000}";
        StringBuilder sales = new StringBuilder();
        if (includeSales) {
            for (int i = 1; i <= 6; i++) {
                if (i > 1) {
                    sales.append(',');
                }
                sales.append("{\"item\":").append(item)
                        .append(",\"seller\":{\"name\":\"seller\"},\"price\":19200,\"unixMillisDateSold\":")
                        .append(observedAt - i * 60000L).append('}');
            }
        }
        return "{\"schemaVersion\":1,\"observedAtMillis\":" + observedAt
                + ",\"listings\":[" + listings + "],\"sales\":[" + sales + "]}";
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(condition.getAsBoolean(), "condition did not become true before timeout");
    }
}
