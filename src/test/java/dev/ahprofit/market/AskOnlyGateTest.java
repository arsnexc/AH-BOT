package dev.ahprofit.market;

import dev.ahprofit.TestMarketFixtures;
import dev.ahprofit.api.ApiItem;
import dev.ahprofit.api.AuctionListing;
import dev.ahprofit.config.AhProfitConfig;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ask-only regime must actually rank something from a realistic browse, and must say why it did
 * not when it did not. All prices here are whole-stack totals, which is what a listing carries.
 */
class AskOnlyGateTest {
    /** 2,200 per diamond in a 64-stack. */
    private static final BigDecimal RIVAL_TOTAL = new BigDecimal("140800");
    /** 18% below the rivals: the mispricing a harvester is meant to find. */
    private static final BigDecimal CHEAP_TOTAL = new BigDecimal("115456");

    private final ApiItem diamond = TestMarketFixtures.item("minecraft:diamond", 64);

    private List<AuctionListing> market(long now, BigDecimal cheap, int rivals) {
        List<AuctionListing> listings = new ArrayList<>();
        listings.add(TestMarketFixtures.listing(diamond, cheap.toPlainString(), now, "cheap"));
        for (int index = 0; index < rivals; index++) {
            listings.add(TestMarketFixtures.listing(diamond, RIVAL_TOTAL.toPlainString(), now,
                    "rival" + index));
        }
        return listings;
    }

    private AhProfitConfig askOnlyConfig(long now) {
        return AhProfitConfig.defaults()
                .withAskOnlyAnalysis(true)
                .withManualBalance(new BigDecimal("2000000"), now);
    }

    private MarketAnalysis analyse(List<AuctionListing> listings, AhProfitConfig config) {
        long now = System.currentTimeMillis();
        return new OpportunityEngine().analyze(listings, List.of(), config, now, now);
    }

    @Test
    void aRealSpreadIsRankedWithoutAnyCompletedSales() {
        long now = System.currentTimeMillis();
        List<AuctionListing> listings = market(now, CHEAP_TOTAL, 2);

        MarketAnalysis analysis = analyse(listings, askOnlyConfig(now));

        assertFalse(analysis.opportunities().isEmpty(),
                "a listing well below its rivals must be ranked with zero sales: "
                        + analysis.warnings());
        Opportunity best = analysis.opportunities().get(0);
        assertTrue(best.estimate().askBased(), "the estimate must be labelled ask-based");
        assertTrue(best.estimatedNetProfit().signum() > 0, "the ranked flip must be profitable");
        assertTrue(best.roiPercent().compareTo(new BigDecimal("5")) >= 0,
                "the ranked flip must clear the ask-only ROI floor");
    }

    @Test
    void withAskOnlyOffTheSameMarketRanksNothing() {
        long now = System.currentTimeMillis();
        AhProfitConfig config = askOnlyConfig(now).withAskOnlyAnalysis(false);
        List<AuctionListing> listings = market(now, CHEAP_TOTAL, 2);

        MarketAnalysis analysis = analyse(listings, config);

        assertTrue(analysis.opportunities().isEmpty(),
                "with the flag off and no sales there is nothing sale-backed to rank");
    }

    @Test
    void aTinySpreadIsNotRankedButIsExplained() {
        long now = System.currentTimeMillis();
        // Exactly the resale ceiling, so fees and risk make it a loss.
        List<AuctionListing> listings = market(now, RIVAL_TOTAL.multiply(new BigDecimal("0.98")), 2);

        MarketAnalysis analysis = analyse(listings, askOnlyConfig(now));

        assertTrue(analysis.opportunities().isEmpty(), "a 2% spread must not survive the fee haircut");
        assertTrue(analysis.warnings().stream().anyMatch(w -> w.contains("No flip passed")),
                "an empty result must say why, not fail silently: " + analysis.warnings());
    }

    @Test
    void aLoneListingWithNoRivalsIsNotRanked() {
        long now = System.currentTimeMillis();
        List<AuctionListing> listings = List.of(
                TestMarketFixtures.listing(diamond, CHEAP_TOTAL.toPlainString(), now, "only-one"));

        MarketAnalysis analysis = analyse(listings, askOnlyConfig(now));

        assertTrue(analysis.opportunities().isEmpty(), "one listing is not a market");
        assertTrue(analysis.warnings().stream().anyMatch(w -> w.contains("too few rival asks")),
                "the reason must name the missing rivals: " + analysis.warnings());
    }

    @Test
    void twoListingsAreEnoughEvidenceWithTheNewDefault() {
        long now = System.currentTimeMillis();
        AhProfitConfig config = askOnlyConfig(now);
        assertEquals(2, config.askMinimumListings(), "the default must allow a sparse browse");

        MarketAnalysis analysis = analyse(market(now, CHEAP_TOTAL, 2), config);

        assertFalse(analysis.opportunities().isEmpty(), "two comparable asks must be enough evidence");
    }

    @Test
    void theProfitFloorIsRelativeToTheListingNotTheBankroll() {
        long now = System.currentTimeMillis();
        // 2% of a 2,000,000 bankroll would be 40,000 and would reject this flip; 2% of the
        // listing's own price is 2,309 and lets a real spread through.
        MarketAnalysis analysis = analyse(market(now, CHEAP_TOTAL, 2), askOnlyConfig(now));

        assertFalse(analysis.opportunities().isEmpty(),
                "the ask-only floor must not be an absolute fraction of a guessed bankroll: "
                        + analysis.warnings());
    }

    @Test
    void staleRecordingsNeverReachTheRanking() {
        long stale = System.currentTimeMillis() - Duration.ofHours(6).toMillis();
        List<AuctionListing> listings = market(stale, CHEAP_TOTAL, 2);

        MarketAnalysis analysis = analyse(listings, askOnlyConfig(System.currentTimeMillis()));

        assertTrue(analysis.opportunities().isEmpty(), "a listing seen six hours ago is not an ask");
        assertTrue(analysis.warnings().stream().anyMatch(w -> w.contains("stale")),
                "stale records must be reported: " + analysis.warnings());
    }

    @Test
    void anEmptyResultNamesEveryReasonItDroppedListings() {
        long now = System.currentTimeMillis();
        List<AuctionListing> listings = market(now, CHEAP_TOTAL, 2);
        // Remove the balance so the no-balance counter fires alongside the others.
        AhProfitConfig config = AhProfitConfig.defaults().withAskOnlyAnalysis(true);

        MarketAnalysis analysis = analyse(listings, config);

        assertTrue(analysis.opportunities().isEmpty());
        assertTrue(analysis.warnings().stream().anyMatch(w -> w.contains("no configured balance")),
                "the missing balance must be named: " + analysis.warnings());
    }
}
