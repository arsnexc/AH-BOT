package dev.ahprofit.database;

import dev.ahprofit.TestMarketFixtures;
import dev.ahprofit.api.ApiItem;
import dev.ahprofit.api.AuctionListing;
import dev.ahprofit.api.CompletedSale;
import dev.ahprofit.market.LiquidityLevel;
import dev.ahprofit.market.PriceEstimate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketDatabaseTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void pageReplacementIsAtomicAndKeepsHistoricalObservation() throws Exception {
        Path file = temporaryDirectory.resolve("market.sqlite");
        long now = System.currentTimeMillis();
        ApiItem item = TestMarketFixtures.item("minecraft:gilded_blackstone", 64);
        AuctionListing listing = TestMarketFixtures.listing(item, "6400", now, "seller");

        try (MarketDatabase database = new MarketDatabase(file)) {
            database.initialize();
            database.saveListingPage(1, List.of(listing), now);
            assertEquals(1, database.loadActiveListings(now, 300).size());

            database.saveListingPage(1, List.of(), now + 1_000);
            assertTrue(database.loadActiveListings(now + 1_000, 300).isEmpty());
            assertEquals(now + 1_000, database.lastSuccessfulFetchMillis());
        }

        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file);
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM listing_observations")) {
            assertTrue(rows.next());
            assertEquals(1, rows.getInt(1));
        }
    }

    @Test
    void completedTransactionsAndPriceEstimatesPersistAcrossReopen() throws Exception {
        Path file = temporaryDirectory.resolve("market.sqlite");
        long now = System.currentTimeMillis();
        ApiItem item = TestMarketFixtures.item("minecraft:diamond", 64);
        CompletedSale sale = TestMarketFixtures.sale(item, "250", now - Duration.ofHours(1).toMillis(), "seller", 1);
        PriceEstimate estimate = new PriceEstimate(item.id(), new BigDecimal("250"), new BigDecimal("240"),
                new BigDecimal("220"), new BigDecimal("4"), new BigDecimal("0.1"),
                4, 8, sale.soldAtMillis(), new BigDecimal("8"), 2, new BigDecimal("128"),
                new BigDecimal("200"), 2.0, 0.7, LiquidityLevel.MEDIUM, true, List.of());

        try (MarketDatabase database = new MarketDatabase(file)) {
            database.initialize();
            database.saveTransactionPage(1, List.of(sale), now);
            database.savePriceEstimates(Map.of("test-fingerprint", estimate), now);
            assertEquals(1, database.loadRecentSales(now - Duration.ofDays(7).toMillis(), now).size());
        }

        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file);
             Statement statement = connection.createStatement()) {
            try (ResultSet version = statement.executeQuery("PRAGMA user_version")) {
                assertTrue(version.next());
                assertEquals(1, version.getInt(1));
            }
            try (ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM completed_sales")) {
                assertTrue(rows.next());
                assertEquals(1, rows.getInt(1));
            }
            try (ResultSet rows = statement.executeQuery("SELECT conservative_unit, confidence FROM price_estimates")) {
                assertTrue(rows.next());
                assertEquals("220", rows.getString("conservative_unit"));
                assertEquals(0.7, rows.getDouble("confidence"), 0.000001);
                assertFalse(rows.next());
            }
        }

        try (MarketDatabase reopened = new MarketDatabase(file)) {
            reopened.initialize();
            assertEquals(1, reopened.loadRecentSales(now - Duration.ofDays(7).toMillis(), now).size());
        }
    }
}
