package dev.ahprofit.data;

import dev.ahprofit.api.AuctionListing;
import dev.ahprofit.api.CompletedSale;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A user-supplied observation, not a live API response. Reading it never advances its freshness. */
public record LocalMarketSnapshot(long observedAtMillis, List<AuctionListing> listings, List<CompletedSale> sales) {
    public LocalMarketSnapshot {
        if (observedAtMillis <= 0) {
            throw new IllegalArgumentException("A real market observation timestamp is required.");
        }
        listings = List.copyOf(listings);
        sales = List.copyOf(sales);
    }

    /**
     * Combines two snapshots, keeping the newer observation time. On a listing conflict the other
     * snapshot wins, because it is expected to be the more recent observation. Each record keeps its
     * own observation time, so stale records from the older snapshot stay stale.
     */
    public LocalMarketSnapshot merge(LocalMarketSnapshot other) {
        if (other == null) {
            return this;
        }
        long observedAt = Math.max(this.observedAtMillis, other.observedAtMillis);
        Map<String, AuctionListing> listings = new LinkedHashMap<>();
        for (AuctionListing listing : this.listings) {
            listings.putIfAbsent(listing.listingKey(), listing);
        }
        for (AuctionListing listing : other.listings) {
            listings.put(listing.listingKey(), listing);
        }
        Map<String, CompletedSale> sales = new LinkedHashMap<>();
        for (CompletedSale sale : this.sales) {
            sales.putIfAbsent(sale.saleKey(), sale);
        }
        for (CompletedSale sale : other.sales) {
            sales.put(sale.saleKey(), sale);
        }
        return new LocalMarketSnapshot(observedAt, List.copyOf(listings.values()), List.copyOf(sales.values()));
    }
}
