package dev.ahprofit.scanner;

/** Market-source status; LOCAL states explicitly do not describe an authenticated API connection. */
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
    DATABASE_ERROR,
    LOCAL,
    LOCAL_WAITING,
    LOCAL_ERROR;

    public boolean isLocalData() {
        return this == LOCAL || this == LOCAL_WAITING || this == LOCAL_ERROR;
    }

    public boolean hasUsableData() {
        return this == CONNECTED || this == LOCAL;
    }
}
