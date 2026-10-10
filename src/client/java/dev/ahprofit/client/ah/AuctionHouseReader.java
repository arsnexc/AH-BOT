package dev.ahprofit.client.ah;

import dev.ahprofit.ah.AuctionEntry;
import dev.ahprofit.ah.AuctionHouseSnapshot;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

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
            entries.add(AuctionEntry.of(slot.index, itemId(stack), displayName(stack),
                    stack.getCount(), lore(stack)));
        }
        return new AuctionHouseSnapshot(menu.containerId, title, entries, nowMillis);
    }

    /** True when a container menu other than the player's own inventory is open. */
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
