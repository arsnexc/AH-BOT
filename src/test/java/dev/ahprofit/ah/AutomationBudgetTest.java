package dev.ahprofit.ah;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutomationBudgetTest {
    private static final long NOW = 1_000_000_000_000L;

    @Test
    void spendCeilingIsEnforcedPerHour() {
        AutomationBudget budget = new AutomationBudget(new BigDecimal("10000"), 10, 0);

        assertTrue(budget.allowsSpend(new BigDecimal("6000"), NOW));
        assertTrue(budget.allowsSpend(new BigDecimal("4000"), NOW));
        assertFalse(budget.allowsSpend(new BigDecimal("1"), NOW), "the hourly ceiling must block the next spend");
    }

    @Test
    void actionCountCeilingIsEnforcedPerHour() {
        AutomationBudget budget = new AutomationBudget(new BigDecimal("1000000"), 2, 0);

        assertTrue(budget.allowsAction(NOW));
        budget.record(new BigDecimal("100"), NOW);
        assertTrue(budget.allowsAction(NOW));
        budget.record(new BigDecimal("100"), NOW);
        assertFalse(budget.allowsAction(NOW));
        assertEquals(2, budget.actionsInWindow());
    }

    @Test
    void cooldownBlocksTheImmediatelyFollowingAction() {
        AutomationBudget budget = new AutomationBudget(new BigDecimal("1000000"), 10,
                TimeUnit.SECONDS.toMillis(10));

        budget.record(new BigDecimal("100"), NOW);
        assertFalse(budget.allowsAction(NOW));
        assertEquals(10_000L, budget.millisUntilNextAction(NOW));
        assertTrue(budget.allowsAction(NOW + 10_000L));
    }

    @Test
    void countersRollOverAfterAnHour() {
        AutomationBudget budget = new AutomationBudget(new BigDecimal("10000"), 1, 0);
        budget.record(new BigDecimal("10000"), NOW);

        assertFalse(budget.allowsSpend(new BigDecimal("1"), NOW));
        assertFalse(budget.allowsAction(NOW));

        long later = NOW + Duration.ofHours(1).toMillis();
        assertTrue(budget.allowsSpend(new BigDecimal("1"), later));
        assertTrue(budget.allowsAction(later));
    }

    @Test
    void resetClearsEverything() {
        AutomationBudget budget = new AutomationBudget(new BigDecimal("10000"), 1, 5_000);
        budget.record(new BigDecimal("10000"), NOW);
        budget.reset();

        assertEquals(BigDecimal.ZERO, budget.spendInWindow());
        assertEquals(0, budget.actionsInWindow());
        assertTrue(budget.allowsSpend(new BigDecimal("10000"), NOW));
    }
}
