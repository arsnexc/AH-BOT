package dev.ahprofit.data;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalMarketDataSourceTest {
    private static final long NOW = 1_800_000_000_000L;
    private static final String LISTING = """
            {"item":{"id":"minecraft:diamond","count":64},"seller":{"name":"seller"},"price":6400,"time_left":60000}
            """;
    private static final String SALE = """
            {"item":{"id":"minecraft:diamond","count":64},"seller":{"name":"seller"},"price":12800,"unixMillisDateSold":1799999940000}
            """;

    @TempDir
    Path temporaryDirectory;

    @Test
    void realObservationTimesUnitPricesAndDuplicateKeysArePreserved() throws Exception {
        LocalMarketSnapshot snapshot = load(snapshot(NOW - 1000, LISTING + "," + LISTING, SALE + "," + SALE));
        assertEquals(NOW - 1000, snapshot.observedAtMillis());
        assertEquals(1, snapshot.listings().size());
        assertEquals(1, snapshot.sales().size());
        var listing = snapshot.listings().getFirst();
        assertEquals(NOW - 1000, listing.observedAtMillis());
        assertEquals(NOW + 59000, listing.expiresAtMillis());
        assertEquals("100", listing.unitPrice().toPlainString());
        assertEquals("200", snapshot.sales().getFirst().unitPrice().toPlainString());
    }

    @Test
    void emptySnapshotIsAcceptedButMissingArraysAreNot() throws Exception {
        assertTrue(load(snapshot(NOW, "", "")).listings().isEmpty());
        assertThrows(IOException.class, () -> load("{\"schemaVersion\":1,\"observedAtMillis\":" + NOW + "}"));
    }

    @Test
    void invalidRecordRejectsTheWholeFileWithoutEchoingInput() {
        String privateValue = "private-value-do-not-echo";
        IOException error = assertThrows(IOException.class,
                () -> load(snapshot(NOW, LISTING + ",{\"price\":\"" + privateValue + "\"}", SALE)));
        assertTrue(error.getMessage().contains("last valid local snapshot was kept"));
        assertFalse(error.getMessage().contains(privateValue));
        assertThrows(IOException.class, () -> load(snapshot(NOW, "null", SALE)));
        assertThrows(IOException.class, () -> load(snapshot(NOW, LISTING.replace("6400", "-1"), SALE)));
    }

    @Test
    void missingZeroFractionalAndFutureTimestampsAreRejected() {
        assertThrows(IOException.class, () -> load(snapshot(0, LISTING, SALE)));
        assertThrows(IOException.class, () -> load(snapshot(NOW + 1, LISTING, SALE)));
        assertThrows(IOException.class, () -> load(snapshot(NOW, LISTING, SALE).replace(Long.toString(NOW), NOW + ".5")));
        assertThrows(IOException.class, () -> load(snapshot(NOW - 120000, LISTING, SALE)));
        assertThrows(IOException.class, () -> load(snapshot(NOW, LISTING, SALE.replace("1799999940000", "0"))));
        assertThrows(IOException.class, () -> load(snapshot(NOW, LISTING, SALE).replace("\"schemaVersion\":1", "\"schemaVersion\":2")));
    }

    @Test
    void oldInputRemainsOldAndJsonStringsDoNotCountAsNesting() throws Exception {
        long old = NOW - 3_600_000;
        LocalMarketSnapshot data = load(snapshot(old, LISTING.replace("60000", "7200000"), ""));
        assertEquals(old, data.listings().getFirst().observedAtMillis());
        String itemWithLore = LISTING.replace("\"count\":64", "\"count\":64,\"lore\":[\"[[[[{{{{\\\"\\\"\"]");
        assertEquals(1, load(snapshot(NOW, itemWithLore, "")).listings().size());
    }

    @Test
    void sizeNestingAndRecordLimitsAreEnforced() {
        assertThrows(IOException.class, () -> load(" ".repeat(4 * 1024 * 1024 + 1)));
        assertThrows(IOException.class, () -> load("[".repeat(40) + "0" + "]".repeat(40)));
        assertThrows(IOException.class, () -> load(snapshot(NOW, "{},".repeat(10000) + "{}", "")));
    }

    @Test
    void symbolicLinksAreNotRead() throws Exception {
        Path actual = temporaryDirectory.resolve("actual.json");
        Files.writeString(actual, snapshot(NOW, LISTING, SALE));
        Path link = temporaryDirectory.resolve("market-input.json");
        Files.createSymbolicLink(link, actual);
        assertThrows(IOException.class, () -> new LocalMarketDataSource(link).load(NOW));
    }

    private LocalMarketSnapshot load(String content) throws IOException {
        Path file = temporaryDirectory.resolve("market-input.json");
        Files.writeString(file, content);
        return new LocalMarketDataSource(file).load(NOW);
    }

    private static String snapshot(long observedAt, String listings, String sales) {
        return "{\"schemaVersion\":1,\"observedAtMillis\":" + observedAt
                + ",\"listings\":[" + listings + "],\"sales\":[" + sales + "]}";
    }
}
