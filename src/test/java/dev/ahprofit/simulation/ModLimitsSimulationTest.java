package dev.ahprofit.simulation;

import dev.ahprofit.api.ApiItem;
import dev.ahprofit.api.AuctionListing;
import dev.ahprofit.config.AhProfitConfig;
import dev.ahprofit.market.OpportunityEngine;
import dev.ahprofit.market.PriceEstimate;
import dev.ahprofit.market.PriceEstimator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the estimator and the engine against synthetic markets with a known ground truth, to
 * find where the key-free path stops being trustworthy.
 *
 * <p>The ground truth is the price at which a unit actually clears. A listing is a genuine flip when
 * its ask is low enough that the clearing price still covers the selling fee; everything else is a
 * listing the mod must not present as an opportunity.
 */
class ModLimitsSimulationTest {
    private static final int MIN_LISTINGS = 2;
    private static final BigDecimal MIN_SPREAD = new BigDecimal("15");
    private static final BigDecimal UNDERCUT = new BigDecimal("2");
    private static final int MAX_AGE_SECONDS = 300;
    private static final double TRUE_UNIT = 2_200.0;
    private static final double FEE = 0.05;

    private final PriceEstimator estimator = new PriceEstimator();
    private final SimulationReport report = new SimulationReport();
    private final List<String> failures = new ArrayList<>();

    @Test
    @DisplayName("Simulation of the ask-based path against known ground truth")
    void simulateTheAskBasedPath() {
        long now = System.currentTimeMillis();
        MarketSimulator simulator = new MarketSimulator(20261011L);

        cleanMarkets(simulator, now);
        contaminatedMarkets(simulator, now);
        dispersedMarkets(simulator, now);
        thinAndDegenerateMarkets(simulator, now);
        baitMarket(simulator, now);
        enginePrecisionAndRecall(simulator, now);
        scaleAndThroughput(simulator, now);

        System.out.println(report.render());
        assertTrue(failures.isEmpty(), "simulation limits were breached:\n  "
                + String.join("\n  ", failures) + "\n\n" + report.render());
    }

    // ---------------------------------------------------------------- scenarios

    private void cleanMarkets(MarketSimulator simulator, long now) {
        for (int trial = 0; trial < 200; trial++) {
            List<AuctionListing> rivals = simulator.clusteredMarket(now, TRUE_UNIT, 1.05, 0.04, 12);
            double markup = 0.70 + simulator.nextUniform() * 0.25;
            AuctionListing candidate = simulator.candidate(now, TRUE_UNIT, markup);
            PriceEstimate estimate = estimator.estimateFromAsks(candidate, rivals, now,
                    MIN_LISTINGS, MIN_SPREAD, UNDERCUT, MAX_AGE_SECONDS);

            double ceiling = estimate.conservativeUnitPrice() == null
                    ? Double.NaN : estimate.conservativeUnitPrice().doubleValue();
            double candidateUnit = candidate.unitPrice().doubleValue();
            boolean genuinelyProfitable = candidateUnit < TRUE_UNIT * (1.0 - FEE);
            report.count("clean markets evaluated");
            report.recordCeiling(ceiling, TRUE_UNIT);
            report.recordCalibration(estimate.confidence(), ceiling <= TRUE_UNIT);
            if (genuinelyProfitable) {
                report.count("clean: candidate was a genuine flip");
                if (ceiling > TRUE_UNIT * 1.001) {
                    // Inherent, not a defect: when the cheapest ask already sits above the
                    // clearing price, undercutting it by 2% still lands above it. That is a
                    // genuinely inefficient market, and the risk buffer is what covers it.
                    report.count("clean: market priced above the clearing price");
                }
            }
        }
    }

