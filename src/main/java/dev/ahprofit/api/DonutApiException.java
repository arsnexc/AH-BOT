package dev.ahprofit.api;

import java.time.Duration;

public final class DonutApiException extends Exception {
    public enum Kind {
        MISSING_KEY,
        AUTHENTICATION,
        RATE_LIMIT,
        NO_MORE_PAGES,
        SERVER,
        CLIENT,
        TIMEOUT,
        NETWORK,
        SCHEMA
    }

    private final Kind kind;
    private final int httpStatus;
    private final Duration retryAfter;

    public DonutApiException(Kind kind, String safeMessage, int httpStatus, Duration retryAfter) {
        super(safeMessage);
        this.kind = kind;
        this.httpStatus = httpStatus;
        this.retryAfter = retryAfter;
    }

    public DonutApiException(Kind kind, String safeMessage, int httpStatus, Duration retryAfter, Throwable cause) {
        super(safeMessage, cause);
        this.kind = kind;
        this.httpStatus = httpStatus;
        this.retryAfter = retryAfter;
    }

    public Kind kind() {
        return kind;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public Duration retryAfter() {
        return retryAfter;
    }
}
