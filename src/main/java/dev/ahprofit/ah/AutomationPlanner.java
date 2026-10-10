package dev.ahprofit.ah;

import dev.ahprofit.api.AuctionListing;
import dev.ahprofit.bankroll.BankrollLimits;
import dev.ahprofit.config.AhProfitConfig;
import dev.ahprofit.market.MarketAnalysis;
import dev.ahprofit.market.Opportunity;
import dev.ahprofit.market.PriceEstimate;
import dev.ahprofit.util.Money;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Comparator;
import java.util.List;

/**
 * Decides whether the auction house may be acted on right now.
 *
 * <p>Automation is off by default and only ever acts on a listing that still clears every
 * economic filter at its <em>live</em> price, so a stale recommendation can never buy an
 * overpriced listing.
 */
public final class AutomationPlanner {
    private AutomationPlanner() {
    }

    public enum Kind {
        /** Nothing to do; keep watching. */
        WAIT,
        /** Buy the entry. */
        BUY,
        /** Would buy, but dry-run mode is on. */
        REPORT,
        /** Refuses to act and explains why. */
        BLOCKED
    }

    public record Decision(Kind kind, AuctionEntry entry, BigDecimal plannedSpend, String reason,
                           Opportunity opportunity) {
        public Decision {
            plannedSpend = plannedSpend == null ? BigDecimal.ZERO : plannedSpend;
            reason = reason == null ? "" : reason;
        }
    }

    public static Decision plan(AuctionHouseSnapshot snapshot, MarketAnalysis analysis, AhProfitConfig config,
                                BankrollLimits bankroll, AutomationBudget budget, long nowMillis) {
        if (snapshot == null || !snapshot.hasEntries()) {
            return new Decision(Kind.WAIT, null, BigDecimal.ZERO,
                    "No auction-house menu is open, so there is nothing to act on.", null);
        }
        if (!config.automationEnabled()) {
            return new Decision(Kind.BLOCKED, null, BigDecimal.ZERO,
                    "Auction automation is switched off. Enable it with /ahprofit ah on.", null);
        }
        if (analysis == null || analysis.opportunities().isEmpty()) {
            return new Decision(Kind.WAIT, null, BigDecimal.ZERO,
                    "No evidence-backed opportunity is currently ranked, so nothing is worth buying.", null);
        }

        List<Opportunity> ranked = analysis.opportunities().stream()
                .sorted(Comparator.comparingDouble(Opportunity::score).reversed())
                .toList();

        String bestRejection = null;
        for (Opportunity opportunity : ranked) {
            AuctionListing listing = opportunity.listing();
            AuctionEntry entry = snapshot.cheapestFor(listing.item().id(), listing.quantity());
            if (entry == null) {
                continue;
            }
            BigDecimal spend = entry.totalPrice();
            if (spend.compareTo(config.automationMaxSpendPerAction()) > 0) {
                bestRejection = first(bestRejection, "live price " + Money.format(spend)
                        + " exceeds the per-action cap of " + Money.format(config.automationMaxSpendPerAction()));
                continue;
            }
            if (bankroll.maxRecommendedListingSpend().signum() > 0
                    && spend.compareTo(bankroll.maxRecommendedListingSpend()) > 0) {
                bestRejection = first(bestRejection, "live price " + Money.format(spend)
                        + " exceeds the single-listing spend cap");
                continue;
            }
            if (bankroll.deployableCapital().signum() > 0
                    && spend.compareTo(bankroll.deployableCapital()) > 0) {
                bestRejection = first(bestRejection, "live price " + Money.format(spend)
                        + " exceeds deployable capital");
                continue;
            }
            if (opportunity.score() < config.automationMinimumScore().doubleValue()) {
                bestRejection = first(bestRejection, "best live candidate scores "
                        + String.format(java.util.Locale.ROOT, "%.1f", opportunity.score())
                        + " below the required " + Money.format(config.automationMinimumScore()));
                continue;
            }
            if (!budget.allowsSpend(spend, nowMillis)) {
                return new Decision(Kind.BLOCKED, entry, spend,
                        "Hourly spend ceiling reached (" + Money.format(budget.spendInWindow())
                        + " spent this hour; cap " + Money.format(config.automationMaxSpendPerHour()) + ").",
                        opportunity);
            }
            if (!budget.allowsAction(nowMillis)) {
                return new Decision(Kind.WAIT, entry, spend,
                        "Cooling down before the next automated action.", opportunity);
            }
            String rejection = liveEconomicsRejection(entry, opportunity.estimate(), config, bankroll);
            if (rejection != null) {
                bestRejection = first(bestRejection, rejection);
                continue;
            }
            if (config.automationDryRun()) {
                return new Decision(Kind.REPORT, entry, spend,
                        "Dry run: this listing still clears every filter at its live price, but no click is sent.",
                        opportunity);
            }
            return new Decision(Kind.BUY, entry, spend,
                    "Buying at the live price; the listing still clears every filter at that price.", opportunity);
        }

        if (bestRejection != null) {
            return new Decision(Kind.WAIT, null, BigDecimal.ZERO, bestRejection, null);
        }
        return new Decision(Kind.WAIT, null, BigDecimal.ZERO,
                "No currently listed item matches a ranked opportunity.", null);
    }

