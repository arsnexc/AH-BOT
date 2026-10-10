package dev.ahprofit.market;

import java.math.BigDecimal;
import java.util.List;

public record PriceEstimate(
        String fingerprint,
        BigDecimal median24HourUnitPrice,
        BigDecimal median7DayUnitPrice,
        BigDecimal conservativeUnitPrice,
        BigDecimal trendPercent,
        BigDecimal volatilityFraction,
        int comparableSales24Hours,
        int comparableSales7Days,
        long latestSaleAtMillis,
        BigDecimal unitsSoldPerDay,
        int competingListings,
        BigDecimal competingQuantity,
        BigDecimal lowestCompetingUnitPrice,
        double daysToClearProxy,
        double confidence,
        LiquidityLevel liquidity,
        boolean sufficientEvidence,
        List<String> flags
) {
    public PriceEstimate {
        fingerprint = fingerprint == null ? "" : fingerprint;
        unitsSoldPerDay = unitsSoldPerDay == null ? BigDecimal.ZERO : unitsSoldPerDay;
        competingQuantity = competingQuantity == null ? BigDecimal.ZERO : competingQuantity;
        liquidity = liquidity == null ? LiquidityLevel.UNKNOWN : liquidity;
        flags = List.copyOf(flags == null ? List.of() : flags);
        if (comparableSales24Hours < 0 || comparableSales7Days < 0 || competingListings < 0) {
            throw new IllegalArgumentException("Price-estimate counts cannot be negative.");
        }
        if (Double.isNaN(confidence) || confidence < 0 || confidence > 1) {
            throw new IllegalArgumentException("Confidence must be between 0 and 1.");
        }
    }

    public static PriceEstimate noEvidence(String fingerprint, String reason) {
        return new PriceEstimate(fingerprint, null, null, null, null, null,
                0, 0, 0, BigDecimal.ZERO, 0, BigDecimal.ZERO, null,
                Double.POSITIVE_INFINITY, 0, LiquidityLevel.UNKNOWN, false, List.of(reason));
    }
}
