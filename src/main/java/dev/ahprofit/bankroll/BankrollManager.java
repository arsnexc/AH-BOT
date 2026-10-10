package dev.ahprofit.bankroll;

import dev.ahprofit.config.AhProfitConfig;
import dev.ahprofit.util.Money;

import java.math.BigDecimal;
import java.util.Objects;

public final class BankrollManager {
    private BankrollManager() {
    }

    public static BankrollLimits calculate(AhProfitConfig config, long nowMillis) {
        Objects.requireNonNull(config, "config");
        BigDecimal balance = config.manualBalance();
        BigDecimal percentageReserve = Money.percentOf(balance, config.reservePercent());
        BigDecimal reserve = percentageReserve.max(config.minimumReserve());
        BigDecimal deployable = balance.subtract(reserve).max(BigDecimal.ZERO);

        BigDecimal maximumByBalance = Money.percentOf(balance, config.maxListingBalancePercent());
        BigDecimal maximumByDeployable = Money.percentOf(deployable, config.maxListingDeployablePercent());
        BigDecimal maximumListing = maximumByBalance.min(maximumByDeployable).max(BigDecimal.ZERO);

        BigDecimal maxTotalExposure = Money.percentOf(deployable, config.maxTotalExposurePercent());
        BigDecimal maxCommodityExposure = Money.percentOf(deployable, config.maxCommodityExposurePercent());
        BigDecimal maxCategoryExposure = Money.percentOf(deployable, config.maxCategoryExposurePercent());
        BigDecimal balanceBasedProfitFloor = Money.percentOf(balance, config.minimumProfitBalancePercent());
        BigDecimal minProfit = config.minimumProfitFloor().max(balanceBasedProfitFloor);

        return new BankrollLimits(
                balance,
                reserve,
                deployable,
                maximumListing,
                maxTotalExposure,
                maxCommodityExposure,
                maxCategoryExposure,
                minProfit,
                "manual",
                config.hasUsableBalance(),
                config.balanceMayBeStale(nowMillis)
        );
    }
}
