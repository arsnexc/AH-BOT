package dev.ahprofit.data;

import dev.ahprofit.api.AuctionListing;
import dev.ahprofit.api.CompletedSale;

import java.util.List;

/** A user-supplied observation, not a live API response. Reading it never advances its freshness. */
public record LocalMarketSnapshot(long observedAtMillis, List<AuctionListing> listings, List<CompletedSale> sales) {
    public LocalMarketSnapshot {
        if (observedAtMillis <= 0) {
            throw new IllegalArgumentException("A real market observation timestamp is required.");
        }
        listings = List.copyOf(listings);
        sales = List.copyOf(sales);
    }
}
