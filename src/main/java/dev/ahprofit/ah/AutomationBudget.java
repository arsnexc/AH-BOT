package dev.ahprofit.ah;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * Rolling one-hour spend and action counters. Every automated trade must pass through here, so a
 * misbehaving planner can never spend more than the configured hourly ceilings.
 */
public final class AutomationBudget {
    private static final long WINDOW_MILLIS = Duration.ofHours(1).toMillis();

    private final BigDecimal maxSpendPerHour;
    private final int maxActionsPerHour;
    private final long cooldownMillis;

    private long windowStartMillis;
    private BigDecimal windowSpend = BigDecimal.ZERO;
    private int windowActions;
    private long lastActionAtMillis;

    public AutomationBudget(BigDecimal maxSpendPerHour, int maxActionsPerHour, long cooldownMillis) {
        this.maxSpendPerHour = maxSpendPerHour == null ? BigDecimal.ZERO : maxSpendPerHour;
        this.maxActionsPerHour = Math.max(0, maxActionsPerHour);
        this.cooldownMillis = Math.max(0, cooldownMillis);
    }

    /** Drops counters once the rolling window has fully elapsed. */
    public synchronized void roll(long nowMillis) {
        if (nowMillis - windowStartMillis >= WINDOW_MILLIS) {
            windowStartMillis = nowMillis;
            windowSpend = BigDecimal.ZERO;
            windowActions = 0;
        }
    }

    public synchronized boolean allowsSpend(BigDecimal amount, long nowMillis) {
        roll(nowMillis);
        if (amount == null || amount.signum() <= 0) {
            return false;
        }
        return windowSpend.add(amount).compareTo(maxSpendPerHour) <= 0;
    }

    public synchronized boolean allowsAction(long nowMillis) {
        roll(nowMillis);
        if (windowActions >= maxActionsPerHour) {
            return false;
        }
        return lastActionAtMillis == 0 || nowMillis - lastActionAtMillis >= cooldownMillis;
    }

    /** Records a completed action; callers must only call this after the click was actually sent. */
    public synchronized void record(BigDecimal amount, long nowMillis) {
        roll(nowMillis);
        windowSpend = windowSpend.add(amount == null ? BigDecimal.ZERO : amount);
        windowActions++;
        lastActionAtMillis = nowMillis;
    }

    public synchronized BigDecimal spendInWindow() {
        return windowSpend;
    }

    public synchronized int actionsInWindow() {
        return windowActions;
    }

    public synchronized long millisUntilNextAction(long nowMillis) {
        if (lastActionAtMillis == 0) {
            return 0;
        }
        return Math.max(0, cooldownMillis - (nowMillis - lastActionAtMillis));
    }

    public synchronized void reset() {
        windowStartMillis = 0;
        windowSpend = BigDecimal.ZERO;
        windowActions = 0;
        lastActionAtMillis = 0;
    }
}
