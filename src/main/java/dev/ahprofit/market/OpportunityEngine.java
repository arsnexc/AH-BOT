package dev.ahprofit.market;

import dev.ahprofit.api.AuctionListing;
import dev.ahprofit.api.CompletedSale;
import dev.ahprofit.bankroll.BankrollLimits;
import dev.ahprofit.bankroll.BankrollManager;
import dev.ahprofit.bankroll.CapitalAllocator;
import dev.ahprofit.config.AhProfitConfig;
import dev.ahprofit.util.Money;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Converts read-only market snapshots into ranked, hypothetical flip recommendations. */
public final class OpportunityEngine {
    private final PriceEstimator priceEstimator;
    private final CapitalAllocator capitalAllocator;

    public OpportunityEngine() {
        this(new PriceEstimator(), new CapitalAllocator());
    }

    public OpportunityEngine(PriceEstimator priceEstimator, CapitalAllocator capitalAllocator) {
        this.priceEstimator = java.util.Objects.requireNonNull(priceEstimator, "priceEstimator");
        this.capitalAllocator = java.util.Objects.requireNonNull(capitalAllocator, "capitalAllocator");
    }

    public MarketAnalysis analyze(List<AuctionListing> listings, List<CompletedSale> sales,
                                 AhProfitConfig config, long nowMillis, long lastSuccessfulFetchMillis) {
        List<AuctionListing> safeListings = listings == null ? List.of() : List.copyOf(listings);
        List<CompletedSale> safeSales = sales == null ? List.of() : List.copyOf(sales);
        BankrollLimits bankroll = BankrollManager.calculate(config, nowMillis);
        List<String> warnings = new ArrayList<>();
        List<Opportunity> candidates = new ArrayList<>();
        Map<String, PriceEstimate> estimates = new HashMap<>();
        Map<String, List<CompletedSale>> salesByFingerprint = new HashMap<>();
        Map<String, List<AuctionListing>> listingsByFingerprint = new HashMap<>();
        Set<String> seenListings = new HashSet<>();
        int uncertain = 0;
        int economicallyQualified = 0;
        int analyzed = 0;
        long maxListingAgeMillis = Duration.ofSeconds(config.maxDataAgeSeconds()).toMillis();
        BigDecimal minimumProfit = bankroll.minimumProfitThreshold();

        for (CompletedSale sale : safeSales) {
            if (sale.fingerprint().comparable()) {
                salesByFingerprint.computeIfAbsent(sale.fingerprint().value(), ignored -> new ArrayList<>()).add(sale);
            }
        }
        for (AuctionListing listing : safeListings) {
            if (listing.fingerprint().comparable()) {
                listingsByFingerprint.computeIfAbsent(listing.fingerprint().value(), ignored -> new ArrayList<>()).add(listing);
            }
        }

        for (AuctionListing listing : safeListings) {
            if (!seenListings.add(listing.listingKey())) {
                continue;
            }
            analyzed++;
            if (nowMillis - listing.observedAtMillis() > maxListingAgeMillis
                    || listing.expiresAtMillis() <= nowMillis) {
                continue;
            }
            if (!config.enabledCategories().contains(listing.category())
                    || config.excludedItems().contains(listing.item().id().toLowerCase(java.util.Locale.ROOT))) {
                continue;
            }
            if (!listing.fingerprint().comparable()) {
                uncertain++;
                continue;
            }
            if (!bankroll.balanceConfigured() || bankroll.balanceMayBeStale()
                    || bankroll.maxRecommendedListingSpend().signum() <= 0) {
                continue;
            }
            if (listing.totalPrice().compareTo(bankroll.maxRecommendedListingSpend()) > 0) {
                continue;
            }

            List<CompletedSale> exactSales = salesByFingerprint.getOrDefault(listing.fingerprint().value(), List.of());
            List<AuctionListing> exactListings = listingsByFingerprint.getOrDefault(listing.fingerprint().value(), List.of());
            PriceEstimate estimate = priceEstimator.estimate(listing, exactSales, exactListings, nowMillis,
                    config.minimumHistoricalTransactions(), config.maxDataAgeSeconds());
            estimates.put(listing.fingerprint().value(), estimate);
            if (estimate.conservativeUnitPrice() == null || estimate.comparableSales7Days() < config.minimumHistoricalTransactions()) {
                uncertain++;
                continue;
            }

            BigDecimal resaleRevenue = estimate.conservativeUnitPrice().multiply(BigDecimal.valueOf(listing.quantity()), MathContext.DECIMAL128);
            BigDecimal fee = Money.percentOf(resaleRevenue, config.assumedSellingFeePercent());
            BigDecimal volatilityRiskPercent = estimate.volatilityFraction() == null
                    ? BigDecimal.ZERO
                    : estimate.volatilityFraction()
                            .multiply(config.volatilityBufferMultiplier(), MathContext.DECIMAL128)
                            .multiply(new BigDecimal("100"), MathContext.DECIMAL128);
            BigDecimal effectiveRiskPercent = config.baseRiskBufferPercent().add(volatilityRiskPercent, MathContext.DECIMAL128);
            BigDecimal risk = Money.percentOf(resaleRevenue, effectiveRiskPercent);
            BigDecimal netProfit = resaleRevenue.subtract(fee).subtract(risk).subtract(listing.totalPrice());
            if (netProfit.signum() <= 0 || netProfit.compareTo(minimumProfit) < 0) {
                continue;
            }
            BigDecimal roiPercent = Money.divide(netProfit, listing.totalPrice())
                    .multiply(new BigDecimal("100"), MathContext.DECIMAL128);
            BigDecimal discountPercent = Money.divide(resaleRevenue.subtract(listing.totalPrice()), resaleRevenue)
                    .multiply(new BigDecimal("100"), MathContext.DECIMAL128);
            if (roiPercent.compareTo(config.minimumRoiPercent()) < 0
                    || discountPercent.compareTo(config.minimumDiscountPercent()) < 0) {
                continue;
            }

            economicallyQualified++;
            List<String> flags = new ArrayList<>(estimate.flags());
            flags.add("selling-fee planning haircut " + Money.formatPercent(config.assumedSellingFeePercent()) + " is not confirmed by DonutSMP documentation");
            if (bankroll.balanceMayBeStale()) {
                flags.add("manual balance may be stale");
            }
            String rationale = rationale(listing, estimate, config, effectiveRiskPercent, bankroll);
            double score = score(netProfit, roiPercent, estimate, listing, bankroll, nowMillis, maxListingAgeMillis);
            candidates.add(new Opportunity(listing, estimate, resaleRevenue, fee, risk, netProfit,
                    roiPercent, discountPercent, Money.divide(netProfit, listing.totalPrice()), score,
                    rationale, flags));
        }

        CapitalAllocator.Allocation allocation = capitalAllocator.allocate(candidates, bankroll, config.maxRecommendations());
        if (!bankroll.balanceConfigured()) {
            warnings.add("No spendable balance is configured. Set it with /ahprofit budget <amount>; no candidates are recommended meanwhile.");
        } else if (bankroll.balanceMayBeStale()) {
            warnings.add("The manually entered balance is missing or older than 24 hours; update it with /ahprofit budget <amount>.");
        }
        if (lastSuccessfulFetchMillis <= 0 || nowMillis - lastSuccessfulFetchMillis > maxListingAgeMillis) {
            warnings.add("Market data is stale or has not been fetched yet; treat any cached estimates as historical only.");
        }
        if (safeListings.isEmpty()) {
            warnings.add("No current auction listings are cached yet.");
        }
        if (economicallyQualified > allocation.opportunities().size()) {
            warnings.add("Some profitable listings were left out to respect total, item, or category exposure limits.");
        }

        return new MarketAnalysis(analyzed, uncertain, economicallyQualified, allocation.opportunities(),
                bankroll, allocation.proposedCapital(), allocation.remainingDeployableCapital(),
                allocation.categoryExposure(), estimates, warnings);
    }

