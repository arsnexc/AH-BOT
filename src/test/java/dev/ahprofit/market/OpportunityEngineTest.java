package dev.ahprofit.market;

import dev.ahprofit.TestMarketFixtures;
import dev.ahprofit.api.ApiItem;
import dev.ahprofit.api.AuctionListing;
import dev.ahprofit.api.CompletedSale;
import dev.ahprofit.config.AhProfitConfig;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpportunityEngineTest {
    @Test
    void movingBalanceChangesSingleListingEligibilityAutomatically() {
        long now = System.currentTimeMillis();
        ApiItem item = TestMarketFixtures.item("minecraft:gilded_blackstone", 64);
        AuctionListing listing = TestMarketFixtures.listing(item, "2000000", now, "seller");
        List<CompletedSale> sales = TestMarketFixtures.repeatedSales(item, "50000", 8, now);
        OpportunityEngine engine = new OpportunityEngine();

        MarketAnalysis smallBalance = engine.analyze(List.of(listing), sales,
                TestMarketFixtures.bankroll("2000000", now), now, now);
        MarketAnalysis largeBalance = engine.analyze(List.of(listing), sales,
                TestMarketFixtures.bankroll("20000000", now), now, now);

        assertTrue(smallBalance.bankroll().maxRecommendedListingSpend().compareTo(new BigDecimal("2000000")) < 0);
        assertTrue(smallBalance.opportunities().isEmpty());
        assertEquals(1, largeBalance.opportunities().size());
        assertEquals(0, largeBalance.bankroll().maxRecommendedListingSpend().compareTo(new BigDecimal("3000000")));
        assertTrue(largeBalance.opportunities().getFirst().estimatedNetProfit().compareTo(new BigDecimal("400000")) > 0);
    }

    @Test
    void reducingManualBalanceImmediatelyRemovesUnaffordableRecommendations() {
        long now = System.currentTimeMillis();
        ApiItem item = TestMarketFixtures.item("minecraft:emerald_block", 64);
        AuctionListing listing = TestMarketFixtures.listing(item, "2000000", now, "seller");
        List<CompletedSale> sales = TestMarketFixtures.repeatedSales(item, "50000", 8, now);
        OpportunityEngine engine = new OpportunityEngine();

        assertFalse(engine.analyze(List.of(listing), sales, TestMarketFixtures.bankroll("20000000", now), now, now)
                .opportunities().isEmpty());
        MarketAnalysis updated = engine.analyze(List.of(listing), sales,
                TestMarketFixtures.bankroll("1000000", now), now, now);

        assertTrue(updated.opportunities().isEmpty());
        assertEquals(0, updated.bankroll().maxRecommendedListingSpend().compareTo(new BigDecimal("150000")));
    }

    @Test
    void portfolioAllocatorRespectsSharedCategoryExposure() {
        long now = System.currentTimeMillis();
        ApiItem gilded = TestMarketFixtures.item("minecraft:gilded_blackstone", 64);
        ApiItem diamond = TestMarketFixtures.item("minecraft:diamond_block", 64);
        AuctionListing first = TestMarketFixtures.listing(gilded, "300000", now, "first");
        AuctionListing second = TestMarketFixtures.listing(diamond, "300000", now, "second");
        List<CompletedSale> sales = new ArrayList<>();
        sales.addAll(TestMarketFixtures.repeatedSales(gilded, "8000", 8, now));
        sales.addAll(TestMarketFixtures.repeatedSales(diamond, "8000", 8, now));
        AhProfitConfig config = TestMarketFixtures.bankroll("2000000", now);

        MarketAnalysis analysis = new OpportunityEngine().analyze(List.of(first, second), sales, config, now, now);

        assertEquals(2, analysis.economicallyQualifiedListings());
        assertEquals(1, analysis.opportunities().size());
        assertEquals(new BigDecimal("300000"), analysis.totalProposedCapital());
        assertEquals(new BigDecimal("300000"), analysis.categoryExposure().get(ItemCategory.RESOURCES));
        assertTrue(analysis.warnings().stream().anyMatch(warning -> warning.contains("exposure limits")));
    }

    @Test
    void activeListingAsksCanLowerButNotEstablishResaleValue() {
        long now = System.currentTimeMillis();
        ApiItem item = TestMarketFixtures.item("minecraft:diamond", 64);
        AuctionListing candidate = TestMarketFixtures.listing(item, "1000", now, "candidate");
        AuctionListing lowCompetitor = TestMarketFixtures.listing(item, "320", now, "competitor");
        List<CompletedSale> sales = TestMarketFixtures.repeatedSales(item, "100", 8, now);

        PriceEstimate estimate = new PriceEstimator().estimate(candidate, sales,
                List.of(candidate, lowCompetitor), now, 5, 300);
        MarketAnalysis analysis = new OpportunityEngine().analyze(List.of(candidate, lowCompetitor), sales,
                TestMarketFixtures.bankroll("2000000", now), now, now);

        assertEquals(new BigDecimal("5"), estimate.conservativeUnitPrice());
        assertTrue(analysis.opportunities().isEmpty());
    }

    @Test
    void staleListingsAndOldFetchesAreLabeledAndNotRecommended() {
        long now = System.currentTimeMillis();
        long observedAt = now - Duration.ofMinutes(10).toMillis();
        ApiItem item = TestMarketFixtures.item("minecraft:emerald", 64);
        AuctionListing staleListing = TestMarketFixtures.listing(item, "1000", observedAt, "seller");
        List<CompletedSale> sales = TestMarketFixtures.repeatedSales(item, "100", 8, now);

        MarketAnalysis analysis = new OpportunityEngine().analyze(List.of(staleListing), sales,
                TestMarketFixtures.bankroll("2000000", now), now,
                now - Duration.ofMinutes(10).toMillis());

        assertTrue(analysis.opportunities().isEmpty());
        assertTrue(analysis.warnings().stream().anyMatch(warning -> warning.toLowerCase().contains("stale")));
    }

    @Test
    void staleManualBalanceBlocksCapitalRecommendationsUntilUpdated() {
        long now = System.currentTimeMillis();
        ApiItem item = TestMarketFixtures.item("minecraft:diamond", 64);
        AuctionListing listing = TestMarketFixtures.listing(item, "10000", now, "seller");
        List<CompletedSale> sales = TestMarketFixtures.repeatedSales(item, "1000", 8, now);
        AhProfitConfig staleBalance = TestMarketFixtures.bankroll("2000000", now - Duration.ofHours(25).toMillis());

        MarketAnalysis analysis = new OpportunityEngine().analyze(List.of(listing), sales, staleBalance, now, now);

        assertTrue(analysis.opportunities().isEmpty());
        assertTrue(analysis.bankroll().balanceMayBeStale());
        assertTrue(analysis.warnings().stream().anyMatch(warning -> warning.contains("older than 24 hours")));
    }

    @Test
    void insufficientSalesAndUnconfiguredBalanceNeverCreateRecommendations() {
        long now = System.currentTimeMillis();
        ApiItem item = TestMarketFixtures.item("minecraft:diamond", 64);
        AuctionListing listing = TestMarketFixtures.listing(item, "100", now, "seller");
        List<CompletedSale> sales = TestMarketFixtures.repeatedSales(item, "1000", 3, now);

        MarketAnalysis insufficient = new OpportunityEngine().analyze(List.of(listing), sales,
                TestMarketFixtures.bankroll("2000000", now), now, now);
        MarketAnalysis noBalance = new OpportunityEngine().analyze(List.of(listing),
                TestMarketFixtures.repeatedSales(item, "1000", 8, now),
                TestMarketFixtures.bankroll("0", 0), now, now);

        assertTrue(insufficient.opportunities().isEmpty());
        assertTrue(insufficient.skippedUncertainListings() > 0);
        assertTrue(noBalance.opportunities().isEmpty());
        assertTrue(noBalance.warnings().stream().anyMatch(warning -> warning.contains("No spendable balance")));
    }
}
