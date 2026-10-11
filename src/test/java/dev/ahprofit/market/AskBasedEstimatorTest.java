package dev.ahprofit.market;

import dev.ahprofit.TestMarketFixtures;
import dev.ahprofit.api.ApiItem;
import dev.ahprofit.api.AuctionListing;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AskBasedEstimatorTest {
    private static final int MIN_LISTINGS = 4;
    private static final BigDecimal MIN_SPREAD = new BigDecimal("35");
    private static final BigDecimal UNDERCUT = new BigDecimal("2");

    @Test
    void anUndercutListingIsValuedFromTheMarketAsks() {
        long now = System.currentTimeMillis();
        ApiItem diamond = TestMarketFixtures.item("minecraft:diamond", 64);
        AuctionListing candidate = TestMarketFixtures.listing(diamond, "32000", now, "cheap");
        List<AuctionListing> market = market(diamond, now, "2000", "2000", "2000", "2000", "2000");

        PriceEstimate estimate = new PriceEstimator().estimateFromAsks(candidate, market, now,
                MIN_LISTINGS, MIN_SPREAD, UNDERCUT, 300);

        assertTrue(estimate.askBased(), "the estimate must be marked ask-based");
        assertTrue(estimate.sufficientEvidence());
        assertEquals(new BigDecimal("2000"), estimate.median7DayUnitPrice());
        // The resale ceiling is the cheapest rival ask undercut by 2%, never the market median.
        assertEquals(new BigDecimal("1960"), estimate.conservativeUnitPrice());
        assertEquals(0, estimate.comparableSales7Days());
        assertTrue(estimate.flags().stream().anyMatch(flag -> flag.startsWith("ASK-BASED")));
    }

    @Test
    void aListingAtMarketPriceIsNotValuedAsAFlip() {
        long now = System.currentTimeMillis();
        ApiItem diamond = TestMarketFixtures.item("minecraft:diamond", 64);
        AuctionListing candidate = TestMarketFixtures.listing(diamond, "128000", now, "market");
        List<AuctionListing> market = market(diamond, now, "2000", "2000", "2000", "2000");

        PriceEstimate estimate = new PriceEstimator().estimateFromAsks(candidate, market, now,
                MIN_LISTINGS, MIN_SPREAD, UNDERCUT, 300);

        assertFalse(estimate.sufficientEvidence(), "no spread means no flip");
        assertTrue(estimate.flags().stream().anyMatch(flag -> flag.contains("asking-price spread")));
    }

    @Test
    void tooFewListingsMeansNoEvidence() {
        long now = System.currentTimeMillis();
        ApiItem diamond = TestMarketFixtures.item("minecraft:diamond", 64);
        AuctionListing candidate = TestMarketFixtures.listing(diamond, "1000", now, "cheap");
        List<AuctionListing> market = market(diamond, now, "2000", "2000");

        PriceEstimate estimate = new PriceEstimator().estimateFromAsks(candidate, market, now,
                MIN_LISTINGS, MIN_SPREAD, UNDERCUT, 300);

        assertFalse(estimate.sufficientEvidence());
        assertTrue(estimate.flags().stream().anyMatch(flag -> flag.contains("at least 4")));
    }

    @Test
    void staleAndDifferentVariantListingsDoNotCount() {
        long now = System.currentTimeMillis();
        ApiItem diamond = TestMarketFixtures.item("minecraft:diamond", 64);
        ApiItem emerald = TestMarketFixtures.item("minecraft:emerald", 64);
        AuctionListing candidate = TestMarketFixtures.listing(diamond, "32000", now, "cheap");
        List<AuctionListing> market = new ArrayList<>();
        market.add(TestMarketFixtures.listing(diamond, "128000", now - 3_600_000L, "stale"));
        market.add(TestMarketFixtures.listing(emerald, "128000", now, "other-item"));
        market.addAll(market(diamond, now, "2000", "2000"));

        PriceEstimate estimate = new PriceEstimator().estimateFromAsks(candidate, market, now,
                MIN_LISTINGS, MIN_SPREAD, UNDERCUT, 300);

        assertFalse(estimate.sufficientEvidence(), "stale and other-item listings must be ignored");
    }

    @Test
    void theCandidateItselfIsExcludedFromTheMarketReference() {
        long now = System.currentTimeMillis();
        ApiItem diamond = TestMarketFixtures.item("minecraft:diamond", 64);
        AuctionListing candidate = TestMarketFixtures.listing(diamond, "32000", now, "cheap");
        List<AuctionListing> market = market(diamond, now, "2000", "2000", "2000", "2000", "2000");
        market = new ArrayList<>(market);
        market.add(candidate);

        PriceEstimate estimate = new PriceEstimator().estimateFromAsks(candidate, market, now,
                MIN_LISTINGS, MIN_SPREAD, UNDERCUT, 300);

        assertEquals(new BigDecimal("2000"), estimate.median7DayUnitPrice(),
                "the candidate's own ask must not drag the market reference down");
    }

    @Test
    void theEngineRanksAskOnlyFlipsWhenEnabledAndSkipsThemWhenDisabled() {
        long now = System.currentTimeMillis();
        ApiItem diamond = TestMarketFixtures.item("minecraft:diamond", 64);
        AuctionListing candidate = TestMarketFixtures.listing(diamond, "32000", now, "cheap");
        List<AuctionListing> market = market(diamond, now, "2000", "2000", "2000", "2000", "2000");
        List<AuctionListing> all = new ArrayList<>(market);
        all.add(candidate);

        MarketAnalysis enabled = new OpportunityEngine().analyze(all, List.of(),
                TestMarketFixtures.bankroll("2000000", now).withAskOnlyAnalysis(true), now, now);
        MarketAnalysis disabled = new OpportunityEngine().analyze(all, List.of(),
                TestMarketFixtures.bankroll("2000000", now), now, now);

        assertTrue(disabled.opportunities().isEmpty(), "without sales and without ask-only, nothing qualifies");
        assertEquals(1, enabled.opportunities().size());
        assertTrue(enabled.opportunities().getFirst().estimate().askBased());
        assertTrue(enabled.opportunities().getFirst().estimatedNetProfit().signum() > 0);
        assertNotNull(enabled.opportunities().getFirst().flipScore());
    }

    private static List<AuctionListing> market(ApiItem item, long now, String... unitPrices) {
        List<AuctionListing> result = new ArrayList<>();
        for (int index = 0; index < unitPrices.length; index++) {
            BigDecimal total = new BigDecimal(unitPrices[index]).multiply(BigDecimal.valueOf(item.count()));
            result.add(TestMarketFixtures.listing(item, total.toPlainString(), now, "rival-" + index));
        }
        return result;
    }
}
