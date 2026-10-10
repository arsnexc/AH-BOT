package dev.ahprofit.bankroll;

import java.math.BigDecimal;

public record BankrollLimits(
        BigDecimal balance,
        BigDecimal protectedReserve,
        BigDecimal deployableCapital,
        BigDecimal maxRecommendedListingSpend,
        BigDecimal maxTotalExposure,
        BigDecimal maxCommodityExposure,
        BigDecimal maxCategoryExposure,
        BigDecimal minimumProfitThreshold,
        String balanceSource,
        boolean balanceConfigured,
        boolean balanceMayBeStale
) {
    public BankrollLimits {
        balanceSource = balanceSource == null ? "manual" : balanceSource;
    }
}
