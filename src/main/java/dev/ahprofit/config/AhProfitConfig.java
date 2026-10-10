package dev.ahprofit.config;

import dev.ahprofit.market.ItemCategory;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;

/** Immutable runtime settings. The API key intentionally lives in a separate, private file. */
public record AhProfitConfig(
        int schemaVersion,
        String username,
        String balanceMode,
        BigDecimal manualBalance,
        long manualBalanceUpdatedAtMillis,
        BigDecimal reservePercent,
        BigDecimal minimumReserve,
        BigDecimal maxListingBalancePercent,
        BigDecimal maxListingDeployablePercent,
        BigDecimal maxTotalExposurePercent,
        BigDecimal maxCommodityExposurePercent,
        BigDecimal maxCategoryExposurePercent,
        int pollIntervalSeconds,
        BigDecimal minimumRoiPercent,
        BigDecimal minimumDiscountPercent,
        BigDecimal minimumProfitFloor,
        BigDecimal minimumProfitBalancePercent,
        int minimumHistoricalTransactions,
        int maxDataAgeSeconds,
        BigDecimal assumedSellingFeePercent,
        BigDecimal baseRiskBufferPercent,
        BigDecimal volatilityBufferMultiplier,
        int maxRecommendations,
        boolean showHud,
        boolean alertsEnabled,
        Set<ItemCategory> enabledCategories,
        Set<String> excludedItems,
        MarketDataMode marketDataMode,
        BigDecimal minimumConfidence,
        int maximumDaysToClear,
        boolean automationEnabled,
        boolean automationDryRun,
        BigDecimal automationMaxSpendPerAction,
        BigDecimal automationMaxSpendPerHour,
        int automationMaxActionsPerHour,
        int automationCooldownSeconds,
        BigDecimal automationMinimumScore,
        int automationSellSlot,
        boolean askOnlyAnalysis,
        int askMinimumListings,
        BigDecimal askMinimumSpreadPercent,
        BigDecimal askUndercutPercent
) {
    public AhProfitConfig {
        if (schemaVersion < 1) {
            schemaVersion = 1;
        }
        username = username == null ? "" : username.trim();
        balanceMode = "manual";
        marketDataMode = marketDataMode == null ? MarketDataMode.AUTO : marketDataMode;
        manualBalance = nonNegative(manualBalance, "manualBalance");
        reservePercent = boundedPercent(reservePercent, "reservePercent", 100);
        minimumReserve = nonNegative(minimumReserve, "minimumReserve");
        maxListingBalancePercent = boundedPercent(maxListingBalancePercent, "maxListingBalancePercent", 100);
        maxListingDeployablePercent = boundedPercent(maxListingDeployablePercent, "maxListingDeployablePercent", 100);
        maxTotalExposurePercent = boundedPercent(maxTotalExposurePercent, "maxTotalExposurePercent", 100);
        maxCommodityExposurePercent = boundedPercent(maxCommodityExposurePercent, "maxCommodityExposurePercent", 100);
        maxCategoryExposurePercent = boundedPercent(maxCategoryExposurePercent, "maxCategoryExposurePercent", 100);
        minimumRoiPercent = boundedPercent(minimumRoiPercent, "minimumRoiPercent", 1_000);
        minimumDiscountPercent = boundedPercent(minimumDiscountPercent, "minimumDiscountPercent", 100);
        minimumProfitFloor = nonNegative(minimumProfitFloor, "minimumProfitFloor");
        minimumProfitBalancePercent = boundedPercent(minimumProfitBalancePercent, "minimumProfitBalancePercent", 100);
        assumedSellingFeePercent = boundedPercent(assumedSellingFeePercent, "assumedSellingFeePercent", 100);
        baseRiskBufferPercent = boundedPercent(baseRiskBufferPercent, "baseRiskBufferPercent", 100);
        volatilityBufferMultiplier = boundedPercent(volatilityBufferMultiplier, "volatilityBufferMultiplier", 10);
        enabledCategories = Set.copyOf(enabledCategories == null || enabledCategories.isEmpty()
                ? EnumSet.allOf(ItemCategory.class) : enabledCategories);
        excludedItems = Set.copyOf(excludedItems == null ? Set.of() : excludedItems);

        if (manualBalanceUpdatedAtMillis < 0) {
            manualBalanceUpdatedAtMillis = 0;
        }
        if (pollIntervalSeconds < 10 || pollIntervalSeconds > 3_600) {
            pollIntervalSeconds = 20;
        }
        if (minimumHistoricalTransactions < 1 || minimumHistoricalTransactions > 500) {
            minimumHistoricalTransactions = 5;
        }
        if (maxDataAgeSeconds < 30 || maxDataAgeSeconds > 86_400) {
            maxDataAgeSeconds = 300;
        }
        if (maxRecommendations < 1 || maxRecommendations > 10) {
            maxRecommendations = 5;
        }
        minimumConfidence = boundedPercent(minimumConfidence, "minimumConfidence", 1);
        if (maximumDaysToClear < 1 || maximumDaysToClear > 90) {
            maximumDaysToClear = 14;
        }
        automationMaxSpendPerAction = nonNegative(automationMaxSpendPerAction, "automationMaxSpendPerAction");
        automationMaxSpendPerHour = nonNegative(automationMaxSpendPerHour, "automationMaxSpendPerHour");
        automationMinimumScore = boundedPercent(automationMinimumScore, "automationMinimumScore", 100);
        if (automationMaxActionsPerHour < 1 || automationMaxActionsPerHour > 500) {
            automationMaxActionsPerHour = 12;
        }
        if (automationCooldownSeconds < 3 || automationCooldownSeconds > 3_600) {
            automationCooldownSeconds = 10;
        }
        if (automationSellSlot < -1 || automationSellSlot > 255) {
            automationSellSlot = -1;
        }
        if (askMinimumListings < 2 || askMinimumListings > 50) {
            askMinimumListings = 4;
        }
        askMinimumSpreadPercent = boundedPercent(askMinimumSpreadPercent, "askMinimumSpreadPercent", 99);
        askUndercutPercent = boundedPercent(askUndercutPercent, "askUndercutPercent", 50);
    }

    public static AhProfitConfig defaults() {
        return new AhProfitConfig(
                1,
                "",
                "manual",
                BigDecimal.ZERO,
                0,
                new BigDecimal("20"),
                BigDecimal.ZERO,
                new BigDecimal("15"),
                new BigDecimal("25"),
                new BigDecimal("50"),
                new BigDecimal("20"),
                new BigDecimal("35"),
                20,
                new BigDecimal("20"),
                new BigDecimal("30"),
                BigDecimal.ZERO,
                new BigDecimal("2"),
                5,
                300,
                new BigDecimal("5"),
                new BigDecimal("5"),
                new BigDecimal("0.5"),
                5,
                true,
                true,
                EnumSet.allOf(ItemCategory.class),
                Set.of(),
                MarketDataMode.AUTO,
                new BigDecimal("0.50"),
                14,
                false,
                true,
                new BigDecimal("500000"),
                new BigDecimal("5000000"),
                12,
                10,
                new BigDecimal("55"),
                -1,
                false,
                4,
                new BigDecimal("35"),
                new BigDecimal("2")
        );
    }

    public AhProfitConfig withManualBalance(BigDecimal value, long updatedAtMillis) {
        return copy(value, updatedAtMillis, minimumReserve, minimumProfitFloor, minimumRoiPercent, marketDataMode);
    }

    public AhProfitConfig withMinimumReserve(BigDecimal value) {
        return copy(manualBalance, manualBalanceUpdatedAtMillis, value, minimumProfitFloor, minimumRoiPercent, marketDataMode);
    }

    public AhProfitConfig withMinimumProfitFloor(BigDecimal value) {
        return copy(manualBalance, manualBalanceUpdatedAtMillis, minimumReserve, value, minimumRoiPercent, marketDataMode);
    }

    public AhProfitConfig withMinimumRoiPercent(BigDecimal value) {
        return copy(manualBalance, manualBalanceUpdatedAtMillis, minimumReserve, minimumProfitFloor, value, marketDataMode);
    }

    public AhProfitConfig withMarketDataMode(MarketDataMode value) {
        return copy(manualBalance, manualBalanceUpdatedAtMillis, minimumReserve, minimumProfitFloor, minimumRoiPercent, value);
    }

    public AhProfitConfig withAutomationEnabled(boolean value) {
        return copy(manualBalance, manualBalanceUpdatedAtMillis, minimumReserve, minimumProfitFloor, minimumRoiPercent,
                marketDataMode, value);
    }

    public AhProfitConfig withAutomationDryRun(boolean value) {
        return copy(manualBalance, manualBalanceUpdatedAtMillis, minimumReserve, minimumProfitFloor, minimumRoiPercent,
                marketDataMode, automationEnabled, value);
    }

    public AhProfitConfig withAutomationLimits(BigDecimal maxSpendPerAction, BigDecimal maxSpendPerHour,
                                               int maxActionsPerHour, int cooldownSeconds, BigDecimal minimumScore) {
        return copy(manualBalance, manualBalanceUpdatedAtMillis, minimumReserve, minimumProfitFloor, minimumRoiPercent,
                marketDataMode, automationEnabled, automationDryRun, maxSpendPerAction, maxSpendPerHour,
                maxActionsPerHour, cooldownSeconds, minimumScore, askOnlyAnalysis);
    }

    public AhProfitConfig withAskOnlyAnalysis(boolean value) {
        return copy(manualBalance, manualBalanceUpdatedAtMillis, minimumReserve, minimumProfitFloor, minimumRoiPercent,
                marketDataMode, automationEnabled, automationDryRun, automationMaxSpendPerAction,
                automationMaxSpendPerHour, automationMaxActionsPerHour, automationCooldownSeconds,
                automationMinimumScore, value);
    }

    private AhProfitConfig copy(BigDecimal newBalance, long balanceUpdatedAt, BigDecimal newReserve,
                                BigDecimal newProfitFloor, BigDecimal newRoi, MarketDataMode newMode) {
        return copy(newBalance, balanceUpdatedAt, newReserve, newProfitFloor, newRoi, newMode,
                automationEnabled, automationDryRun, automationMaxSpendPerAction, automationMaxSpendPerHour,
                automationMaxActionsPerHour, automationCooldownSeconds, automationMinimumScore, askOnlyAnalysis);
    }

    private AhProfitConfig copy(BigDecimal newBalance, long balanceUpdatedAt, BigDecimal newReserve,
                                BigDecimal newProfitFloor, BigDecimal newRoi, MarketDataMode newMode,
                                boolean newAutomationEnabled) {
        return copy(newBalance, balanceUpdatedAt, newReserve, newProfitFloor, newRoi, newMode,
                newAutomationEnabled, automationDryRun, automationMaxSpendPerAction, automationMaxSpendPerHour,
                automationMaxActionsPerHour, automationCooldownSeconds, automationMinimumScore, askOnlyAnalysis);
    }

    private AhProfitConfig copy(BigDecimal newBalance, long balanceUpdatedAt, BigDecimal newReserve,
                                BigDecimal newProfitFloor, BigDecimal newRoi, MarketDataMode newMode,
                                boolean newAutomationEnabled, boolean newDryRun) {
        return copy(newBalance, balanceUpdatedAt, newReserve, newProfitFloor, newRoi, newMode,
                newAutomationEnabled, newDryRun, automationMaxSpendPerAction, automationMaxSpendPerHour,
                automationMaxActionsPerHour, automationCooldownSeconds, automationMinimumScore, askOnlyAnalysis);
    }

    private AhProfitConfig copy(BigDecimal newBalance, long balanceUpdatedAt, BigDecimal newReserve,
                                BigDecimal newProfitFloor, BigDecimal newRoi, MarketDataMode newMode,
                                boolean newAutomationEnabled, boolean newDryRun, BigDecimal newMaxSpendPerAction,
                                BigDecimal newMaxSpendPerHour, int newMaxActionsPerHour, int newCooldownSeconds,
                                BigDecimal newMinimumScore) {
        return copy(newBalance, balanceUpdatedAt, newReserve, newProfitFloor, newRoi, newMode,
                newAutomationEnabled, newDryRun, newMaxSpendPerAction, newMaxSpendPerHour,
                newMaxActionsPerHour, newCooldownSeconds, newMinimumScore, askOnlyAnalysis);
    }

    private AhProfitConfig copy(BigDecimal newBalance, long balanceUpdatedAt, BigDecimal newReserve,
                                BigDecimal newProfitFloor, BigDecimal newRoi, MarketDataMode newMode,
                                boolean newAutomationEnabled, boolean newDryRun, BigDecimal newMaxSpendPerAction,
                                BigDecimal newMaxSpendPerHour, int newMaxActionsPerHour, int newCooldownSeconds,
                                BigDecimal newMinimumScore, boolean newAskOnlyAnalysis) {
        return new AhProfitConfig(
                schemaVersion, username, "manual", newBalance, balanceUpdatedAt,
                reservePercent, newReserve, maxListingBalancePercent, maxListingDeployablePercent,
                maxTotalExposurePercent, maxCommodityExposurePercent, maxCategoryExposurePercent,
                pollIntervalSeconds, newRoi, minimumDiscountPercent, newProfitFloor,
                minimumProfitBalancePercent, minimumHistoricalTransactions, maxDataAgeSeconds,
                assumedSellingFeePercent, baseRiskBufferPercent, volatilityBufferMultiplier,
                maxRecommendations, showHud, alertsEnabled, enabledCategories, excludedItems, newMode,
                minimumConfidence, maximumDaysToClear, newAutomationEnabled, newDryRun, newMaxSpendPerAction,
                newMaxSpendPerHour, newMaxActionsPerHour, newCooldownSeconds, newMinimumScore, automationSellSlot,
                newAskOnlyAnalysis, askMinimumListings, askMinimumSpreadPercent, askUndercutPercent
        );
    }

    private static BigDecimal nonNegative(BigDecimal value, String name) {
        if (value == null || value.signum() < 0) {
            throw new IllegalArgumentException(name + " must be non-negative.");
        }
        return value.stripTrailingZeros();
    }

    private static BigDecimal boundedPercent(BigDecimal value, String name, int maximum) {
        BigDecimal normalized = nonNegative(value, name);
        if (normalized.compareTo(BigDecimal.valueOf(maximum)) > 0) {
            throw new IllegalArgumentException(name + " must not exceed " + maximum + ".");
        }
        return normalized;
    }

    public boolean hasUsableBalance() {
        return manualBalance.signum() > 0;
    }

    public boolean balanceMayBeStale(long nowMillis) {
        long ageMillis = nowMillis - manualBalanceUpdatedAtMillis;
        return manualBalanceUpdatedAtMillis == 0 || ageMillis < 0
                || ageMillis > DurationConstants.ONE_DAY_MILLIS;
    }

    public Instant manualBalanceUpdatedAt() {
        return manualBalanceUpdatedAtMillis <= 0 ? null : Instant.ofEpochMilli(manualBalanceUpdatedAtMillis);
    }

    private static final class DurationConstants {
        private static final long ONE_DAY_MILLIS = 24L * 60L * 60L * 1_000L;
    }
}
