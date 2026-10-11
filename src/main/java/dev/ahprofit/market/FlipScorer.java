package dev.ahprofit.market;

import dev.ahprofit.api.AuctionListing;
import dev.ahprofit.bankroll.BankrollLimits;
import dev.ahprofit.util.Money;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Ranks flips by capital efficiency and risk-adjusted return rather than by raw profit.
 *
 * <p>The previous ranking weighted absolute profit heavily, so a single expensive listing with a
 * mediocre return could outrank several fast, cheap, high-turnover flips. This scorer adds the two
 * terms that decide real profitability: how long capital is locked up, and how likely the item is
 * to actually sell.
 */
public final class FlipScorer {
    private FlipScorer() {
    }

    private static final double WEIGHT_PROFIT = 0.18;
    private static final double WEIGHT_EFFICIENCY = 0.26;
    private static final double WEIGHT_ROI = 0.12;
    private static final double WEIGHT_CONFIDENCE = 0.20;
    private static final double WEIGHT_LIQUIDITY = 0.14;
    private static final double WEIGHT_EVIDENCE = 0.06;
    private static final double WEIGHT_FRESHNESS = 0.04;

    /** Profit per capital per day that earns a full efficiency score. */
    private static final double FULL_EFFICIENCY_PER_DAY = 0.05;
    private static final double MAX_CLEARANCE_DAYS = 14.0;
    private static final double CLEARANCE_GRACE_DAYS = 2.0;
    private static final int MAX_EXPECTED_DAYS = 30;

    public static FlipScore score(AuctionListing listing, PriceEstimate estimate, BigDecimal netProfit,
                                  BigDecimal roiPercent, BankrollLimits bankroll, long nowMillis,
                                  long maximumListingAgeMillis) {
        if (listing == null || estimate == null || netProfit == null || roiPercent == null || bankroll == null) {
            throw new IllegalArgumentException("Listing, estimate, profit, roi, and bankroll are required.");
        }

        double capital = listing.totalPrice().doubleValue();
        double expectedDays = expectedDaysToSell(estimate);
        double sellThrough = sellThroughProbability(estimate, expectedDays);
        double profitPerCapitalPerDay = capital <= 0 || expectedDays <= 0
                ? 0.0
                : netProfit.doubleValue() / (capital * expectedDays);

        BigDecimal profitDenominator = bankroll.balance().multiply(new BigDecimal("0.10"), MathContext.DECIMAL128)
                .max(bankroll.minimumProfitThreshold());
        double profitComponent = clamp(Money.divide(netProfit, profitDenominator).doubleValue(), 0, 1);
        double efficiencyComponent = clamp(profitPerCapitalPerDay / FULL_EFFICIENCY_PER_DAY, 0, 1);
        double roiComponent = clamp(roiPercent.doubleValue() / 100.0, 0, 1);
        double confidenceComponent = clamp(estimate.confidence(), 0, 1);
        double liquidityComponent = liquidityScore(estimate.liquidity());
        double evidenceComponent = clamp(estimate.comparableSales7Days() / 20.0, 0, 1);
        double age = Math.max(0, nowMillis - listing.observedAtMillis());
        double freshnessComponent = maximumListingAgeMillis <= 0 ? 0
                : clamp(1.0 - age / (double) maximumListingAgeMillis, 0, 1);

        List<String> penalties = new ArrayList<>();
        double volatilityPenalty = volatilityPenalty(estimate, penalties);
        double competitionPenalty = competitionPenalty(estimate, penalties);
        double capitalPenalty = capitalPenalty(listing, bankroll, penalties);
        double clearancePenalty = clearancePenalty(expectedDays, penalties);
        double trendPenalty = trendPenalty(estimate, penalties);

        double weighted = WEIGHT_PROFIT * profitComponent
                + WEIGHT_EFFICIENCY * efficiencyComponent
                + WEIGHT_ROI * roiComponent
                + WEIGHT_CONFIDENCE * confidenceComponent
                + WEIGHT_LIQUIDITY * liquidityComponent
                + WEIGHT_EVIDENCE * evidenceComponent
                + WEIGHT_FRESHNESS * freshnessComponent;
        double penaltyTotal = volatilityPenalty + competitionPenalty + capitalPenalty
                + clearancePenalty + trendPenalty;
        double total = clamp(100.0 * weighted - 100.0 * penaltyTotal, 0, 100);

        return new FlipScore(total, profitComponent, efficiencyComponent, roiComponent, confidenceComponent,
                liquidityComponent, evidenceComponent, freshnessComponent, volatilityPenalty, competitionPenalty,
                capitalPenalty, clearancePenalty, trendPenalty, expectedDays, sellThrough,
                profitPerCapitalPerDay, penalties);
    }

