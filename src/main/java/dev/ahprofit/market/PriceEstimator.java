package dev.ahprofit.market;

import dev.ahprofit.api.AuctionListing;
import dev.ahprofit.api.CompletedSale;
import dev.ahprofit.util.Money;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Locale;
import java.util.List;

/** Robust, completed-sales-first resale-price estimator. Active asks only cap, never establish, value. */
public final class PriceEstimator {
    private static final long DAY_MILLIS = Duration.ofDays(1).toMillis();
    private static final long SEVEN_DAYS_MILLIS = Duration.ofDays(7).toMillis();
    private static final long HALF_LIFE_MILLIS = Duration.ofHours(36).toMillis();
    /** Ask-based estimates have no sale velocity, so clearance is unknown rather than infinite. */
    private static final double UNKNOWN_CLEARANCE_DAYS = 7.0;
    /** An ask further than this many MADs from the median is treated as noise, not a market. */
    private static final double OUTLIER_MAD_MULTIPLE = 3.0;
    /** A spread wider than this is more likely a misread price than a real mispricing. */
    private static final BigDecimal IMPLAUSIBLE_SPREAD_PERCENT = new BigDecimal("50");
    /** Spread beyond which extra width stops adding confidence. */
    private static final BigDecimal SPREAD_SATURATION_PERCENT = new BigDecimal("40");
    private static final BigDecimal ZERO = BigDecimal.ZERO;

    public PriceEstimate estimate(AuctionListing candidate, List<CompletedSale> allSales,
                                  List<AuctionListing> activeListings, long nowMillis,
                                  int minimumEvidence, int maximumListingAgeSeconds) {
        String fingerprint = candidate.fingerprint().value();
        if (!candidate.fingerprint().comparable()) {
            return PriceEstimate.noEvidence(fingerprint,
                    "item attributes are incomplete; this variant is not compared with other listings");
        }

        long sevenDayStart = nowMillis - SEVEN_DAYS_MILLIS;
        long dayStart = nowMillis - DAY_MILLIS;
        List<CompletedSale> sales7d = allSales.stream()
                .filter(sale -> sale.fingerprint().comparable())
                .filter(sale -> sale.fingerprint().value().equals(fingerprint))
                .filter(sale -> sale.soldAtMillis() >= sevenDayStart && sale.soldAtMillis() <= nowMillis)
                .sorted(Comparator.comparingLong(CompletedSale::soldAtMillis))
                .toList();
        List<CompletedSale> sales24h = sales7d.stream()
                .filter(sale -> sale.soldAtMillis() >= dayStart)
                .toList();

        if (sales7d.isEmpty()) {
            return PriceEstimate.noEvidence(fingerprint, "no comparable completed sales in the last seven days");
        }

        List<BigDecimal> prices7d = sales7d.stream().map(CompletedSale::unitPrice).toList();
        BigDecimal median7d = median(prices7d);
        BigDecimal median24h = sales24h.size() >= 3
                ? median(sales24h.stream().map(CompletedSale::unitPrice).toList())
                : null;
        BigDecimal mad = medianAbsoluteDeviation(prices7d, median7d);
        BigDecimal volatility = median7d.signum() == 0 ? BigDecimal.ONE
                : Money.divide(mad, median7d).max(BigDecimal.ZERO);

        BigDecimal weightedRecent = weightedMedian(sales7d, median7d, mad, nowMillis);
        BigDecimal conservative = weightedRecent.min(median7d);
        BigDecimal trendPercent = null;
        if (median24h != null) {
            conservative = conservative.min(median24h);
            trendPercent = Money.divide(median24h.subtract(median7d), median7d)
                    .multiply(new BigDecimal("100"), MathContext.DECIMAL128);
        }

        Competition competition = competition(candidate, activeListings, nowMillis, maximumListingAgeSeconds);
        BigDecimal lowestAsk = competition.lowestUnitPrice();
        if (lowestAsk != null) {
            conservative = conservative.min(lowestAsk);
        }

        long latestSale = sales7d.getLast().soldAtMillis();
        double hoursSinceLast = Math.max(0, nowMillis - latestSale) / (double) Duration.ofHours(1).toMillis();
        double sampleFactor = Math.min(1.0, Math.sqrt(sales7d.size() / 12.0));
        double recencyFactor = Math.exp(-hoursSinceLast / 72.0);
        double volatilityFactor = 1.0 / (1.0 + 2.0 * volatility.doubleValue());
        double competitionFactor = 1.0 / (1.0 + (double) competition.listingCount() / Math.max(1, sales7d.size()));
        double confidence = clamp(sampleFactor * recencyFactor * volatilityFactor * competitionFactor, 0, 1);
        boolean sufficientEvidence = sales7d.size() >= minimumEvidence;
        if (!sufficientEvidence) {
            confidence = Math.min(confidence, 0.39);
        }

        BigDecimal unitVolume = sales7d.stream()
                .map(sale -> BigDecimal.valueOf(sale.quantity()))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(7), MathContext.DECIMAL128);
        BigDecimal visibleSupply = competition.quantity().add(BigDecimal.valueOf(candidate.quantity()));
        double daysToClear = unitVolume.signum() == 0
                ? Double.POSITIVE_INFINITY
                : Money.divide(visibleSupply, unitVolume).doubleValue();

