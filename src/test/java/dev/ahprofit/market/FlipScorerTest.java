package dev.ahprofit.market;

import dev.ahprofit.TestMarketFixtures;
import dev.ahprofit.api.ApiItem;
import dev.ahprofit.api.AuctionListing;
import dev.ahprofit.api.CompletedSale;
import dev.ahprofit.bankroll.BankrollLimits;
import dev.ahprofit.bankroll.BankrollManager;
import dev.ahprofit.config.AhProfitConfig;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlipScorerTest {
    private static final long MAX_LISTING_AGE = Duration.ofSeconds(300).toMillis();

    @Test
    void capitalEfficiencyOutranksAbsoluteProfitAtSimilarTurnover() {
        long now = System.currentTimeMillis();
        ApiItem diamond = TestMarketFixtures.item("minecraft:diamond", 64);
        BankrollLimits bankroll = BankrollManager.calculate(TestMarketFixtures.bankroll("100000", now), now);
        List<CompletedSale> sales = TestMarketFixtures.repeatedSales(diamond, "300", 12, now);
        AuctionListing cheap = TestMarketFixtures.listing(diamond, "8000", now, "cheap");
        AuctionListing costly = TestMarketFixtures.listing(diamond, "15000", now, "costly");
        PriceEstimate estimate = new PriceEstimator().estimate(cheap, sales, List.of(cheap), now, 5, 300);

        FlipScore cheapFlip = FlipScorer.score(cheap, estimate, new BigDecimal("9280"),
                new BigDecimal("116"), bankroll, now, MAX_LISTING_AGE);
        FlipScore costlyFlip = FlipScorer.score(costly, estimate, new BigDecimal("2280"),
                new BigDecimal("15"), bankroll, now, MAX_LISTING_AGE);

        assertTrue(cheapFlip.total() > costlyFlip.total(),
                "the cheap flip should outrank the expensive one: " + cheapFlip.total() + " vs " + costlyFlip.total());
        assertTrue(cheapFlip.efficiencyComponent() > costlyFlip.efficiencyComponent());
        assertEquals(1.0, cheapFlip.efficiencyComponent(), 0.0001);
        assertTrue(cheapFlip.profitPerCapitalPerDay() > costlyFlip.profitPerCapitalPerDay());
    }

    @Test
    void slowClearingInventoryLosesToFastTurnover() {
        long now = System.currentTimeMillis();
        ApiItem single = TestMarketFixtures.item("minecraft:diamond", 1);
        List<CompletedSale> slowSales = TestMarketFixtures.repeatedSales(single, "300", 6, now);
        List<CompletedSale> fastSales = TestMarketFixtures.repeatedSales(
                TestMarketFixtures.item("minecraft:diamond", 64), "300", 12, now);
        AuctionListing stack = TestMarketFixtures.listing(TestMarketFixtures.item("minecraft:diamond", 64),
                "8000", now, "cheap");
        BankrollLimits bankroll = BankrollManager.calculate(TestMarketFixtures.bankroll("100000", now), now);

        PriceEstimate slow = new PriceEstimator().estimate(stack, slowSales, List.of(stack), now, 5, 300);
        PriceEstimate fast = new PriceEstimator().estimate(stack, fastSales, List.of(stack), now, 5, 300);

        FlipScore slowScore = FlipScorer.score(stack, slow, new BigDecimal("9280"), new BigDecimal("116"),
                bankroll, now, MAX_LISTING_AGE);
        FlipScore fastScore = FlipScorer.score(stack, fast, new BigDecimal("9280"), new BigDecimal("116"),
                bankroll, now, MAX_LISTING_AGE);

        assertTrue(slow.daysToClearProxy() > fast.daysToClearProxy());
        assertTrue(slowScore.clearancePenalty() > fastScore.clearancePenalty());
        assertTrue(slowScore.total() < fastScore.total());
        assertTrue(slowScore.penalties().stream().anyMatch(penalty -> penalty.contains("days to clear")));
    }

    @Test
    void fallingMarketIsPenalisedAndFlatMarketIsNot() {
        long now = System.currentTimeMillis();
        ApiItem diamond = TestMarketFixtures.item("minecraft:diamond", 64);
        BankrollLimits bankroll = BankrollManager.calculate(TestMarketFixtures.bankroll("100000", now), now);
        AuctionListing listing = TestMarketFixtures.listing(diamond, "8000", now, "cheap");

        List<CompletedSale> falling = new ArrayList<>();
        for (int index = 0; index < 3; index++) {
            falling.add(TestMarketFixtures.sale(diamond, "100", now - Duration.ofHours(index + 1L).toMillis(),
                    "recent-" + index, 1));
        }
        for (int index = 0; index < 5; index++) {
            falling.add(TestMarketFixtures.sale(diamond, "200",
                    now - Duration.ofHours(30L + index * 10L).toMillis(), "old-" + index, 2));
        }
        PriceEstimate fallingEstimate = new PriceEstimator().estimate(listing, falling, List.of(listing), now, 5, 300);

        FlipScore fallingScore = FlipScorer.score(listing, fallingEstimate, new BigDecimal("9280"),
                new BigDecimal("116"), bankroll, now, MAX_LISTING_AGE);
        FlipScore flatScore = FlipScorer.score(listing,
                new PriceEstimator().estimate(listing, TestMarketFixtures.repeatedSales(diamond, "300", 12, now),
                        List.of(listing), now, 5, 300),
                new BigDecimal("9280"), new BigDecimal("116"), bankroll, now, MAX_LISTING_AGE);

        assertTrue(fallingEstimate.trendPercent().signum() < 0, "expected a negative 24-hour trend");
        assertTrue(fallingScore.trendPenalty() > 0);
        assertEquals(0.0, flatScore.trendPenalty(), 0.0001);
        assertTrue(fallingScore.total() < flatScore.total());
    }

    @Test
    void sellThroughProbabilityStaysBounded() {
        long now = System.currentTimeMillis();
        ApiItem diamond = TestMarketFixtures.item("minecraft:diamond", 64);
        AuctionListing listing = TestMarketFixtures.listing(diamond, "8000", now, "cheap");
        PriceEstimate strong = new PriceEstimator().estimate(listing,
                TestMarketFixtures.repeatedSales(diamond, "300", 20, now), List.of(listing), now, 5, 300);

        double bounded = FlipScorer.sellThroughProbability(strong, FlipScorer.expectedDaysToSell(strong));
        assertTrue(bounded > 0 && bounded <= 0.95, "sell-through must be a probability, was " + bounded);

        PriceEstimate weak = PriceEstimate.noEvidence("fingerprint", "no comparable sales");
        assertEquals(0.02, FlipScorer.sellThroughProbability(weak, FlipScorer.expectedDaysToSell(weak)), 0.0001);
    }

    @Test
    void totalIsAlwaysFiniteAndInsideZeroToOneHundred() {
        long now = System.currentTimeMillis();
        ApiItem diamond = TestMarketFixtures.item("minecraft:diamond", 64);
        BankrollLimits bankroll = BankrollManager.calculate(TestMarketFixtures.bankroll("100000", now), now);
        AuctionListing listing = TestMarketFixtures.listing(diamond, "8000", now, "cheap");
        PriceEstimate estimate = PriceEstimate.noEvidence("fingerprint", "no comparable completed sales");

        FlipScore score = FlipScorer.score(listing, estimate, new BigDecimal("9280"),
                new BigDecimal("116"), bankroll, now, MAX_LISTING_AGE);

        assertTrue(score.total() >= 0 && score.total() <= 100);
        assertEquals(0.0, score.confidenceComponent(), 0.0001);
    }

    @Test
    void engineRanksTheCheaperFlipFirstWhenBothQualify() {
        long now = System.currentTimeMillis();
        ApiItem diamond = TestMarketFixtures.item("minecraft:diamond", 64);
        AhProfitConfig config = TestMarketFixtures.bankroll("100000", now);
        List<CompletedSale> sales = TestMarketFixtures.repeatedSales(diamond, "300", 12, now);
        AuctionListing cheap = TestMarketFixtures.listing(diamond, "8000", now, "cheap");
        AuctionListing costly = TestMarketFixtures.listing(diamond, "15000", now, "costly");

        MarketAnalysis analysis = new OpportunityEngine().analyze(List.of(cheap, costly), sales, config, now, now);

        assertEquals(1, analysis.opportunities().size());
        assertEquals(cheap.listingKey(), analysis.opportunities().getFirst().listing().listingKey());
        assertTrue(analysis.opportunities().getFirst().flipScore().total() > 50);
    }

    @Test
    void lowConfidenceAndSlowTurnoverListingsAreNotRecommended() {
        long now = System.currentTimeMillis();
        ApiItem single = TestMarketFixtures.item("minecraft:diamond", 1);
        AhProfitConfig config = TestMarketFixtures.bankroll("100000", now);
        List<CompletedSale> thinSales = TestMarketFixtures.repeatedSales(single, "300", 6, now);
        AuctionListing stack = TestMarketFixtures.listing(TestMarketFixtures.item("minecraft:diamond", 64),
                "8000", now, "cheap");

        MarketAnalysis analysis = new OpportunityEngine().analyze(List.of(stack), thinSales, config, now, now);

        assertTrue(analysis.opportunities().isEmpty());
        assertTrue(analysis.skippedUncertainListings() > 0);
    }
}