    private void contaminatedMarkets(MarketSimulator simulator, long now) {
        for (int trial = 0; trial < 120; trial++) {
            List<AuctionListing> clean = simulator.clusteredMarket(now, TRUE_UNIT, 1.05, 0.04, 12);
            List<AuctionListing> dirty = new java.util.ArrayList<>(clean);
            dirty.addAll(simulator.contaminatedMarket(now, TRUE_UNIT, 0.25, 8));
            AuctionListing candidate = simulator.candidate(now, TRUE_UNIT, 0.82);

            PriceEstimate fromClean = estimator.estimateFromAsks(candidate, clean, now,
                    MIN_LISTINGS, MIN_SPREAD, UNDERCUT, MAX_AGE_SECONDS);
            PriceEstimate fromDirty = estimator.estimateFromAsks(candidate, dirty, now,
                    MIN_LISTINGS, MIN_SPREAD, UNDERCUT, MAX_AGE_SECONDS);

            double cleanCeiling = fromClean.conservativeUnitPrice().doubleValue();
            double dirtyCeiling = fromDirty.conservativeUnitPrice() == null
                    ? Double.NaN : fromDirty.conservativeUnitPrice().doubleValue();
            report.count("contaminated markets evaluated");
            // A dump must not drag the ceiling down: it is not a price anyone would pay.
            if (dirtyCeiling < cleanCeiling * 0.9) {
                report.count("contaminated: ceiling dragged down by a dump");
                failure("contaminated market trial " + trial + ": ceiling fell from " + cleanCeiling
                        + " to " + dirtyCeiling + " after dumps were added");
            } else {
                report.count("contaminated: dump correctly ignored");
            }
            if (fromDirty.flags().stream().noneMatch(flag -> flag.contains("ignored as outliers"))) {
                report.count("contaminated: dump not disclosed");
            }
        }
    }

    private void dispersedMarkets(MarketSimulator simulator, long now) {
        for (int trial = 0; trial < 120; trial++) {
            List<AuctionListing> rivals = simulator.dispersedMarket(now, TRUE_UNIT, 20);
            AuctionListing candidate = simulator.candidate(now, TRUE_UNIT, 0.82);
            PriceEstimate estimate = estimator.estimateFromAsks(candidate, rivals, now,
                    MIN_LISTINGS, MIN_SPREAD, UNDERCUT, MAX_AGE_SECONDS);

            double ceiling = estimate.conservativeUnitPrice() == null
                    ? Double.NaN : estimate.conservativeUnitPrice().doubleValue();
            report.count("dispersed markets evaluated");
            report.recordCeiling(ceiling, TRUE_UNIT);
            report.recordCalibration(estimate.confidence(), ceiling <= TRUE_UNIT);
            if (ceiling > TRUE_UNIT * 1.5) {
                report.count("dispersed: ceiling wildly above truth");
                failure("dispersed market trial " + trial + ": ceiling " + ceiling
                        + " is far above the clearing price " + TRUE_UNIT);
            }
        }
    }

    private void thinAndDegenerateMarkets(MarketSimulator simulator, long now) {
        // One rival only: not a market, and the candidate must not count as its own evidence.
        List<AuctionListing> single = List.of(simulator.rival(now, TRUE_UNIT, 1.05, "only"));
        PriceEstimate thin = estimator.estimateFromAsks(simulator.candidate(now, TRUE_UNIT, 0.5),
                single, now, MIN_LISTINGS, MIN_SPREAD, UNDERCUT, MAX_AGE_SECONDS);
        report.count("degenerate: single rival produced evidence", thin.sufficientEvidence() ? 1 : 0);
        if (thin.sufficientEvidence()) {
            failure("a single rival was treated as enough evidence");
        }

        // Every ask identical, so the MAD is zero and only the relative band can reject a dump.
        List<AuctionListing> identical = simulator.identicalMarket(now, TRUE_UNIT, 1.05, 8);
        identical.add(simulator.rival(now, TRUE_UNIT, 0.01, "dump"));
        PriceEstimate identicalEstimate = estimator.estimateFromAsks(
                simulator.candidate(now, TRUE_UNIT, 0.82), identical, now,
                MIN_LISTINGS, MIN_SPREAD, UNDERCUT, MAX_AGE_SECONDS);
        report.count("degenerate: identical asks with one dump");
        if (identicalEstimate.lowestCompetingUnitPrice().doubleValue() < TRUE_UNIT * 0.5) {
            failure("a dump survived into the competitive ask of an otherwise identical market: "
                    + identicalEstimate.lowestCompetingUnitPrice());
        }

        // Stale listings must not describe the market.
        long stale = now - 6L * 60L * 60L * 1000L;
        List<AuctionListing> staleMarket = simulator.clusteredMarket(stale, TRUE_UNIT, 1.05, 0.04, 10);
        PriceEstimate staleEstimate = estimator.estimateFromAsks(
                simulator.candidate(now, TRUE_UNIT, 0.82), staleMarket, now,
                MIN_LISTINGS, MIN_SPREAD, UNDERCUT, MAX_AGE_SECONDS);
        report.count("degenerate: stale market produced evidence", staleEstimate.sufficientEvidence() ? 1 : 0);
        if (staleEstimate.sufficientEvidence()) {
            failure("a market observed six hours ago was treated as current");
        }

        // No rivals at all.
        PriceEstimate empty = estimator.estimateFromAsks(
                simulator.candidate(now, TRUE_UNIT, 0.5), List.of(), now,
                MIN_LISTINGS, MIN_SPREAD, UNDERCUT, MAX_AGE_SECONDS);
        report.count("degenerate: empty market produced evidence", empty.sufficientEvidence() ? 1 : 0);
        if (empty.sufficientEvidence()) {
            failure("an empty market produced evidence");
        }
    }

