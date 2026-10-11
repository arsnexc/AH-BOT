package dev.ahprofit.config;

import java.util.Locale;

/** Local mode never contacts DonutSMP; auto uses it only when no API key is configured. */
public enum MarketDataMode {
    AUTO,
    API,
    LOCAL;

    public boolean usesLocalData(boolean hasApiKey) {
        return this == LOCAL || (this == AUTO && !hasApiKey);
    }

    public String configValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static MarketDataMode parse(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Data source must be auto, api, or local.");
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Data source must be auto, api, or local.");
        }
    }
}
