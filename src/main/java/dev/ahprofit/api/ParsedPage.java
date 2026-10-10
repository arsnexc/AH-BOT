package dev.ahprofit.api;

import java.util.List;

public record ParsedPage<T>(List<T> entries, int nullEntries, int malformedEntries, int duplicateEntries) {
    public ParsedPage {
        entries = List.copyOf(entries);
        if (nullEntries < 0 || malformedEntries < 0 || duplicateEntries < 0) {
            throw new IllegalArgumentException("Page counters cannot be negative");
        }
    }
}
