package dev.ahprofit.ah;

import java.util.List;

/** Everything the mod can see about the auction-house menu that is currently open. */
public record AuctionHouseSnapshot(
        int containerId,
        String title,
        List<AuctionEntry> entries,
        long observedAtMillis
) {
    public AuctionHouseSnapshot {
        entries = List.copyOf(entries == null ? List.of() : entries);
        title = title == null ? "" : title;
    }

    public static AuctionHouseSnapshot empty(int containerId, String title, long observedAtMillis) {
        return new AuctionHouseSnapshot(containerId, title, List.of(), observedAtMillis);
    }

    public boolean hasEntries() {
        return !entries.isEmpty();
    }

    /** Cheapest readable entry for an item, or null when the item is not currently listed. */
    public AuctionEntry cheapestFor(String itemId, int count) {
        AuctionEntry best = null;
        for (AuctionEntry entry : entries) {
            if (!entry.matchesItem(itemId, count) || !entry.priced()) {
                continue;
            }
            if (best == null || entry.totalPrice().compareTo(best.totalPrice()) < 0) {
                best = entry;
            }
        }
        return best;
    }
}
