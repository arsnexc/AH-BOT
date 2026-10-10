package dev.ahprofit.api;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.ahprofit.market.ItemFingerprint;

import java.math.BigDecimal;
import java.time.Duration;

/** Shared market-record validation for authenticated API pages and key-free local snapshots. */
public final class MarketRecordParser {
    private static final long MAX_TIME_LEFT_MILLIS = Duration.ofDays(365).toMillis();

    private MarketRecordParser() {
    }

    public static AuctionListing parseListing(JsonElement element, int page, long observedAtMillis) {
        if (!element.isJsonObject()) {
            throw new IllegalArgumentException("Listing is not an object.");
        }
        JsonObject object = element.getAsJsonObject();
        JsonElement itemElement = object.get("item");
        if (itemElement == null || !itemElement.isJsonObject()) {
            throw new IllegalArgumentException("Listing item is missing.");
        }
        ApiItem item = ApiItem.fromJson(itemElement.getAsJsonObject());
        ItemFingerprint.Result fingerprint = ItemFingerprint.of(item);
        BigDecimal totalPrice = requiredPositiveDecimal(object, "price");
        JsonObject seller = requiredObject(object, "seller");
        String sellerName = optionalString(seller, "name");
        String sellerId = optionalString(seller, "uuid");
        if (sellerName.isBlank() && sellerId.isBlank()) {
            throw new IllegalArgumentException("Listing seller is missing.");
        }
        long timeLeftMillis = requiredLong(object, "time_left");
        if (timeLeftMillis < 0 || timeLeftMillis > MAX_TIME_LEFT_MILLIS) {
            throw new IllegalArgumentException("Listing time remaining is outside the supported range.");
        }
        long expiresAtMillis;
        try {
            expiresAtMillis = Math.addExact(observedAtMillis, timeLeftMillis);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Listing expiry overflowed.");
        }
        String listingKey = AuctionListing.stableKey(sellerId, sellerName, fingerprint.value(), totalPrice,
                item.count(), expiresAtMillis);
        BigDecimal unitPrice = AuctionListing.unitPrice(totalPrice, item.count());
        return new AuctionListing(listingKey, item, fingerprint, totalPrice, unitPrice, sellerName, sellerId,
                timeLeftMillis, observedAtMillis, expiresAtMillis, page);
    }

    public static CompletedSale parseSale(JsonElement element, int page, long observedAtMillis) {
        if (!element.isJsonObject()) {
            throw new IllegalArgumentException("Transaction is not an object.");
        }
        JsonObject object = element.getAsJsonObject();
        JsonElement itemElement = object.get("item");
        if (itemElement == null || !itemElement.isJsonObject()) {
            throw new IllegalArgumentException("Transaction item is missing.");
        }
        ApiItem item = ApiItem.fromJson(itemElement.getAsJsonObject());
        ItemFingerprint.Result fingerprint = ItemFingerprint.of(item);
        BigDecimal totalPrice = requiredPositiveDecimal(object, "price");
        long soldAtMillis = requiredLong(object, "unixMillisDateSold");
        if (soldAtMillis < 0 || soldAtMillis > observedAtMillis + Duration.ofMinutes(5).toMillis()) {
            throw new IllegalArgumentException("Transaction sale timestamp is invalid.");
        }
        String sellerName = "";
        String sellerId = "";
        JsonElement sellerElement = object.get("seller");
        if (sellerElement != null && sellerElement.isJsonObject()) {
            JsonObject seller = sellerElement.getAsJsonObject();
            sellerName = optionalString(seller, "name");
            sellerId = optionalString(seller, "uuid");
        }
        String saleKey = CompletedSale.stableKey(fingerprint.value(), totalPrice, item.count(), sellerId,
                sellerName, soldAtMillis);
        BigDecimal unitPrice = CompletedSale.unitPrice(totalPrice, item.count());
        return new CompletedSale(saleKey, item, fingerprint, totalPrice, unitPrice, sellerName, sellerId,
                soldAtMillis, observedAtMillis, page);
    }

    private static BigDecimal requiredPositiveDecimal(JsonObject object, String name) {
        JsonElement element = object.get(name);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("Missing or invalid numeric field: " + name);
        }
        try {
            BigDecimal value = new BigDecimal(element.getAsString());
            if (value.signum() <= 0 || value.compareTo(new BigDecimal("1000000000000000000")) > 0) {
                throw new IllegalArgumentException("Numeric field is outside the supported range.");
            }
            return value.stripTrailingZeros();
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid numeric field: " + name);
        }
    }

    private static long requiredLong(JsonObject object, String name) {
        JsonElement element = object.get(name);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("Missing or invalid integer field: " + name);
        }
        try {
            return new BigDecimal(element.getAsString()).longValueExact();
        } catch (ArithmeticException | NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid integer field: " + name);
        }
    }

    private static JsonObject requiredObject(JsonObject object, String name) {
        JsonElement element = object.get(name);
        if (element == null || !element.isJsonObject()) {
            throw new IllegalArgumentException("Missing object field: " + name);
        }
        return element.getAsJsonObject();
    }

    private static String optionalString(JsonObject object, String name) {
        JsonElement element = object.get(name);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isString()) {
            return "";
        }
        return element.getAsString().trim();
    }

}
