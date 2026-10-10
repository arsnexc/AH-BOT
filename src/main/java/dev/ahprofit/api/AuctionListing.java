package dev.ahprofit.api;

import dev.ahprofit.market.ItemCategory;
import dev.ahprofit.market.ItemFingerprint;
import java.math.BigDecimal;
import java.math.MathContext;

public record AuctionListing(
        String listingKey,
        ApiItem item,
        ItemFingerprint.Result fingerprint,
        BigDecimal totalPrice,
        BigDecimal unitPrice,
        String sellerName,
        String sellerId,
        long timeLeftMillis,
        long observedAtMillis,
        long expiresAtMillis,
        int sourcePage
) {
    public AuctionListing {
        if (listingKey == null || listingKey.isBlank()) {
            throw new IllegalArgumentException("listingKey is required");
        }
        if (item == null || fingerprint == null || totalPrice == null || unitPrice == null) {
            throw new IllegalArgumentException("Listing item and price data are required");
        }
        if (totalPrice.signum() <= 0 || unitPrice.signum() <= 0 || timeLeftMillis < 0 || sourcePage < 1) {
            throw new IllegalArgumentException("Invalid listing values");
        }
        sellerName = sellerName == null ? "unknown" : sellerName;
        sellerId = sellerId == null ? "" : sellerId;
    }

    public int quantity() {
        return item.count();
    }

    public ItemCategory category() {
        return fingerprint.category();
    }

    public static BigDecimal unitPrice(BigDecimal total, int quantity) {
        return total.divide(BigDecimal.valueOf(quantity), MathContext.DECIMAL128);
    }

    public static String stableKey(String sellerId, String sellerName, String fingerprint, BigDecimal price,
                                   int quantity, long expiryMillis) {
        String seller = sellerId == null || sellerId.isBlank()
                ? (sellerName == null ? "unknown" : sellerName.toLowerCase(java.util.Locale.ROOT))
                : sellerId.toLowerCase(java.util.Locale.ROOT);
        long expiryBucket = Math.floorDiv(expiryMillis, 60_000L);
        String material = String.join("|", seller, fingerprint, price.stripTrailingZeros().toPlainString(),
                Integer.toString(quantity), Long.toString(expiryBucket));
        return hash(material);
    }

    private static String hash(String value) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte next : digest) {
                result.append(Character.forDigit((next >>> 4) & 15, 16));
                result.append(Character.forDigit(next & 15, 16));
            }
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime.", exception);
        }
    }
}