        LiquidityLevel liquidity = liquidityLevel(sales7d.size(), sales24h.size(), competition.listingCount());
        List<String> flags = new ArrayList<>();
        if (!sufficientEvidence) {
            flags.add("insufficient comparable sales (< " + minimumEvidence + " in seven days)");
        }
        if (hoursSinceLast > 72) {
            flags.add("last comparable sale is over 72 hours old");
        }
        if (volatility.compareTo(new BigDecimal("0.35")) >= 0) {
            flags.add("high historical volatility");
        }
        if (competition.listingCount() >= Math.max(5, sales7d.size())) {
            flags.add("heavy active-listing competition");
        }
        if (median24h == null) {
            flags.add("fewer than three comparable sales in 24 hours");
        }
        return new PriceEstimate(fingerprint, median24h, median7d, conservative, trendPercent, volatility,
                sales24h.size(), sales7d.size(), latestSale, unitVolume, competition.listingCount(),
                competition.quantity(), lowestAsk, daysToClear, confidence, liquidity,
                sufficientEvidence, false, flags);
    }

    /**
     * Values a candidate from the asking prices of comparable listings instead of completed sales.
     *
     * <p>This is the key-free path: with no API key there are no completed sales, so the only
     * available signal is how far a listing undercuts the rest of the market for the same variant.
     * The result is explicitly marked ask-based so it is never presented as sale evidence.
     */
    public PriceEstimate estimateFromAsks(AuctionListing candidate, List<AuctionListing> activeListings,
                                          long nowMillis, int minimumListings, BigDecimal minimumSpreadPercent,
                                          BigDecimal undercutPercent, int maximumListingAgeSeconds) {
        String fingerprint = candidate.fingerprint().value();
        if (!candidate.fingerprint().comparable()) {
            return PriceEstimate.noEvidence(fingerprint,
                    "item attributes are incomplete; this variant is not compared with other listings");
        }

        List<AuctionListing> market = comparableListings(candidate, activeListings, nowMillis, maximumListingAgeSeconds);
        // Only rivals count as evidence. The candidate's own ask is the thing being valued, so
        // counting it toward the minimum is circular and would let a single listing be a market.
        if (market.size() < minimumListings) {
            return PriceEstimate.noEvidence(fingerprint,
                    "only " + market.size() + " comparable rival listings are visible; at least "
                            + minimumListings + " are needed to judge an asking-price spread");
        }

        List<BigDecimal> asks = market.stream().map(AuctionListing::unitPrice).toList();
        BigDecimal marketMedian = median(asks);
        BigDecimal mad = medianAbsoluteDeviation(asks, marketMedian);
        BigDecimal dispersion = marketMedian.signum() == 0 ? BigDecimal.ONE
                : Money.divide(mad, marketMedian).max(BigDecimal.ZERO);

        // To resell, the listing must undercut the cheapest rival a buyer would actually take, so
        // that is the ceiling. The raw minimum is an extreme value, not an estimate: one manipulated
        // or mis-keyed listing would otherwise set the resale price for the entire market, so asks
        // too far from the median are discarded first.
        List<AuctionListing> credible = credibleListings(market, marketMedian, mad);
        BigDecimal lowestCompeting = credible.stream().map(AuctionListing::unitPrice)
                .min(BigDecimal::compareTo).orElse(marketMedian);

        BigDecimal candidateUnit = candidate.unitPrice();
        BigDecimal spreadPercent = Money.divide(marketMedian.subtract(candidateUnit), marketMedian)
                .multiply(new BigDecimal("100"), MathContext.DECIMAL128);
        boolean spreadSufficient = spreadPercent.compareTo(minimumSpreadPercent) >= 0;

        BigDecimal target = lowestCompeting.multiply(BigDecimal.ONE.subtract(
                Money.divide(undercutPercent, new BigDecimal("100")), MathContext.DECIMAL128), MathContext.DECIMAL128);
        BigDecimal conservative = new BigDecimal(
                target.max(BigDecimal.ONE).stripTrailingZeros().toPlainString());

        boolean ceilingClearsAsk = target.compareTo(candidateUnit) > 0;
        // A very wide spread is not proportionally more convincing: past a point it is far more
        // likely to be a misread price or a variant that is not actually comparable than a genuine
        // mispricing, so extra width must not keep raising confidence.
        boolean spreadImplausible = spreadPercent.compareTo(IMPLAUSIBLE_SPREAD_PERCENT) > 0;

        double listingFactor = Math.min(1.0, market.size() / (double) Math.max(1, minimumListings * 2));
        // An implausible spread contributes almost nothing: width past a point is a symptom, not
        // evidence, so it must not be able to outweigh the thin-market and dispersion penalties.
        double spreadFactor = spreadImplausible ? 0.1
                : clamp(Money.divide(spreadPercent, SPREAD_SATURATION_PERCENT).doubleValue(), 0, 1);
        double dispersionFactor = 1.0 / (1.0 + 2.0 * dispersion.doubleValue());
        double confidence = clamp(listingFactor * 0.4 + spreadFactor * 0.35 + dispersionFactor * 0.25, 0, 0.85);

        LiquidityLevel liquidity = liquidityLevel(market.size() + 1, market.isEmpty() ? 0 : 1, market.size());
        BigDecimal visibleSupply = market.stream()
                .map(listing -> BigDecimal.valueOf(listing.quantity()))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .add(BigDecimal.valueOf(candidate.quantity()));

        List<String> flags = new ArrayList<>();
        flags.add("ASK-BASED estimate: derived from active asking prices, not completed sales");
        if (!spreadSufficient) {
            flags.add("asking-price spread " + Money.formatPercent(spreadPercent) + " is below the required "
                    + Money.formatPercent(minimumSpreadPercent));
        }
        if (!ceilingClearsAsk) {
            flags.add("the resale ceiling of " + Money.format(conservative)
                    + " does not clear this listing's " + Money.format(candidateUnit)
                    + " per unit, so no spread survives the fee and risk buffer");
        }
        if (market.size() < minimumListings * 2) {
            flags.add("few comparable listings (" + (market.size() + 1) + "), so the market reference is thin");
        }
        if (dispersion.compareTo(new BigDecimal("0.35")) >= 0) {
            flags.add("asking prices for this variant are widely dispersed");
        }
        if (credible.size() < market.size()) {
            flags.add((market.size() - credible.size()) + " asking price(s) were ignored as outliers");
        }
        if (spreadImplausible) {
            flags.add("the spread of " + Money.formatPercent(spreadPercent)
                    + " is wide enough to suggest a misread price or a non-comparable variant");
        }

        return new PriceEstimate(fingerprint, null, marketMedian, conservative, null, dispersion,
                0, 0, 0, BigDecimal.ZERO, market.size(), visibleSupply, lowestCompeting,
                UNKNOWN_CLEARANCE_DAYS, confidence, liquidity,
                spreadSufficient && ceilingClearsAsk && market.size() + 1 >= minimumListings,
                true, flags);
    }

    /**
     * Listings whose asking price is close enough to the median to describe the market. The band is
     * the wider of three MADs and half the median, so it still rejects a stray dump when every
     * genuine ask is identical and the MAD collapses to zero.
     */
    private static List<AuctionListing> credibleListings(List<AuctionListing> market, BigDecimal median,
                                                         BigDecimal mad) {
        if (market.isEmpty()) {
            return market;
        }
        BigDecimal relativeBand = Money.percentOf(median, new BigDecimal("50"));
        BigDecimal band = mad.multiply(BigDecimal.valueOf(OUTLIER_MAD_MULTIPLE)).max(relativeBand);
        List<AuctionListing> credible = market.stream()
                .filter(listing -> listing.unitPrice().subtract(median).abs().compareTo(band) <= 0)
                .toList();
        return credible.isEmpty() ? market : credible;
    }

    private static List<AuctionListing> comparableListings(AuctionListing candidate, List<AuctionListing> listings,
                                                           long nowMillis, int maxAgeSeconds) {
        long oldestAllowed = nowMillis - Duration.ofSeconds(maxAgeSeconds).toMillis();
        List<AuctionListing> result = new ArrayList<>();
        for (AuctionListing listing : listings) {
            if (listing.listingKey().equals(candidate.listingKey())
                    || !listing.fingerprint().comparable()
                    || !listing.fingerprint().value().equals(candidate.fingerprint().value())
                    || listing.observedAtMillis() < oldestAllowed
                    || listing.expiresAtMillis() <= nowMillis) {
                continue;
            }
            result.add(listing);
        }
        return result;
    }

    private static Competition competition(AuctionListing candidate, List<AuctionListing> listings,
                                          long nowMillis, int maxAgeSeconds) {
        BigDecimal lowest = null;
        BigDecimal quantity = BigDecimal.ZERO;
        int count = 0;
        long oldestAllowed = nowMillis - Duration.ofSeconds(maxAgeSeconds).toMillis();
        for (AuctionListing listing : listings) {
            if (listing.listingKey().equals(candidate.listingKey())
                    || !listing.fingerprint().comparable()
                    || !listing.fingerprint().value().equals(candidate.fingerprint().value())
                    || listing.observedAtMillis() < oldestAllowed
                    || listing.expiresAtMillis() <= nowMillis) {
                continue;
            }
            count++;
            quantity = quantity.add(BigDecimal.valueOf(listing.quantity()));
            if (lowest == null || listing.unitPrice().compareTo(lowest) < 0) {
                lowest = listing.unitPrice();
            }
        }
        return new Competition(lowest, quantity, count);
    }

    private static BigDecimal weightedMedian(List<CompletedSale> sales, BigDecimal median, BigDecimal mad, long nowMillis) {
        BigDecimal tolerance = mad.multiply(new BigDecimal("4.5"), MathContext.DECIMAL128);
        if (tolerance.signum() == 0) {
            tolerance = median.multiply(new BigDecimal("0.10"), MathContext.DECIMAL128);
        }
        BigDecimal lower = median.subtract(tolerance).max(BigDecimal.ZERO);
        BigDecimal upper = median.add(tolerance);
        List<WeightedPrice> weighted = new ArrayList<>(sales.size());
        double totalWeight = 0;
        for (CompletedSale sale : sales) {
            BigDecimal clipped = sale.unitPrice().max(lower).min(upper);
            double age = Math.max(0, nowMillis - sale.soldAtMillis());
            double weight = Math.pow(0.5, age / HALF_LIFE_MILLIS);
            weighted.add(new WeightedPrice(clipped, weight));
            totalWeight += weight;
        }
        weighted.sort(Comparator.comparing(WeightedPrice::price));
        double target = totalWeight / 2.0;
        double cumulative = 0;
        for (WeightedPrice price : weighted) {
            cumulative += price.weight();
            if (cumulative >= target) {
                return price.price();
            }
        }
        return weighted.getLast().price();
    }

    public static BigDecimal median(List<BigDecimal> values) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("Median requires at least one value.");
        }
        List<BigDecimal> sorted = values.stream().sorted().toList();
        int middle = sorted.size() / 2;
        if (sorted.size() % 2 == 1) {
            return sorted.get(middle);
        }
        return sorted.get(middle - 1).add(sorted.get(middle), MathContext.DECIMAL128)
                .divide(new BigDecimal("2"), MathContext.DECIMAL128);
    }

    private static BigDecimal medianAbsoluteDeviation(List<BigDecimal> values, BigDecimal median) {
        List<BigDecimal> deviations = values.stream()
                .map(value -> value.subtract(median).abs())
                .toList();
        return median(deviations);
    }

    private static LiquidityLevel liquidityLevel(int sales7d, int sales24h, int competitionCount) {
        if (sales7d >= 15 && sales24h >= 2 && competitionCount <= sales7d) {
            return LiquidityLevel.HIGH;
        }
        if (sales7d >= 5 && (sales24h >= 1 || sales7d >= 10)) {
            return LiquidityLevel.MEDIUM;
        }
        if (sales7d == 0) {
            return LiquidityLevel.UNKNOWN;
        }
        return LiquidityLevel.LOW;
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private record WeightedPrice(BigDecimal price, double weight) {
    }

    private record Competition(BigDecimal lowestUnitPrice, BigDecimal quantity, int listingCount) {
    }
}