    /** Days of visible supply the market currently absorbs per day; unbounded demand is capped. */
    public static double expectedDaysToSell(PriceEstimate estimate) {
        double days = estimate.daysToClearProxy();
        if (Double.isNaN(days) || Double.isInfinite(days)) {
            return MAX_EXPECTED_DAYS;
        }
        return clamp(days, 0.05, MAX_EXPECTED_DAYS);
    }

    /**
     * Probability a unit bought now actually sells, derived from liquidity, competing supply, and
     * how long the current supply takes to clear. Deliberately capped below 1.0.
     */
    public static double sellThroughProbability(PriceEstimate estimate, double expectedDays) {
        double liquidityBase = switch (estimate.liquidity()) {
            case HIGH -> 0.92;
            case MEDIUM -> 0.76;
            case LOW -> 0.44;
            case UNKNOWN -> 0.18;
        };
        double competing = Math.max(0.0, estimate.competingQuantity().doubleValue());
        double dailyUnits = estimate.unitsSoldPerDay().doubleValue();
        // Without any sale velocity there is no basis to penalise supply, so it is not penalised.
        double competitionFactor = dailyUnits <= 0
                ? 1.0
                : 1.0 / (1.0 + competing / Math.max(1.0, dailyUnits));
        double timeFactor = 1.0 / (1.0 + Math.max(0.0, expectedDays) / 7.0);
        return clamp(liquidityBase * competitionFactor * timeFactor, 0.02, 0.95);
    }

    private static double volatilityPenalty(PriceEstimate estimate, List<String> penalties) {
        if (estimate.volatilityFraction() == null) {
            penalties.add("no volatility data");
            return 0.18;
        }
        double penalty = Math.min(0.18, Math.max(0.0, estimate.volatilityFraction().doubleValue()) * 0.12);
        if (penalty > 0.06) {
            penalties.add(String.format(Locale.ROOT, "high historical volatility (%.0f%% price spread)",
                    estimate.volatilityFraction().doubleValue() * 100.0));
        }
        return penalty;
    }

    private static double competitionPenalty(PriceEstimate estimate, List<String> penalties) {
        int sales = Math.max(1, estimate.comparableSales7Days());
        double penalty = Math.min(0.15, estimate.competingListings() / (double) sales * 0.05);
        if (penalty > 0.05) {
            penalties.add(estimate.competingListings() + " competing listings against "
                    + estimate.comparableSales7Days() + " recent sales");
        }
        return penalty;
    }

    private static double capitalPenalty(AuctionListing listing, BankrollLimits bankroll, List<String> penalties) {
        BigDecimal maximum = bankroll.maxRecommendedListingSpend();
        if (maximum.signum() <= 0) {
            return 0;
        }
        double ratio = clamp(Money.divide(listing.totalPrice(), maximum).doubleValue(), 0, 1);
        if (ratio > 0.7) {
            penalties.add(String.format(Locale.ROOT, "uses %.0f%% of the single-listing spend cap", ratio * 100.0));
        }
        return ratio * 0.08;
    }

    private static double clearancePenalty(double expectedDays, List<String> penalties) {
        double penalty = clamp((expectedDays - CLEARANCE_GRACE_DAYS) / (MAX_CLEARANCE_DAYS - CLEARANCE_GRACE_DAYS), 0, 1) * 0.20;
        if (penalty > 0.05) {
            penalties.add(String.format(Locale.ROOT, "current supply needs about %.1f days to clear", expectedDays));
        }
        return penalty;
    }

    private static double trendPenalty(PriceEstimate estimate, List<String> penalties) {
        BigDecimal trend = estimate.trendPercent();
        if (trend == null || trend.signum() >= 0) {
            return 0;
        }
        double decline = Math.min(1.0, trend.abs().doubleValue() / 100.0);
        penalties.add(String.format(Locale.ROOT, "24-hour price trend is down %s",
                estimate.trendPercent().abs().toPlainString() + "%"));
        return decline * 0.5;
    }

    private static double liquidityScore(LiquidityLevel liquidity) {
        return switch (liquidity) {
            case HIGH -> 1.0;
            case MEDIUM -> 0.68;
            case LOW -> 0.28;
            case UNKNOWN -> 0.0;
        };
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
