package dev.ahprofit.ah;

import dev.ahprofit.TestMarketFixtures;
import dev.ahprofit.api.ApiItem;
import dev.ahprofit.api.AuctionListing;
import dev.ahprofit.api.CompletedSale;
import dev.ahprofit.bankroll.BankrollLimits;
import dev.ahprofit.bankroll.BankrollManager;
import dev.ahprofit.config.AhProfitConfig;
import dev.ahprofit.market.MarketAnalysis;
import dev.ahprofit.market.OpportunityEngine;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutomationPlannerTest {
    private static final BigDecimal HOURLY_CAP = new BigDecimal("5000000");

    @Test
    void automationIsOffByDefaultSoNothingIsEverClicked() {
        Fixture fixture = new Fixture(false);
        AutomationPlanner.Decision decision = AutomationPlanner.plan(fixture.snapshot, fixture.analysis,
                fixture.config, fixture.bankroll, fixture.budget, fixture.now);

        assertEquals(AutomationPlanner.Kind.BLOCKED, decision.kind());
        assertTrue(decision.reason().contains("switched off"));
    }

    @Test
    void dryRunReportsButNeverReturnsABuy() {
        Fixture fixture = new Fixture(true);
        AhProfitConfig dryRun = fixture.config.withAutomationDryRun(true);
        AutomationPlanner.Decision decision = AutomationPlanner.plan(fixture.snapshot, fixture.analysis,
                dryRun, fixture.bankroll, fixture.budget, fixture.now);

        assertEquals(AutomationPlanner.Kind.REPORT, decision.kind());
        assertNotNull(decision.entry());
        assertTrue(decision.reason().contains("Dry run"));
    }

    @Test
    void liveModeBuysWhenTheListingStillClearsEveryFilter() {
        Fixture fixture = new Fixture(true);
        AhProfitConfig live = fixture.config.withAutomationDryRun(false);
        AutomationPlanner.Decision decision = AutomationPlanner.plan(fixture.snapshot, fixture.analysis,
                live, fixture.bankroll, fixture.budget, fixture.now);

        assertEquals(AutomationPlanner.Kind.BUY, decision.kind());
        assertEquals(new BigDecimal("8000"), decision.plannedSpend());
        assertNotNull(decision.opportunity());
    }

    @Test
    void aListingPricedAboveTheFiltersIsNotBought() {
        Fixture fixture = new Fixture(true);
        AhProfitConfig live = fixture.config.withAutomationDryRun(false);
        AuctionHouseSnapshot expensive = new AuctionHouseSnapshot(fixture.snapshot.containerId(),
                "Auction House",
                List.of(AuctionEntry.of(10, "minecraft:diamond", "Diamond", 64, List.of("Price: $15,000"))),
                fixture.now);

        AutomationPlanner.Decision decision = AutomationPlanner.plan(expensive, fixture.analysis,
                live, fixture.bankroll, fixture.budget, fixture.now);

        assertEquals(AutomationPlanner.Kind.WAIT, decision.kind());
        assertTrue(decision.reason().contains("ROI"), "expected an ROI rejection, was: " + decision.reason());
    }

    @Test
    void anUnrelatedListingIsIgnored() {
        Fixture fixture = new Fixture(true);
        AhProfitConfig live = fixture.config.withAutomationDryRun(false);
        AuctionHouseSnapshot unrelated = new AuctionHouseSnapshot(fixture.snapshot.containerId(),
                "Auction House",
                List.of(AuctionEntry.of(10, "minecraft:emerald", "Emerald", 64, List.of("Price: $8,000"))),
                fixture.now);

        AutomationPlanner.Decision decision = AutomationPlanner.plan(unrelated, fixture.analysis,
                live, fixture.bankroll, fixture.budget, fixture.now);

        assertEquals(AutomationPlanner.Kind.WAIT, decision.kind());
        assertTrue(decision.reason().contains("does not match") || decision.reason().contains("No currently listed"));
    }

    @Test
    void theHourlySpendCeilingBlocksAutomation() {
        Fixture fixture = new Fixture(true);
        AhProfitConfig live = fixture.config.withAutomationDryRun(false);
        AutomationBudget tight = new AutomationBudget(new BigDecimal("1000"), 12, 0);

        AutomationPlanner.Decision decision = AutomationPlanner.plan(fixture.snapshot, fixture.analysis,
                live, fixture.bankroll, tight, fixture.now);

        assertEquals(AutomationPlanner.Kind.BLOCKED, decision.kind());
        assertTrue(decision.reason().contains("Hourly spend ceiling"));
    }

    @Test
    void theCooldownMakesAutomationWaitInsteadOfActing() {
        Fixture fixture = new Fixture(true);
        AhProfitConfig live = fixture.config.withAutomationDryRun(false);
        AutomationBudget cooling = new AutomationBudget(HOURLY_CAP, 12, 10_000);
        cooling.record(new BigDecimal("100"), fixture.now);

        AutomationPlanner.Decision decision = AutomationPlanner.plan(fixture.snapshot, fixture.analysis,
                live, fixture.bankroll, cooling, fixture.now);

        assertEquals(AutomationPlanner.Kind.WAIT, decision.kind());
        assertTrue(decision.reason().contains("Cooling down"));
    }

    @Test
    void noOpenMenuMeansNothingToActOn() {
        Fixture fixture = new Fixture(true);
        AhProfitConfig live = fixture.config.withAutomationDryRun(false);

        AutomationPlanner.Decision decision = AutomationPlanner.plan(null, fixture.analysis,
                live, fixture.bankroll, fixture.budget, fixture.now);

        assertEquals(AutomationPlanner.Kind.WAIT, decision.kind());
        assertTrue(decision.reason().contains("No auction-house menu"));
    }

    @Test
    void noRankedOpportunityMeansNothingToActOn() {
        Fixture fixture = new Fixture(true);
        AhProfitConfig live = fixture.config.withAutomationDryRun(false);

        AutomationPlanner.Decision decision = AutomationPlanner.plan(fixture.snapshot, null,
                live, fixture.bankroll, fixture.budget, fixture.now);

        assertEquals(AutomationPlanner.Kind.WAIT, decision.kind());
        assertTrue(decision.reason().contains("No evidence-backed opportunity"));
    }

    @Test
    void theCheapestMatchingListingIsChosen() {
        Fixture fixture = new Fixture(true);
        AhProfitConfig live = fixture.config.withAutomationDryRun(false);
        AuctionHouseSnapshot crowded = new AuctionHouseSnapshot(fixture.snapshot.containerId(), "Auction House",
                List.of(AuctionEntry.of(10, "minecraft:diamond", "Diamond", 64, List.of("Price: $9,500")),
                        AuctionEntry.of(11, "minecraft:diamond", "Diamond", 64, List.of("Price: $8,000"))),
                fixture.now);

        AutomationPlanner.Decision decision = AutomationPlanner.plan(crowded, fixture.analysis,
                live, fixture.bankroll, fixture.budget, fixture.now);

        assertEquals(AutomationPlanner.Kind.BUY, decision.kind());
        assertEquals(11, decision.entry().slotIndex());
    }

    @Test
    void liveEconomicsRejectsAListingThatNoLongerClearsTheRoiFloor() {
        Fixture fixture = new Fixture(true);
        AuctionEntry overpriced = AuctionEntry.of(10, "minecraft:diamond", "Diamond", 64,
                List.of("Price: $15,000"));

        String rejection = AutomationPlanner.liveEconomicsRejection(overpriced, fixture.estimate,
                fixture.config, fixture.bankroll);

        assertNotNull(rejection);
        assertTrue(rejection.contains("ROI"), rejection);
    }

    @Test
    void liveEconomicsAcceptsAListingThatStillClearsEveryFilter() {
        Fixture fixture = new Fixture(true);
        AuctionEntry fair = AuctionEntry.of(10, "minecraft:diamond", "Diamond", 64, List.of("Price: $8,000"));

        assertTrue(AutomationPlanner.liveEconomicsRejection(fair, fixture.estimate, fixture.config,
                fixture.bankroll) == null);
    }

    @Test
    void recommendedListPriceUndercutsTheCheapestRivalAsk() {
        BigDecimal list = AutomationPlanner.recommendedListPrice(fixtureEstimate(), 64,
                new BigDecimal("300"), new BigDecimal("2"));

        assertNotNull(list);
        assertTrue(list.compareTo(new BigDecimal("19200")) < 0, "the list price must undercut the rival ask");
        assertTrue(list.compareTo(new BigDecimal("18000")) >= 0, "the undercut must stay small, was " + list);
    }

    private dev.ahprofit.market.PriceEstimate fixtureEstimate() {
        long now = System.currentTimeMillis();
        ApiItem diamond = TestMarketFixtures.item("minecraft:diamond", 64);
        AuctionListing listing = TestMarketFixtures.listing(diamond, "8000", now, "cheap");
        return new dev.ahprofit.market.PriceEstimator().estimate(listing,
                TestMarketFixtures.repeatedSales(diamond, "300", 12, now), List.of(listing), now, 5, 300);
    }

    private static final class Fixture {
        private final long now = System.currentTimeMillis();
        private final AhProfitConfig config;
        private final BankrollLimits bankroll;
        private final MarketAnalysis analysis;
        private final dev.ahprofit.market.PriceEstimate estimate;
        private final AuctionHouseSnapshot snapshot;
        private final AutomationBudget budget;

        private Fixture(boolean automationEnabled) {
            ApiItem diamond = TestMarketFixtures.item("minecraft:diamond", 64);
            List<CompletedSale> sales = TestMarketFixtures.repeatedSales(diamond, "300", 12, now);
            AuctionListing listing = TestMarketFixtures.listing(diamond, "8000", now, "cheap");
            AhProfitConfig base = TestMarketFixtures.bankroll("100000", now);
            this.config = base.withAutomationEnabled(automationEnabled);
            this.bankroll = BankrollManager.calculate(base, now);
            this.estimate = new dev.ahprofit.market.PriceEstimator().estimate(listing, sales,
                    List.of(listing), now, 5, 300);
            this.analysis = new OpportunityEngine().analyze(List.of(listing), sales, base, now, now);
            this.snapshot = new AuctionHouseSnapshot(7, "Auction House",
                    List.of(AuctionEntry.of(10, "minecraft:diamond", "Diamond", 64, List.of("Price: $8,000"))), now);
            this.budget = new AutomationBudget(HOURLY_CAP, 12, 0);
        }
    }
}