    /**
     * Re-derives the flip at the live price instead of trusting the cached recommendation.
     *
     * @return null when the trade still works, otherwise the reason it does not.
     */
    public static String liveEconomicsRejection(AuctionEntry entry, PriceEstimate estimate, AhProfitConfig config,
                                                BankrollLimits bankroll) {
        if (!entry.priced() || estimate == null || estimate.conservativeUnitPrice() == null) {
            return "the listing has no readable price or no comparable-sale estimate";
        }
        int quantity = Math.max(1, entry.count());
        BigDecimal resale = estimate.conservativeUnitPrice().multiply(BigDecimal.valueOf(quantity), MathContext.DECIMAL128);
        BigDecimal fee = Money.percentOf(resale, config.assumedSellingFeePercent());
        BigDecimal volatility = estimate.volatilityFraction() == null ? BigDecimal.ZERO : estimate.volatilityFraction();
        BigDecimal riskPercent = config.baseRiskBufferPercent()
                .add(volatility.multiply(config.volatilityBufferMultiplier(), MathContext.DECIMAL128)
                        .multiply(new BigDecimal("100"), MathContext.DECIMAL128), MathContext.DECIMAL128);
        BigDecimal risk = Money.percentOf(resale, riskPercent);
        BigDecimal net = resale.subtract(fee).subtract(risk).subtract(entry.totalPrice());
        if (net.signum() <= 0) {
            return "live price " + Money.format(entry.totalPrice()) + " leaves no profit after fees and risk";
        }
        if (net.compareTo(bankroll.minimumProfitThreshold()) < 0) {
            return "live price " + Money.format(entry.totalPrice()) + " nets " + Money.format(net)
                    + ", below the " + Money.format(bankroll.minimumProfitThreshold()) + " floor";
        }
        BigDecimal roi = Money.divide(net, entry.totalPrice()).multiply(new BigDecimal("100"), MathContext.DECIMAL128);
        if (roi.compareTo(config.minimumRoiPercent()) < 0) {
            return "live ROI " + Money.formatPercent(roi) + " is below the required "
                    + Money.formatPercent(config.minimumRoiPercent());
        }
        BigDecimal discount = Money.divide(resale.subtract(entry.totalPrice()), resale)
                .multiply(new BigDecimal("100"), MathContext.DECIMAL128);
        if (discount.compareTo(config.minimumDiscountPercent()) < 0) {
            return "live discount " + Money.formatPercent(discount) + " is below the required "
                    + Money.formatPercent(config.minimumDiscountPercent());
        }
        return null;
    }

    /** Recommended list price for an item you already own, undercutting the cheapest rival ask. */
    public static BigDecimal recommendedListPrice(PriceEstimate estimate, int quantity,
                                                  BigDecimal cheapestCompetingUnitPrice,
                                                  BigDecimal undercutPercent) {
        if (estimate == null || estimate.conservativeUnitPrice() == null || quantity <= 0) {
            return null;
        }
        BigDecimal base = estimate.conservativeUnitPrice().multiply(BigDecimal.valueOf(quantity), MathContext.DECIMAL128);
        if (cheapestCompetingUnitPrice != null && cheapestCompetingUnitPrice.signum() > 0) {
            BigDecimal rival = cheapestCompetingUnitPrice.multiply(BigDecimal.valueOf(quantity), MathContext.DECIMAL128);
            BigDecimal undercut = Money.percentOf(rival, undercutPercent == null ? BigDecimal.ONE : undercutPercent);
            base = base.min(rival.subtract(undercut));
        }
        return base.max(BigDecimal.ONE).stripTrailingZeros();
    }

    private static String first(String existing, String candidate) {
        return existing == null ? candidate : existing;
    }
}
