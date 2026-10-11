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
        // Why listings were dropped, so an empty result is never a silent mystery.
        int rejectedStale = 0;
        int rejectedNoBalance = 0;
        int rejectedTooExpensive = 0;
        int rejectedNoEvidence = 0;
        int rejectedNoRival = 0;
        int rejectedSpreadTooSmall = 0;
        int rejectedUnprofitable = 0;
        int rejectedBelowRoi = 0;
        int rejectedBelowDiscount = 0;
        long maxListingAgeMillis = Duration.ofSeconds(config.maxDataAgeSeconds()).toMillis();
        BigDecimal minimumProfit = bankroll.minimumProfitThreshold();

        for (CompletedSale sale : safeSales) {
            if (sale.fingerprint().comparable()) {
                salesByFingerprint.computeIfAbsent(sale.fingerprint().value(), ignored -> new ArrayList<>()).add(sale);
            }
        }
        // Fresh, comparable, unexpired listings grouped by variant. The estimator needs the market
        // statistics of a variant once, not once per candidate, so they are summarised up front.
        Map<String, List<AuctionListing>> freshByFingerprint = new HashMap<>();
        for (AuctionListing listing : safeListings) {
            if (!listing.fingerprint().comparable()
                    || nowMillis - listing.observedAtMillis() > maxListingAgeMillis
                    || listing.expiresAtMillis() <= nowMillis) {
                continue;
            }
            listingsByFingerprint.computeIfAbsent(listing.fingerprint().value(), ignored -> new ArrayList<>()).add(listing);
            freshByFingerprint.computeIfAbsent(listing.fingerprint().value(), ignored -> new ArrayList<>()).add(listing);
        }
        Map<String, PriceEstimator.AskMarketStats> statsByFingerprint = new HashMap<>();
        for (Map.Entry<String, List<AuctionListing>> entry : freshByFingerprint.entrySet()) {
            statsByFingerprint.put(entry.getKey(), PriceEstimator.summarise(entry.getValue()));
        }

        for (AuctionListing listing : safeListings) {
            if (!seenListings.add(listing.listingKey())) {
                continue;
            }
            analyzed++;
            if (nowMillis - listing.observedAtMillis() > maxListingAgeMillis
                    || listing.expiresAtMillis() <= nowMillis) {
                rejectedStale++;
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
                rejectedNoBalance++;
                continue;
            }
            if (listing.totalPrice().compareTo(bankroll.maxRecommendedListingSpend()) > 0) {
                rejectedTooExpensive++;
                continue;
            }

            List<CompletedSale> exactSales = salesByFingerprint.getOrDefault(listing.fingerprint().value(), List.of());
            List<AuctionListing> exactListings = listingsByFingerprint.getOrDefault(listing.fingerprint().value(), List.of());
            PriceEstimate estimate;
            if (config.askOnlyAnalysis() && exactSales.isEmpty()) {
                // The fresh group for this variant, candidate included, with its statistics
                // already summarised once rather than recomputed for every listing.
                List<AuctionListing> group = freshByFingerprint.getOrDefault(
                        listing.fingerprint().value(), List.of());
                estimate = priceEstimator.estimateFromAsks(listing, group,
                        statsByFingerprint.getOrDefault(listing.fingerprint().value(),
                                PriceEstimator.summarise(group)),
                        nowMillis, config.askMinimumListings(), config.askMinimumSpreadPercent(),
                        config.askUndercutPercent(), config.maxDataAgeSeconds());
            } else {
                estimate = priceEstimator.estimate(listing, exactSales, exactListings, nowMillis,
                        config.minimumHistoricalTransactions(), config.maxDataAgeSeconds());
            }
            estimates.put(listing.fingerprint().value(), estimate);
            if (estimate.conservativeUnitPrice() == null) {
                uncertain++;
                continue;
            }
            if (estimate.askBased()) {
                // Ask-based estimates are gated only on the spread being real; sale-count,
                // confidence, and clearance gates do not apply because there are no sales.
                if (!estimate.sufficientEvidence()) {
                    uncertain++;
                    if (freshByFingerprint.getOrDefault(listing.fingerprint().value(), List.of()).size() - 1
                            < config.askMinimumListings()) {
                        rejectedNoRival++;
                    } else {
                        rejectedSpreadTooSmall++;
                    }
                    continue;
                }
            } else {
                if (estimate.comparableSales7Days() < config.minimumHistoricalTransactions()) {
                    uncertain++;
                    continue;
                }
                if (estimate.confidence() < config.minimumConfidence().doubleValue()) {
                    uncertain++;
                    continue;
                }
                if (FlipScorer.expectedDaysToSell(estimate) > config.maximumDaysToClear()) {
                    uncertain++;
                    continue;
                }
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
            // With no completed sales the bankroll is a guess with no relation to observed spreads,
            // so an ask-based flip is held to a floor relative to its own price, not to the balance.
            BigDecimal profitFloor = estimate.askBased()
                    ? Money.percentOf(listing.totalPrice(), config.minimumProfitBalancePercent())
                    : minimumProfit;
            if (netProfit.signum() <= 0 || netProfit.compareTo(profitFloor) < 0) {
                if (estimate.askBased()) {
                    rejectedUnprofitable++;
                }
                continue;
            }
            BigDecimal roiPercent = Money.divide(netProfit, listing.totalPrice())
                    .multiply(new BigDecimal("100"), MathContext.DECIMAL128);
            BigDecimal discountPercent = Money.divide(resaleRevenue.subtract(listing.totalPrice()), resaleRevenue)
                    .multiply(new BigDecimal("100"), MathContext.DECIMAL128);
            // An ask-based flip already proved its spread against rival asks, so that spread is its
            // discount requirement; the sale-backed discount against a median still applies otherwise.
            BigDecimal discountFloor = estimate.askBased()
                    ? config.askMinimumSpreadPercent()
                    : config.minimumDiscountPercent();
            BigDecimal roiFloor = estimate.askBased()
                    ? config.askMinimumRoiPercent()
                    : config.minimumRoiPercent();
            if (roiPercent.compareTo(roiFloor) < 0) {
                if (estimate.askBased()) {
                    rejectedBelowRoi++;
                }
                continue;
            }
            if (discountPercent.compareTo(discountFloor) < 0) {
                if (estimate.askBased()) {
                    rejectedBelowDiscount++;
                }
                continue;
            }

            economicallyQualified++;
            List<String> flags = new ArrayList<>(estimate.flags());
            flags.add("selling-fee planning haircut " + Money.formatPercent(config.assumedSellingFeePercent()) + " is not confirmed by DonutSMP documentation");
            if (bankroll.balanceMayBeStale()) {
                flags.add("manual balance may be stale");
            }
            String rationale = rationale(listing, estimate, config, effectiveRiskPercent, bankroll);
            FlipScore flipScore = FlipScorer.score(listing, estimate, netProfit, roiPercent, bankroll,
                    nowMillis, maxListingAgeMillis);
            candidates.add(new Opportunity(listing, estimate, resaleRevenue, fee, risk, netProfit,
                    roiPercent, discountPercent, Money.divide(netProfit, listing.totalPrice()),
                    flipScore.total(), flipScore, rationale, flags));
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
        if (analyzed > 0 && allocation.opportunities().isEmpty()) {
            warnings.add("No flip passed the filters. Seen " + analyzed + " listings; dropped "
                    + rejectedStale + " stale, " + rejectedNoBalance + " for no configured balance"
                    + ", " + rejectedTooExpensive + " above the spend cap, " + rejectedNoRival
                    + " with too few rival asks, " + rejectedSpreadTooSmall
                    + " with too small a spread, " + rejectedUnprofitable + " unprofitable after fees"
                    + ", " + rejectedBelowRoi + " below the ROI floor, " + rejectedBelowDiscount
                    + " below the spread floor.");
        }
        if (economicallyQualified > allocation.opportunities().size()) {
            warnings.add("Some profitable listings were left out to respect total, item, or category exposure limits.");
        }

        return new MarketAnalysis(analyzed, uncertain, economicallyQualified, allocation.opportunities(),
                bankroll, allocation.proposedCapital(), allocation.remainingDeployableCapital(),
                allocation.categoryExposure(), estimates, warnings);
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
}
