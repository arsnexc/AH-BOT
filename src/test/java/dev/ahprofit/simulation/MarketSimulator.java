package dev.ahprofit.simulation;

import dev.ahprofit.TestMarketFixtures;
import dev.ahprofit.config.AhProfitConfig;
import dev.ahprofit.api.ApiItem;
import dev.ahprofit.api.AuctionListing;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Generates synthetic auction markets with a known ground truth, so the estimator can be scored
 * against reality instead of against itself.
 *
 * <p>The ground truth is {@code trueUnitPrice}: the price at which a unit actually clears. Sellers
 * ask above it and mispriced listings ask below it, so a listing is a genuine flip exactly when its
 * ask is low enough that the clearing price still covers the selling fee.
 */
final class MarketSimulator {
    /** Every simulated item is a 64-stack, which is what makes the unit price meaningful. */
    private static final int STACK = 64;

    private final Random random;
    private final ApiItem item;

    MarketSimulator(long seed) {
        this.random = new Random(seed);
        this.item = TestMarketFixtures.item("minecraft:diamond", STACK);
    }

    ApiItem item() {
        return item;
    }

    /** A different item, so a scale market can mix one huge variant with a normal one. */
    ApiItem otherItem(String id, int count) {
        return TestMarketFixtures.item(id, count);
    }

    AuctionListing listingOf(ApiItem item, long now, double unitPrice, String seller) {
        BigDecimal total = BigDecimal.valueOf(unitPrice)
                .multiply(BigDecimal.valueOf(item.count()))
                .setScale(0, RoundingMode.HALF_UP);
        if (total.signum() <= 0) {
            total = BigDecimal.ONE;
        }
        return TestMarketFixtures.listing(item, total.toPlainString(), now, seller);
    }

    /** A uniform draw in [0,1), so scenarios stay reproducible from the seed. */
    double nextUniform() {
        return random.nextDouble();
    }

    AhProfitConfig bankroll(String balance, long updatedAtMillis) {
        return TestMarketFixtures.bankroll(balance, updatedAtMillis);
    }

    /** A rival listing whose ask sits at {@code markup} times the true unit price. */
    AuctionListing rival(long now, double trueUnit, double markup, String seller) {
        return listing(now, trueUnit * markup, seller);
    }

    /** A listing whose ask is exactly {@code unitPrice}. */
    AuctionListing listing(long now, double unitPrice, String seller) {
        BigDecimal total = BigDecimal.valueOf(unitPrice)
                .multiply(BigDecimal.valueOf(STACK))
                .setScale(0, RoundingMode.HALF_UP);
        if (total.signum() <= 0) {
            total = BigDecimal.ONE;
        }
        return TestMarketFixtures.listing(item, total.toPlainString(), now, seller);
    }

    BigDecimal unitPriceOf(AuctionListing listing) {
        return listing.unitPrice();
    }

    /**
     * A market of {@code count} rivals whose markups are log-normal around {@code centre}, which is
     * how real asking prices cluster: most sellers ask a similar premium, a few ask wildly more.
     */
    List<AuctionListing> clusteredMarket(long now, double trueUnit, double centre, double sigma, int count) {
        List<AuctionListing> listings = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            double markup = Math.exp(sigma * random.nextGaussian()) * centre;
            listings.add(rival(now, trueUnit, Math.max(0.01, markup), "rival-" + index));
        }
        return listings;
    }

    /** A market with a controlled share of dumped listings far below any credible price. */
    List<AuctionListing> contaminatedMarket(long now, double trueUnit, double dumpShare, int count) {
        List<AuctionListing> listings = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            boolean dumped = random.nextDouble() < dumpShare;
            double markup = dumped ? 0.02 + random.nextDouble() * 0.05 : 1.0 + random.nextGaussian() * 0.03;
            listings.add(rival(now, trueUnit, Math.max(0.01, markup), "rival-" + index));
        }
        return listings;
    }

    /** A market where every ask is drawn uniformly across a very wide band. */
    List<AuctionListing> dispersedMarket(long now, double trueUnit, int count) {
        List<AuctionListing> listings = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            listings.add(rival(now, trueUnit, 0.3 + random.nextDouble() * 2.7, "rival-" + index));
        }
        return listings;
    }

    /** Rivals that all ask the same premium, so the median absolute deviation collapses to zero. */
    List<AuctionListing> identicalMarket(long now, double trueUnit, double markup, int count) {
        List<AuctionListing> listings = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            listings.add(rival(now, trueUnit, markup, "rival-" + index));
        }
        return listings;
    }

    /** A candidate priced at {@code markup} times the true unit price. */
    AuctionListing candidate(long now, double trueUnit, double markup) {
        return rival(now, trueUnit, markup, "candidate");
    }

    static BigDecimal bd(double value) {
        return BigDecimal.valueOf(value).round(new MathContext(12));
    }
}
