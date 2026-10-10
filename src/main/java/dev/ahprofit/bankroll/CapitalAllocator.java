package dev.ahprofit.bankroll;

import dev.ahprofit.market.ItemCategory;
import dev.ahprofit.market.Opportunity;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Plans a non-binding hypothetical allocation; it never performs a purchase. */
public final class CapitalAllocator {
    public Allocation allocate(List<Opportunity> candidates, BankrollLimits limits, int maximumRecommendations) {
        if (candidates == null || limits == null || maximumRecommendations < 1) {
            throw new IllegalArgumentException("Candidates, limits, and a positive recommendation limit are required.");
        }
        List<Opportunity> sorted = new ArrayList<>(candidates);
        sorted.sort(Comparator.comparingDouble(Opportunity::score).reversed()
                .thenComparing(Opportunity::estimatedNetProfit, Comparator.reverseOrder())
                .thenComparing(value -> value.listing().listingKey()));

        List<Opportunity> selected = new ArrayList<>();
        Map<ItemCategory, BigDecimal> categoryExposure = new EnumMap<>(ItemCategory.class);
        Map<String, BigDecimal> commodityExposure = new HashMap<>();
        Set<String> listingKeys = new HashSet<>();
        BigDecimal total = BigDecimal.ZERO;

        if (!limits.balanceConfigured() || limits.balanceMayBeStale() || limits.deployableCapital().signum() <= 0) {
            return new Allocation(List.of(), BigDecimal.ZERO, limits.deployableCapital(), Map.of());
        }

        for (Opportunity candidate : sorted) {
            if (selected.size() >= maximumRecommendations) {
                break;
            }
            if (!listingKeys.add(candidate.listing().listingKey())) {
                continue;
            }
            BigDecimal cost = candidate.listing().totalPrice();
            ItemCategory category = candidate.listing().category();
            String fingerprint = candidate.listing().fingerprint().value();
            BigDecimal categoryCurrent = categoryExposure.getOrDefault(category, BigDecimal.ZERO);
            BigDecimal commodityCurrent = commodityExposure.getOrDefault(fingerprint, BigDecimal.ZERO);

            if (total.add(cost).compareTo(limits.maxTotalExposure()) > 0
                    || categoryCurrent.add(cost).compareTo(limits.maxCategoryExposure()) > 0
                    || commodityCurrent.add(cost).compareTo(limits.maxCommodityExposure()) > 0) {
                continue;
            }

            double categoryRatio = ratio(categoryCurrent, limits.maxCategoryExposure());
            double commodityRatio = ratio(commodityCurrent, limits.maxCommodityExposure());
            double concentrationPenalty = 8.0 * categoryRatio + 12.0 * commodityRatio;
            Opportunity allocated = candidate.withScoreAndRationale(
                    Math.max(0, candidate.score() - concentrationPenalty),
                    String.format(java.util.Locale.ROOT,
                            "portfolio exposure after this hypothetical entry: %s at %s of its category cap and %s of its item cap",
                            category.name().toLowerCase(java.util.Locale.ROOT),
                            percent(categoryCurrent.add(cost), limits.maxCategoryExposure()),
                            percent(commodityCurrent.add(cost), limits.maxCommodityExposure())));
            selected.add(allocated);
            total = total.add(cost);
            categoryExposure.put(category, categoryCurrent.add(cost));
            commodityExposure.put(fingerprint, commodityCurrent.add(cost));
        }

        selected.sort(Comparator.comparingDouble(Opportunity::score).reversed()
                .thenComparing(Opportunity::estimatedNetProfit, Comparator.reverseOrder())
                .thenComparing(value -> value.listing().listingKey()));
        BigDecimal remaining = limits.deployableCapital().subtract(total).max(BigDecimal.ZERO);
        return new Allocation(selected, total, remaining, categoryExposure);
    }

    private static double ratio(BigDecimal value, BigDecimal maximum) {
        if (maximum.signum() <= 0) {
            return 1;
        }
        return Math.max(0, Math.min(1, value.divide(maximum, MathContext.DECIMAL128).doubleValue()));
    }

    private static String percent(BigDecimal value, BigDecimal maximum) {
        if (maximum.signum() <= 0) {
            return "n/a";
        }
        BigDecimal result = value.multiply(new BigDecimal("100"), MathContext.DECIMAL128)
                .divide(maximum, MathContext.DECIMAL128);
        return result.setScale(0, java.math.RoundingMode.HALF_UP).toPlainString() + "% of cap";
    }

    public record Allocation(List<Opportunity> opportunities, BigDecimal proposedCapital,
                             BigDecimal remainingDeployableCapital, Map<ItemCategory, BigDecimal> categoryExposure) {
        public Allocation {
            opportunities = List.copyOf(opportunities);
            categoryExposure = Map.copyOf(categoryExposure);
        }
    }
}
