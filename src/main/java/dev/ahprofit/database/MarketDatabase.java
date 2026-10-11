package dev.ahprofit.database;

import dev.ahprofit.api.ApiItem;
import dev.ahprofit.api.AuctionListing;
import dev.ahprofit.api.CompletedSale;
import dev.ahprofit.data.LocalMarketSnapshot;
import dev.ahprofit.market.ItemFingerprint;
import dev.ahprofit.market.PriceEstimate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Versioned local SQLite store for observations, recent completed sales and calculated estimates. */
public final class MarketDatabase implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("ahprofit");
    private static final int SCHEMA_VERSION = 1;

    private final Path databaseFile;
    private Connection connection;

    public MarketDatabase(Path databaseFile) {
        this.databaseFile = databaseFile.toAbsolutePath();
    }

    public synchronized void initialize() throws SQLException, IOException {
        if (connection != null && !connection.isClosed()) {
            return;
        }
        Files.createDirectories(databaseFile.getParent());
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException exception) {
            throw new SQLException("SQLite JDBC library is not present in the mod runtime.", exception);
        }
        connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile);
        try {
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA busy_timeout = 5000");
                statement.execute("PRAGMA foreign_keys = ON");
                statement.execute("PRAGMA journal_mode = WAL");
            }
            migrate();
        } catch (SQLException | RuntimeException exception) {
            try {
                connection.close();
            } catch (SQLException closeException) {
                exception.addSuppressed(closeException);
            }
            connection = null;
            throw exception;
        }
    }

    private void migrate() throws SQLException {
        int currentVersion;
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("PRAGMA user_version")) {
            currentVersion = result.next() ? result.getInt(1) : 0;
        }
        if (currentVersion > SCHEMA_VERSION) {
            throw new SQLException("AhProfit market database schema is newer than this mod; refusing to modify it.");
        }
        if (currentVersion == SCHEMA_VERSION) {
            return;
        }

        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS listing_observations (
                        listing_key TEXT NOT NULL,
                        source_page INTEGER NOT NULL,
                        item_fingerprint TEXT NOT NULL,
                        variant_comparable INTEGER NOT NULL,
                        item_id TEXT NOT NULL,
                        item_name TEXT NOT NULL,
                        quantity INTEGER NOT NULL,
                        total_price TEXT NOT NULL,
                        unit_price TEXT NOT NULL,
                        seller_name TEXT NOT NULL,
                        seller_id TEXT NOT NULL,
                        time_left_ms INTEGER NOT NULL,
                        observed_at_ms INTEGER NOT NULL,
                        expires_at_ms INTEGER NOT NULL,
                        category TEXT NOT NULL,
                        item_json TEXT NOT NULL,
                        PRIMARY KEY (listing_key, observed_at_ms)
                    )
                    """);
            statement.execute("CREATE INDEX IF NOT EXISTS idx_listing_observations_fingerprint_time ON listing_observations(item_fingerprint, observed_at_ms)");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS active_listings (
                        listing_key TEXT PRIMARY KEY,
                        source_page INTEGER NOT NULL,
                        item_fingerprint TEXT NOT NULL,
                        variant_comparable INTEGER NOT NULL,
                        item_id TEXT NOT NULL,
                        item_name TEXT NOT NULL,
                        quantity INTEGER NOT NULL,
                        total_price TEXT NOT NULL,
                        unit_price TEXT NOT NULL,
                        seller_name TEXT NOT NULL,
                        seller_id TEXT NOT NULL,
                        time_left_ms INTEGER NOT NULL,
                        observed_at_ms INTEGER NOT NULL,
                        expires_at_ms INTEGER NOT NULL,
                        category TEXT NOT NULL,
                        item_json TEXT NOT NULL
                    )
                    """);
            statement.execute("CREATE INDEX IF NOT EXISTS idx_active_listings_fingerprint ON active_listings(item_fingerprint)");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS completed_sales (
                        sale_key TEXT PRIMARY KEY,
                        item_fingerprint TEXT NOT NULL,
                        variant_comparable INTEGER NOT NULL,
                        item_id TEXT NOT NULL,
                        item_name TEXT NOT NULL,
                        quantity INTEGER NOT NULL,
                        total_price TEXT NOT NULL,
                        unit_price TEXT NOT NULL,
                        seller_name TEXT NOT NULL,
                        seller_id TEXT NOT NULL,
                        sold_at_ms INTEGER NOT NULL,
                        observed_at_ms INTEGER NOT NULL,
                        source_page INTEGER NOT NULL,
                        category TEXT NOT NULL,
                        item_json TEXT NOT NULL
                    )
                    """);
            statement.execute("CREATE INDEX IF NOT EXISTS idx_completed_sales_fingerprint_time ON completed_sales(item_fingerprint, sold_at_ms)");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS price_estimates (
                        item_fingerprint TEXT NOT NULL,
                        estimated_at_ms INTEGER NOT NULL,
                        median_24h_unit TEXT,
                        median_7d_unit TEXT,
                        conservative_unit TEXT,
                        trend_percent TEXT,
                        volatility_fraction TEXT,
                        comparable_24h INTEGER NOT NULL,
                        comparable_7d INTEGER NOT NULL,
                        latest_sale_at_ms INTEGER NOT NULL,
                        freshness_age_ms INTEGER NOT NULL,
                        units_sold_per_day TEXT NOT NULL,
                        competing_listings INTEGER NOT NULL,
                        competing_quantity TEXT NOT NULL,
                        lowest_competing_unit TEXT,
                        days_to_clear_proxy REAL NOT NULL,
                        confidence REAL NOT NULL,
                        liquidity TEXT NOT NULL,
                        PRIMARY KEY (item_fingerprint, estimated_at_ms)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS successful_fetches (
                        source TEXT NOT NULL,
                        page INTEGER NOT NULL,
                        fetched_at_ms INTEGER NOT NULL,
                        records_seen INTEGER NOT NULL,
                        PRIMARY KEY (source, page)
                    )
                    """);
            statement.execute("PRAGMA user_version = 1");
            connection.commit();
        } catch (SQLException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    public synchronized void saveListingPage(int page, List<AuctionListing> listings, long fetchedAtMillis) throws SQLException {
        inTransaction(() -> replaceListingPageInternal(page, listings, fetchedAtMillis));
    }

    public synchronized void saveTransactionPage(int page, List<CompletedSale> sales, long fetchedAtMillis) throws SQLException {
        inTransaction(() -> saveTransactionPageInternal(page, sales, fetchedAtMillis));
    }

    /** Listings, sales and freshness advance together; never turn file-read time into market-observation time. */
    public synchronized void saveLocalSnapshot(LocalMarketSnapshot snapshot) throws SQLException {
        inTransaction(() -> {
            replaceListingPageInternal(1, snapshot.listings(), snapshot.observedAtMillis());
            saveTransactionPageInternal(1, snapshot.sales(), snapshot.observedAtMillis());
        });
    }

    private void inTransaction(SqlAction action) throws SQLException {
        requireInitialized();
        connection.setAutoCommit(false);
        try {
            action.run();
            connection.commit();
        } catch (SQLException | RuntimeException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    @FunctionalInterface
    private interface SqlAction {
        void run() throws SQLException;
    }

    private void replaceListingPageInternal(int page, List<AuctionListing> listings, long fetchedAtMillis) throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement("DELETE FROM active_listings WHERE source_page = ?")) {
            delete.setInt(1, page);
            delete.executeUpdate();
            try (PreparedStatement active = connection.prepareStatement("""
                    INSERT INTO active_listings (
                        listing_key, source_page, item_fingerprint, variant_comparable, item_id, item_name,
                        quantity, total_price, unit_price, seller_name, seller_id, time_left_ms,
                        observed_at_ms, expires_at_ms, category, item_json
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(listing_key) DO UPDATE SET
                        source_page = excluded.source_page,
                        item_fingerprint = excluded.item_fingerprint,
                        variant_comparable = excluded.variant_comparable,
                        item_id = excluded.item_id,
                        item_name = excluded.item_name,
                        quantity = excluded.quantity,
                        total_price = excluded.total_price,
                        unit_price = excluded.unit_price,
                        seller_name = excluded.seller_name,
                        seller_id = excluded.seller_id,
                        time_left_ms = excluded.time_left_ms,
                        observed_at_ms = excluded.observed_at_ms,
                        expires_at_ms = excluded.expires_at_ms,
                        category = excluded.category,
                        item_json = excluded.item_json
                    """ );
                 PreparedStatement history = connection.prepareStatement("""
                    INSERT OR IGNORE INTO listing_observations (
                        listing_key, source_page, item_fingerprint, variant_comparable, item_id, item_name,
                        quantity, total_price, unit_price, seller_name, seller_id, time_left_ms,
                        observed_at_ms, expires_at_ms, category, item_json
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """)) {
                for (AuctionListing listing : listings) {
                    bindListing(active, listing);
                    active.addBatch();
                    bindListing(history, listing);
                    history.addBatch();
                }
                active.executeBatch();
                history.executeBatch();
            }
            recordFetchInternal("listings", page, fetchedAtMillis, listings.size());
        }
    }

    private void saveTransactionPageInternal(int page, List<CompletedSale> sales, long fetchedAtMillis) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO completed_sales (
                    sale_key, item_fingerprint, variant_comparable, item_id, item_name, quantity,
                    total_price, unit_price, seller_name, seller_id, sold_at_ms, observed_at_ms,
                    source_page, category, item_json
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(sale_key) DO UPDATE SET
                    observed_at_ms = excluded.observed_at_ms,
                    source_page = excluded.source_page
                """)) {
            for (CompletedSale sale : sales) {
                bindSale(insert, sale);
                insert.addBatch();
            }
            insert.executeBatch();
            recordFetchInternal("transactions", page, fetchedAtMillis, sales.size());
        }
    }

    public synchronized List<AuctionListing> loadActiveListings(long nowMillis, int maximumAgeSeconds) throws SQLException {
        requireInitialized();
        long cutoff = nowMillis - Duration.ofSeconds(maximumAgeSeconds).toMillis();
        List<AuctionListing> result = new ArrayList<>();
        int malformed = 0;
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT listing_key, source_page, item_fingerprint, variant_comparable, item_id, item_name,
                       quantity, total_price, unit_price, seller_name, seller_id, time_left_ms,
                       observed_at_ms, expires_at_ms, category, item_json
                FROM active_listings
                WHERE observed_at_ms >= ? AND expires_at_ms > ?
                ORDER BY CAST(unit_price AS REAL) ASC
                """)) {
            statement.setLong(1, cutoff);
            statement.setLong(2, nowMillis);
            try (ResultSet row = statement.executeQuery()) {
                while (row.next()) {
                    try {
                        ApiItem item = ApiItem.fromJson(row.getString("item_json"));
                        ItemFingerprint.Result fingerprint = ItemFingerprint.of(item);
                        result.add(new AuctionListing(
                                row.getString("listing_key"), item, fingerprint,
                                new BigDecimal(row.getString("total_price")),
                                new BigDecimal(row.getString("unit_price")),
                                row.getString("seller_name"), row.getString("seller_id"),
                                row.getLong("time_left_ms"), row.getLong("observed_at_ms"),
                                row.getLong("expires_at_ms"), row.getInt("source_page")));
                    } catch (IllegalArgumentException exception) {
                        malformed++;
                    }
                }
            }
        }
        if (malformed > 0) {
            LOGGER.warn("Skipped {} malformed cached listing record(s); the remaining local snapshot is still available.", malformed);
        }
        return List.copyOf(result);
    }

    public synchronized List<CompletedSale> loadRecentSales(long sinceMillis, long nowMillis) throws SQLException {
        requireInitialized();
        List<CompletedSale> result = new ArrayList<>();
        int malformed = 0;
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT sale_key, item_fingerprint, variant_comparable, item_id, item_name, quantity,
                       total_price, unit_price, seller_name, seller_id, sold_at_ms, observed_at_ms,
                       source_page, category, item_json
                FROM completed_sales
                WHERE sold_at_ms >= ? AND sold_at_ms <= ?
                ORDER BY sold_at_ms DESC
                """)) {
            statement.setLong(1, sinceMillis);
            statement.setLong(2, nowMillis);
            try (ResultSet row = statement.executeQuery()) {
                while (row.next()) {
                    try {
                        ApiItem item = ApiItem.fromJson(row.getString("item_json"));
                        ItemFingerprint.Result fingerprint = ItemFingerprint.of(item);
                        result.add(new CompletedSale(
                                row.getString("sale_key"), item, fingerprint,
                                new BigDecimal(row.getString("total_price")),
                                new BigDecimal(row.getString("unit_price")),
                                row.getString("seller_name"), row.getString("seller_id"),
                                row.getLong("sold_at_ms"), row.getLong("observed_at_ms"), row.getInt("source_page")));
                    } catch (IllegalArgumentException exception) {
                        malformed++;
                    }
                }
            }
        }
        if (malformed > 0) {
            LOGGER.warn("Skipped {} malformed cached transaction record(s); the remaining local history is still available.", malformed);
        }
        return List.copyOf(result);
    }

    public synchronized void savePriceEstimates(Map<String, PriceEstimate> estimates, long estimatedAtMillis) throws SQLException {
        requireInitialized();
        if (estimates == null || estimates.isEmpty()) {
            return;
        }
        connection.setAutoCommit(false);
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT OR REPLACE INTO price_estimates (
                    item_fingerprint, estimated_at_ms, median_24h_unit, median_7d_unit, conservative_unit,
                    trend_percent, volatility_fraction, comparable_24h, comparable_7d,
                    latest_sale_at_ms, freshness_age_ms, units_sold_per_day, competing_listings,
                    competing_quantity, lowest_competing_unit, days_to_clear_proxy, confidence, liquidity
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            for (PriceEstimate estimate : estimates.values()) {
                statement.setString(1, estimate.fingerprint());
                statement.setLong(2, estimatedAtMillis);
                setDecimal(statement, 3, estimate.median24HourUnitPrice());
                setDecimal(statement, 4, estimate.median7DayUnitPrice());
                setDecimal(statement, 5, estimate.conservativeUnitPrice());
                setDecimal(statement, 6, estimate.trendPercent());
                setDecimal(statement, 7, estimate.volatilityFraction());
                statement.setInt(8, estimate.comparableSales24Hours());
                statement.setInt(9, estimate.comparableSales7Days());
                statement.setLong(10, estimate.latestSaleAtMillis());
                statement.setLong(11, estimate.latestSaleAtMillis() <= 0
                        ? Long.MAX_VALUE : Math.max(0, estimatedAtMillis - estimate.latestSaleAtMillis()));
                statement.setString(12, estimate.unitsSoldPerDay().toPlainString());
                statement.setInt(13, estimate.competingListings());
                statement.setString(14, estimate.competingQuantity().toPlainString());
                setDecimal(statement, 15, estimate.lowestCompetingUnitPrice());
                statement.setDouble(16, Double.isFinite(estimate.daysToClearProxy()) ? estimate.daysToClearProxy() : -1.0);
                statement.setDouble(17, estimate.confidence());
                statement.setString(18, estimate.liquidity().name());
                statement.addBatch();
            }
            statement.executeBatch();
            connection.commit();
        } catch (SQLException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    public synchronized long lastSuccessfulFetchMillis() throws SQLException {
        requireInitialized();
        try (Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery("SELECT COALESCE(MAX(fetched_at_ms), 0) FROM successful_fetches")) {
            return row.next() ? row.getLong(1) : 0;
        }
    }

    public synchronized void pruneOldData(long nowMillis) throws SQLException {
        requireInitialized();
        long observationCutoff = nowMillis - Duration.ofDays(90).toMillis();
        long activeCutoff = nowMillis - Duration.ofDays(1).toMillis();
        connection.setAutoCommit(false);
        try (PreparedStatement observations = connection.prepareStatement("DELETE FROM listing_observations WHERE observed_at_ms < ?");
             PreparedStatement sales = connection.prepareStatement("DELETE FROM completed_sales WHERE sold_at_ms < ?");
             PreparedStatement active = connection.prepareStatement("DELETE FROM active_listings WHERE observed_at_ms < ?");
             PreparedStatement estimates = connection.prepareStatement("DELETE FROM price_estimates WHERE estimated_at_ms < ?")) {
            observations.setLong(1, observationCutoff);
            observations.executeUpdate();
            sales.setLong(1, observationCutoff);
            sales.executeUpdate();
            active.setLong(1, activeCutoff);
            active.executeUpdate();
            estimates.setLong(1, observationCutoff);
            estimates.executeUpdate();
            connection.commit();
        } catch (SQLException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    private void recordFetchInternal(String source, int page, long fetchedAtMillis, int recordsSeen) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO successful_fetches (source, page, fetched_at_ms, records_seen)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(source, page) DO UPDATE SET
                    fetched_at_ms = excluded.fetched_at_ms,
                    records_seen = excluded.records_seen
                """)) {
            statement.setString(1, source);
            statement.setInt(2, page);
            statement.setLong(3, fetchedAtMillis);
            statement.setInt(4, recordsSeen);
            statement.executeUpdate();
        }
    }

    private static void bindListing(PreparedStatement statement, AuctionListing listing) throws SQLException {
        statement.setString(1, listing.listingKey());
        statement.setInt(2, listing.sourcePage());
        statement.setString(3, listing.fingerprint().value());
        statement.setInt(4, listing.fingerprint().comparable() ? 1 : 0);
        statement.setString(5, listing.item().id());
        statement.setString(6, listing.item().displayName());
        statement.setInt(7, listing.quantity());
        statement.setString(8, listing.totalPrice().toPlainString());
        statement.setString(9, listing.unitPrice().toPlainString());
        statement.setString(10, listing.sellerName());
        statement.setString(11, listing.sellerId());
        statement.setLong(12, listing.timeLeftMillis());
        statement.setLong(13, listing.observedAtMillis());
        statement.setLong(14, listing.expiresAtMillis());
        statement.setString(15, listing.category().name());
        statement.setString(16, listing.item().rawJson());
    }

    private static void bindSale(PreparedStatement statement, CompletedSale sale) throws SQLException {
        statement.setString(1, sale.saleKey());
        statement.setString(2, sale.fingerprint().value());
        statement.setInt(3, sale.fingerprint().comparable() ? 1 : 0);
        statement.setString(4, sale.item().id());
        statement.setString(5, sale.item().displayName());
        statement.setInt(6, sale.quantity());
        statement.setString(7, sale.totalPrice().toPlainString());
        statement.setString(8, sale.unitPrice().toPlainString());
        statement.setString(9, sale.sellerName());
        statement.setString(10, sale.sellerId());
        statement.setLong(11, sale.soldAtMillis());
        statement.setLong(12, sale.observedAtMillis());
        statement.setInt(13, sale.sourcePage());
        statement.setString(14, sale.category().name());
        statement.setString(15, sale.item().rawJson());
    }

    private static void setDecimal(PreparedStatement statement, int index, BigDecimal value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.VARCHAR);
        } else {
            statement.setString(index, value.toPlainString());
        }
    }

    private void requireInitialized() throws SQLException {
        if (connection == null || connection.isClosed()) {
            throw new SQLException("Market database is not initialized.");
        }
    }

    @Override
    public synchronized void close() throws SQLException {
        if (connection != null && !connection.isClosed()) {
            connection.close();
        }
        connection = null;
    }
}
