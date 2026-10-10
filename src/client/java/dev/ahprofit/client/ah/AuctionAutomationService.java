package dev.ahprofit.client.ah;

import dev.ahprofit.ah.AutomationBudget;
import dev.ahprofit.ah.AutomationPlanner;
import dev.ahprofit.ah.AuctionEntry;
import dev.ahprofit.ah.HoldingsTracker;
import dev.ahprofit.ah.AuctionHouseSnapshot;
import dev.ahprofit.bankroll.BankrollLimits;
import dev.ahprofit.bankroll.BankrollManager;
import dev.ahprofit.config.AhProfitConfig;
import dev.ahprofit.config.ConfigManager;
import dev.ahprofit.market.MarketAnalysis;
import dev.ahprofit.scanner.ScannerService;
import dev.ahprofit.util.Money;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Items;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.List;
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
    private static final int SELL_TICKS_PER_STEP = 5;
    private static final int SELL_TIMEOUT_TICKS = 60;

    private final ScannerService scanner;
    private final ConfigManager configManager;
    private final AutomationBudget budget;
    private final AtomicReference<String> lastDecision = new AtomicReference<>("Auction automation has not run yet.");
    private final AtomicBoolean halted = new AtomicBoolean(false);

    private int tickCounter;
    private int placingSlot = -1;
    private int placingTicks;
    private BigDecimal placingSpend = BigDecimal.ZERO;
    private AuctionEntry placingEntry;
    private final HoldingsTracker holdings = new HoldingsTracker();
    private int sellStage;
    private int sellTicks;
    private HoldingsTracker.Holding sellingHolding;
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
        if (sellStage > 0) {
            advanceSell(client);
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
        placingEntry = entry;
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
        long now = System.currentTimeMillis();
        budget.record(placingSpend, now);
        holdings.record(placingEntry, placingSpend, now);
        resetPlacement();
    }

    private void resetPlacement() {
        placingSlot = -1;
        placingTicks = 0;
        placingSpend = BigDecimal.ZERO;
        placingEntry = null;
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
        resetSell();
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

    /** Everything automation bought this session; in-memory only, never a verified inventory. */
    public List<HoldingsTracker.Holding> holdings() {
        return holdings.holdings();
    }

    public String holdingsSummary() {
        return HoldingsTracker.summarise(holdings.holdings());
    }

    public boolean hasHoldings() {
        return !holdings.isEmpty();
    }

    public void forgetHoldings() {
        holdings.clear();
    }

    /** Cheapest asking price per unit among the currently visible listings for one item. */
    private BigDecimal cheapestRivalUnitPrice(String itemId) {
        AuctionHouseSnapshot snapshot = AuctionHouseReader.readCurrent();
        if (snapshot == null) {
            return null;
        }
        return snapshot.entries().stream()
                .filter(entry -> entry.itemId().equals(itemId) && entry.count() > 0 && entry.priced())
                .map(entry -> entry.totalPrice().divide(BigDecimal.valueOf(entry.count()), MathContext.DECIMAL128))
                .min(BigDecimal::compareTo)
                .orElse(null);
    }

    /** Sell plan text for every holding, or a message explaining why there is nothing to say. */
    public List<String> sellPlan() {
        AhProfitConfig config = configManager.get();
        List<String> lines = new ArrayList<>();
        for (HoldingsTracker.Holding holding : holdings.holdings()) {
            BigDecimal rival = cheapestRivalUnitPrice(holding.itemId());
            BigDecimal target = HoldingsTracker.recommendedAskBasedListPrice(rival, holding.count(),
                    config.askUndercutPercent(), holding);
            StringBuilder line = new StringBuilder(holding.describe());
            if (target == null) {
                line.append(" | break-even ").append(Money.format(HoldingsTracker.breakEvenTotal(holding)))
                        .append(" | no rival ask visible, open the auction house for this item");
            } else {
                BigDecimal margin = target.subtract(HoldingsTracker.breakEvenTotal(holding));
                line.append(" | break-even ").append(Money.format(HoldingsTracker.breakEvenTotal(holding)))
                        .append(" | recommended list ").append(Money.format(target))
                        .append(" | margin ").append(Money.format(margin));
            }
            lines.add(line.toString());
        }
        return lines;
    }

    /**
     * Starts selling the first holding. Requires both the sell-button and confirm slots to be
     * configured, because the mod does not know DonutSMP's sell menu layout; without them it refuses
     * rather than clicking blind.
     */
    public String beginSell() {
        if (holdings.isEmpty()) {
            return "Nothing was bought this session, so there is nothing to sell.";
        }
        if (sellingHolding != null) {
            return "Already selling " + sellingHolding.describe();
        }
        AhProfitConfig config = configManager.get();
        if (config.automationSellSlot() < 0 || config.automationConfirmSlot() < 0) {
            return "Selling is not mapped: set automationSellSlot and automationConfirmSlot in "
                    + "config/ahprofit/settings.json to the slots of your server's sell menu, then retry. "
                    + "Refusing to click unverified slots.";
        }
        sellingHolding = holdings.holdings().get(0);
        sellStage = 1;
        sellTicks = 0;
        return (config.automationDryRun() ? "Dry run: " : "")
                + "selling " + sellingHolding.describe() + " via slots " + config.automationSellSlot()
                + " and " + config.automationConfirmSlot();
    }

    private void advanceSell(Minecraft client) {
        if (client.player == null || client.gameMode == null) {
            resetSell();
            return;
        }
        AhProfitConfig config = configManager.get();
        sellTicks++;
        if (sellTicks > SELL_TIMEOUT_TICKS) {
            lastDecision.set("Sell sequence timed out; no confirm click was sent for "
                    + sellingHolding.itemId() + ".");
            resetSell();
            return;
        }
        if (sellTicks < SELL_TICKS_PER_STEP) {
            return;
        }
        int containerId = client.player.containerMenu.containerId;
        switch (sellStage) {
            case 1 -> click(client, containerId, config.automationSellSlot());
            case 2 -> {
                int itemSlot = findInventorySlot(client, sellingHolding.itemId());
                if (itemSlot < 0) {
                    lastDecision.set("No inventory slot holds " + sellingHolding.itemId()
                            + "; the sell sequence was abandoned.");
                    resetSell();
                    return;
                }
                click(client, containerId, itemSlot);
            }
            case 3 -> click(client, containerId, config.automationConfirmSlot());
            default -> {
                holdings.forget(sellingHolding);
                lastDecision.set((config.automationDryRun() ? "Dry run: " : "") + "finished selling "
                        + sellingHolding.describe());
                resetSell();
                return;
            }
        }
        sellStage++;
        sellTicks = 0;
    }

    private void click(Minecraft client, int containerId, int slot) {
        client.gameMode.handleInventoryMouseClick(containerId, slot, 0, ClickType.PICKUP, client.player);
    }

    private void resetSell() {
        sellStage = 0;
        sellTicks = 0;
        sellingHolding = null;
    }

    private static int findInventorySlot(Minecraft client, String itemId) {
        if (client.player == null) {
            return -1;
        }
        for (Slot slot : client.player.containerMenu.slots) {
            if (slot.container == client.player.getInventory()
                    && slot.getItem().getItem() == Items.AIR) {
                continue;
            }
            if (slot.container == client.player.getInventory()
                    && BuiltInRegistries.ITEM.getKey(slot.getItem().getItem()).toString().equals(itemId)) {
                return slot.index;
            }
        }
        return -1;
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
