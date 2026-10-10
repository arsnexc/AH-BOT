package dev.ahprofit.client;

import dev.ahprofit.bankroll.BankrollLimits;
import dev.ahprofit.bankroll.BankrollManager;
import dev.ahprofit.config.AhProfitConfig;
import dev.ahprofit.market.ItemFingerprint;
import dev.ahprofit.market.MarketAnalysis;
import dev.ahprofit.market.Opportunity;
import dev.ahprofit.scanner.ApiStatus;
import dev.ahprofit.scanner.ScannerService;
import dev.ahprofit.scanner.ScannerSnapshot;
import dev.ahprofit.util.Money;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Small read-only HUD fed only by the scanner's immutable atomic snapshot. */
public final class AhProfitHud {
    private static final int LEFT = 8;
    private static final int TOP = 8;
    private static final int MAX_LINES = 9;
    private static volatile long lastTextRefreshMillis;
    private static volatile List<HudLine> cachedLines = List.of();

    private AhProfitHud() {
    }

    public static void register(ScannerService scanner, dev.ahprofit.config.ConfigManager configManager) {
        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, AhProfitClient.id("market_hud"),
                (graphics, deltaTracker) -> render(graphics, scanner, configManager));
    }

    private static void render(GuiGraphicsExtractor graphics, ScannerService scanner,
                               dev.ahprofit.config.ConfigManager configManager) {
        AhProfitConfig config = configManager.get();
        if (!config.showHud()) {
            return;
        }
        ScannerSnapshot snapshot = scanner.snapshot();
        if (!snapshot.running()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastTextRefreshMillis >= 500 || cachedLines.isEmpty()) {
            cachedLines = buildLines(snapshot, config, now);
            lastTextRefreshMillis = now;
        }
        if (cachedLines.isEmpty()) {
            return;
        }

        Minecraft client = Minecraft.getInstance();
        int panelWidth = Math.min(372, Math.max(200, client.getWindow().getGuiScaledWidth() - 16));
        int panelHeight = 8 + cachedLines.size() * 10;
        graphics.fill(LEFT, TOP, LEFT + panelWidth, TOP + panelHeight, 0xA0000000);
        graphics.outline(LEFT, TOP, panelWidth, panelHeight, 0x704A7080);
        int y = TOP + 5;
        for (HudLine line : cachedLines) {
            graphics.text(client.font, truncate(line.text(), 62), LEFT + 6, y, line.color(), true);
            y += 10;
        }
    }

    private static List<HudLine> buildLines(ScannerSnapshot snapshot, AhProfitConfig config, long nowMillis) {
        List<HudLine> lines = new ArrayList<>();
        boolean stale = snapshot.lastSuccessfulFetchMillis() <= 0
                || nowMillis - snapshot.lastSuccessfulFetchMillis() > config.maxDataAgeSeconds() * 1_000L;
        String freshness = snapshot.lastSuccessfulFetchMillis() <= 0 ? "never fetched"
                : relativeTime(snapshot.lastSuccessfulFetchMillis(), nowMillis) + (stale ? " STALE" : "");
        int headingColor = stale || snapshot.apiStatus() != ApiStatus.CONNECTED ? 0xFFFFC857 : 0xFF70E0A0;
        lines.add(new HudLine("AhProfit READ-ONLY | API " + snapshot.apiStatus().name() + " | data " + freshness, headingColor));

        BankrollLimits bankroll = BankrollManager.calculate(config, nowMillis);
        lines.add(new HudLine("Balance " + (bankroll.balanceConfigured() ? shortMoney(bankroll.balance()) : "NOT SET")
                + " manual" + (bankroll.balanceMayBeStale() ? " STALE" : "")
                + " | reserve " + shortMoney(bankroll.protectedReserve())
                + " | deployable " + shortMoney(bankroll.deployableCapital()), 0xFFE5EAF0));
        lines.add(new HudLine("Per-listing cap " + shortMoney(bankroll.maxRecommendedListingSpend())
                + " | scanned " + snapshot.listingsAnalyzed()
                + " | qualified " + snapshot.qualifiedOpportunities()
                + " | uncertain " + (snapshot.analysis() == null ? 0 : snapshot.analysis().skippedUncertainListings()), 0xFFE5EAF0));

        MarketAnalysis analysis = snapshot.analysis();
        if (analysis == null) {
            lines.add(new HudLine("Waiting for the first safe market snapshot. Use /ahprofit status.", 0xFFFFC857));
            return lines;
        }
        lines.add(new HudLine("Proposed " + shortMoney(analysis.totalProposedCapital())
                + " hypothetical | remaining deployable " + shortMoney(analysis.remainingDeployableCapital()), 0xFFE5EAF0));
        if (bankroll.balanceMayBeStale()) {
            lines.add(new HudLine("WARNING: manual balance is unset or older than 24h; update /ahprofit budget.", 0xFFFFC857));
        }
        if (analysis.opportunities().isEmpty()) {
            lines.add(new HudLine("No evidence-backed flips pass current risk/profit/capital filters.", 0xFFB9C3D0));
        } else {
            int index = 1;
            for (Opportunity opportunity : analysis.opportunities()) {
                if (index > 3 || lines.size() + 2 > MAX_LINES) {
                    break;
                }
                String name = ItemFingerprint.displayName(opportunity.listing().item());
                BigDecimal resale = opportunity.conservativeResaleRevenue();
                lines.add(new HudLine(index + ". " + name + " x" + opportunity.listing().quantity()
                        + " ask " + shortMoney(opportunity.listing().totalPrice())
                        + " -> resale <= " + shortMoney(resale)
                        + " | net " + shortMoney(opportunity.estimatedNetProfit())
                        + " | ROI " + Money.formatPercent(opportunity.roiPercent()), 0xFF8EE6B0));
                lines.add(new HudLine("   " + opportunity.estimate().comparableSales7Days() + " sales/7d"
                        + " | " + opportunity.estimate().liquidity().name().toLowerCase(Locale.ROOT)
                        + " | confidence " + Math.round(opportunity.estimate().confidence() * 100) + "%"
                        + " | score " + Math.round(opportunity.score()), 0xFFCAD2DC));
                index++;
            }
        }

        if (!analysis.categoryExposure().isEmpty() && lines.size() < MAX_LINES) {
            StringBuilder exposure = new StringBuilder("Category exposure: ");
            analysis.categoryExposure().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .limit(2)
                    .forEach(entry -> {
                        if (exposure.charAt(exposure.length() - 1) != ' ') {
                            exposure.append(" | ");
                        }
                        exposure.append(entry.getKey().name().toLowerCase(Locale.ROOT))
                                .append(' ').append(shortMoney(entry.getValue()));
                    });
            lines.add(new HudLine(exposure.toString(), 0xFFB9C3D0));
        }
        return List.copyOf(lines.subList(0, Math.min(MAX_LINES, lines.size())));
    }

    private static String shortMoney(BigDecimal value) {
        BigDecimal absolute = value.abs();
        if (absolute.compareTo(new BigDecimal("1000000")) >= 0) {
            return "$" + value.divide(new BigDecimal("1000000"), 1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() + "M";
        }
        if (absolute.compareTo(new BigDecimal("1000")) >= 0) {
            return "$" + value.divide(new BigDecimal("1000"), 1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() + "k";
        }
        return Money.format(value);
    }

    private static String truncate(String value, int maxCharacters) {
        if (value.length() <= maxCharacters) {
            return value;
        }
        return value.substring(0, Math.max(1, maxCharacters - 1)) + "…";
    }

    private static String relativeTime(long timestamp, long now) {
        long seconds = Math.max(0, (now - timestamp) / 1_000);
        if (seconds < 60) {
            return seconds + "s ago";
        }
        if (seconds < 3_600) {
            return (seconds / 60) + "m ago";
        }
        if (seconds < 172_800) {
            return (seconds / 3_600) + "h ago";
        }
        return (seconds / 86_400) + "d ago";
    }

    private record HudLine(String text, int color) {
    }
}
