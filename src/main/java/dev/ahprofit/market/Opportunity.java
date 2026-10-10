package dev.ahprofit.market;

import dev.ahprofit.api.AuctionListing;

import java.math.BigDecimal;
import java.util.List;

public record Opportunity(
        AuctionListing listing,
        PriceEstimate estimate,
        BigDecimal conservativeResaleRevenue,
        BigDecimal assumedSellingFee,
        BigDecimal riskBuffer,
        BigDecimal estimatedNetProfit,
        BigDecimal roiPercent,
        BigDecimal discountPercent,
        BigDecimal profitPerCapitalUnit,
        double score,
        String rationale,
        List<String> flags
) {
    public Opportunity {
        if (listing == null || estimate == null || conservativeResaleRevenue == null
                || assumedSellingFee == null || riskBuffer == null || estimatedNetProfit == null
                || roiPercent == null || discountPercent == null || profitPerCapitalUnit == null) {
            throw new IllegalArgumentException("Opportunity values are required.");
        }
        rationale = rationale == null ? "" : rationale;
        flags = List.copyOf(flags == null ? List.of() : flags);
    }

    public Opportunity withScoreAndRationale(double adjustedScore, String additionalReason) {
        String nextReason = rationale;
        if (additionalReason != null && !additionalReason.isBlank()) {
            nextReason = nextReason.isBlank() ? additionalReason : nextReason + "; " + additionalReason;
        }
        return new Opportunity(listing, estimate, conservativeResaleRevenue, assumedSellingFee, riskBuffer,
                estimatedNetProfit, roiPercent, discountPercent, profitPerCapitalUnit, adjustedScore,
                nextReason, flags);
    }
}