    private void baitMarket(MarketSimulator simulator, long now) {
        // A listing priced at one coin, surrounded by a normal market. This is the classic bait.
        List<AuctionListing> rivals = simulator.clusteredMarket(now, TRUE_UNIT, 1.05, 0.04, 10);
        rivals.add(simulator.listing(now, 1.0, "bait"));
        AuctionListing bait = simulator.listing(now, 1.0, "candidate-bait");

        PriceEstimate estimate = estimator.estimateFromAsks(bait, rivals, now,
                MIN_LISTINGS, MIN_SPREAD, UNDERCUT, MAX_AGE_SECONDS);
        report.count("bait market evaluated");
        double ceiling = estimate.conservativeUnitPrice() == null
                ? Double.NaN : estimate.conservativeUnitPrice().doubleValue();
        report.recordCeiling(ceiling, TRUE_UNIT);
        // The candidate is at 1 coin, so any ceiling at all is a profit on paper. The question is
        // whether the ceiling is honest, and a 1-coin market must not be read as a 2,200 market.
        if (ceiling > TRUE_UNIT * 0.5) {
            report.count("bait: ceiling anchored to the honest market");
        } else {
            report.count("bait: ceiling correctly suppressed");
        }
        if (!estimate.sufficientEvidence()) {
            report.count("bait: not presented as a flip");
        }
    }

