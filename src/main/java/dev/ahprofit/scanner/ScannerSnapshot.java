package dev.ahprofit.scanner;

import dev.ahprofit.market.MarketAnalysis;

public record ScannerSnapshot(
        boolean running,
        ApiStatus apiStatus,
        String apiMessage,
        long lastSuccessfulFetchMillis,
        long newestListingObservationMillis,
        int listingsAnalyzed,
        int qualifiedOpportunities,
        int economicallyQualifiedListings,
        int comparableSalesCached,
        int listingPageCursor,
        int transactionPageCursor,
        long nextAttemptAtMillis,
        MarketAnalysis analysis
) {
    public ScannerSnapshot {
        apiStatus = apiStatus == null ? ApiStatus.STOPPED : apiStatus;
        apiMessage = apiMessage == null ? "" : apiMessage;
    }

    public static ScannerSnapshot initial() {
        return new ScannerSnapshot(false, ApiStatus.STOPPED, "Scanner is stopped.",
                0, 0, 0, 0, 0, 0, 1, 2, 0, null);
    }
}
