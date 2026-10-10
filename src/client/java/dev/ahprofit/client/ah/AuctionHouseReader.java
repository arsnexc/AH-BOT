package dev.ahprofit.client.ah;

import dev.ahprofit.ah.AuctionEntry;
import dev.ahprofit.ah.LorePriceParser;
import dev.ahprofit.ah.AuctionHouseSnapshot;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads whatever auction-house container the player currently has open. Nothing here sends a
 * packet; it only inspects what the server already synchronised to the client.
 */
public final class AuctionHouseReader {
    private AuctionHouseReader() {
    }

    /** @return a snapshot, or null when no third-party container menu is open. */
    public static AuctionHouseSnapshot read(Minecraft client, long nowMillis) {
        if (client == null || client.player == null || client.level == null) {
            return null;
        }
        AbstractContainerMenu menu = client.player.containerMenu;
        if (menu == null || menu == client.player.inventoryMenu || menu.containerId == 0) {
            return null;
        }

        String title = "";
        if (client.screen instanceof AbstractContainerScreen<?> screen) {
            title = screen.getTitle().getString();
        }

        List<AuctionEntry> entries = new ArrayList<>();
        for (Slot slot : menu.slots) {
            if (slot.container == client.player.getInventory()) {
                continue;
            }
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) {
                continue;
            }
            List<String> lore = lore(stack);
            String name = displayName(stack);
            BigDecimal price = LorePriceParser.findPrice(lore, name, priceText(stack, lore, name));
            entries.add(AuctionEntry.of(slot.index, itemId(stack), name, stack.getCount(), lore, price));
        }
        return new AuctionHouseSnapshot(menu.containerId, title, entries, nowMillis);
    }

    /**
     * Every piece of text on an item that could carry a price: lore, the display name, and the
     * custom-name and custom-data components. Plugin GUIs frequently store the asking price in NBT
     * rather than lore, so lore alone is not enough.
     */
    private static List<String> priceText(ItemStack stack, List<String> lore, String displayName) {
        List<String> sources = new ArrayList<>();
        if (lore != null) {
            sources.addAll(lore);
        }
        if (displayName != null && !displayName.isBlank()) {
            sources.add(displayName);
        }
        try {
            Component customName = stack.get(DataComponents.CUSTOM_NAME);
            if (customName != null) {
                sources.add(customName.getString());
            }
            Component itemName = stack.get(DataComponents.ITEM_NAME);
            if (itemName != null) {
                sources.add(itemName.getString());
            }
        } catch (RuntimeException ignored) {
            // A component that cannot be read simply contributes no text.
        }
        try {
            CustomData custom = stack.get(DataComponents.CUSTOM_DATA);
            if (custom != null) {
                sources.add(custom.toString());
            }
        } catch (RuntimeException ignored) {
            // Same: an unreadable component contributes nothing.
        }
        return sources;
    }

    /** True when a container menu other than the player's own inventory is open. */
    /** Latest reading of the open auction house, or null when none is open. */
    public static AuctionHouseSnapshot readCurrent() {
        Minecraft client = Minecraft.getInstance();
        if (client == null) {
            return null;
        }
        return read(client, System.currentTimeMillis());
    }

    /**
     * Explains exactly what the mod can see right now, so an unreadable menu can be diagnosed
     * instead of guessed at. Pure inspection; sends no packets and clicks nothing.
     */
    public static List<String> probe(Minecraft client) {
        List<String> lines = new ArrayList<>();
        if (client == null || client.player == null || client.level == null) {
            lines.add("No player or level is loaded.");
            return lines;
        }
        AbstractContainerMenu menu = client.player.containerMenu;
        lines.add("Open screen: " + (client.screen == null ? "none"
                : client.screen.getClass().getSimpleName()));
        if (menu == null) {
            lines.add("Container menu: none.");
            return lines;
        }
        lines.add("Container menu: " + menu.getClass().getSimpleName() + " (containerId "
                + menu.containerId + ")");
        lines.add("Is the player's own inventory menu: " + (menu == client.player.inventoryMenu));
        String title = "";
        boolean isContainerScreen = client.screen instanceof AbstractContainerScreen<?>;
        if (isContainerScreen) {
            title = ((AbstractContainerScreen<?>) client.screen).getTitle().getString();
        }
        lines.add("Screen is a container screen: " + isContainerScreen + " | title: \""
                + title + "\"");
        lines.add("AhProfit reads this menu: " + (read(client, System.currentTimeMillis()) != null));

        int containerSlots = 0;
        int priced = 0;
        int listed = 0;
        List<String> samples = new ArrayList<>();
        for (Slot slot : menu.slots) {
            if (slot.container == client.player.getInventory()) {
                continue;
            }
            containerSlots++;
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) {
                continue;
            }
            listed++;
            List<String> lore = lore(stack);
            String name = displayName(stack);
            BigDecimal price = LorePriceParser.findPrice(lore, name, priceText(stack, lore, name));
            if (price != null) {
                priced++;
            }
            if (samples.size() < 4) {
                samples.add("  slot " + slot.index + " " + itemId(stack) + " x" + stack.getCount()
                        + " | lore lines: " + lore.size()
                        + (price == null ? " | NO PRICE FOUND" : " | price " + price));
                for (String line : lore) {
                    samples.add("      | " + line);
                }
            }
        }
        lines.add("Non-inventory slots: " + containerSlots + ", holding items: " + listed
                + ", with a readable price: " + priced);
        if (listed == 0) {
            lines.add("No items are visible in the container right now; open an auction page first.");
        }
        lines.addAll(samples);
        return lines;
    }

    public static boolean isAuctionHouseOpen(Minecraft client) {
        return client != null && client.player != null
                && client.player.containerMenu != null
                && client.player.containerMenu != client.player.inventoryMenu
                && client.player.containerMenu.containerId != 0;
    }

    private static String itemId(ItemStack stack) {
        try {
            return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        } catch (RuntimeException exception) {
            return "";
        }
    }

    private static String displayName(ItemStack stack) {
        try {
            return stack.getHoverName().getString();
        } catch (RuntimeException exception) {
            return "";
        }
    }

    private static List<String> lore(ItemStack stack) {
        try {
            List<String> lines = new ArrayList<>();
            for (var component : stack.getOrDefault(DataComponents.LORE, ItemLore.EMPTY).lines()) {
                lines.add(component.getString());
            }
            return lines;
        } catch (RuntimeException exception) {
            return List.of();
        }
    }
}
