package dev.ahprofit.ah;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuctionEntryTest {
    @Test
    void pricedEntriesExposeBothTotalAndUnitPrice() {
        AuctionEntry entry = AuctionEntry.of(12, "minecraft:diamond", "Diamond", 64, List.of("Price: $8,000"));

        assertTrue(entry.priced());
        assertEquals(new BigDecimal("8000"), entry.totalPrice());
        assertEquals(new BigDecimal("125"), entry.unitPrice());
    }

    @Test
    void entriesWithoutAReadablePriceAreNotPriced() {
        AuctionEntry entry = AuctionEntry.of(12, "minecraft:diamond", "Diamond", 64, List.of("Click to browse"));

        assertFalse(entry.priced());
        assertEquals(null, entry.totalPrice());
        assertEquals(null, entry.unitPrice());
    }

    @Test
    void matchingRequiresBothItemAndStackSize() {
        AuctionEntry entry = AuctionEntry.of(12, "minecraft:diamond", "Diamond", 64, List.of("Price: $8,000"));

        assertTrue(entry.matchesItem("minecraft:diamond", 64));
        assertTrue(entry.matchesItem("MINECRAFT:DIAMOND", 64));
        assertFalse(entry.matchesItem("minecraft:diamond", 1));
        assertFalse(entry.matchesItem("minecraft:emerald", 64));
    }

    @Test
    void snapshotPicksTheCheapestMatchingEntry() {
        AuctionHouseSnapshot snapshot = new AuctionHouseSnapshot(7, "Auction House", List.of(
                AuctionEntry.of(10, "minecraft:diamond", "Diamond", 64, List.of("Price: $9,500")),
                AuctionEntry.of(11, "minecraft:emerald", "Emerald", 64, List.of("Price: $1")),
                AuctionEntry.of(12, "minecraft:diamond", "Diamond", 64, List.of("Price: $8,000")),
                AuctionEntry.of(13, "minecraft:diamond", "Diamond", 64, List.of("no price here"))), 1_000L);

        AuctionEntry cheapest = snapshot.cheapestFor("minecraft:diamond", 64);

        assertEquals(12, cheapest.slotIndex());
        assertEquals(new BigDecimal("8000"), cheapest.totalPrice());
        assertTrue(snapshot.hasEntries());
    }

    @Test
    void snapshotReturnsNullWhenNothingMatches() {
        AuctionHouseSnapshot snapshot = new AuctionHouseSnapshot(7, "Auction House",
                List.of(AuctionEntry.of(10, "minecraft:emerald", "Emerald", 64, List.of("Price: $1"))), 1_000L);

        assertEquals(null, snapshot.cheapestFor("minecraft:diamond", 64));
        assertTrue(AuctionHouseSnapshot.empty(7, "Auction House", 1_000L).entries().isEmpty());
    }
}
