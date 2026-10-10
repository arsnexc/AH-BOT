package dev.ahprofit.scanner;

import dev.ahprofit.api.AuctionListing;
import dev.ahprofit.api.CompletedSale;
import dev.ahprofit.api.DonutApiClient;
import dev.ahprofit.api.DonutApiException;
import dev.ahprofit.api.ParsedPage;
import dev.ahprofit.config.AhProfitConfig;
import dev.ahprofit.config.ConfigManager;
import dev.ahprofit.config.MarketDataMode;
import dev.ahprofit.data.LocalMarketDataSource;
import dev.ahprofit.data.LocalMarketSnapshot;
import dev.ahprofit.database.MarketDatabase;
import dev.ahprofit.market.MarketAnalysis;
import dev.ahprofit.market.Opportunity;
import dev.ahprofit.market.OpportunityEngine;
import dev.ahprofit.util.Money;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** One-thread background scanner. All network and database work is kept off the client/render thread. */
public final class ScannerService implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("ahprofit");
    private static final int MAX_FAILURE_BACKOFF_SECONDS = 300;
    private static final long AUTH_NOTICE_COOLDOWN_MILLIS = Duration.ofMinutes(5).toMillis();
    private static final long BOUNDARY_PROBE_INTERVAL_MILLIS = Duration.ofMinutes(5).toMillis();
    private static final long MANUAL_REFRESH_COOLDOWN_MILLIS = Duration.ofSeconds(15).toMillis();
    private static final long DATABASE_MAINTENANCE_INTERVAL_MILLIS = Duration.ofHours(6).toMillis();

    private final ConfigManager configManager;
    private final MarketDatabase database;
    private final MarketStore apiStore;
    private final MarketStore localStore;
    private final LocalMarketDataSource localDataSource;
    private final DonutApiClient apiClient;
    private final OpportunityEngine opportunityEngine;
    private final Consumer<String> playerNotice;
    private final ScheduledExecutorService executor;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong generation = new AtomicLong();
    private final AtomicReference<ScannerSnapshot> snapshot = new AtomicReference<>(ScannerSnapshot.initial());
    private final AlertDeduplicator alertDeduplicator = new AlertDeduplicator();
    private final Object lifecycleLock = new Object();

    private volatile ScheduledFuture<?> scanTask;
    private volatile Future<?> manualTask;
    private volatile boolean authenticationBlocked;
    private volatile long backoffUntilMillis;
    private volatile long lastManualRefreshAtMillis;
    private volatile long lastMalformedNoticeAtMillis;
    private volatile long lastApiNoticeAtMillis;
    private volatile ApiStatus lastNotifiedStatus = ApiStatus.STOPPED;
    private int consecutiveFailures;
    private int discoveryPage = 2;
    private int knownMaximumListingPage = -1;
    private long nextBoundaryProbeAtMillis;
    private int historicalTransactionPage = 2;

    public ScannerService(ConfigManager configManager, MarketDatabase database,
                          DonutApiClient apiClient, OpportunityEngine opportunityEngine,
                          Consumer<String> playerNotice) {
        this.configManager = java.util.Objects.requireNonNull(configManager, "configManager");
        this.database = java.util.Objects.requireNonNull(database, "database");
        this.apiStore = new MarketStore(database);
        this.localStore = new MarketStore(new MarketDatabase(configManager.localMarketDatabasePath()));
        this.localDataSource = new LocalMarketDataSource(configManager.marketInputPath());
        this.apiClient = java.util.Objects.requireNonNull(apiClient, "apiClient");
        this.opportunityEngine = java.util.Objects.requireNonNull(opportunityEngine, "opportunityEngine");
        this.playerNotice = playerNotice == null ? ignored -> { } : playerNotice;
        this.executor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "AhProfit-market-scanner");
            thread.setDaemon(true);
            thread.setUncaughtExceptionHandler((failedThread, exception) ->
                    LOGGER.error("Uncaught scanner worker error on {}.", failedThread.getName(), exception));
            return thread;
        });
    }

    public boolean isRunning() {
        return running.get();
    }

    public ScannerSnapshot snapshot() {
        return snapshot.get();
    }

    public boolean isUsingLocalData() {
        return configManager.get().marketDataMode().usesLocalData(!configManager.apiKeyForRequest().isBlank());
    }

    private MarketStore activeStore() {
        return isUsingLocalData() ? localStore : apiStore;
    }

    /** Invalidate in-flight work and clear the displayed analysis before changing isolated data stores. */
    public boolean setMarketDataMode(MarketDataMode mode) {
        java.util.Objects.requireNonNull(mode, "mode");
        synchronized (lifecycleLock) {
            if (closed.get()) {
                return false;
            }
            boolean resume = running.get();
            running.set(false);
            generation.incrementAndGet();
            if (scanTask != null) {
                scanTask.cancel(true);
                scanTask = null;
            }
            if (manualTask != null) {
                manualTask.cancel(true);
                manualTask = null;
            }
            configManager.update(current -> current.withMarketDataMode(mode));
            snapshot.set(ScannerSnapshot.initial());
            // Authentication and upstream rate-limit pauses are deliberately retained for API mode.
            if (resume) {
                start();
            } else {
                recomputeCached();
            }
            return true;
        }
    }

    /** @return true if the new state is running, false if it is stopped. */
    public boolean toggle() {
        if (running.get()) {
            stop();
            return false;
        }
        return start();
    }

    public boolean start() {
        synchronized (lifecycleLock) {
            if (closed.get() || running.get()) {
                return false;
            }
            running.set(true);
            long taskGeneration = generation.incrementAndGet();
            int pollSeconds = configManager.get().pollIntervalSeconds();
            updateControlSnapshot(ApiStatus.STARTING, "Scanner enabled; preparing a background market refresh.", 0);
            scanTask = executor.scheduleWithFixedDelay(
                    () -> runCycle(taskGeneration, false), 0, pollSeconds, TimeUnit.SECONDS);
            return true;
        }
    }

    public boolean stop() {
        synchronized (lifecycleLock) {
            if (!running.get()) {
                return false;
            }
            running.set(false);
            generation.incrementAndGet();
            ScheduledFuture<?> currentScan = scanTask;
            if (currentScan != null) {
                currentScan.cancel(true);
                scanTask = null;
            }
            Future<?> currentManual = manualTask;
            if (currentManual != null) {
                currentManual.cancel(true);
                manualTask = null;
            }
            ScannerSnapshot previous = snapshot.get();
            ApiStatus retainedStatus = previous.apiStatus() == ApiStatus.STARTING ? ApiStatus.STOPPED : previous.apiStatus();
            updateControlSnapshot(retainedStatus,
                    "Scanner stopped; no recurring market refreshes will be made. Last data state is " + retainedStatus + ".", 0);
            return true;
        }
    }

    /** Re-scores the cached snapshot after a bankroll/threshold change without making an API request. */
    public void recomputeCached() {
        if (closed.get()) {
            return;
        }
        long taskGeneration = generation.get();
        try {
            executor.execute(() -> {
                if (!isCurrent(taskGeneration, true)) {
                    return;
                }
                long now = System.currentTimeMillis();
                MarketStore store = activeStore();
                if (!store.initialized && now < store.nextRetryAtMillis) {
                    updateControlSnapshot(ApiStatus.DATABASE_ERROR,
                            "Local market database retry is waiting for its backoff window.", store.nextRetryAtMillis);
                    return;
                }
                try {
                    ensureDatabase(store, now);
                } catch (SQLException | IOException | RuntimeException failure) {
                    markDatabaseUnavailable(store, failure,
                            "Could not open the local market database while re-scoring cached recommendations.",
                            "Local market database is unavailable; cached recommendations could not be re-scored.",
                            "AhProfit could not load its local market database for a cached re-score; see the client log.",
                            now);
                    return;
                }
                ScannerSnapshot current = snapshot.get();
                ApiStatus status = current.apiStatus();
                String message = current.apiMessage();
                if (store == localStore && !status.isLocalData()) {
                    status = ApiStatus.LOCAL_WAITING;
                    message = "Key-free local cache selected. Use /ahprofit refresh to load market-input.json; no API requests are made.";
                }
                publishCached(store, status, message, taskGeneration, true);
            });
        } catch (java.util.concurrent.RejectedExecutionException exception) {
            LOGGER.warn("Cached market re-score was skipped because the scanner is shutting down.");
        }
    }

    public RefreshResult requestManualRefresh() {
        if (closed.get()) {
            return RefreshResult.CLOSED;
        }
        long now = System.currentTimeMillis();
        synchronized (lifecycleLock) {
            if (now - lastManualRefreshAtMillis < MANUAL_REFRESH_COOLDOWN_MILLIS) {
                return RefreshResult.RATE_LIMITED;
            }
            lastManualRefreshAtMillis = now;
            long taskGeneration = generation.get();
            try {
                manualTask = executor.submit(() -> {
                    if (!isCurrent(taskGeneration, true)) {
                        return;
                    }
                    boolean wasAuthenticationBlocked = authenticationBlocked;
                    boolean keyLoaded = configManager.get().marketDataMode() != MarketDataMode.LOCAL
                            && configManager.reloadCredentials();
                    if (keyLoaded && wasAuthenticationBlocked) {
                        authenticationBlocked = false;
                        backoffUntilMillis = 0;
                        consecutiveFailures = 0;
                    }
                    runCycle(taskGeneration, true);
                });
            } catch (java.util.concurrent.RejectedExecutionException exception) {
                return RefreshResult.CLOSED;
            }
            return RefreshResult.QUEUED;
        }
    }

    private void runCycle(long taskGeneration, boolean oneShot) {
        if (!isCurrent(taskGeneration, oneShot)) {
            return;
        }
        long now = System.currentTimeMillis();
        if (isUsingLocalData()) {
            runLocalCycle(taskGeneration, oneShot, now);
            return;
        }
        if (!prepareDatabase(apiStore, now)) {
            return;
        }

        if (authenticationBlocked) {
            publishCached(ApiStatus.AUTH_REQUIRED,
                    "API authentication is paused. Update credentials.json and use /ahprofit refresh.", taskGeneration, oneShot);
            return;
        }
        if (configManager.apiKeyForRequest().isBlank()) {
            publishCached(ApiStatus.MISSING_KEY,
                    "API mode needs a key from /api. To work without a key, use /ahprofit source local and supply market-input.json.",
                    taskGeneration, oneShot);
            notifyOnTransition(ApiStatus.MISSING_KEY,
                    "AhProfit API mode has no key. Use /ahprofit source local for key-free local data, or configure credentials.json for live API access.");
            return;
        }
        if (now < backoffUntilMillis) {
            ApiStatus status = consecutiveFailures > 0 ? ApiStatus.BACKING_OFF : ApiStatus.RATE_LIMITED;
            publishCached(status, "Requests are paused until the upstream backoff expires.", taskGeneration, oneShot);
            return;
        }

        updateControlSnapshot(ApiStatus.STARTING, "Fetching read-only auction and transaction pages in the background.", 0);
        try {
            if (!fetchListingPage(1, false, taskGeneration, oneShot)) {
                return;
            }
            if (!isCurrent(taskGeneration, oneShot)) {
                return;
            }
            fetchNextDiscoveryPage(taskGeneration, oneShot);
            if (!isCurrent(taskGeneration, oneShot)) {
                return;
            }
            fetchTransactionPage(1, taskGeneration, oneShot);
            if (!isCurrent(taskGeneration, oneShot)) {
                return;
            }
            fetchTransactionPage(historicalTransactionPage, taskGeneration, oneShot);
            historicalTransactionPage = historicalTransactionPage >= 10 ? 2 : historicalTransactionPage + 1;
            consecutiveFailures = 0;
            backoffUntilMillis = 0;
            publishCached(ApiStatus.CONNECTED,
                    "Documented DonutSMP auction and transaction endpoints are responding.", taskGeneration, oneShot);
        } catch (DonutApiException exception) {
            if (exception.kind() == DonutApiException.Kind.NO_MORE_PAGES) {
                // Boundary detection is handled in fetchNextDiscoveryPage; a page-one boundary is unusual.
                knownMaximumListingPage = 0;
                nextBoundaryProbeAtMillis = System.currentTimeMillis() + BOUNDARY_PROBE_INTERVAL_MILLIS;
                publishCached(ApiStatus.CONNECTED, "No current auction pages are available.", taskGeneration, oneShot);
                return;
            }
            handleApiFailure(exception);
            publishCached(statusFor(exception), exception.getMessage(), taskGeneration, oneShot);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (SQLException exception) {
            markDatabaseUnavailable(exception,
                    "Could not persist or load a market snapshot; the previous snapshot remains available.",
                    "Local market database write/read failed; the last valid market snapshot was kept.",
                    "AhProfit could not update its local market database. The previous market snapshot is preserved; see the client log.",
                    System.currentTimeMillis());
        } catch (RuntimeException exception) {
            consecutiveFailures++;
            backoffUntilMillis = System.currentTimeMillis() + nextBackoffMillis();
            LOGGER.error("Unexpected market-scanner processing error; keeping the last valid market snapshot.", exception);
            publishCached(ApiStatus.OFFLINE,
                    "Market processing failed safely; the last valid snapshot is preserved. See the client log.", taskGeneration, oneShot);
        }
    }

    private void runLocalCycle(long taskGeneration, boolean oneShot, long nowMillis) {
        if (!prepareDatabase(localStore, nowMillis)) {
            return;
        }
        try {
            LocalMarketSnapshot input = localDataSource.load(System.currentTimeMillis());
            if (!isCurrent(taskGeneration, oneShot)) {
                return;
            }
            long previousObservation = localStore.database.lastSuccessfulFetchMillis();
            if (input.observedAtMillis() < previousObservation) {
                throw new IOException("The input predates the last cached local observation. Supply a newer genuine snapshot.");
            }
            if (input.observedAtMillis() > previousObservation) {
                localStore.database.saveLocalSnapshot(input);
            }
            publishCached(localStore, ApiStatus.LOCAL,
                    "Key-free local data from market-input.json; not a live API quote. Re-reading a file never renews its freshness.",
                    taskGeneration, oneShot);
        } catch (NoSuchFileException exception) {
            publishCached(localStore, ApiStatus.LOCAL_WAITING,
                    "Key-free local mode is ready. Add actual listings and completed sales to config/ahprofit/market-input.json, then /ahprofit refresh. No API requests are made.",
                    taskGeneration, oneShot);
            if (isCurrent(taskGeneration, oneShot)) {
                notifyOnTransition(ApiStatus.LOCAL_WAITING,
                        "AhProfit is using key-free local data. Supply config/ahprofit/market-input.json and run /ahprofit refresh; bankroll commands and cached analysis remain available.");
            }
        } catch (SQLException exception) {
            markDatabaseUnavailable(localStore, exception,
                    "Could not persist the local market input; the previous snapshot is preserved.",
                    "Local market database update failed; the previous snapshot is preserved.",
                    "AhProfit could not update its key-free local database; see the client log.", System.currentTimeMillis());
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("Local market input was rejected or could not be read; the last valid local snapshot was kept.");
            String message = exception instanceof IOException ? exception.getMessage()
                    : "Local market input could not be processed. Check market-input.json; the last valid snapshot was kept.";
            publishCached(localStore, ApiStatus.LOCAL_ERROR, message, taskGeneration, oneShot);
            if (isCurrent(taskGeneration, oneShot)) {
                notifyOnTransition(ApiStatus.LOCAL_ERROR, message);
            }
        }
    }

    private boolean prepareDatabase(MarketStore store, long nowMillis) {
        if (!store.initialized && nowMillis < store.nextRetryAtMillis) {
            updateControlSnapshot(ApiStatus.DATABASE_ERROR,
                    "Selected local market database retry is waiting for its backoff window.", store.nextRetryAtMillis);
            return false;
        }
        try {
            ensureDatabase(store, nowMillis);
            return true;
        } catch (SQLException | IOException | RuntimeException exception) {
            markDatabaseUnavailable(store, exception,
                    "Could not open the selected market database; the last in-memory snapshot is being preserved.",
                    "Selected market database is unavailable; market refreshes are paused until it can be opened.",
                    "AhProfit could not open its selected market database; see the client log.", nowMillis);
            return false;
        }
    }

    private void ensureDatabase(MarketStore store, long nowMillis) throws SQLException, IOException {
        if (store.initialized) {
            if (nowMillis >= store.nextMaintenanceAtMillis) {
                store.database.pruneOldData(nowMillis);
                store.nextMaintenanceAtMillis = nowMillis + DATABASE_MAINTENANCE_INTERVAL_MILLIS;
            }
            return;
        }
        if (nowMillis < store.nextRetryAtMillis) {
            throw new SQLException("Database retry is waiting for its backoff window.");
        }
        store.database.initialize();
        store.database.pruneOldData(nowMillis);
        store.initialized = true;
        store.nextMaintenanceAtMillis = nowMillis + DATABASE_MAINTENANCE_INTERVAL_MILLIS;
    }

    private boolean fetchListingPage(int page, boolean discovery, long taskGeneration, boolean oneShot)
            throws DonutApiException, InterruptedException, SQLException {
        ParsedPage<AuctionListing> response = apiClient.fetchListings(page);
        if (!isCurrent(taskGeneration, oneShot)) {
            return false;
        }
        long fetchedAt = System.currentTimeMillis();
        logMalformed("auction listing", page, response.malformedEntries(), response.nullEntries(), response.duplicateEntries(), fetchedAt);
        rejectAmbiguousEmptyPage("auction listing", page, response);
        database.saveListingPage(page, response.entries(), fetchedAt);

        if (response.entries().isEmpty()) {
            markListingBoundary(page, fetchedAt);
        } else if (discovery) {
            markListingPageFound(page, fetchedAt);
        }
        return true;
    }

    private void fetchNextDiscoveryPage(long taskGeneration, boolean oneShot)
            throws DonutApiException, InterruptedException, SQLException {
        long now = System.currentTimeMillis();
        boolean probe = knownMaximumListingPage >= 1 && now >= nextBoundaryProbeAtMillis;
        int page;
        if (probe) {
            page = knownMaximumListingPage + 1;
        } else if (knownMaximumListingPage >= 1) {
            if (knownMaximumListingPage < 2) {
                return;
            }
            if (discoveryPage < 2 || discoveryPage > knownMaximumListingPage) {
                discoveryPage = 2;
            }
            page = discoveryPage;
        } else {
            page = Math.max(2, discoveryPage);
        }

        try {
            ParsedPage<AuctionListing> response = apiClient.fetchListings(page);
            if (!isCurrent(taskGeneration, oneShot)) {
                return;
            }
            long fetchedAt = System.currentTimeMillis();
            logMalformed("auction listing", page, response.malformedEntries(), response.nullEntries(), response.duplicateEntries(), fetchedAt);
            rejectAmbiguousEmptyPage("auction listing", page, response);
            database.saveListingPage(page, response.entries(), fetchedAt);
            if (response.entries().isEmpty()) {
                markListingBoundary(page, fetchedAt);
            } else {
                if (probe) {
                    knownMaximumListingPage = page;
                    discoveryPage = page + 1;
                    // Continue walking the newly discovered tail one page per poll until its end is found.
                    nextBoundaryProbeAtMillis = fetchedAt;
                } else {
                    markListingPageFound(page, fetchedAt);
                }
            }
        } catch (DonutApiException exception) {
            if (exception.kind() != DonutApiException.Kind.NO_MORE_PAGES) {
                throw exception;
            }
            markListingBoundary(page, System.currentTimeMillis());
        }
    }

    private static <T> void rejectAmbiguousEmptyPage(String label, int page, ParsedPage<T> response)
            throws DonutApiException {
        if (response.entries().isEmpty() && (response.nullEntries() > 0 || response.malformedEntries() > 0)) {
            throw new DonutApiException(DonutApiException.Kind.SCHEMA,
                    "DonutSMP " + label + " page " + page
                            + " contained no usable records but did contain malformed entries; the last valid cached page was preserved.",
                    200, null);
        }
    }

    private void markListingBoundary(int page, long nowMillis) {
        if (page <= 1) {
            knownMaximumListingPage = 1;
        } else {
            knownMaximumListingPage = Math.max(1, page - 1);
        }
        discoveryPage = 2;
        nextBoundaryProbeAtMillis = nowMillis + BOUNDARY_PROBE_INTERVAL_MILLIS;
    }

    private void markListingPageFound(int page, long nowMillis) {
        if (knownMaximumListingPage >= 1 && page > knownMaximumListingPage) {
            knownMaximumListingPage = page;
            discoveryPage = 2;
            nextBoundaryProbeAtMillis = nowMillis + BOUNDARY_PROBE_INTERVAL_MILLIS;
        } else if (knownMaximumListingPage < 1) {
            discoveryPage = page + 1;
        } else if (page >= knownMaximumListingPage) {
            discoveryPage = 2;
        } else {
            discoveryPage = page + 1;
        }
    }

    private void fetchTransactionPage(int page, long taskGeneration, boolean oneShot)
            throws DonutApiException, InterruptedException, SQLException {
        if (page < 1 || page > 10) {
            throw new IllegalArgumentException("Transaction page cursor exceeded the documented 1..10 range.");
        }
        ParsedPage<CompletedSale> response = apiClient.fetchTransactions(page);
        if (!isCurrent(taskGeneration, oneShot)) {
            return;
        }
        long fetchedAt = System.currentTimeMillis();
        logMalformed("completed transaction", page, response.malformedEntries(), response.nullEntries(), response.duplicateEntries(), fetchedAt);
        rejectAmbiguousEmptyPage("completed transaction", page, response);
        database.saveTransactionPage(page, response.entries(), fetchedAt);
    }

    private void handleApiFailure(DonutApiException exception) {
        long now = System.currentTimeMillis();
        LOGGER.warn("DonutSMP request paused: {} (kind={}, HTTP {}).", exception.getMessage(), exception.kind(), exception.httpStatus());
        if (exception.kind() == DonutApiException.Kind.AUTHENTICATION) {
            authenticationBlocked = true;
            backoffUntilMillis = 0;
            notifyOnTransition(ApiStatus.AUTH_REQUIRED, exception.getMessage());
            return;
        }
        if (exception.kind() == DonutApiException.Kind.MISSING_KEY) {
            notifyOnTransition(ApiStatus.MISSING_KEY, exception.getMessage());
            return;
        }
        consecutiveFailures++;
        long exponentialBackoff = nextBackoffMillis();
        long serverDelay = exception.retryAfter() == null ? 0 : exception.retryAfter().toMillis();
        long pause = Math.max(exponentialBackoff, serverDelay);
        backoffUntilMillis = now + pause;
        if (exception.kind() == DonutApiException.Kind.RATE_LIMIT) {
            LOGGER.info("Market requests will remain paused for at least {} seconds after HTTP 429.", Math.max(1, pause / 1_000));
        }
        ApiStatus status = statusFor(exception);
        if (now - lastApiNoticeAtMillis > AUTH_NOTICE_COOLDOWN_MILLIS) {
            notifyOnTransition(status, exception.getMessage());
            lastApiNoticeAtMillis = now;
        }
    }

    private long nextBackoffMillis() {
        int exponent = Math.min(8, Math.max(1, consecutiveFailures));
        long ceiling = Math.min(MAX_FAILURE_BACKOFF_SECONDS, 1L << exponent) * 1_000L;
        long lower = Math.max(1_000L, ceiling / 2);
        long delay = ThreadLocalRandom.current().nextLong(lower, ceiling + 1);
        return Math.min(Duration.ofMinutes(5).toMillis(), delay);
    }

    private static ApiStatus statusFor(DonutApiException exception) {
        return switch (exception.kind()) {
            case MISSING_KEY -> ApiStatus.MISSING_KEY;
            case AUTHENTICATION -> ApiStatus.AUTH_REQUIRED;
            case RATE_LIMIT -> ApiStatus.RATE_LIMITED;
            case SCHEMA -> ApiStatus.SCHEMA_ERROR;
            case NETWORK, TIMEOUT, SERVER, CLIENT, NO_MORE_PAGES -> ApiStatus.OFFLINE;
        };
    }

    private void publishCached(ApiStatus status, String message, long taskGeneration, boolean oneShot) {
        publishCached(apiStore, status, message, taskGeneration, oneShot);
    }

    private void publishCached(MarketStore store, ApiStatus status, String message, long taskGeneration, boolean oneShot) {
        if (!isCurrent(taskGeneration, oneShot) || !store.initialized) {
            return;
        }
        try {
            long now = System.currentTimeMillis();
            AhProfitConfig config = configManager.get();
            List<AuctionListing> listings = store.database.loadActiveListings(now, config.maxDataAgeSeconds());
            List<CompletedSale> sales = store.database.loadRecentSales(now - Duration.ofDays(7).toMillis(), now);
            long lastFetch = store.database.lastSuccessfulFetchMillis();
            MarketAnalysis analysis = opportunityEngine.analyze(listings, sales, config, now, lastFetch);
            if (store == localStore) {
                analysis = analysis.withWarning("Local user-supplied market records, not live API-verified quotes. Check every listing and completed-sale record in-game.");
            }
            store.database.savePriceEstimates(analysis.priceEstimates(), now);
            long newestListing = listings.stream().mapToLong(AuctionListing::observedAtMillis).max().orElse(0);
            ScannerSnapshot next = new ScannerSnapshot(running.get(), status, message, lastFetch, newestListing,
                    analysis.analyzedListings(), analysis.opportunities().size(), analysis.economicallyQualifiedListings(),
                    sales.size(), store == localStore ? 1 : discoveryPage, store == localStore ? 1 : historicalTransactionPage,
                    store == localStore ? 0 : backoffUntilMillis, analysis);
            synchronized (lifecycleLock) {
                if (!isCurrent(taskGeneration, oneShot)) {
                    return;
                }
                snapshot.set(next);
                maybeAlert(analysis, status, now, config);
            }
        } catch (SQLException exception) {
            markDatabaseUnavailable(store, exception,
                    "Could not rebuild the cached market view; the previous recommendation snapshot is preserved.",
                    "Could not rebuild the cached market view; the previous snapshot is preserved.",
                    "AhProfit could not rebuild cached recommendations from the local database. The previous snapshot is preserved; see the client log.",
                    System.currentTimeMillis());
        } catch (RuntimeException exception) {
            LOGGER.error("Market analysis failed; the previous recommendation snapshot is preserved.", exception);
            updateControlSnapshot(store == localStore ? ApiStatus.LOCAL_ERROR : ApiStatus.OFFLINE,
                    "Market analysis failed safely; the previous recommendation snapshot is preserved.", backoffUntilMillis);
        }
    }

    private void maybeAlert(MarketAnalysis analysis, ApiStatus status, long nowMillis, AhProfitConfig config) {
        if (!config.alertsEnabled() || !status.hasUsableData()
                || analysis.opportunities().isEmpty()) {
            return;
        }
        List<Opportunity> alerts = alertDeduplicator.collect(analysis.opportunities(), nowMillis);
        int count = Math.min(3, alerts.size());
        for (int index = 0; index < count; index++) {
            Opportunity opportunity = alerts.get(index);
            playerNotice.accept((status == ApiStatus.LOCAL ? "Potential LOCAL-DATA opportunity" : "Potential read-only opportunity")
                    + " (new or materially changed): "
                    + dev.ahprofit.market.ItemFingerprint.displayName(opportunity.listing().item())
                    + " at " + Money.format(opportunity.listing().totalPrice()) + ", estimated net "
                    + Money.format(opportunity.estimatedNetProfit()) + " (" + Money.formatPercent(opportunity.roiPercent())
                    + "). See the AhProfit HUD; all trades remain manual.");
        }
    }

    private void logMalformed(String label, int page, int malformed, int nulls, int duplicates, long nowMillis) {
        if (malformed + duplicates == 0) {
            return;
        }
        if (nowMillis - lastMalformedNoticeAtMillis >= Duration.ofMinutes(5).toMillis()) {
            LOGGER.warn("DonutSMP {} page {} had {} malformed and {} duplicate entr(y/ies); {} null padding entr(y/ies) were skipped.",
                    label, page, malformed, duplicates, nulls);
            lastMalformedNoticeAtMillis = nowMillis;
        }
    }

    private void markDatabaseUnavailable(Exception failure, String logMessage, String statusMessage,
                                         String playerMessage, long nowMillis) {
        markDatabaseUnavailable(apiStore, failure, logMessage, statusMessage, playerMessage, nowMillis);
    }

    private void markDatabaseUnavailable(MarketStore store, Exception failure, String logMessage, String statusMessage,
                                         String playerMessage, long nowMillis) {
        store.initialized = false;
        store.nextRetryAtMillis = nowMillis + Duration.ofMinutes(1).toMillis();
        try {
            store.database.close();
        } catch (SQLException closeFailure) {
            failure.addSuppressed(closeFailure);
        }
        LOGGER.error(logMessage, failure);
        synchronized (lifecycleLock) {
            if (store == activeStore() && !closed.get()) {
                updateControlSnapshot(ApiStatus.DATABASE_ERROR, statusMessage, store.nextRetryAtMillis);
                notifyOnTransition(ApiStatus.DATABASE_ERROR, playerMessage);
            }
        }
    }

    private void updateControlSnapshot(ApiStatus status, String message, long nextAttemptAtMillis) {
        ScannerSnapshot previous = snapshot.get();
        snapshot.set(new ScannerSnapshot(running.get(), status, message,
                previous.lastSuccessfulFetchMillis(), previous.newestListingObservationMillis(),
                previous.listingsAnalyzed(), previous.qualifiedOpportunities(),
                previous.economicallyQualifiedListings(), previous.comparableSalesCached(),
                discoveryPage, historicalTransactionPage, nextAttemptAtMillis, previous.analysis()));
    }

    private boolean isCurrent(long taskGeneration, boolean oneShot) {
        return !closed.get() && generation.get() == taskGeneration && (oneShot || running.get());
    }

    private void notifyOnTransition(ApiStatus status, String message) {
        if (lastNotifiedStatus != status) {
            lastNotifiedStatus = status;
            playerNotice.accept(message);
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        running.set(false);
        generation.incrementAndGet();
        ScheduledFuture<?> currentScan = scanTask;
        if (currentScan != null) {
            currentScan.cancel(true);
        }
        Future<?> currentManual = manualTask;
        if (currentManual != null) {
            currentManual.cancel(true);
        }
        executor.shutdownNow();
        try {
            executor.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
        for (MarketStore store : List.of(apiStore, localStore)) {
            try {
                store.database.close();
            } catch (SQLException exception) {
                LOGGER.warn("Could not close a local market database cleanly.", exception);
            }
        }
    }

    private static final class MarketStore {
        private final MarketDatabase database;
        private boolean initialized;
        private long nextRetryAtMillis;
        private long nextMaintenanceAtMillis;

        private MarketStore(MarketDatabase database) {
            this.database = database;
        }
    }

    public enum RefreshResult {
        QUEUED,
        RATE_LIMITED,
        CLOSED
    }
}