    private void enginePrecisionAndRecall(MarketSimulator simulator, long now) {
        AhProfitConfig config = simulator.bankroll("2000000", now).withAskOnlyAnalysis(true);
        OpportunityEngine engine = new OpportunityEngine();

        int ranked = 0;
        int rankedAndGenuine = 0;
        int genuine = 0;
        double worstProfit = Double.POSITIVE_INFINITY;

        for (int trial = 0; trial < 150; trial++) {
            List<AuctionListing> rivals = simulator.clusteredMarket(now, TRUE_UNIT, 1.08, 0.06, 14);
            // Half the candidates are genuinely mispriced, half are merely ordinary asks.
            double markup = trial % 2 == 0 ? 0.80 + simulator.nextUniform() * 0.10 : 1.0 + simulator.nextUniform() * 0.05;
            AuctionListing candidate = simulator.candidate(now, TRUE_UNIT, markup);
            List<AuctionListing> all = new java.util.ArrayList<>(rivals);
            all.add(candidate);

            boolean isGenuine = candidate.unitPrice().doubleValue() < TRUE_UNIT * (1.0 - FEE);
            if (isGenuine) {
                genuine++;
            }

            var analysis = engine.analyze(all, List.of(), config, now, now);
            boolean wasRanked = analysis.opportunities().stream()
                    .anyMatch(opportunity -> opportunity.listing().listingKey().equals(candidate.listingKey()));
            if (wasRanked) {
                ranked++;
                if (isGenuine) {
                    rankedAndGenuine++;
                }
                // Reality check: buy at the ask, resell at the true clearing price minus the fee.
                double resale = TRUE_UNIT * candidate.quantity() * (1.0 - FEE);
                double profit = resale - candidate.totalPrice().doubleValue();
                worstProfit = Math.min(worstProfit, profit);
                report.recordNetProfit(profit);
                report.recordCeiling(analysis.opportunities().stream()
                        .filter(o -> o.listing().listingKey().equals(candidate.listingKey()))
                        .findFirst().orElseThrow().estimate().conservativeUnitPrice().doubleValue(), TRUE_UNIT);
            }
            report.count("engine markets evaluated");
        }

        report.count("engine: genuinely mispriced candidates", genuine);
        report.count("engine: candidates ranked as flips", ranked);
        report.count("engine: ranked and genuinely mispriced", rankedAndGenuine);
        report.count("engine: ranked but NOT genuinely mispriced", ranked - rankedAndGenuine);
        if (ranked > 0) {
            double precision = 100.0 * rankedAndGenuine / ranked;
            report.finding(String.format("precision %.0f%% (%d of %d ranked flips were genuinely mispriced)",
                    precision, rankedAndGenuine, ranked));
            if (precision < 60.0) {
                failure("precision fell to " + String.format("%.0f%%", precision)
                        + ": the mod is recommending listings that are not genuinely mispriced");
            }
        }
        if (genuine > 0) {
            double recall = 100.0 * rankedAndGenuine / genuine;
            report.finding(String.format("recall %.0f%% (%d of %d genuine flips were found)",
                    recall, rankedAndGenuine, genuine));
        }
        if (ranked > 0 && worstProfit < 0) {
            failure("a ranked flip loses money when resold at the true clearing price: " + worstProfit);
        }
    }

    private void scaleAndThroughput(MarketSimulator simulator, long now) {
        AhProfitConfig config = simulator.bankroll("2000000", now).withAskOnlyAnalysis(true);
        OpportunityEngine engine = new OpportunityEngine();
        // One huge variant is the worst case: every candidate is compared against every other.
        List<AuctionListing> huge = new java.util.ArrayList<>();
        for (int index = 0; index < 9_900; index++) {
            huge.add(simulator.rival(now, TRUE_UNIT, 1.0 + simulator.nextUniform() * 0.12,
                    "bulk-" + index));
        }
        // A second, ordinary variant that does contain genuine mispricing, so the pipeline is
        // exercised end to end at scale rather than only on the worst-case group. The one deeply
        // mispriced listing has to sit well below its rivals, because the ceiling is set by the
        // cheapest rival and a listing cannot undercut itself.
        ApiItem emerald = simulator.otherItem("minecraft:emerald", 16);
        for (int index = 0; index < 100; index++) {
            huge.add(simulator.listingOf(emerald, now, 900.0, "emerald-" + index));
        }
        for (int index = 0; index < 5; index++) {
            huge.add(simulator.listingOf(emerald, now, 700.0 + index * 10.0, "emerald-mid-" + index));
        }
        huge.add(simulator.listingOf(emerald, now, 500.0, "emerald-mispriced"));

        long started = System.nanoTime();
        var analysis = engine.analyze(huge, List.of(), config, now, now);
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000L;

        report.count("scale: listings analysed", analysis.analyzedListings());
        report.count("scale: opportunities produced", analysis.opportunities().size());
        report.finding("10,006 listings analysed in " + elapsedMillis + " ms, producing "
                + analysis.opportunities().size() + " opportunities");
        if (analysis.opportunities().size() > config.maxRecommendations()) {
            failure("the engine produced more opportunities than the configured maximum of "
                    + config.maxRecommendations());
        }
        if (elapsedMillis > 15_000) {
            failure("10,006 listings took " + elapsedMillis + " ms, which is too slow to be usable");
        }
        if (analysis.opportunities().isEmpty()) {
            failure("a market that contains genuine mispricing produced nothing at scale");
        }
    }

    private void failure(String message) {
        failures.add(message);
        report.count("LIMIT BREACHED");
    }
}
