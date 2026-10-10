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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ask-only regime must actually rank something from a realistic browse, and must say why it
 * did not when it did not.
 */
class AskOnlyGateTest {
    private final ApiItem diamond = TestMarketFixtures.item("minecraft:diamond", 64);

    /** One cheap listing surrounded by dearer rivals for the same fingerprint. */
    private List<AuctionListing> market(long now, BigDecimal cheap, BigDecimal... rivals) {
        List<AuctionListing> listings = new ArrayList<>();
        listings.add(TestMarketFixtures.listing(diamond, cheap.toPlainString(), now, "cheap"));
        BigDecimal[] prices = rivals.length == 0
                ? new BigDecimal[]{new BigDecimal("2200"), new BigDecimal("2200")}
                : rivals;
        for (int index = 0; index < prices.length; index++) {
            listings.add(TestMarketFixtures.listing(diamond, prices[index].toPlainString(), now,
                    "rival" + index));
        }
        return listings;
    }

    private MarketAnalysis analyse(List<AuctionListing> listings, AhProfitConfig config) {
        long now = System.currentTimeMillis();
        return new OpportunityEngine().analyze(listings, List.of(), config, now, now);
    }

    @Test
    void aRealSpreadIsRankedWithoutAnyCompletedSales() {
        long now = System.currentTimeMillis();
        AhProfitConfig config = AhProfitConfig.defaults().withAskOnlyAnalysis(true);
        // 18% below the 2200 rivals: 0.82 * 2200 * 64 = 115,456.
        List<AuctionListing> listings = market(now, new BigDecimal("115456"));

        MarketAnalysis analysis = analyse(listings, config);

        assertFalse(analysis.opportunities().isEmpty(),
                "a listing well below its rivals must be ranked with zero sales");
        Opportunity best = analysis.opportunities().get(0);
        assertTrue(best.estimate().askBased(), "the estimate must be labelled ask-based");
        assertEquals(0, best.netProfit().signum());
    }

    @Test
    void withAskOnlyOffTheSameMarketRanksNothing() {
        long now = System.currentTimeMillis();
        AhProfitConfig config = AhProfitConfig.defaults().withAskOnlyAnalysis(false);
        List<AuctionListing> listings = market(now, new BigDecimal("115456"));

        MarketAnalysis analysis = analyse(listings, config);

        assertTrue(analysis.opportunities().isEmpty(),
                "with the flag off and no sales there is nothing sale-backed to rank");
    }

    @Test
    void aTinySpreadIsNotRankedButIsExplained() {
        long now = System.currentTimeMillis();
        AhProfitConfig config = AhProfitConfig.defaults().withAskOnlyAnalysis(true);
        // Only 2% below the rivals, so fees and risk eat it.
        List<AuctionListing> listings = market(now, new BigDecimal("137984"));

        MarketAnalysis analysis = analyse(listings, config);

        assertTrue(analysis.opportunities().isEmpty(), "a 2% spread must not survive the fee haircut");
        assertTrue(analysis.warnings().stream().anyMatch(warning -> warning.contains("No flip passed")),
                "an empty result must say why, not fail silently: " + analysis.warnings());
    }

    @Test
    void aLoneListingWithNoRivalsIsNotRanked() {
        long now = System.currentTimeMillis();
        AhProfitConfig config = AhProfitConfig.defaults().withAskOnlyAnalysis(true);
        List<AuctionListing> listings = List.of(
                TestMarketFixtures.listing(diamond, "50000", now, "only-one"));

        MarketAnalysis analysis = analyse(listings, config);

        assertTrue(analysis.opportunities().isEmpty(), "one listing is not a market");
        assertTrue(analysis.warnings().stream()
                        .anyMatch(warning -> warning.contains("too few rival asks")),
                "the reason must name the missing rivals: " + analysis.warnings());
    }

    @Test
    void twoListingsAreEnoughEvidenceWithTheNewDefault() {
        long now = System.currentTimeMillis();
        AhProfitConfig config = AhProfitConfig.defaults().withAskOnlyAnalysis(true);
        assertEquals(2, config.askMinimumListings(), "the default must allow a sparse browse");

        List<AuctionListing> listings = market(now, new BigDecimal("115456"),
                new BigDecimal("2200"), new BigDecimal("2200"));

        MarketAnalysis analysis = analyse(listings, config);

        assertFalse(analysis.opportunities().isEmpty(), "two comparable asks must be enough evidence");
    }

    @Test
    void theProfitFloorIsRelativeToTheListingNotTheBankroll() {
        long now = System.currentTimeMillis();
        // A large bankroll would make the absolute 2% floor 40,000 and reject this flip.
        AhProfitConfig config = AhProfitConfig.defaults().withAskOnlyAnalysis(true)
                .withManualBalance(new BigDecimal("2000000"), now);
        List<AuctionListing> listings = market(now, new BigDecimal("115456"));

        // A 2% absolute floor on a 2,000,000 bankroll would be 40,000 and would reject this flip;
        // a listing-relative floor of 2% of 115,456 is 2,309 and lets it through.
        MarketAnalysis analysis = analyse(listings, config);

        assertNotNull(analysis.opportunities());
        assertFalse(analysis.opportunities().isEmpty(),
                "the ask-only floor must not be an absolute fraction of a guessed bankroll");
    }

    @Test
    void staleRecordingsNeverReachTheRanking() {
        long stale = System.currentTimeMillis() - Duration.ofHours(6).toMillis();
        AhProfitConfig config = AhProfitConfig.defaults().withAskOnlyAnalysis(true);
        List<AuctionListing> listings = market(stale, new BigDecimal("115456"));

        MarketAnalysis analysis = analyse(listings, config);

        assertTrue(analysis.opportunities().isEmpty(), "a listing seen six hours ago is not an ask");
        assertTrue(analysis.warnings().stream().anyMatch(w -> w.contains("stale")),
                "stale records must be reported: " + analysis.warnings());
    }
}
