package dev.ahprofit.market;

import dev.ahprofit.bankroll.BankrollLimits;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public record MarketAnalysis(
        int analyzedListings,
        int skippedUncertainListings,
        int economicallyQualifiedListings,
        List<Opportunity> opportunities,
        BankrollLimits bankroll,
        BigDecimal totalProposedCapital,
        BigDecimal remainingDeployableCapital,
        Map<ItemCategory, BigDecimal> categoryExposure,
        Map<String, PriceEstimate> priceEstimates,
        List<String> warnings
) {
    public MarketAnalysis {
        opportunities = List.copyOf(opportunities == null ? List.of() : opportunities);
        categoryExposure = Map.copyOf(categoryExposure == null ? Map.of() : categoryExposure);
        priceEstimates = Map.copyOf(priceEstimates == null ? Map.of() : priceEstimates);
        warnings = List.copyOf(warnings == null ? List.of() : warnings);
        totalProposedCapital = totalProposedCapital == null ? BigDecimal.ZERO : totalProposedCapital;
        remainingDeployableCapital = remainingDeployableCapital == null ? BigDecimal.ZERO : remainingDeployableCapital;
    }
}
