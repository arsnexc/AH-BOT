package dev.ahprofit.ah;

import dev.ahprofit.TestMarketFixtures;
import dev.ahprofit.api.ApiItem;
import dev.ahprofit.api.AuctionListing;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HoldingsTrackerTest {
    private final ApiItem diamond = TestMarketFixtures.item("minecraft:diamond", 64);

    private AuctionEntry entry(String total, int count) {
        BigDecimal price = new BigDecimal(total);
        BigDecimal unit = price.divide(BigDecimal.valueOf(count), java.math.MathContext.DECIMAL128);
        return new AuctionEntry(5, "minecraft:diamond", "Diamond", count, List.of(), unit, price, true);
    }

    @Test
    void recordingABuyKeepsItsUnitCost() {
        HoldingsTracker tracker = new HoldingsTracker();

        tracker.record(entry("128000", 64), new BigDecimal("128000"), System.currentTimeMillis());

        List<HoldingsTracker.Holding> holdings = tracker.holdings();
        assertEquals(1, holdings.size());
        assertEquals(64, holdings.get(0).count());
        assertEquals(0, new BigDecimal("2000").compareTo(holdings.get(0).unitCost()));
        assertFalse(tracker.isEmpty());
    }

    @Test
    void aBuyIsRefusedWithoutAPositiveSpend() {
        HoldingsTracker tracker = new HoldingsTracker();

        tracker.record(entry("128000", 64), BigDecimal.ZERO, System.currentTimeMillis());
        tracker.record(entry("128000", 64), null, System.currentTimeMillis());
        tracker.record(null, new BigDecimal("128000"), System.currentTimeMillis());

        assertTrue(tracker.isEmpty());
    }

    @Test
    void aSoldHoldingIsForgotten() {
        HoldingsTracker tracker = new HoldingsTracker();
        tracker.record(entry("128000", 64), new BigDecimal("128000"), System.currentTimeMillis());
        HoldingsTracker.Holding bought = tracker.holdings().get(0);

        tracker.forget(bought);

        assertTrue(tracker.isEmpty());
    }

    @Test
    void theAskBasedListPriceUndercutsTheRivalButNeverGoesBelowBreakEven() {
        HoldingsTracker tracker = new HoldingsTracker();
        tracker.record(entry("128000", 64), new BigDecimal("128000"), System.currentTimeMillis());
        HoldingsTracker.Holding holding = tracker.holdings().get(0);
        BigDecimal rival = new BigDecimal("2200");
        BigDecimal undercut = new BigDecimal("2");

        // 2200 * 0.98 * 64 = 138,112, well above the 128,000 break-even.
        BigDecimal target = HoldingsTracker.recommendedAskBasedListPrice(rival, 64, undercut, holding);

        assertEquals(0, new BigDecimal("138112").compareTo(target));
    }

    @Test
    void theAskBasedListPriceRisesToBreakEvenWhenTheRivalIsTooCheap() {
        HoldingsTracker tracker = new HoldingsTracker();
        tracker.record(entry("128000", 64), new BigDecimal("128000"), System.currentTimeMillis());
        HoldingsTracker.Holding holding = tracker.holdings().get(0);

        // 1900 * 0.98 * 64 = 119,168 is below the 128,000 cost, so break-even must win.
        BigDecimal target = HoldingsTracker.recommendedAskBasedListPrice(new BigDecimal("1900"), 64,
                new BigDecimal("2"), holding);

        assertEquals(0, new BigDecimal("128000").compareTo(target));
    }

    @Test
    void noRivalAskMeansNoPriceRecommendation() {
        HoldingsTracker tracker = new HoldingsTracker();
        tracker.record(entry("128000", 64), new BigDecimal("128000"), System.currentTimeMillis());

        assertNull(HoldingsTracker.recommendedAskBasedListPrice(null, 64, new BigDecimal("2"),
                tracker.holdings().get(0)));
    }

    @Test
    void anEmptyTrackerSummarisesAsNothing() {
        assertEquals("nothing bought this session", HoldingsTracker.summarise(List.of()));
        assertEquals("nothing bought this session", HoldingsTracker.summarise(null));
    }
}
