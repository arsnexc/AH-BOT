package dev.ahprofit.api;

import dev.ahprofit.market.ItemCategory;

import java.math.BigDecimal;
import java.math.MathContext;

public record CompletedSale(
        String saleKey,
        ApiItem item,
        dev.ahprofit.market.ItemFingerprint.Result fingerprint,
        BigDecimal totalPrice,
        BigDecimal unitPrice,
        String sellerName,
        String sellerId,
        long soldAtMillis,
        long observedAtMillis,
        int sourcePage
) {
    public CompletedSale {
        if (saleKey == null || saleKey.isBlank() || item == null || fingerprint == null
                || totalPrice == null || unitPrice == null) {
            throw new IllegalArgumentException("Transaction fields are required");
        }
        if (totalPrice.signum() <= 0 || unitPrice.signum() <= 0 || soldAtMillis < 0 || sourcePage < 1) {
            throw new IllegalArgumentException("Invalid transaction values");
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

    public static String stableKey(String fingerprint, BigDecimal price, int quantity, String sellerId,
                                   String sellerName, long soldAtMillis) {
        String seller = sellerId == null || sellerId.isBlank()
                ? (sellerName == null ? "unknown" : sellerName.toLowerCase(java.util.Locale.ROOT))
                : sellerId.toLowerCase(java.util.Locale.ROOT);
        String value = String.join("|", fingerprint, price.stripTrailingZeros().toPlainString(),
                Integer.toString(quantity), seller, Long.toString(soldAtMillis));
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
