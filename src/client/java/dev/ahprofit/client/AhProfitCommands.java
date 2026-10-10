package dev.ahprofit.client;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.ahprofit.bankroll.BankrollLimits;
import dev.ahprofit.bankroll.BankrollManager;
import dev.ahprofit.config.AhProfitConfig;
import dev.ahprofit.config.ConfigManager;
import dev.ahprofit.config.MarketDataMode;
import dev.ahprofit.market.ItemFingerprint;
import dev.ahprofit.market.LiquidityLevel;
import dev.ahprofit.market.MarketAnalysis;
import dev.ahprofit.market.Opportunity;
import dev.ahprofit.scanner.ApiStatus;
import dev.ahprofit.scanner.ScannerService;
import dev.ahprofit.scanner.ScannerSnapshot;
import dev.ahprofit.util.Money;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.network.chat.Component;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public final class AhProfitCommands {
    private static final int MAX_STATUS_OPPORTUNITIES = 5;

    private AhProfitCommands() {
    }

    public static void register(ScannerService scanner, ConfigManager configManager) {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) -> {
            dispatcher.register(ClientCommandManager.literal("ahprofit")
                    .executes(context -> toggle(context, scanner, configManager))
                    .then(ClientCommandManager.literal("toggle")
                            .executes(context -> toggle(context, scanner, configManager)))
                    .then(ClientCommandManager.literal("status")
                            .executes(context -> status(context, scanner, configManager)))
                    .then(ClientCommandManager.literal("source")
                            .executes(context -> source(context, scanner, configManager, false))
                            .then(ClientCommandManager.argument("mode", StringArgumentType.word())
                                    .suggests((context, builder) -> {
                                        builder.suggest("auto");
                                        builder.suggest("api");
                                        builder.suggest("local");
                                        return builder.buildFuture();
                                    })
                                    .executes(context -> source(context, scanner, configManager, true))))
                    .then(ClientCommandManager.literal("budget")
                            .then(ClientCommandManager.argument("amount", StringArgumentType.greedyString())
                                    .executes(context -> budget(context, scanner, configManager))))
                    .then(ClientCommandManager.literal("reserve")
                            .then(ClientCommandManager.argument("amount", StringArgumentType.greedyString())
                                    .executes(context -> reserve(context, scanner, configManager))))
                    .then(ClientCommandManager.literal("minprofit")
                            .then(ClientCommandManager.argument("amount", StringArgumentType.greedyString())
                                    .executes(context -> minimumProfit(context, scanner, configManager))))
                    .then(ClientCommandManager.literal("minroi")
                            .then(ClientCommandManager.argument("percent", StringArgumentType.greedyString())
                                    .executes(context -> minimumRoi(context, scanner, configManager))))
                    .then(ClientCommandManager.literal("refresh")
                            .executes(context -> refresh(context, scanner)))
                    .then(ClientCommandManager.literal("help")
                            .executes(AhProfitCommands::help)));
        });
    }

    private static int toggle(CommandContext<FabricClientCommandSource> context,
                              ScannerService scanner, ConfigManager configManager) {
        boolean enabled = scanner.toggle();
        ScannerSnapshot state = scanner.snapshot();
        sendCompactStatus(context.getSource(), enabled, state, configManager.get());
        return 1;
    }

    private static int status(CommandContext<FabricClientCommandSource> context,
                              ScannerService scanner, ConfigManager configManager) {
        sendFullStatus(context.getSource(), scanner.snapshot(), configManager.get());
        return 1;
    }

    private static int source(CommandContext<FabricClientCommandSource> context,
                              ScannerService scanner, ConfigManager configManager, boolean change) {
        if (change) {
            try {
                MarketDataMode mode = MarketDataMode.parse(StringArgumentType.getString(context, "mode"));
                if (!scanner.setMarketDataMode(mode)) {
                    return error(context, "AhProfit is shutting down and cannot change its data source.");
                }
            } catch (IllegalArgumentException exception) {
                return error(context, exception.getMessage());
            }
        }
        context.getSource().sendFeedback(Component.literal("AhProfit source: "
                + configManager.get().marketDataMode().configValue() + " -> "
                + (scanner.isUsingLocalData() ? "key-free local data (no API requests)" : "authenticated DonutSMP API")
                + ". API and local histories are kept separate."));
        if (change) {
            context.getSource().sendFeedback(Component.literal(scanner.isRunning()
                    ? "Displayed analysis cleared; a background refresh is scheduled for the selected source."
                    : "Displayed analysis cleared; the selected cache is being re-scored. Use /ahprofit refresh to load new market data."));
        }
        return 1;
    }

    private static int budget(CommandContext<FabricClientCommandSource> context,
                              ScannerService scanner, ConfigManager configManager) {
        try {
            BigDecimal amount = Money.parseUserAmount(StringArgumentType.getString(context, "amount"));
            AhProfitConfig updated = configManager.update(current -> current.withManualBalance(amount, System.currentTimeMillis()));
            scanner.recomputeCached();
            BankrollLimits limits = BankrollManager.calculate(updated, System.currentTimeMillis());
            context.getSource().sendFeedback(Component.literal("AhProfit balance estimate set to " + Money.format(amount)
                    + " (manual, not API-verified). Protected reserve " + Money.format(limits.protectedReserve())
                    + ", deployable " + Money.format(limits.deployableCapital()) + ", max listing spend "
                    + Money.format(limits.maxRecommendedListingSpend()) + ". Cached recommendations are being recalculated."));
            return 1;
        } catch (IllegalArgumentException exception) {
            return error(context, exception.getMessage());
        }
    }

    private static int reserve(CommandContext<FabricClientCommandSource> context,
                               ScannerService scanner, ConfigManager configManager) {
        try {
            BigDecimal amount = Money.parseUserAmount(StringArgumentType.getString(context, "amount"));
            AhProfitConfig updated = configManager.update(current -> current.withMinimumReserve(amount));
            scanner.recomputeCached();
            BankrollLimits limits = BankrollManager.calculate(updated, System.currentTimeMillis());
            context.getSource().sendFeedback(Component.literal("AhProfit minimum protected reserve set to " + Money.format(amount)
                    + ". Current protected reserve is " + Money.format(limits.protectedReserve())
                    + ", deployable capital is " + Money.format(limits.deployableCapital()) + "."));
            return 1;
        } catch (IllegalArgumentException exception) {
            return error(context, exception.getMessage());
        }
    }

    private static int minimumProfit(CommandContext<FabricClientCommandSource> context,
                                     ScannerService scanner, ConfigManager configManager) {
        try {
            BigDecimal amount = Money.parseUserAmount(StringArgumentType.getString(context, "amount"));
            AhProfitConfig updated = configManager.update(current -> current.withMinimumProfitFloor(amount));
            scanner.recomputeCached();
            BankrollLimits limits = BankrollManager.calculate(updated, System.currentTimeMillis());
            context.getSource().sendFeedback(Component.literal("AhProfit configured minimum-profit floor set to " + Money.format(amount)
                    + ". The active threshold is the greater of this floor and "
                    + Money.formatPercent(updated.minimumProfitBalancePercent()) + " of the manual balance: "
                    + Money.format(limits.minimumProfitThreshold()) + "."));
            return 1;
        } catch (IllegalArgumentException exception) {
            return error(context, exception.getMessage());
        }
    }

    private static int minimumRoi(CommandContext<FabricClientCommandSource> context,
                                  ScannerService scanner, ConfigManager configManager) {
        try {
            BigDecimal percent = Money.parseUserPercent(StringArgumentType.getString(context, "percent"), "Minimum ROI");
            AhProfitConfig updated = configManager.update(current -> current.withMinimumRoiPercent(percent));
            scanner.recomputeCached();
            context.getSource().sendFeedback(Component.literal("AhProfit minimum ROI set to " + Money.formatPercent(updated.minimumRoiPercent())
                    + ". Cached recommendations are being recalculated."));
            return 1;
        } catch (IllegalArgumentException exception) {
            return error(context, exception.getMessage());
        }
    }

    private static int refresh(CommandContext<FabricClientCommandSource> context, ScannerService scanner) {
        ScannerService.RefreshResult result = scanner.requestManualRefresh();
        return switch (result) {
            case QUEUED -> {
                context.getSource().sendFeedback(Component.literal("AhProfit manual refresh queued on the background worker. It will not start recurring scanning if scanning is off."));
                yield 1;
            }
            case RATE_LIMITED -> error(context, "Manual refresh is rate-limited; wait at least 15 seconds before requesting another.");
            case CLOSED -> error(context, "AhProfit is shutting down and cannot queue a refresh.");
        };
    }

    private static int help(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        source.sendFeedback(Component.literal("/ahprofit — toggle the local, read-only scanner and show its current status."));
        source.sendFeedback(Component.literal("/ahprofit status | source [auto|api|local] | budget <amount> | reserve <amount> | minprofit <amount> | minroi <percent> | refresh | help"));
        source.sendFeedback(Component.literal("Amounts accept values such as 2000000, 2,000,000, or $2,000,000. Balance is manual and is not fetched from /stats."));
        source.sendFeedback(Component.literal("No key? Auto mode uses config/ahprofit/market-input.json, or force /ahprofit source local. Supply actual listings and completed sales; then /ahprofit refresh."));
        source.sendFeedback(Component.literal("Live API mode still needs a key from /api in credentials.json. Local-file prices are not live API-verified quotes."));
        source.sendFeedback(Component.literal("AhProfit never buys, lists, moves items, clicks menus, or sends trading commands. All trading is manual."));
        return 1;
    }

    private static void sendCompactStatus(FabricClientCommandSource source, boolean enabled,
                                         ScannerSnapshot state, AhProfitConfig config) {
        long now = System.currentTimeMillis();
        BankrollLimits limits = BankrollManager.calculate(config, now);
        String balanceLabel = limits.balanceConfigured() ? Money.format(limits.balance()) + " manual" : "not configured";
        String dataLabel = state.apiStatus().name().toLowerCase(Locale.ROOT).replace('_', ' ');
        String mode = enabled ? "ON" : "OFF";
        source.sendFeedback(Component.literal("AhProfit " + mode + " | balance " + balanceLabel
                + (limits.balanceMayBeStale() ? " (stale)" : "")
                + " | listings analyzed " + state.listingsAnalyzed()
                + " | qualified opportunities " + state.qualifiedOpportunities()
                + " | market data " + freshness(state.lastSuccessfulFetchMillis(), now, config.maxDataAgeSeconds())
                + " | source " + config.marketDataMode().configValue() + " | state " + dataLabel));
        if (!enabled) {
            source.sendFeedback(Component.literal("No recurring market refreshes are scheduled. Use /ahprofit status for the selected cached analysis."));
        } else if (state.apiStatus() == ApiStatus.MISSING_KEY) {
            source.sendFeedback(Component.literal("API mode needs a key. Choose /ahprofit source local for key-free files, or configure credentials.json for live API access."));
        } else if (state.apiStatus() == ApiStatus.LOCAL_WAITING) {
            source.sendFeedback(Component.literal("Key-free mode: supply config/ahprofit/market-input.json with actual observation timestamps, listings, and completed sales; then /ahprofit refresh."));
        }
    }

    private static void sendFullStatus(FabricClientCommandSource source, ScannerSnapshot state, AhProfitConfig config) {
        long now = System.currentTimeMillis();
        BankrollLimits limits = BankrollManager.calculate(config, now);
        source.sendFeedback(Component.literal("AhProfit scanner: " + (state.running() ? "RUNNING" : "STOPPED")
                + " | source: " + config.marketDataMode().configValue() + " | data state: " + state.apiStatus().name() + " — " + state.apiMessage()));
        source.sendFeedback(Component.literal("Last successful market observation: "
                + freshness(state.lastSuccessfulFetchMillis(), now, config.maxDataAgeSeconds())
                + " | last listing observation: " + relativeTime(state.newestListingObservationMillis(), now)));
        source.sendFeedback(Component.literal("Listings analyzed: " + state.listingsAnalyzed()
                + " | qualified recommendations: " + state.qualifiedOpportunities()
                + " | economically qualified before portfolio limits: " + state.economicallyQualifiedListings()
                + " | uncertain/low-data skipped: " + (state.analysis() == null ? 0 : state.analysis().skippedUncertainListings())
                + " | transaction records cached (7d query window): " + state.comparableSalesCached()));
        source.sendFeedback(Component.literal("Balance: " + (limits.balanceConfigured() ? Money.format(limits.balance()) : "not configured")
                + " (manual, never API-verified)" + (limits.balanceMayBeStale() ? " — stale; update with /ahprofit budget" : "")
                + " | reserve " + Money.format(limits.protectedReserve())
                + " | deployable " + Money.format(limits.deployableCapital())
                + " | max listing " + Money.format(limits.maxRecommendedListingSpend())
                + " | minimum net profit " + Money.format(limits.minimumProfitThreshold())));

        MarketAnalysis analysis = state.analysis();
        if (analysis == null) {
            source.sendFeedback(Component.literal("No cached analysis is available yet. Start scanning with /ahprofit or request /ahprofit refresh."));
            return;
        }
        source.sendFeedback(Component.literal("Hypothetical portfolio only: proposed capital "
                + Money.format(analysis.totalProposedCapital()) + " | deployable remaining after hypothetical entries "
                + Money.format(analysis.remainingDeployableCapital()) + ". No trade is made or reserved."));
        if (analysis.categoryExposure().isEmpty()) {
            source.sendFeedback(Component.literal("Category exposure: none proposed."));
        } else {
            analysis.categoryExposure().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> source.sendFeedback(Component.literal("Category exposure "
                            + entry.getKey().name().toLowerCase(Locale.ROOT) + ": " + Money.format(entry.getValue()))));
        }

        int shown = Math.min(MAX_STATUS_OPPORTUNITIES, analysis.opportunities().size());
        if (shown == 0) {
            source.sendFeedback(Component.literal("No evidence-backed opportunities currently pass the profit, ROI, discount, freshness, and capital filters."));
        }
        for (int index = 0; index < shown; index++) {
            sendOpportunity(source, index + 1, analysis.opportunities().get(index));
        }
        analysis.warnings().forEach(warning -> source.sendError(Component.literal("AhProfit warning: " + warning)));
    }

    private static void sendOpportunity(FabricClientCommandSource source, int rank, Opportunity opportunity) {
        var listing = opportunity.listing();
        var estimate = opportunity.estimate();
        String item = ItemFingerprint.displayName(listing.item());
        if (!listing.fingerprint().variantDescription().equals("standard variant")) {
            item += " [" + listing.fingerprint().variantDescription() + "]";
        }
        String unit = Money.format(listing.unitPrice()) + "/unit";
        String resaleUnit = estimate.conservativeUnitPrice() == null ? "unknown" : Money.format(estimate.conservativeUnitPrice()) + "/unit";
        source.sendFeedback(Component.literal("#" + rank + " " + item + " x" + listing.quantity()
                + " | ask " + Money.format(listing.totalPrice()) + " (" + unit + ")"
                + " | conservative suggested resale " + Money.format(opportunity.conservativeResaleRevenue())
                + " (" + resaleUnit + ")"));
        source.sendFeedback(Component.literal("Net " + Money.format(opportunity.estimatedNetProfit())
                + " | ROI " + Money.formatPercent(opportunity.roiPercent())
                + " | discount " + Money.formatPercent(opportunity.discountPercent())
                + " | confidence " + Math.round(estimate.confidence() * 100) + "%"
                + " | sales 24h/7d " + estimate.comparableSales24Hours() + "/" + estimate.comparableSales7Days()
                + " | liquidity " + liquidity(estimate.liquidity(), estimate.daysToClearProxy())
                + " | competing listings " + estimate.competingListings()
                + " | capital " + Money.format(listing.totalPrice())
                + " | observed " + relativeTime(listing.observedAtMillis(), System.currentTimeMillis())));
        source.sendFeedback(Component.literal("Ranked because: " + opportunity.rationale()));
        if (!opportunity.flags().isEmpty()) {
            source.sendError(Component.literal("Risk/data flags: " + String.join("; ", opportunity.flags())));
        }
    }

    private static String freshness(long timestampMillis, long nowMillis, int maximumAgeSeconds) {
        if (timestampMillis <= 0) {
            return "never observed";
        }
        String age = relativeTime(timestampMillis, nowMillis);
        boolean stale = nowMillis - timestampMillis > TimeUnit.SECONDS.toMillis(maximumAgeSeconds);
        return stale ? "STALE, " + age : age;
    }

    private static String relativeTime(long timestampMillis, long nowMillis) {
        if (timestampMillis <= 0) {
            return "never";
        }
        long seconds = Math.max(0, (nowMillis - timestampMillis) / 1_000);
        if (seconds < 60) {
            return seconds + "s ago";
        }
        long minutes = seconds / 60;
        if (minutes < 60) {
            return minutes + "m ago";
        }
        long hours = minutes / 60;
        if (hours < 48) {
            return hours + "h ago";
        }
        return (hours / 24) + "d ago";
    }

    private static String liquidity(LiquidityLevel liquidity, double daysToClearProxy) {
        String value = liquidity.name().toLowerCase(Locale.ROOT);
        if (!Double.isFinite(daysToClearProxy) || daysToClearProxy < 0) {
            return value + " (no volume proxy)";
        }
        return value + " (~" + String.format(Locale.ROOT, "%.1f", daysToClearProxy) + "d supply proxy)";
    }

    private static int error(CommandContext<FabricClientCommandSource> context, String message) {
        context.getSource().sendError(Component.literal("AhProfit: " + (message == null ? "Invalid command argument." : message)));
        return 0;
    }
}
