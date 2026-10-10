package dev.ahprofit.api;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/** Serial-request spacing. Production uses a one-second minimum gap (at most 60 requests/minute). */
public final class RequestThrottle {
    private final long minimumGapNanos;
    private long nextAllowedNanos;

    public RequestThrottle(Duration minimumGap) {
        if (minimumGap == null || minimumGap.isNegative()) {
            throw new IllegalArgumentException("Minimum request gap must be non-negative.");
        }
        this.minimumGapNanos = minimumGap.toNanos();
    }

    public static RequestThrottle conservativeDefault() {
        return new RequestThrottle(Duration.ofSeconds(1));
    }

    public void acquire() throws InterruptedException {
        long waitNanos;
        synchronized (this) {
            long now = System.nanoTime();
            waitNanos = Math.max(0L, nextAllowedNanos - now);
            nextAllowedNanos = Math.max(now, nextAllowedNanos) + minimumGapNanos;
        }
        if (waitNanos > 0) {
            TimeUnit.NANOSECONDS.sleep(waitNanos);
        }
    }
}
