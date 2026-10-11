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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ask-based estimator must not let one bad number describe the whole market.
 */
class RobustAskEstimatorTest {
    private static final int MIN_LISTINGS = 4;
    private static final BigDecimal MIN_SPREAD = new BigDecimal("15");
    private static final BigDecimal UNDERCUT = new BigDecimal("2");
    private static final int MAX_AGE_SECONDS = 300;

    private final ApiItem diamond = TestMarketFixtures.item("minecraft:diamond", 64);

    /** Rival listings priced per unit; totals are the stack price. */
    private List<AuctionListing> rivals(long now, String... unitPrices) {
        List<AuctionListing> result = new ArrayList<>();
        for (int index = 0; index < unitPrices.length; index++) {
            BigDecimal total = new BigDecimal(unitPrices[index]).multiply(BigDecimal.valueOf(diamond.count()));
            result.add(TestMarketFixtures.listing(diamond, total.toPlainString(), now, "rival-" + index));
        }
        return result;
    }

    private PriceEstimate estimate(AuctionListing candidate, List<AuctionListing> market, long now) {
        return new PriceEstimator().estimateFromAsks(candidate, market, now, MIN_LISTINGS, MIN_SPREAD,
                UNDERCUT, MAX_AGE_SECONDS);
    }

    @Test
    void aDumpedListingDoesNotSetTheResaleCeilingForTheWholeMarket() {
        long now = System.currentTimeMillis();
        AuctionListing candidate = TestMarketFixtures.listing(diamond, "115456", now, "cheap");
        // Four genuine asks at 2,200 and one dump at 40. The dump must not become the ceiling.
        List<AuctionListing> market = rivals(now, "2200", "2200", "2200", "2200", "40");

        PriceEstimate result = estimate(candidate, market, now);

        assertEquals(new BigDecimal("2156"), result.conservativeUnitPrice(),
                "the ceiling must come from the credible market, not from a dump");
        assertEquals(new BigDecimal("2200"), result.lowestCompetingUnitPrice(),
                "the dump must not be reported as the cheapest competing ask");
        assertTrue(result.flags().stream().anyMatch(flag -> flag.contains("ignored as outliers")),
                "the discarded dump must be disclosed: " + result.flags());
    }

    @Test
    void aGenuinelyCheapMarketStillLowersTheCeiling() {
        long now = System.currentTimeMillis();
        AuctionListing candidate = TestMarketFixtures.listing(diamond, "115456", now, "cheap");
        // Every ask really is low, so the low price is the market and must be honoured.
        List<AuctionListing> market = rivals(now, "1900", "1950", "2000", "2050");

        PriceEstimate result = estimate(candidate, market, now);

        assertEquals(new BigDecimal("1862"), result.conservativeUnitPrice(),
                "a genuinely cheap market must still set a low ceiling");
        assertTrue(result.flags().stream().noneMatch(flag -> flag.contains("ignored as outliers")),
                "no ask should be discarded when they all agree: " + result.flags());
    }

    @Test
    void anOutlierBandStillWorksWhenEveryGenuineAskIsIdentical() {
        long now = System.currentTimeMillis();
        AuctionListing candidate = TestMarketFixtures.listing(diamond, "115456", now, "cheap");
        // With five identical asks the MAD collapses to zero, so the relative band must carry it.
        List<AuctionListing> market = rivals(now, "2000", "2000", "2000", "2000", "2000", "10");

        PriceEstimate result = estimate(candidate, market, now);

        assertEquals(new BigDecimal("1960"), result.conservativeUnitPrice());
        assertTrue(result.flags().stream().anyMatch(flag -> flag.contains("ignored as outliers")));
    }

    @Test
    void anImpossibleFlipIsNamedRatherThanSilentlyScored() {
        long now = System.currentTimeMillis();
        // Priced exactly at the cheapest rival, so the undercut ceiling sits below the ask.
        AuctionListing candidate = TestMarketFixtures.listing(diamond, "140800", now, "at-market");
        List<AuctionListing> market = rivals(now, "2200", "2200", "2200", "2200");

        PriceEstimate result = estimate(candidate, market, now);

        assertFalse(result.sufficientEvidence(), "a flip whose ceiling is below its ask cannot work");
        assertTrue(result.flags().stream().anyMatch(flag -> flag.contains("does not clear")),
                "the impossibility must be explained: " + result.flags());
    }

    @Test
    void anExtremeSpreadIsTreatedAsSuspiciousNotAsCertainty() {
        long now = System.currentTimeMillis();
        // A 96% spread is far more likely a misread price than a real mispricing.
        AuctionListing candidate = TestMarketFixtures.listing(diamond, "5632", now, "suspicious");
        List<AuctionListing> market = rivals(now, "2200", "2200", "2200", "2200");

        PriceEstimate wide = estimate(candidate, market, now);

        AuctionListing sane = TestMarketFixtures.listing(diamond, "115456", now, "sane");
        PriceEstimate normal = estimate(sane, market, now);

        assertTrue(wide.flags().stream().anyMatch(flag -> flag.contains("misread price")),
                "an extreme spread must be flagged: " + wide.flags());
        assertTrue(wide.confidence() < normal.confidence(),
                "an extreme spread must lower, not raise, confidence: wide=" + wide.confidence()
                        + " normal=" + normal.confidence());
    }

    @Test
    void aNormalSpreadIsNotTreatedAsSuspicious() {
        long now = System.currentTimeMillis();
        AuctionListing candidate = TestMarketFixtures.listing(diamond, "115456", now, "cheap");
        List<AuctionListing> market = rivals(now, "2200", "2200", "2200", "2200");

        PriceEstimate result = estimate(candidate, market, now);

        assertTrue(result.flags().stream().noneMatch(flag -> flag.contains("misread price")),
                "an 18% spread is ordinary and must not be flagged: " + result.flags());
        assertTrue(result.sufficientEvidence());
        assertEquals(new BigDecimal("2156"), result.conservativeUnitPrice());
    }
}
