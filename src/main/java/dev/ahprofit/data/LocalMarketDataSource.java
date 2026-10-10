package dev.ahprofit.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import dev.ahprofit.api.AuctionListing;
import dev.ahprofit.api.CompletedSale;
import dev.ahprofit.api.MarketRecordParser;
import dev.ahprofit.util.JsonSafety;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Strict, bounded, offline input. One invalid record rejects the whole snapshot before any DB write. */
public final class LocalMarketDataSource {
    private static final int MAX_BYTES = 4 * 1024 * 1024;
    private static final int MAX_RECORDS = 10_000;
    private static final int MAX_NESTING = 32;

    private final Path inputFile;
    private final String label;

    public LocalMarketDataSource(Path inputFile) {
        this(inputFile, "market-input.json");
    }

    public LocalMarketDataSource(Path inputFile, String label) {
        this.inputFile = java.util.Objects.requireNonNull(inputFile, "inputFile");
        this.label = label == null || label.isBlank() ? "market-input.json" : label;
    }

    public LocalMarketSnapshot load(long nowMillis) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(inputFile, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile()) {
            throw new IOException(label + " must be a regular file, not a symbolic link or directory.");
        }
        if (attributes.size() > MAX_BYTES) {
            throw new IOException(label + " exceeds the 4 MiB input limit.");
        }
        byte[] bytes;
        try (InputStream input = Files.newInputStream(inputFile, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            bytes = input.readNBytes(MAX_BYTES + 1);
        }
        if (bytes.length > MAX_BYTES) {
            throw new IOException(label + " exceeds the 4 MiB input limit.");
        }
        String content = new String(bytes, StandardCharsets.UTF_8);
        if (!JsonSafety.hasSafeNesting(content, MAX_NESTING)) {
            throw new IOException(label + " has invalid or excessive JSON nesting (maximum 32 levels).");
        }

        try {
            JsonElement parsed = JsonParser.parseString(content);
            if (!parsed.isJsonObject()) {
                throw new IllegalArgumentException("The local snapshot root must be an object.");
            }
            JsonObject root = parsed.getAsJsonObject();
            if (requiredLong(root, "schemaVersion") != 1) {
                throw new IllegalArgumentException("Only local snapshot schemaVersion 1 is supported.");
            }
            long observedAt = requiredLong(root, "observedAtMillis");
            if (observedAt <= 0 || observedAt > nowMillis) {
                throw new IllegalArgumentException("observedAtMillis must be the actual observation time, positive and not in the future.");
            }
            JsonArray listingRecords = requiredArray(root, "listings");
            JsonArray saleRecords = requiredArray(root, "sales");
            if (listingRecords.size() + saleRecords.size() > MAX_RECORDS) {
                throw new IllegalArgumentException("A local snapshot may contain at most 10,000 total records.");
            }

            Map<String, AuctionListing> listings = new LinkedHashMap<>();
            for (JsonElement record : listingRecords) {
                AuctionListing listing = MarketRecordParser.parseListing(record, 1, observedAt);
                listings.putIfAbsent(listing.listingKey(), listing);
            }
            Map<String, CompletedSale> sales = new LinkedHashMap<>();
            for (JsonElement record : saleRecords) {
                CompletedSale sale = MarketRecordParser.parseSale(record, 1, observedAt);
                if (sale.soldAtMillis() <= 0 || sale.soldAtMillis() > observedAt) {
                    throw new IllegalArgumentException("Completed-sale times must be positive and no later than observedAtMillis.");
                }
                sales.putIfAbsent(sale.saleKey(), sale);
            }
            return new LocalMarketSnapshot(observedAt, List.copyOf(listings.values()), List.copyOf(sales.values()));
        } catch (JsonParseException | IllegalStateException | IllegalArgumentException exception) {
            // Do not echo arbitrary input or parser exceptions that may contain private file contents.
            throw new IOException("Invalid " + label + ". Check schemaVersion, timestamps, arrays, items, sellers, and numeric prices; the last valid local snapshot was kept.");
        }
    }

    private static JsonArray requiredArray(JsonObject root, String field) {
        JsonElement value = root.get(field);
        if (value == null || !value.isJsonArray()) {
            throw new IllegalArgumentException("Missing required snapshot array.");
        }
        return value.getAsJsonArray();
    }

    private static long requiredLong(JsonObject root, String field) {
        JsonElement value = root.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("Missing numeric snapshot metadata.");
        }
        try {
            return new BigDecimal(value.getAsString()).longValueExact();
        } catch (ArithmeticException | NumberFormatException exception) {
            throw new IllegalArgumentException("Snapshot metadata must be an integer.");
        }
    }
}
