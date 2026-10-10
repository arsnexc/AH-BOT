package dev.ahprofit.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import dev.ahprofit.market.ItemFingerprint;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.net.http.HttpTimeoutException;

/** Read-only client for the two auction endpoints documented by DonutSMP. */
public final class DonutApiClient {
    private static final int MAX_PAGE_ARRAY_SIZE = 1_000;
    private static final int MAX_JSON_NESTING = 32;
    private static final long MAX_TIME_LEFT_MILLIS = Duration.ofDays(365).toMillis();

    private final ApiTransport transport;
    private final Supplier<String> apiKeySupplier;
    private final RequestThrottle throttle;

    public DonutApiClient(Supplier<String> apiKeySupplier) {
        this(new JdkApiTransport(), apiKeySupplier, RequestThrottle.conservativeDefault());
    }

    public DonutApiClient(ApiTransport transport, Supplier<String> apiKeySupplier, RequestThrottle throttle) {
        this.transport = java.util.Objects.requireNonNull(transport, "transport");
        this.apiKeySupplier = java.util.Objects.requireNonNull(apiKeySupplier, "apiKeySupplier");
        this.throttle = java.util.Objects.requireNonNull(throttle, "throttle");
    }

    public ParsedPage<AuctionListing> fetchListings(int page) throws DonutApiException, InterruptedException {
        return fetchListings(page, System.currentTimeMillis());
    }

    public ParsedPage<AuctionListing> fetchListings(int page, long observedAtMillis)
            throws DonutApiException, InterruptedException {
        if (page < 1 || page > 100_000) {
            throw new IllegalArgumentException("Auction page must be between 1 and 100,000.");
        }
        String body = "{\"sort\":\"lowest_price\"}";
        ApiResponse response = request("/v1/auction/list/" + page, body);
        checkStatus(response, true);
        JsonArray entries = resultArray(response);

        List<AuctionListing> parsed = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int nulls = 0;
        int malformed = 0;
        int duplicates = 0;
        for (JsonElement element : entries) {
            if (element == null || element.isJsonNull()) {
                nulls++;
                continue;
            }
            try {
                AuctionListing listing = parseListing(element, page, observedAtMillis);
                if (seen.add(listing.listingKey())) {
                    parsed.add(listing);
                } else {
                    duplicates++;
                }
            } catch (IllegalArgumentException exception) {
                malformed++;
            }
        }
        return new ParsedPage<>(parsed, nulls, malformed, duplicates);
    }

    public ParsedPage<CompletedSale> fetchTransactions(int page) throws DonutApiException, InterruptedException {
        return fetchTransactions(page, System.currentTimeMillis());
    }

    public ParsedPage<CompletedSale> fetchTransactions(int page, long observedAtMillis)
            throws DonutApiException, InterruptedException {
        if (page < 1 || page > 10) {
            throw new IllegalArgumentException("Transaction page must be between 1 and 10 per the DonutSMP API documentation.");
        }
        ApiResponse response = request("/v1/auction/transactions/" + page, null);
        checkStatus(response, false);
        JsonArray entries = resultArray(response);

        List<CompletedSale> parsed = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int nulls = 0;
        int malformed = 0;
        int duplicates = 0;
        for (JsonElement element : entries) {
            if (element == null || element.isJsonNull()) {
                nulls++;
                continue;
            }
            try {
                CompletedSale sale = parseSale(element, page, observedAtMillis);
                if (seen.add(sale.saleKey())) {
                    parsed.add(sale);
                } else {
                    duplicates++;
                }
            } catch (IllegalArgumentException exception) {
                malformed++;
            }
        }
        return new ParsedPage<>(parsed, nulls, malformed, duplicates);
    }

    private ApiResponse request(String path, String body) throws DonutApiException, InterruptedException {
        String token = apiKeySupplier.get();
        if (token == null || token.isBlank()) {
            throw new DonutApiException(DonutApiException.Kind.MISSING_KEY,
                    "No DonutSMP API key is configured. Add one to config/ahprofit/credentials.json.", 0, null);
        }
        if (token.length() > 4_096 || token.chars().anyMatch(Character::isISOControl)) {
            throw new DonutApiException(DonutApiException.Kind.MISSING_KEY,
                    "The configured API key has an invalid format. Correct credentials.json; the key value was not logged.", 0, null);
        }

        try {
            throttle.acquire();
            return transport.get(path, body, token.trim());
        } catch (HttpTimeoutException exception) {
            throw new DonutApiException(DonutApiException.Kind.TIMEOUT,
                    "DonutSMP request timed out; the last valid market snapshot was kept.", 0, null);
        } catch (IOException exception) {
            throw new DonutApiException(DonutApiException.Kind.NETWORK,
                    "Could not reach the DonutSMP API; the last valid market snapshot was kept.", 0, null);
        } catch (IllegalArgumentException exception) {
            throw new DonutApiException(DonutApiException.Kind.CLIENT,
                    "The local DonutSMP request configuration was rejected; check credentials.json. The API key was not logged.",
                    0, null);
        }
    }

