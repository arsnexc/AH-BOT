package dev.ahprofit.market;

import java.util.List;

/**
 * Transparent, additive breakdown of why one flip outranks another. Every component is bounded so
 * the ranking can be explained to the player instead of being an opaque number.
 */
public record FlipScore(
        double total,
        double profitComponent,
        double efficiencyComponent,
        double roiComponent,
        double confidenceComponent,
        double liquidityComponent,
        double evidenceComponent,
        double freshnessComponent,
        double volatilityPenalty,
        double competitionPenalty,
        double capitalPenalty,
        double clearancePenalty,
        double trendPenalty,
        double expectedDaysToSell,
        double sellThroughProbability,
        double profitPerCapitalPerDay,
        List<String> penalties
) {
    public FlipScore {
        penalties = List.copyOf(penalties == null ? List.of() : penalties);
        if (Double.isNaN(total) || Double.isInfinite(total)) {
            throw new IllegalArgumentException("Flip score total must be finite.");
        }
    }

    /** Highest-value single sentence explaining the dominant weakness of this flip. */
    public String dominantWeakness() {
        if (penalties.isEmpty()) {
            return "no scoring penalties applied";
        }
        return penalties.getFirst();
    }
}
