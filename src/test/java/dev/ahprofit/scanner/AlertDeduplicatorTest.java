package dev.ahprofit.scanner;

import dev.ahprofit.TestMarketFixtures;
import dev.ahprofit.api.ApiItem;
import dev.ahprofit.api.AuctionListing;
import dev.ahprofit.api.CompletedSale;
import dev.ahprofit.market.MarketAnalysis;
import dev.ahprofit.market.Opportunity;
import dev.ahprofit.market.OpportunityEngine;
import dev.ahprofit.market.PriceEstimate;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AlertDeduplicatorTest {
    @Test
    void suppressesStableRepeatsAndRealertsAfterMaterialConfidenceChange() {
        long now = System.currentTimeMillis();
        ApiItem item = TestMarketFixtures.item("minecraft:diamond", 64);
        AuctionListing listing = TestMarketFixtures.listing(item, "1000", now, "seller");
        List<CompletedSale> sales = TestMarketFixtures.repeatedSales(item, "1000", 8, now);
        MarketAnalysis analysis = new OpportunityEngine().analyze(List.of(listing), sales,
                TestMarketFixtures.bankroll("2000000", now), now, now);
        Opportunity original = analysis.opportunities().getFirst();
        AlertDeduplicator deduplicator = new AlertDeduplicator();

        assertEquals(1, deduplicator.collect(List.of(original), now).size());
        assertEquals(0, deduplicator.collect(List.of(original), now + 1_000).size());

        PriceEstimate oldEstimate = original.estimate();
        PriceEstimate changedEstimate = new PriceEstimate(oldEstimate.fingerprint(), oldEstimate.median24HourUnitPrice(),
                oldEstimate.median7DayUnitPrice(), oldEstimate.conservativeUnitPrice(), oldEstimate.trendPercent(),
                oldEstimate.volatilityFraction(), oldEstimate.comparableSales24Hours(), oldEstimate.comparableSales7Days(),
                oldEstimate.latestSaleAtMillis(), oldEstimate.unitsSoldPerDay(), oldEstimate.competingListings(),
                oldEstimate.competingQuantity(), oldEstimate.lowestCompetingUnitPrice(), oldEstimate.daysToClearProxy(),
                Math.min(1.0, oldEstimate.confidence() + 0.2), oldEstimate.liquidity(), oldEstimate.sufficientEvidence(),
                oldEstimate.askBased(),
                oldEstimate.flags());
        Opportunity changed = new Opportunity(original.listing(), changedEstimate, original.conservativeResaleRevenue(),
                original.assumedSellingFee(), original.riskBuffer(), original.estimatedNetProfit(), original.roiPercent(),
                original.discountPercent(), original.profitPerCapitalUnit(), original.score(), original.flipScore(),
                original.rationale(), original.flags());

        assertEquals(1, deduplicator.collect(List.of(changed), now + 2_000).size());
    }
}