    private static double score(BigDecimal netProfit, BigDecimal roiPercent, PriceEstimate estimate,
                                AuctionListing listing, BankrollLimits bankroll, long nowMillis,
                                long maximumListingAgeMillis) {
        BigDecimal profitDenominator = bankroll.balance().multiply(new BigDecimal("0.10"), MathContext.DECIMAL128)
                .max(bankroll.minimumProfitThreshold());
        double normalizedProfit = Money.divide(netProfit, profitDenominator).doubleValue();
        double profitScore = clamp(normalizedProfit, 0, 1);
        double roiScore = clamp(roiPercent.doubleValue() / 100.0, 0, 1);
        double confidence = estimate.confidence();
        double liquidityScore = switch (estimate.liquidity()) {
            case HIGH -> 1.0;
            case MEDIUM -> 0.68;
            case LOW -> 0.28;
            case UNKNOWN -> 0.0;
        };
        double salesScore = clamp(estimate.comparableSales7Days() / 20.0, 0, 1);
        double age = Math.max(0, nowMillis - listing.observedAtMillis());
        double freshnessScore = maximumListingAgeMillis <= 0 ? 0
                : clamp(1.0 - age / (double) maximumListingAgeMillis, 0, 1);
        double volatilityPenalty = estimate.volatilityFraction() == null ? 0.20
                : Math.min(0.20, estimate.volatilityFraction().doubleValue() * 0.15);
        double competitionPenalty = Math.min(0.12,
                estimate.competingListings() / (double) Math.max(1, estimate.comparableSales7Days()) * 0.05);
        double capitalPenalty = bankroll.maxRecommendedListingSpend().signum() == 0 ? 0
                : clamp(listing.totalPrice().divide(bankroll.maxRecommendedListingSpend(), MathContext.DECIMAL128).doubleValue(), 0, 1) * 0.08;

        double result = 100.0 * (0.30 * profitScore + 0.20 * roiScore + 0.20 * confidence
                + 0.15 * liquidityScore + 0.10 * salesScore + 0.05 * freshnessScore)
                - 100.0 * (volatilityPenalty + competitionPenalty + capitalPenalty);
        return Math.max(0, result);
    }

    private static String rationale(AuctionListing listing, PriceEstimate estimate, AhProfitConfig config,
                                    BigDecimal effectiveRiskPercent, BankrollLimits bankroll) {
        String recentMedian = estimate.median24HourUnitPrice() == null
                ? "no 24-hour median (fewer than three sales)"
                : "24-hour unit median " + Money.format(estimate.median24HourUnitPrice());
        String trend = estimate.trendPercent() == null
                ? "7-day median " + Money.format(estimate.median7DayUnitPrice())
                : "24-hour trend " + Money.formatPercent(estimate.trendPercent());
        return estimate.comparableSales7Days() + " exact-variant completed sales in 7d; " + recentMedian
                + "; " + trend + "; " + estimate.liquidity().name().toLowerCase(java.util.Locale.ROOT)
                + " liquidity proxy; asking prices cap rather than establish resale value; uses "
                + Money.formatPercent(config.assumedSellingFeePercent()) + " unverified fee haircut and "
                + Money.formatPercent(effectiveRiskPercent) + " risk buffer; spend "
                + Money.format(listing.totalPrice()) + " is within the single-listing cap of "
                + Money.format(bankroll.maxRecommendedListingSpend());
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
