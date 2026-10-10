package dev.ahprofit.market;

import dev.ahprofit.TestMarketFixtures;
import dev.ahprofit.api.ApiItem;
import dev.ahprofit.api.AuctionListing;
import dev.ahprofit.api.CompletedSale;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PriceEstimatorTest {
    @Test
    void medianAndMedianAbsoluteDeviationResistOneExtremeSale() {
        long now = System.currentTimeMillis();
        ApiItem item = TestMarketFixtures.item("minecraft:gilded_blackstone", 64);
        AuctionListing candidate = TestMarketFixtures.listing(item, "5000", now, "candidate");
        List<CompletedSale> sales = new ArrayList<>(TestMarketFixtures.repeatedSales(item, "100", 4, now));
        sales.add(TestMarketFixtures.sale(item, "10000", now - Duration.ofHours(2).toMillis(), "outlier", 1));

        PriceEstimate estimate = new PriceEstimator().estimate(candidate, sales, List.of(candidate), now, 5, 300);

        assertEquals(new BigDecimal("100"), estimate.median7DayUnitPrice());
        assertEquals(new BigDecimal("100"), estimate.conservativeUnitPrice());
        assertEquals(5, estimate.comparableSales7Days());
        assertTrue(estimate.sufficientEvidence());
    }

    @Test
    void candidateAskCapsButNeverRaisesCompletedSaleEstimate() {
        long now = System.currentTimeMillis();
        ApiItem item = TestMarketFixtures.item("minecraft:diamond", 64);
        AuctionListing candidate = TestMarketFixtures.listing(item, "1000", now, "candidate");
        AuctionListing higherAsk = TestMarketFixtures.listing(item, "64000", now, "other");
        List<CompletedSale> sales = TestMarketFixtures.repeatedSales(item, "100", 8, now);

        PriceEstimate estimate = new PriceEstimator().estimate(candidate, sales,
                List.of(candidate, higherAsk), now, 5, 300);

        assertEquals(new BigDecimal("100"), estimate.conservativeUnitPrice());
        assertEquals(new BigDecimal("1000"), estimate.lowestCompetingUnitPrice());
    }

    @Test
    void lowVolumeIsFlaggedAndConfidenceIsCappedBelowRecommendationThreshold() {
        long now = System.currentTimeMillis();
        ApiItem item = TestMarketFixtures.item("minecraft:emerald", 16);
        AuctionListing candidate = TestMarketFixtures.listing(item, "1000", now, "candidate");
        List<CompletedSale> sales = TestMarketFixtures.repeatedSales(item, "200", 3, now);

        PriceEstimate estimate = new PriceEstimator().estimate(candidate, sales, List.of(candidate), now, 5, 300);

        assertFalse(estimate.sufficientEvidence());
        assertEquals(0.39, estimate.confidence(), 0.0000001);
        assertTrue(estimate.flags().stream().anyMatch(flag -> flag.contains("insufficient comparable sales")));
    }

    @Test
    void listingQuantityDoesNotChangeCommodityFingerprintButVariantDoes() {
        ApiItem stackOfSixtyFour = TestMarketFixtures.item("minecraft:diamond", 64);
        ApiItem singleItem = TestMarketFixtures.item("minecraft:diamond", 1);
        ItemFingerprint.Result first = ItemFingerprint.of(stackOfSixtyFour);
        ItemFingerprint.Result second = ItemFingerprint.of(singleItem);

        assertEquals(first.value(), second.value());
        assertTrue(first.comparable());

        ApiItem sharpFive = ApiItem.fromJson("""
                {"id":"minecraft:diamond_sword","count":1,"enchants":{"enchantments":{"levels":{"minecraft:sharpness":5}},"trim":{"material":"minecraft:gold","pattern":"minecraft:spire"}},"damage":10}
                """);
        ApiItem sharpSix = ApiItem.fromJson("""
                {"id":"minecraft:diamond_sword","count":1,"enchants":{"enchantments":{"levels":{"minecraft:sharpness":6}},"trim":{"material":"minecraft:gold","pattern":"minecraft:spire"}},"damage":10}
                """);
        ItemFingerprint.Result sharpFiveFingerprint = ItemFingerprint.of(sharpFive);
        ItemFingerprint.Result sharpSixFingerprint = ItemFingerprint.of(sharpSix);

        assertTrue(sharpFiveFingerprint.comparable());
        assertNotEquals(sharpFiveFingerprint.value(), sharpSixFingerprint.value());
        assertTrue(sharpFiveFingerprint.variantDescription().contains("sharpness 5"));
    }

    @Test
    void hiddenEquipmentVariantsWithoutCompleteAttributesAreNotCompared() {
        ApiItem unknownSword = TestMarketFixtures.item("minecraft:diamond_sword", 1);
        assertFalse(ItemFingerprint.of(unknownSword).comparable());
    }
}
