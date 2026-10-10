package dev.ahprofit.client.ah;

import dev.ahprofit.ah.AutomationBudget;
import dev.ahprofit.ah.AutomationPlanner;
import dev.ahprofit.ah.AuctionEntry;
import dev.ahprofit.ah.AuctionHouseSnapshot;
import dev.ahprofit.bankroll.BankrollLimits;
import dev.ahprofit.bankroll.BankrollManager;
import dev.ahprofit.config.AhProfitConfig;
import dev.ahprofit.config.ConfigManager;
import dev.ahprofit.market.MarketAnalysis;
import dev.ahprofit.scanner.ScannerService;
import dev.ahprofit.util.Money;
import net.minecraft.client.Minecraft;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Auction-house control.
 *
 * <p>Off by default. Even when enabled it refuses to act unless the live listing still clears every
 * economic filter at its live price, and it never exceeds the configured per-action, hourly, or
 * action-count ceilings. {@code /ahprofit ah stop} halts it immediately until it is re-enabled.
 */
public final class AuctionAutomationService {
    private static final int TICKS_PER_EVALUATION = 10;
    private static final int PLACEMENT_TIMEOUT_TICKS = 10;

    private final ScannerService scanner;
    private final ConfigManager configManager;
    private final AutomationBudget budget;
    private final AtomicReference<String> lastDecision = new AtomicReference<>("Auction automation has not run yet.");
    private final AtomicBoolean halted = new AtomicBoolean(false);

    private int tickCounter;
    private int placingSlot = -1;
    private int placingTicks;
    private BigDecimal placingSpend = BigDecimal.ZERO;
    private int lastContainerId = -1;

    public AuctionAutomationService(ScannerService scanner, ConfigManager configManager) {
        this.scanner = scanner;
        this.configManager = configManager;
        AhProfitConfig config = configManager.get();
        this.budget = new AutomationBudget(config.automationMaxSpendPerHour(),
                config.automationMaxActionsPerHour(),
                config.automationCooldownSeconds() * 1_000L);
    }

    public void onClientTick(Minecraft client) {
        if (client == null || client.player == null || client.isPaused()) {
            return;
        }
        AhProfitConfig config = configManager.get();
        if (!config.automationEnabled() || halted.get()) {
            resetPlacement();
            return;
        }
        if (placingSlot >= 0) {
            advancePlacement(client);
            return;
        }
        if (++tickCounter < TICKS_PER_EVALUATION) {
            return;
        }
        tickCounter = 0;

        long now = System.currentTimeMillis();
        AuctionHouseSnapshot snapshot = AuctionHouseReader.read(client, now);
        if (snapshot == null) {
            lastDecision.set("No auction-house menu is open.");
            return;
        }
        lastContainerId = snapshot.containerId();
        MarketAnalysis analysis = scanner.snapshot().analysis();
        BankrollLimits bankroll = BankrollManager.calculate(config, now);
        AutomationPlanner.Decision decision = AutomationPlanner.plan(snapshot, analysis, config, bankroll, budget, now);
        lastDecision.set(decision.reason());
        if (decision.kind() == AutomationPlanner.Kind.BUY) {
            beginBuy(client, decision);
        }
    }

    private void beginBuy(Minecraft client, AutomationPlanner.Decision decision) {
        AuctionEntry entry = decision.entry();
        if (entry == null || client.player == null || client.gameMode == null) {
            return;
        }
        int containerId = client.player.containerMenu.containerId;
        if (containerId != lastContainerId) {
            return;
        }
        client.gameMode.handleInventoryMouseClick(containerId, entry.slotIndex(), 0, ClickType.PICKUP, client.player);
        placingSlot = entry.slotIndex();
        placingTicks = 0;
        placingSpend = decision.plannedSpend();
    }

    private void advancePlacement(Minecraft client) {
        if (client.player == null || client.gameMode == null) {
            resetPlacement();
            return;
        }
        placingTicks++;
        ItemStack carried = client.player.containerMenu.getCarried();
        if (carried == null || carried.isEmpty()) {
            finishPlacement();
            return;
        }
        if (placingTicks < PLACEMENT_TIMEOUT_TICKS) {
            return;
        }
        int target = firstEmptyInventorySlot(client);
        if (target >= 0) {
            client.gameMode.handleInventoryMouseClick(client.player.containerMenu.containerId, target, 0,
                    ClickType.PICKUP, client.player);
        }
        finishPlacement();
    }

    /** Counts the spend even if placement failed, so the hourly ceiling stays conservative. */
    private void finishPlacement() {
        budget.record(placingSpend, System.currentTimeMillis());
        resetPlacement();
    }

    private void resetPlacement() {
        placingSlot = -1;
        placingTicks = 0;
        placingSpend = BigDecimal.ZERO;
    }

    private static int firstEmptyInventorySlot(Minecraft client) {
        if (client.player == null) {
            return -1;
        }
        for (Slot slot : client.player.containerMenu.slots) {
            if (slot.container == client.player.getInventory() && slot.getItem().isEmpty()) {
                return slot.index;
            }
        }
        return -1;
    }

    /** Immediate halt; stays halted until {@link #resume()} is called. */
    public void stop() {
        halted.set(true);
        resetPlacement();
        lastDecision.set("Halted by request. Use /ahprofit ah on to re-enable auction automation.");
    }

    public void resume() {
        halted.set(false);
        budget.reset();
        lastDecision.set("Auction automation re-enabled; watching the open auction house.");
    }

    public boolean isHalted() {
        return halted.get();
    }

    public String lastDecision() {
        return lastDecision.get();
    }

    public BigDecimal spendThisHour() {
        return budget.spendInWindow();
    }

    public int actionsThisHour() {
        return budget.actionsInWindow();
    }

    /** One-line status for the HUD and /ahprofit status. */
    public String statusLine(AhProfitConfig config) {
        if (!config.automationEnabled()) {
            return "AH automation OFF (never clicks; /ahprofit ah on to enable)";
        }
        if (halted.get()) {
            return "AH automation HALTED (" + Money.format(budget.spendInWindow()) + " spent this hour)";
        }
        return "AH automation " + (config.automationDryRun() ? "DRY RUN" : "LIVE")
                + " | " + budget.actionsInWindow() + " actions, " + Money.format(budget.spendInWindow())
                + " of " + Money.format(config.automationMaxSpendPerHour()) + " this hour";
    }
}