    private static void checkStatus(ApiResponse response, boolean listingEndpoint) throws DonutApiException {
        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            return;
        }
        if (response.statusCode() == 401 || response.statusCode() == 403) {
            throw new DonutApiException(DonutApiException.Kind.AUTHENTICATION,
                    "DonutSMP rejected the API key. Regenerate it with /api, update credentials.json, then use /ahprofit refresh.",
                    response.statusCode(), null);
        }
        if (response.statusCode() == 429) {
            throw new DonutApiException(DonutApiException.Kind.RATE_LIMIT,
                    "DonutSMP rate-limited the request (429); requests are paused according to Retry-After.",
                    429, parseRetryAfter(response.header("Retry-After")));
        }
        if (response.statusCode() == 500 && listingEndpoint && isInvalidPage(response.body())) {
            throw new DonutApiException(DonutApiException.Kind.NO_MORE_PAGES,
                    "The auction listing page is outside the current page range.", 500, null);
        }
        if (response.statusCode() >= 500) {
            throw new DonutApiException(DonutApiException.Kind.SERVER,
                    "DonutSMP returned a server error (HTTP " + response.statusCode() + ").", response.statusCode(), null);
        }
        throw new DonutApiException(DonutApiException.Kind.CLIENT,
                "DonutSMP returned HTTP " + response.statusCode() + ".", response.statusCode(), null);
    }

    private static JsonArray resultArray(ApiResponse response) throws DonutApiException {
        if (response.body().length() > 4 * 1024 * 1024
                || !hasSafeJsonNesting(response.body(), MAX_JSON_NESTING)) {
            throw schemaError("Response exceeded the parsing size or nesting safety limit.");
        }
        final JsonElement parsed;
        try {
            parsed = JsonParser.parseString(response.body());
        } catch (JsonParseException | IllegalStateException exception) {
            throw schemaError("DonutSMP returned malformed JSON.");
        }
        if (parsed == null || !parsed.isJsonObject()) {
            throw schemaError("DonutSMP response was not a JSON object.");
        }
        JsonObject root = parsed.getAsJsonObject();
        JsonElement statusElement = root.get("status");
        if (statusElement != null && !statusElement.isJsonNull()) {
            if (!statusElement.isJsonPrimitive() || !statusElement.getAsJsonPrimitive().isNumber()) {
                throw schemaError("DonutSMP response contained an invalid status field.");
            }
            try {
                if (statusElement.getAsInt() != 200) {
                    throw schemaError("DonutSMP response status did not indicate success.");
                }
            } catch (NumberFormatException exception) {
                throw schemaError("DonutSMP response contained an invalid status field.");
            }
        }
        JsonElement result = root.get("result");
        if (result == null || !result.isJsonArray() || result.getAsJsonArray().size() > MAX_PAGE_ARRAY_SIZE) {
            throw schemaError("DonutSMP response did not contain a supported result array.");
        }
        return result.getAsJsonArray();
    }

    private static AuctionListing parseListing(JsonElement element, int page, long observedAtMillis) {
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

    private static CompletedSale parseSale(JsonElement element, int page, long observedAtMillis) {
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

    private static Duration parseRetryAfter(String value) {
        if (value == null || value.isBlank()) {
            return Duration.ofSeconds(60);
        }
        try {
            long seconds = Long.parseLong(value.trim());
            return Duration.ofSeconds(Math.max(1, Math.min(seconds, 3_600)));
        } catch (NumberFormatException ignored) {
            try {
                Instant retryAt = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                Duration delay = Duration.between(Instant.now(), retryAt);
                if (delay.isNegative()) {
                    return Duration.ofSeconds(1);
                }
                return delay.compareTo(Duration.ofHours(1)) > 0 ? Duration.ofHours(1) : delay;
            } catch (DateTimeParseException ignoredDate) {
                return Duration.ofSeconds(60);
            }
        }
    }

    private static boolean hasSafeJsonNesting(String body, int maximumDepth) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int index = 0; index < body.length(); index++) {
            char character = body.charAt(index);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (character == '\\') {
                    escaped = true;
                } else if (character == '"') {
                    inString = false;
                }
                continue;
            }
            if (character == '"') {
                inString = true;
            } else if (character == '{' || character == '[') {
                if (++depth > maximumDepth) {
                    return false;
                }
            } else if (character == '}' || character == ']') {
                if (--depth < 0) {
                    return false;
                }
            }
        }
        return !inString && depth == 0;
    }

    private static boolean isInvalidPage(String body) {
        if (body == null || body.length() > 64 * 1024 || !hasSafeJsonNesting(body, MAX_JSON_NESTING)) {
            return false;
        }
        try {
            JsonElement element = JsonParser.parseString(body);
            if (!element.isJsonObject()) {
                return false;
            }
            JsonElement message = element.getAsJsonObject().get("message");
            if (message == null || !message.isJsonPrimitive() || !message.getAsJsonPrimitive().isString()) {
                return false;
            }
            String normalized = message.getAsString().toLowerCase(java.util.Locale.ROOT);
            return normalized.contains("page you entered does not exist")
                    || normalized.contains("page does not exist");
        } catch (JsonParseException | IllegalStateException exception) {
            return false;
        }
    }

    private static DonutApiException schemaError(String message) {
        return new DonutApiException(DonutApiException.Kind.SCHEMA, message, 200, null);
    }
}
