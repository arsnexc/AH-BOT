package dev.ahprofit.data;

import dev.ahprofit.TestMarketFixtures;
import dev.ahprofit.api.ApiItem;
import dev.ahprofit.api.AuctionListing;
import dev.ahprofit.api.CompletedSale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalMarketSnapshotMergeTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void mergingKeepsTheNewerObservationAndDeduplicatesRecords() {
        long older = System.currentTimeMillis() - 600_000L;
        long newer = System.currentTimeMillis();
        ApiItem diamond = TestMarketFixtures.item("minecraft:diamond", 64);
        AuctionListing shared = TestMarketFixtures.listing(diamond, "128000", older, "shared");
        AuctionListing onlyOlder = TestMarketFixtures.listing(diamond, "64000", older, "only-older");
        AuctionListing onlyNewer = TestMarketFixtures.listing(diamond, "192000", newer, "only-newer");
        CompletedSale sale = TestMarketFixtures.sale(diamond, "2000", older, "buyer", 1);

        LocalMarketSnapshot first = new LocalMarketSnapshot(older, List.of(shared, onlyOlder), List.of(sale));
        LocalMarketSnapshot second = new LocalMarketSnapshot(newer, List.of(shared, onlyNewer), List.of());

        LocalMarketSnapshot merged = first.merge(second);

        assertEquals(newer, merged.observedAtMillis());
        assertEquals(3, merged.listings().size(), "duplicates collapse to one record");
        assertEquals(1, merged.sales().size());
        assertTrue(merged.listings().stream().anyMatch(listing -> listing.listingKey().equals(onlyNewer.listingKey())));
        // The newer observation wins for a shared listing key, so its freshness is the honest one.
        assertEquals(newer, merged.listings().stream()
                .filter(listing -> listing.listingKey().equals(shared.listingKey()))
                .findFirst().orElseThrow().observedAtMillis());
    }

    @Test
    void mergingWithNullReturnsTheOriginal() {
        long now = System.currentTimeMillis();
        LocalMarketSnapshot snapshot = new LocalMarketSnapshot(now, List.of(), List.of());
        assertEquals(snapshot, snapshot.merge(null));
    }

    @Test
    void eachRecordKeepsItsOwnObservationTimeSoStaleRecordsStayStale() {
        long older = System.currentTimeMillis() - Duration.ofHours(2).toMillis();
        long newer = System.currentTimeMillis();
        ApiItem diamond = TestMarketFixtures.item("minecraft:diamond", 64);
        AuctionListing stale = TestMarketFixtures.listing(diamond, "128000", older, "stale");
        AuctionListing fresh = TestMarketFixtures.listing(diamond, "192000", newer, "fresh");

        LocalMarketSnapshot merged = new LocalMarketSnapshot(older, List.of(stale), List.of())
                .merge(new LocalMarketSnapshot(newer, List.of(fresh), List.of()));

        assertEquals(older, merged.listings().stream()
                .filter(listing -> listing.listingKey().equals(stale.listingKey()))
                .findFirst().orElseThrow().observedAtMillis());
    }

    @Test
    void aSnapshotRejectsANonPositiveObservationTime() {
        assertThrows(IllegalArgumentException.class, () -> new LocalMarketSnapshot(0, List.of(), List.of()));
    }

    @Test
    void theLoaderNamesTheFileItRejected() throws IOException {
        Path file = temporaryDirectory.resolve("ah-observed.json");
        Files.writeString(file, "{\"schemaVersion\":2}");
        LocalMarketDataSource source = new LocalMarketDataSource(file, "ah-observed.json");

        IOException failure = assertThrows(IOException.class, () -> source.load(System.currentTimeMillis()));

        assertTrue(failure.getMessage().contains("ah-observed.json"), failure.getMessage());
    }
}
