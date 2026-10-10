package dev.ahprofit.client.ah;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.ahprofit.ah.AuctionEntry;
import dev.ahprofit.ah.AuctionHouseSnapshot;
import dev.ahprofit.scanner.ScannerService;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Records the auction-house listings the player actually looks at, so key-free analysis has real
 * market data without an API key.
 *
 * <p>It only reads the open container menu and writes a local JSON file in the same schema as
 * {@code market-input.json}; it never clicks anything. Listings are kept for a short rolling window
 * because a listing seen ten minutes ago is no longer a current asking price.
 */
public final class AuctionHouseRecorder {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final int TICKS_PER_SCAN = 10;
    private static final long RETENTION_MILLIS = Duration.ofMinutes(10).toMillis();
    private static final long WRITE_INTERVAL_MILLIS = Duration.ofSeconds(5).toMillis();
    private static final int MAX_BUFFERED = 5_000;
    private static final long ASSUMED_TIME_LEFT_MILLIS = Duration.ofMinutes(10).toMillis();

    private final Path outputFile;
    private final ScannerService scanner;
    private final Map<String, BufferedListing> buffer = new LinkedHashMap<>();
    private final AtomicBoolean recording = new AtomicBoolean(false);
    private final AtomicReference<String> status = new AtomicReference<>("Not recording.");

    private int tickCounter;
    private long lastWriteMillis;

    public AuctionHouseRecorder(Path outputFile, ScannerService scanner) {
        this.outputFile = outputFile;
        this.scanner = scanner;
    }

    public void onClientTick(Minecraft client) {
        if (!recording.get() || client == null || client.player == null || client.isPaused()) {
            return;
        }
        if (++tickCounter < TICKS_PER_SCAN) {
            return;
        }
        tickCounter = 0;

        long now = System.currentTimeMillis();
        AuctionHouseSnapshot snapshot = AuctionHouseReader.read(client, now);
        if (snapshot == null) {
            status.set("Recording; no auction-house menu is open, so nothing new is buffered.");
            return;
        }
        int added = 0;
        for (AuctionEntry entry : snapshot.entries()) {
            if (!entry.priced()) {
                continue;
            }
            BufferedListing listing = new BufferedListing(entry.itemId(), entry.count(),
                    entry.totalPrice(), now);
            if (buffer.put(listing.identity(), listing) == null) {
                added++;
            }
        }
        prune(now);
        status.set("Recording; " + buffer.size() + " listings buffered from the open menu ("
                + added + " newly seen).");

        if (added > 0 && now - lastWriteMillis >= WRITE_INTERVAL_MILLIS) {
            write(now);
            // Queues a one-shot import; the scanner's own cooldown applies.
            scanner.requestManualRefresh();
        }
    }

    private void prune(long now) {
        buffer.values().removeIf(listing -> now - listing.observedAtMillis() > RETENTION_MILLIS);
        if (buffer.size() <= MAX_BUFFERED) {
            return;
        }
        buffer.entrySet().stream()
                .sorted(Map.Entry.<String, BufferedListing>comparingByValue(
                        java.util.Comparator.comparingLong(BufferedListing::observedAtMillis)).reversed())
                .skip(MAX_BUFFERED)
                .map(Map.Entry::getKey)
                .toList()
                .forEach(buffer::remove);
    }

    private void write(long now) {
        lastWriteMillis = now;
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", 1);
        root.addProperty("observedAtMillis", now);
        JsonArray listings = new JsonArray();
        for (BufferedListing listing : buffer.values()) {
            JsonObject item = new JsonObject();
            item.addProperty("id", listing.itemId());
            item.addProperty("count", listing.count());
            JsonObject seller = new JsonObject();
            seller.addProperty("name", "ah-menu");
            JsonObject record = new JsonObject();
            record.add("item", item);
            record.add("seller", seller);
            record.addProperty("price", listing.totalPrice());
            record.addProperty("time_left", ASSUMED_TIME_LEFT_MILLIS);
            listings.add(record);
        }
        root.add("listings", listings);
        root.add("sales", new JsonArray());

        try {
            Files.createDirectories(outputFile.toAbsolutePath().getParent());
            Path temporary = outputFile.resolveSibling(outputFile.getFileName() + ".tmp");
            Files.writeString(temporary, GSON.toJson(root) + System.lineSeparator(), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, outputFile, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, outputFile, StandardCopyOption.REPLACE_EXISTING);
            }
            status.set("Wrote " + buffer.size() + " observed listings to " + outputFile.getFileName()
                    + "; a refresh was queued to import them.");
        } catch (IOException exception) {
            status.set("Could not write " + outputFile.getFileName() + "; observed listings stay buffered in memory.");
        }
    }

    public void start() {
        recording.set(true);
        status.set("Recording; open the auction house and browse to collect listings.");
    }

    public void stop() {
        recording.set(false);
        status.set("Not recording; " + buffer.size() + " buffered listings were kept in memory only.");
    }

    public boolean isRecording() {
        return recording.get();
    }

    public int bufferedCount() {
        return buffer.size();
    }

    public String status() {
        return status.get();
    }

    public Path outputPath() {
        return outputFile;
    }

    public boolean outputExists() {
        return Files.exists(outputFile, LinkOption.NOFOLLOW_LINKS);
    }

    private record BufferedListing(String itemId, int count, BigDecimal totalPrice, long observedAtMillis) {
        private String identity() {
            return itemId + ":" + count + ":" + totalPrice.stripTrailingZeros().toPlainString();
        }
    }
}
