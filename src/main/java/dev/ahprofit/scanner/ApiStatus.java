package dev.ahprofit.scanner;

public enum ApiStatus {
    STOPPED,
    STARTING,
    MISSING_KEY,
    CONNECTED,
    AUTH_REQUIRED,
    RATE_LIMITED,
    BACKING_OFF,
    OFFLINE,
    SCHEMA_ERROR,
    DATABASE_ERROR
}
