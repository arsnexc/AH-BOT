package dev.ahprofit;

import dev.ahprofit.api.ApiItem;
import dev.ahprofit.api.AuctionListing;
import dev.ahprofit.api.CompletedSale;
import dev.ahprofit.config.AhProfitConfig;
import dev.ahprofit.market.ItemFingerprint;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public final class TestMarketFixtures {
    private TestMarketFixtures() {
    }

    public static ApiItem item(String id, int count) {
        return ApiItem.fromJson("{\"id\":\"" + id + "\",\"count\":" + count + "}");
    }

    public static AuctionListing listing(ApiItem item, String totalPrice, long observedAtMillis, String seller) {
        BigDecimal total = new BigDecimal(totalPrice);
        ItemFingerprint.Result fingerprint = ItemFingerprint.of(item);
        long timeLeft = Duration.ofHours(12).toMillis();
        long expiresAt = observedAtMillis + timeLeft;
        String key = AuctionListing.stableKey(seller + "-uuid", seller, fingerprint.value(), total,
                item.count(), expiresAt);
        return new AuctionListing(key, item, fingerprint, total,
                AuctionListing.unitPrice(total, item.count()), seller, seller + "-uuid",
                timeLeft, observedAtMillis, expiresAt, 1);
    }

    public static CompletedSale sale(ApiItem item, String unitPrice, long soldAtMillis, String seller, int page) {
        BigDecimal unit = new BigDecimal(unitPrice);
        BigDecimal total = unit.multiply(BigDecimal.valueOf(item.count()));
        ItemFingerprint.Result fingerprint = ItemFingerprint.of(item);
        String key = CompletedSale.stableKey(fingerprint.value(), total, item.count(), seller + "-uuid", seller, soldAtMillis);
        return new CompletedSale(key, item, fingerprint, total, CompletedSale.unitPrice(total, item.count()),
                seller, seller + "-uuid", soldAtMillis, soldAtMillis, page);
    }

    public static List<CompletedSale> repeatedSales(ApiItem item, String unitPrice, int count, long nowMillis) {
        List<CompletedSale> result = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            long soldAt = nowMillis - Duration.ofHours(index + 1L).toMillis();
            result.add(sale(item, unitPrice, soldAt, "seller-" + index, 1 + (index % 10)));
        }
        return List.copyOf(result);
    }

    public static AhProfitConfig bankroll(String balance, long updatedAtMillis) {
        return AhProfitConfig.defaults().withManualBalance(new BigDecimal(balance), updatedAtMillis);
    }
}
