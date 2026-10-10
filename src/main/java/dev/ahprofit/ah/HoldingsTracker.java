package dev.ahprofit.ah;

import dev.ahprofit.market.PriceEstimate;
import dev.ahprofit.util.Money;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Remembers what automation bought, so the mod can advise on (and later automate) reselling.
 *
 * <p>This is deliberately in-memory only: it is a convenience for the current session and is never
 * treated as a verified inventory.
 */
public final class HoldingsTracker {
    private final List<Holding> holdings = new CopyOnWriteArrayList<>();

    /** Records a completed buy at its live price. */
    public void record(AuctionEntry entry, BigDecimal spend, long nowMillis) {
        if (entry == null || spend == null || spend.signum() <= 0 || entry.count() <= 0) {
            return;
        }
        BigDecimal unitCost = spend.divide(BigDecimal.valueOf(entry.count()), MathContext.DECIMAL128);
        holdings.add(new Holding(entry.itemId(), entry.count(), unitCost, spend, nowMillis));
    }

    public List<Holding> holdings() {
        return List.copyOf(holdings);
    }

    public boolean isEmpty() {
        return holdings.isEmpty();
    }

    public void clear() {
        holdings.clear();
    }

    /** Drops one holding once it has been sold. */
    public void forget(Holding holding) {
        if (holding != null) {
            holdings.remove(holding);
        }
    }

    /** Break-even total price that recovers the cost, ignoring fees and risk. */
    public static BigDecimal breakEvenTotal(Holding holding) {
        return holding.totalSpend();
    }

    /**
     * Recommended list price for a holding: undercut the cheapest rival ask but never below the
     * holding's own break-even, so automation cannot be advised into a guaranteed loss.
     */
    public static BigDecimal recommendedListPrice(Holding holding, PriceEstimate estimate,
                                                  BigDecimal cheapestCompetingUnitPrice,
                                                  BigDecimal undercutPercent) {
        BigDecimal target = AutomationPlanner.recommendedListPrice(estimate, holding.count(),
                cheapestCompetingUnitPrice, undercutPercent);
        if (target == null) {
            return null;
        }
        return target.max(breakEvenTotal(holding));
    }

    /**
     * Ask-based resale price for a holding: undercut the cheapest rival ask for the same item, but
     * never below the holding's own break-even. Returns null when no rival ask is known, because a
     * price with nothing to undercut is a guess.
     */
    public static BigDecimal recommendedAskBasedListPrice(BigDecimal cheapestRivalUnitPrice, int count,
                                                          BigDecimal undercutPercent, Holding holding) {
        if (cheapestRivalUnitPrice == null || cheapestRivalUnitPrice.signum() <= 0 || count <= 0
                || holding == null || undercutPercent == null) {
            return null;
        }
        BigDecimal unit = cheapestRivalUnitPrice.subtract(Money.percentOf(cheapestRivalUnitPrice, undercutPercent));
        BigDecimal total = unit.multiply(BigDecimal.valueOf(count));
        return total.max(breakEvenTotal(holding));
    }

    /** Aggregates the session's buys for status output. */
    public static String summarise(List<Holding> holdings) {
        if (holdings == null || holdings.isEmpty()) {
            return "nothing bought this session";
        }
        BigDecimal total = holdings.stream().map(Holding::totalSpend).reduce(BigDecimal.ZERO, BigDecimal::add);
        long units = holdings.stream().mapToLong(Holding::count).sum();
        return holdings.size() + " purchases, " + units + " units, " + Money.format(total) + " spent";
    }

    public record Holding(String itemId, int count, BigDecimal unitCost, BigDecimal totalSpend,
                          long acquiredAtMillis) {
        public Holding {
            itemId = itemId == null ? "" : itemId;
            unitCost = unitCost == null ? BigDecimal.ZERO : unitCost;
            totalSpend = totalSpend == null ? BigDecimal.ZERO : totalSpend;
        }

        public String describe() {
            return count + "x " + itemId + " at " + Money.format(unitCost) + "/unit ("
                    + Money.format(totalSpend) + " total)";
        }
    }
}
