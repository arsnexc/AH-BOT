package dev.ahprofit.ah;

import java.math.BigDecimal;
import java.util.List;

/** One readable slot of the auction-house container the player currently has open. */
public record AuctionEntry(
        int slotIndex,
        String itemId,
        String displayName,
        int count,
        List<String> loreLines,
        BigDecimal unitPrice,
        BigDecimal totalPrice,
        boolean priced
) {
    public AuctionEntry {
        itemId = itemId == null ? "" : itemId;
        displayName = displayName == null ? "" : displayName;
        loreLines = List.copyOf(loreLines == null ? List.of() : loreLines);
    }

    /** Builds an entry from plain strings so the parsing rules stay unit-testable. */
    public static AuctionEntry of(int slotIndex, String itemId, String displayName, int count,
                                  List<String> loreLines) {
        List<String> safeLines = loreLines == null ? List.of() : List.copyOf(loreLines);
        BigDecimal total = LorePriceParser.findPrice(safeLines, displayName);
        BigDecimal unit = null;
        if (total != null && count > 0) {
            unit = total.divide(BigDecimal.valueOf(count), java.math.MathContext.DECIMAL128);
        }
        return new AuctionEntry(slotIndex, itemId, displayName, count, safeLines, unit, total, total != null);
    }

    /**
     * Builds an entry with a price already resolved from extra sources such as custom-data
     * components, which the plain-string factory cannot see.
     */
    public static AuctionEntry of(int slotIndex, String itemId, String displayName, int count,
                                  List<String> loreLines, BigDecimal totalPrice) {
        List<String> safeLines = loreLines == null ? List.of() : List.copyOf(loreLines);
        BigDecimal total = totalPrice;
        BigDecimal unit = null;
        if (total != null && total.signum() > 0 && count > 0) {
            unit = total.divide(BigDecimal.valueOf(count), java.math.MathContext.DECIMAL128);
        }
        return new AuctionEntry(slotIndex, itemId, displayName, count, safeLines, unit, total, total != null);
    }

    /** Builds an entry from a menu slot, reading the price the way this server writes it. */
    public static AuctionEntry of(int slotIndex, String itemId, String displayName, int count,
                                  List<String> loreLines, List<String> extraSources,
                                  dev.ahprofit.config.ServerProfile profile) {
        List<String> safeLines = loreLines == null ? List.of() : List.copyOf(loreLines);
        BigDecimal total = LorePriceParser.findPrice(safeLines, displayName, extraSources, profile);
        return of(slotIndex, itemId, displayName, count, safeLines, total);
    }

    public boolean matchesItem(String wantedItemId, int wantedCount) {
        return count == wantedCount && itemId.equalsIgnoreCase(wantedItemId);
    }
}
